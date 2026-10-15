# 回答协议：校验与渲染规则（answer protocol v0.1.0）

- 真源实现：知衍 `core/persona_chat.rs`（`normalize_chat_output`）。本文件把其中的**接受 / 拒绝判定**与**渲染规则**写成语言无关契约；两端实现必须与本文件一致。
- 与 `system-prompt.zh.txt` 共用的常量：`max_statements = 12`；本文件其余上限：单条陈述 600 字、整答合计 6000 字、拒答原因 1000 字（一律按 Unicode 标量计数）。

## 1. 解析

1. 模型输出先剥掉可能的 Markdown 代码围栏（```json … ```）；解析失败 → 拒绝（原因：不是合法 JSON）。
2. 字段容忍度：实现可以只接受 `statements` / `refusal_reason` 两个顶层字段（参考实现的 Rust 结构带 `deny_unknown_fields`），多余顶层字段是否拒绝由实现自定，**不构成跨端差异**；`statements` 内条目同理。
3. 「修复式解析」（JSON 尾随逗号等宽容解析）是参考实现的容错行为，**不是契约**：消费端不得依赖它。

## 2. 判定顺序（短路拒绝）

1. `statements` 是多条 → 拒绝：`人物回答超过 {max_statements} 条陈述上限`。
2. `refusal_reason` 存在时先 trim；空串视为不存在。
3. `statements` 为空：
   - 有非空 `refusal_reason` 且 ≤ 1000 字 → **接受为拒答**（`epistemic_status = insufficient_evidence`），渲染文本 = 拒答原因；
   - 否则拒绝：`人物回答没有陈述，也没有说明拒答原因` / 超 1000 字拒绝。
4. `statements` 非空且 `refusal_reason` 非空 → 拒绝：`人物回答不能同时给出陈述和拒答原因`。
5. 逐条校验 statements（按输出顺序）：
   - text trim 后为空或 > 600 字 → 拒绝；
   - text 含 `[C` 或 `【C`（自写引用标记）→ 拒绝；
   - `kind` ∉ {`evidence`, `inference`} → 拒绝；
   - 模式 `strict_evidence` 且 `kind = inference` → 拒绝：`严格证据模式出现了推演陈述`；
   - `citation_ids`：逐项 trim、丢空项、去重（**去重后按字典序**输出，这是契约）后仍需非空，否则拒绝；
   - 任一 `citation_id` 不在本轮提供的引用 ID 集合中 → 拒绝：`人物回答引用了未提供的证据：{id}`；
   - 累计字数（trim 后 text 的 Unicode 标量数之和）> 6000 → 拒绝。
6. 全部通过 → 接受。

## 3. 渲染规则

1. 每条接受的陈述：`kind = inference` 时前缀 `【推演】`，`evidence` 不加前缀。
2. 形式：`【推演】(可有)text [C1][C7]…`——text 后跟一个半角空格，再按（字典序去重后的）`citation_ids` 依次输出 `[C{n}]`，之间无分隔。
3. 陈述之间以空行（`\n\n`）连接。
4. 引用标记**只由程序添加**（见上）；模型原样输出 [C1] 会被步骤 2.5 拒绝。
5. 返回给用户的引用卡只含本轮真正被引用的引用（未提及的引用不返回）。

## 4. 向量样例

`vectors/*.json` 是「模型输出样例 → 期望结果」的验证集，每条含：
- `input`：`mode`、`output_text`（模拟模型原文，可为非法 JSON 前的原始形态）、`offered_citation_ids`；
- `expected`：`accepted`（布尔）、`refusal_reason`（拒答时）、`rendered`（接受且有陈述时的渲染文本）、`error`（拒绝时判定的原因类别，见文件内约定）。

两端实现必须对每个向量给出完全一致的 `accepted` / `refusal_reason` / `rendered` / `error`。