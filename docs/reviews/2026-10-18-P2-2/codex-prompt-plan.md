你是独立审核者（Codex）。这是安卓仓库工作包 **P2-2（炉边：人物和对话，按 2026-10-18 修订）** 的**开工前方案审**：开发方还没有按修订写代码，请你读任务书、现有代码和下面的实现方案，指出实现风险、容易漏的地方、以及验收标准里可能做不到的项。

## 输入

- 仓库（只读）：当前检出的 `wp/P2-2-hearth-chat`（已并入 `main` @ `9d3fd82`，之后有两个提交：合并提交和「并入 main 后清零警告」）。
- 任务书：`docs/work-packages/P2-2-hearth-characters-chat.md` —— **以文末「2026-10-18 修订」和「附录：预设人物」为准**，上文「云端模型：炉边不联网」「不做记忆」等已被修订作废。
- 审核指南：`D:\Agent\Worktrees\OMNIX-Mycelium\main\docs\work-packages\REVIEW-GUIDE.md`（按它的报告格式完整输出；本次是方案审，没有交付报告）
- 现有代码（这次要改的）：
  - `hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/`：`CharacterStore.kt`（人物仓储与字段）、`Schema.kt`（建表）、`Sql.kt`（会话接口）、`Prompt.kt`（提示拼装与截断）、`HearthChat.kt`、`ConversationLog.kt`
  - `app/src/main/java/com/xavierxia/mycelium/hearth/`：`HearthDatabase.kt`（SQLCipher + Keystore）、`HearthViewModel.kt`（状态与生成）、`HearthSpace.kt`（三个页面）
  - `net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt`（网关、白名单、`GatewayPurpose`）、`AuditLog.kt`
  - `roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/AnswerModel.kt`（`CloudChatModel` 实现 `:model-api` 的 `ChatModel`）
  - `app/src/main/java/com/xavierxia/mycelium/roundtable/ChatService.kt`（app 侧怎么拼云端模型）、`CloudSettings.kt`（服务与密钥）
  - `hearth/src/test/kotlin/com/xavierxia/mycelium/hearth/HearthArchitectureTest.kt`（`:hearth` 不许依赖 `:net`/HTTP）
- 背景：`docs/STATUS.md`、`docs/work-packages/README.md`、`docs/tracks/开发线.md`、`llm/README.md`、`docs/reviews/2026-10-17-P1-7/DELIVERY.md`（云端那条线的现状）

## 开发方打算这么做（重点审这个方案）

1. **人物的模型选择（三选一）**：`HearthCharacter` 现在只有 `modelId`/`modelName`（一个本地模型）。计划加：
   - `model_kind`：`default` / `local` / `cloud`；
   - 云端用 `service_id`、`service_name`（模型名复用 `model_name`）；
   - `cloud_confirmed`：某个人物第一次切到云端时弹一次说明、用户确认后才生效，所以要按人物记住「已确认」。
   - `default` 的含义：跟随全局默认；P2-5 做出全局默认之前，用「我的 → 本地模型」里登记的第一个。
2. **数据库升级**：`Schema.kt` 现在是 `CREATE TABLE IF NOT EXISTS`，老库没有新列。计划用 `PRAGMA table_info(characters)` 判断缺哪些列，再 `ALTER TABLE characters ADD COLUMN ...`（新列都给默认值），**不动老数据**。请重点看：这样升级够不够、失败会不会留下半升级的库、`ConversationLog` 那两张表要不要一起动。
3. **模型注入**：`:hearth` 只认 `:model-api` 的 `ChatModel`，由 app 注入（架构测试保留）。`HearthViewModel` 现在直接用 `LocalModelEngine.shared`，计划改成：
   - `default` / `local` → 用 `LocalModelEngine.shared`（本地引擎是进程内单例）；
   - `cloud` → 每次发送时用 P1-7 的服务配置拼一个云端 `ChatModel`（把 `:roundtable` 的 `CloudChatModel` 泛化：加 `purpose` 参数，默认值保持圆桌现状）。
4. **云端隐私**：
   - 人物卡片和对话页在人物用云端时标「云端」；
   - 第一次切到云端弹一次确认：「这个人物的聊天内容（以后还有它对你的记忆）会发给 <服务名>」；
   - 每条回答下面标「本机 · <模型名>」或「云端 · <服务名> <模型名>」。
