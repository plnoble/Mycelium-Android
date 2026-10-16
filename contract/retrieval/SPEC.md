# 检索规则 SPEC v0（语言无关）

- 契约版本：`0.1.0`。本规则照搬知衍 `core/persona_fts.rs::search_persona_fts` 的现有行为，写成与编程语言无关的步骤；包内参考实现见知衍 `core/persona_pack.rs`。
- 对象：角色包 `persona.sqlite` 里的 `search_docs`（`document_key` 即命中对象 ID）与 `search_fts`（FTS5，`tokenize='trigram'`）。
- 语义约定：字符串比较（`contains` / `instr`）一律**大小写不敏感**；本 SPEC 中「字符数」一律指 Unicode 标量数（Rust `chars().count()` 同义；注意 UTF-16 代码单元计数不算数）。

## 1. 入口

1. 输入：查询 `q`（字符串）、`limit`（1 到 50 的整数）、`include_inactive`（布尔，v0 消费方固定传 false）。
2. 预处理：`q` 去掉首尾空白。空串或字符数 > 200 → 报错，不返回结果。
3. `limit` 不在 1..=50 → 报错。
4. 命中集合 = §3（短查询）或 §4（长查询）的结果；最终每条按列表序标 `rank=1..n`。
5. 返回提示（供 UI 显示，文本不构成契约）：短查询提示「只搜索标题、观点、主题和方法字段，不扫描整篇正文」；长查询提示「优先 FTS5 trigram；仅在标题、观点、主题和方法字段追加二字词回退」。

## 2. `search_docs` 派生规则（一次性、方向固定）

包内 `search_docs` / `search_fts` 由以下步骤派生，任何实现必须产生同一组文档（`document_key` 唯一）：

1. **source 文档**：对 `sources` 每一行（按 `source_id` 升序）：
   - `document_key = "source:" + source_id`，`kind = "source"`；
   - `source_status = sources.status`；
   - `title = sources.title`；
   - `body = source_texts.normalized_body`（`includes_full_text=false` 时为多空串）。
2. **claim / method 文档**：对快照内每条 claim（按 `claim_id` 升序）：
   - claim 文档：`document_key = "claim:" + claim_record_id`，`kind="claim"`，`source_status=对应 sources.status`，`title=对应 sources.title`，`body=claim_text`；再把该 claim 的全部 evidence 按 `(body_start, evidence_id)` 升序依次检查：`evidence_text` 不出现在当前 body 中时，`body += "\n" + evidence_text`（即逐字去重追加）。
   - method 文档（仅当 `claim_kind="method"`，或 `method_structure_json` 非空且既不是 `"{}"` 也不是 `"null"`）：`document_key="method:"+claim_record_id`，`kind="method"`，`source_status` 同上，`title= sources.title + " · 方法"`，`body = claim_text + "\n" + method_structure_json`。
   - 说明：检索键使用 `claim_record_id`（桌面端 persona.db 的行主键），与桌面 `persona_fts` 的 `document_key` 构造**完全一致**——同分平手裁决按此键排序时两端结果相同。消费端经 `search_docs.claim_id` 列回连到包内观点实体。
3. **theme 文档**：对快照内每个主题（按 `(theme_order, theme_id)` 升序）：
   - `document_key="theme:"+theme_record_id`，`kind="theme"`，`title=themes.name`，`body=themes.summary + "\n" + themes.payload_json`；消费端经 `search_docs.theme_id` 列回连。
   - `source_status`：主题任一成员 claim 的来源状态为 active → `active`，否则 `inactive`。
4. `document_id` 按上述产出顺序从 1 递增；`search_fts` 以同一顺序填充：`INSERT INTO search_fts(rowid, title, body) VALUES (document_id, title, body)`（FTS5 trigram 自己分词）。
5. inactive 过滤：`include_inactive=false` 时，任何一步的候选都必须 `source_status='active'`。

## 3. 短查询（字符数 < 3）：`restricted_fields`

