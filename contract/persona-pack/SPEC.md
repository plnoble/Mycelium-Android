# 角色包 v0 格式说明（mycelium-persona-pack）

- 契约版本：`0.1.0`（与 `contract/VERSION` 一致）
- 真源：知衍（Windows 版）仓库。安卓仓库只复制 `contract/` 并校验，不改。
- 配套文件：`manifest.schema.json`（manifest 的 JSON Schema，draft 2020-12）、`pack-db.sql`（包内 SQLite 建表语句）。

## 1. 容器

- 角色包是 `.mpack` 文件，本质是 zip 归档，固定包含两个条目且顺序固定：
  1. `manifest.json` —— 本文件定义的 manifest；
  2. `persona.sqlite` —— 按 `pack-db.sql` 建的只读 SQLite 数据库。
- zip 内文件名、顺序、时间戳固定（时间戳统一为包生成时间的整秒，或由导出器在 manifest 外固定约定）；向量文件、签名留到后续版本（v0 对应 manifest 的 `embedding=null`、`signature=null`）。
- 消费者必须先校验 `logical_sha256`（见 §3）与 `checksums.json`，再使用包内数据。

## 2. manifest 字段（`manifest.json`）

| 字段 | 必填 | 说明 |
|---|---|---|
| `pack_format` | 是 | 恒为 `"mycelium-persona-pack"` |
| `pack_version` | 是 | 本格式版本，恒为 `"0.1"` |
| `contract_version` | 是 | 契约版本号，v0 为 `"0.1.0"` |
| `persona_id` | 是 | 人物 ID（与知衍 persona 表的 `persona_id` 一致） |
| `display_name` | 是 | 人物显示名 |
| `version_id` | 是 | 已确认发布版本 ID（对应知衍 `persona_versions.version_id`） |
| `version_label` | 是 | 版本标签，如 `"v1"` |
| `published_at` | 是 | 用户确认发布该版本的时间（RFC 3339） |
| `data_cutoff` | 是 | 该版本收录材料的数据截止时间（RFC 3339） |
| `counts` | 是 | 统计对象：`sources` / `claims` / `themes` / `relationships` / `evidence` 五个非负整数 |
| `fts_tokenizer` | 是 | 恒为 `"trigram"`；实现须按 `retrieval/SPEC.md` 提供等价检索 |
| `answer_protocol_version` | 是 | 回答协议版本，v0 为 `"0.1.0"` |
| `includes_full_text` | 是 | 布尔：包内 `source_texts` 是否含原文全文 |
| `logical_sha256` | 是 | 包内数据的逻辑哈希（64 位小写十六进制，见 §3） |
| `embedding` | 否 | 预留对象，v0 一律为 `null` |
| `signature` | 否 | 预留对象，同步阶段再加，v0 一律为 `null` |

## 3. 逻辑哈希 `logical_sha256`

- 定义：把包内各表行按**规定顺序**导出成规范化 JSON Lines，拼接后取 SHA256（小写十六进制）。
- 导出行定义（每行一个数组，元素顺序即列顺序；`null` 与缺失一视同仁）：

| 表 | 行顺序 | 行内容 |
|---|---|---|
| `pack_info` | 按 `key` 字典序 | `[key, value]` |
| `persona` | 按 `persona_id` | `[persona_id, display_name, created_at, updated_at]` |
| `sources` | 按 `source_id` | `[source_id, source_kind, title, author, platform, published_at, source_url, revision_id, status, content_sha256, file_sha256, normalized_body_byte_length]` —— `normalized_body_byte_length` 为 `normalized_body` 的 **UTF-8 字节数** |
| `source_texts` | 按 `source_id` | `[source_id, normalized_body]` |
| `claims` | 按 `claim_id` | `[claim_id, claim_record_id, source_id, revision_id, claim_text, claim_kind, claim_role, attribution_json, method_structure_json, confidence]` —— `attribution_json` 按数据库内**原始字符串**参与，不解析、不排序键、不改写 |
| `claim_evidence` | 按 `(claim_id, body_start, evidence_id)` | `[evidence_id, claim_id, body_start, body_end, evidence_text, evidence_sha256]` —— `body_start`/`body_end` 为 **UTF-8 字节区间（左闭右开）**，指向 `source_texts.normalized_body` 的字节偏移 |
| `themes` | 按 `(theme_order, theme_id)` | `[theme_id, theme_record_id, name, summary, payload_json, theme_order]` |
| `theme_claims` | 按 `(theme_id, claim_id)` | `[theme_id, claim_id, membership_role]` |
| `relationships` | 按 `relationship_id` | `[relationship_id, from_claim_id, to_claim_id, relationship_kind, impact_level, rationale, status, payload_json]` |
| `snapshots` | 按 `snapshot_id` | `[snapshot_id, version_id, version_label, published_at, data_cutoff_at, snapshot_sha256]` |
| `search_docs` | 按 `document_key` | `[document_key, document_kind, source_id, claim_id, theme_id, source_status, title, body]` |