5. **审计**：`GatewayPurpose` 加 `HearthChat`（界面标签「炉边聊天」）和 `HearthChatTest`（「炉边 · 测试模式」）。测试模式下网关**照样把正文发出去**，但审计只记时间、host、字数、用途和状态，**不记正文**——计划把「记不记正文」交给网关按 purpose 判定（`HearthChatTest` 一律不记），这样调用方想记也记不了。请审这个做法，以及要不要改成 `GatewayRequest` 上的显式开关。
6. **测试模式（无痕）与沉浸模式**：对话页顶部开关，默认沉浸。
   - 测试模式：不写库、不留历史、离开对话页就没有；不读也不写这个人物的记忆（P2-3 实现时遵守）；界面标「测试模式 · 不留记录」；停止和重新生成照常可用。
   - 计划实现方式：ViewModel 里一个 `mode` 状态，测试模式下生成完**不调** `ConversationLog.append` / `replaceLastAnswer`，也不读历史（窗口只含人物设定 + 当前问题）。
   - 请重点看：停止（协程取消）时「已生成的部分」要不要留、重新生成在测试模式下怎么定义、切模式时已打开的历史怎么处理。
7. **预设人物（5 个）**：第一次打开炉边、且从没放过预设时，把附录里的 5 个人物写进库；预设就是普通人物，可编辑、可删除，删掉的不再自动加回来（计划用一个 prefs 标记 + 「放过就不再放」的语义）。安全兜底那一句计划**写进预设人物自己的设定文字里**（`personality` 末尾追加），这样提示里一定有、用户在编辑页也看得见、可以改。请审：这样够不够，还是应该在 `systemPrompt()` 里按人物单独存一个「安全兜底」字段、预设之外的人物要不要也有。
8. **测试计划**：JVM 测——测试模式不写库（前后行数不变）、云端请求经网关并留审计、测试模式审计里没有正文、预设只放一次且删掉后不再出现、提示拼装含安全兜底、`:hearth` 架构测试仍通过；真机测——预设出现在列表、新建「老周」用本地 Qwen3.5-4B 聊 3 轮并停止/重新生成、切云端只连电脑假服务（`adb reverse`）、测试模式不留历史、飞行模式下本地能聊、1500 token 提示测 3 次速度。

## 约束

- 只在自己的分支上提交；不合并 main、不推送、不打标签。
- 不许提交模型文件、APK、keystore、密钥、构建产物、本机路径、设备序列号。
- 本地构建一律 `--offline`（`scripts/check-all.ps1` 是唯一交付门禁，零失败零警告）。
- `:hearth` **不许**依赖 `:net` 或任何 HTTP 库；云端只能由 app 注入。
- 炉边的库和自己的加密库继续与圆桌分开；不写对话内容日志。
- 不用真实 API key；云端只对电脑上的假服务验证。

## 请给这几样

1. **方案里会真正出错或做不到的地方**：数据库升级（老库、半升级、并发）、`ChatModel` 注入与本地引擎单例的冲突（同一进程只有一个模型，云端和本地切换时要不要卸载）、测试模式的「不写库」在协程取消路径上会不会漏写、预设「只放一次」的并发/重复、`cloud_confirmed` 的粒度（人物级还是服务级）、审计 purpose 决定「不记正文」的做法有没有漏洞。
2. **容易漏的地方**：修订里逐条要求（模型三选一、云端标记、首次确认、回答下面的标记、审计用途、测试模式界面标记与不读不写记忆、预设 5 个 + 安全兜底 + 默认本地 + 联网搜索默认关闭）有没有哪条按上面的方案做不到或会漏；验收标准逐条是否可达。
3. **隐私与出网的专项风险**：哪些内容会离开手机、审计里可能出现什么、测试模式的「不记正文」够不够、`:hearth` 的边界有没有被绕过的路径。
4. **结论**：只能是「通过 / 需修改 / 不通过」。每条「必须修改」写清「什么输入会出什么错」。用中文。

（额度读数请在报告末尾写一行：从 `C:\Users\87953\.codex\sessions\` 下最新 rollout 文件的 `rate_limits` 取；取不到就写「取不到」。）