1. 候选条件（先应用 §2.5 的 inactive 过滤）：
   - `title` 包含 `q`；或
   - `kind` ∈ {claim, theme, method} 且 `body` 包含 `q`。
   - （source 文档的 body 不参与短查询匹配。）
2. 打分：`title` 命中记 100，否则记 25。
3. 排序：score 降序；相同按 `document_key` 升序。
4. 取前 `limit` 条。
5. 摘要（excerpt）：`title` 命中时用 `title`，否则 `body` 前 240 个字。

## 4. 长查询（字符数 ≥ 3）：`trigram_hybrid`

### 4.1 FTS5 trigram 主检索

1. 查询表达式：`q` 按空白切词，每个词去掉首尾空白；每个词转成 `"词"`（词内 `"` 写成 `""`）；词之间用 ` AND ` 连接。`search_fts MATCH <表达式>`。
2. 附加过滤：`include_inactive=false` 时要求对应 `search_docs.source_status='active'`（doc 行经 `rowid` 关联）。
3. 排序键：`bm25(search_fts, 4.0, 1.0)` 升序（两列权重：title=4.0、body=1.0；越小相关度越高）；相同按 `document_key` 升序。
4. 取前 `limit` 条，记 `relevance = -bm25`。
5. 摘要：`snippet(search_fts, 1, '[', ']', '…', 24)`（第 1 列 = body）。

### 4.2 二字词字段回退

1. 词：`terms(q)`（见 §5）。空词集则跳过本步。
2. 对每个词计分：`title` 包含该词 → +4；`body` 包含该词 → +1（两个条件都满足则 +5）。
3. 候选：`kind` ∈ {claim, theme, method}，且至少一个词命中 `title` 或 `body`，且总分 ≥ 阈值（词数 ≥ 4 时阈值 2，否则 1）；先应用 inactive 过滤。
4. 排序：总分降序；相同按 `document_key` 升序；取前 `limit × 5` 条（上限 500）。
5. 摘要：`body` 前 240 个字。

### 4.3 合并

1. 主检索结果全体 `relevance += 1000`。
2. 回退结果按 `document_key` 去重后追加（已在主结果中的丢弃整条）。
3. 全部按 `relevance` 降序；`relevance` 相同按 `document_key` 升序。
4. 截断到 `limit` 条。

## 5. `terms(q)`：二字词切分

1. `q` 转小写（全文），按「非字母数字」字符切段，丢弃空段。
2. 对每段：
   - 若段含 CJK 汉字（U+3400–U+4DBF、U+4E00–U+9FFF、U+F900–U+FAFF）：对段内**每两个相邻字符**取二元组（含混排时也按相邻字符取）；跳过出现在停用词表里的二元组。
   - 若段不含 CJK 且长度 ≥ 2：整段作为一个词。
3. 停用词表（二元组）：`什么 怎么 如何 为何 为什 是否 可以 应该 作者 认为 请问 一下 这个 那个`。
4. 结果去重并**字典序排序**。

## 6. 命中对象

- `document_key` 是跨两端稳定的命中对象 ID。claim/method 用 `claim_record_id`、theme 用 `theme_record_id` 作为排序身份——**与桌面端 `persona_fts` 的键构造一致**（同分平手裁决按此键排序时，两端结果完全相同）。source 文档直接用 `source_id`。
- 消费端用前缀（`source:` / `claim:` / `theme:` / `method:`）识别文档类别，再经 `search_docs.claim_id` / `theme_id` 列回连到包内观点/主题实体。
- 排序对 `document_key` 的平手裁决必须与本节一致，否则两端检索结果不一致。

## 7. 样例验证

`vectors.json` 记录样例包上的一组「查询 → 期望命中对象 ID（有序）」：
- 至少覆盖：2 字短查询、3 字以上长查询、无结果查询、需要二字词回退的查询；
- 验证方式：实现按本 SPEC 在样例包上执行，结果 `document_key` 序列（截断到 `limit`、含 `document_key` 顺序）与 `vectors.json` 完全一致。
- 需要回退的查询特征：trigram 主检索 0 命中，回退因二字词命中产生结果。