- 表之间按上表顺序拼接；每行一个 JSON 数组一行（无额外换行问题：行末统一 `\n`）。全文件最后一个字节即最后一行的 `\n`。
- **值编码（跨语言实现必须一致）**：
  - SQL 整数列导出为 JSON **字符串**（十进制，如 `theme_order` 存为 `"1"` 而非 `1`）；
  - NULL 导出为 JSON `null`；
  - 文本列按 UTF-8 原样参与（不做空白折叠）；`*_json` 列按数据库内原始字符串参与，不解析、不重排键、不改写；
  - 字符串转义与 serde_json/Rust 标准输出一致：`"`→`\"`、`\`→`\\`、`\b`→`\b`、`\f`→`\f`、`\n`→`\n`、`\r`→`\r`、`\t`→`\t` 使用**短转义**；其余控制字符（U+0000–U+001F 中未列出的）使用小写十六进制 `\u00xx`。
- **微型样例**：`themes` 一行 `(theme_id="T1", theme_record_id="rec-9", name="定投", summary="纪律", payload_json="{}", theme_order=1)` 导出为：
  `["T1","rec-9","定投","纪律","{}","1"]` —— 最后一个元素是字符串 `"1"`。
- 检索词项表 `search_fts` 是 FTS5 虚拟表，**不参与**逻辑哈希；由 `search_docs` 决定。
- **确定性要求**：同一已发布版本导出两次，`logical_sha256` 必须相同。文件字节尽量相同（固定 `page_size=4096`、固定插入顺序、写完 `VACUUM`），但以逻辑哈希为准。

## 4. 收录规则

- **只收已确认发布的版本**（知衍 `persona_versions.status='active'` 对应的快照，或用户显式指定发布过的版本 ID）。候选版本不得进包。此约束由 P1-1 导出器执行，本 SPEC 声明为格式规则。
- sources 只含**该快照可达、且冻结在发布时点**的来源与修订：导出经 `snapshot_claims → claims` 取每篇来源的快照内 `revision_id`（不取 `current_revision_id`）。发布后新增的来源、发布后改稿的新修订都**不得**进入包；发布后改稿时包内仍取旧修订正文。无法保持引用一致时拒绝导出。
- `source_texts` 是否收录全文由 `includes_full_text` 决定。
- `search_docs` 从包内五张内容表派生（派生规则见 `retrieval/SPEC.md` §2），不另行携带生成时代元数据。

## 5. 环境要求

- 打开 `persona.sqlite` 的 SQLite 必须 **≥ 3.34 且启用 FTS5**（`tokenize='trigram'` 需要）。
- 安卓 minSdk 29 自带 SQLite 不支持 trigram，安卓侧必须自带满足上述要求的 SQLite 版本（见安卓 P0-7）。

## 6. 版本规则

- 本 SPEC 语义变化（字段、表列、哈希规则、检索/回答协议）必须升 `contract/VERSION` 并更新 `checksums.json`。
- 检索规则写进契约后，改桌面端检索行为即等于改契约：先在此 SPEC/`retrieval/SPEC.md` 升版本，再改实现，并同步安卓仓库。
- `pack_version` 是格式号，只在容器/格式破坏性变化时升；`contract_version` 跟随 `contract/VERSION`。