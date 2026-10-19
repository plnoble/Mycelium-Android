# P2-2 开工前方案审核报告（Codex）

- 审核对象：`wp/P2-2-hearth-chat` 的方案及当前工作树快照 `e94599bc5bdd826dc9abb5c072afe2a99134f9e6`，对照 `main @ 9d3fd82`。当前工作树为 detached HEAD，本报告以该快照为准。
- 审核依据：[审核要求](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/docs/reviews/2026-10-18-P2-2/codex-prompt-plan.md)、[任务书及 2026-10-18 修订](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/docs/work-packages/P2-2-hearth-characters-chat.md:87)、外部 `REVIEW-GUIDE.md`。
- 审核性质：开工前方案审，没有本次交付报告。
- **结论：需修改。**

app 注入 `ChatModel`、保留独立加密库、人物默认本地的方向符合修订要求。但仅增加模型字段、`purpose` 和完成后的“不保存”判断，不能覆盖现有实现的云端接口差异、取消收尾、页面生命周期和初始化并发问题。以下必须修改项应先补入方案。

## 我重跑的验证

全程只读，没有修改文件、运行构建、连接手机或请求模型服务。

| 命令或检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `git status --porcelain=v1`，审核前后检查 | 均无输出，工作树干净 | 本次无交付报告 |
| `git rev-parse HEAD main`、提交历史检查 | 固定审核快照为 `e94599b`，基准为 `9d3fd82` | 与审核要求基本一致；额外包含审核要求入库提交 |
| `git diff --stat main...HEAD`、逐文件检查 | 23 个文件，新增 1623 行、删除 14 行 | 不适用 |
| `git diff --check main...HEAD` | 通过，无输出 | 不适用 |
| Python 标准库 SQLite **纯内存实验** | 确认：新增默认值会改变旧记录的模型选择语义；非事务迁移失败会留下已添加列；事务回滚能撤销添加列；两个过期列清单会触发重复添加列错误 | 是风险验证，不是项目测试 |
| 纯内存消息更新实验 | 确认：覆盖回答后行数不变，“行数不变”不能证明测试模式没有写库 | 同上 |
| 按源码复核架构检查及模块解析正则 | 当前 `:hearth` 的禁用词检查未发现违规；通用检查正则漏掉多参数 `include(...)` 中的模块 | 未运行 Gradle/JUnit |
| `scripts/check-all.ps1`、Android instrumentation、真机走查 | **未执行**：本次明确要求不修改任何文件，构建与设备测试会产生写入 | 不得沿用其他工作包结果作为本次通过证据 |

## 范围检查

- **未发现现有差异中的功能越界改动。** 差异集中于炉边模块、三个页面、数据库适配、入口接线、测试及开发记录。
- `contract/`、发布工作流没有本分支差异；已检查的差异未见模型、APK、keystore、数据库或真实密钥入库。
- 方案需要修改 `:net`、云端模型适配器以及 app 接线，属于修订授权范围。**不能因此增加 `:hearth → :roundtable → :net` 的传递依赖。**
- 总计划和指南中的“炉边永不联网”“云端提示拿不到炉边数据”，应按新修订解释为：默认本地；经逐个人物确认后，允许该人物的数据通过指定云端模型发出；炉边内容仍不得进入圆桌业务。
- 没有找到当前仓库或其已检查父目录中的 `AGENTS.md`。

## 验收逐条

本表中的“通过”仅表示相应方案或静态约束成立，不代表功能已经交付；有明确缺口的方案记“不通过”，需要实现和执行证据的项目记“无法验证”。

| 验收项 | 结果 | 证据及可达条件 |
|---|---|---|
| 模型三选一；默认跟随第一个已登记本地模型 | 不通过 | 方向明确，但缺旧人物映射、登记刷新、失效选择处理和实际模型一致性；见必须修改 5、7 |
| 云端采用已保存的服务和模型，经 app 注入 | 不通过 | 仅增加 `purpose` 无法完成适配；见必须修改 1 |
| 人物卡片、对话页显示“云端” | 无法验证 | 方案已列出；现有页面仍宣称聊天“不联网”，必须同步修改 |
| 首次切云端，确认后才生效 | 不通过 | 人物级布尔值不能约束后续更换接收服务；见必须修改 4 |
| 每条回答下方显示真实来源 | 不通过 | 现有来源在正文上方；还需使用本轮解析出的模型快照，不能读取人物当前选择；见必须修改 7 |
| 默认沉浸，保存对话 | 无法验证 | 现有保存路径存在，但修订后与取消、切模式的组合尚无验证 |
| 测试模式不写库、不留历史、离页清除 | 不通过 | 完成后跳过保存不足以覆盖取消与生命周期；见必须修改 2、3 |
| 测试模式停止、重新生成可用 | 不通过 | 当前重新生成依赖数据库会话；云端 `stop()` 无效；见必须修改 1、3 |
| 顶部模式开关及明显的测试模式标记 | 无法验证 | 方案已覆盖，待实现与截图 |
| 测试模式不读写人物记忆 | 无法验证 | 当前没有记忆实现；应建立模式隔离边界，P2-3 再补端到端验证 |
| 云端请求有“炉边聊天”审计用途 | 无法验证 | 需同步修改网关白名单分支、调用点和隐私记录用途标签 |
| 测试模式审计仅元数据；网关请求有“不记正文”选项 | 不通过 | 方案只按 purpose 判断，遗漏任务书明确要求的请求选项；见必须修改 8 |
| 五个预设首次放入，能编辑删除，删除后不复活 | 不通过 | 内容方向正确，prefs 与数据库分离存在并发和崩溃窗口；见必须修改 6 |
| 五个预设提示都含安全兜底 | 通过（方案） | 写入预设 `personality` 后会进入完整 system 提示；仍需逐个预设验证 |
| 预设默认本地、联网搜索默认关闭 | 无法验证 | 应明确全部使用 `default`；当前不实现搜索即可保持关闭，不必提前做 P2-4 |
| 人物增删改复制测试 | 无法验证 | 已有测试源码；新字段、确认状态及复制规则尚未覆盖 |
| 提示空字段、满字段与截断测试 | 不通过（方案完整性） | 已有基础测试，但超长当前问题及实际上下文不一致未覆盖；见必须修改 11 |
| 加密库文件无明文人物设定和对话 | 无法验证 | 已有 SQLCipher 设备测例和明文对照；未运行，且现有设备测例未覆盖生产 Keystore 初始化 |
| `:hearth` 架构测试通过并有效守住边界 | 不通过（覆盖完整性） | 专用检查存在，但通用模块发现和传递依赖存在盲点；见必须修改 10 |
| 本地 Qwen3.5-4B 三轮、停止、重新生成、杀进程重开 | 无法验证 | 待修订实现后真机执行；方案摘要应补回“杀掉重开后仍在” |
| 飞行模式下重复本地操作 | 无法验证 | 不能只验证单次发送，还应覆盖重开、停止和重新生成 |
| 假服务云端走查及确认弹窗 | 无法验证 | 必须用调试包、假密钥、`adb reverse`，补慢响应、停止和错误路径 |
| 测试模式聊两句，退出后历史没有该段 | 无法验证 | 还应覆盖底栏离开、切人物、切模式及晚到回调 |
| 约 1500 token 提示，三次分别记录读提示和生成速度 | 无法验证 | 现有短提示测例不能替代；需记录实际 `promptTokens`、两阶段耗时及生成 token 数 |
| 七类截图齐全、正式版及版本号前后不变 | 无法验证 | 需补齐任务书列出的截图和正式包前后状态证据 |
| 全新 worktree、已提交版本、唯一门禁零失败零警告 | 无法验证 | 脚本静态上能发现 `:hearth`；最终必须实际完整执行，不能只跑模块子集 |

## 专项检查

### 隐私与出网

允许云端后，实际离开手机的内容不止当前输入：

- 人物姓名、性格、说话方式、背景、关系和开场白等 system 提示；
- 当前问题；
- 沉浸模式下保留下来的历史轮次，**包括切换到云端之前在本地聊过的内容**；
- 将来 P2-3 在沉浸模式下加入的该人物记忆。

测试模式仍会发送人物设定和本次问题；若设计为临时多轮上下文，还会发送当前测试会话的临时轮次。它必须排除持久历史及人物记忆。

**“审计不记正文”只减少手机端审计留存，既不代表不出网，也不保证服务端不留存。** 应保留“测试模式 · 不留记录”要求，同时在云端状态附近说明其范围是本机聊天记录，云端仍会接收内容。

现有审计在 [AuditLog.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/net/src/main/kotlin/com/xavierxia/mycelium/net/AuditLog.kt:22) 直接写 JSON；app 的位置为 `privacy/audit.json`，并有 `.partial` 临时文件。按修订“其他用途照旧记正文”，沉浸模式云端提示将出现在这份**加密库之外的明文审计**中。不能以“SQLCipher 文件搜不到明文”宣称应用私有目录没有聊天明文。该审计例外应明确说明，不能扩散为普通调试日志。

### 模块和数据边界

app 直接构造通用 `CloudChatModel` 再交给炉边调用是可行的。不能复用 `ChatService.ask()` 或 `CloudChat` 的完整圆桌流程：它们包含圆桌检索、提示验证及圆桌数据库写入。

源码包名为 `com.xavierxia.mycelium.hearth` 的 app 文件并不属于 Gradle `:hearth` 模块；应按模块依赖和实际数据流检查，不能只看包名。

### 花钱、密钥与真实语料

本次未发出模型请求，未使用真实 API key、手机数据或真实用户语料。现有网关不把认证头写进审计，方向正确。后续假服务测试必须同时断言审计文件中没有密钥和认证头，错误回显也不能成为正文日志。

### 测试、契约和提示词

未改契约或蒸馏提示。预设安全句进入人物 system 提示符合本包范围，但“字符串存在”只能证明提示被传入，不能证明模型一定遵循。

当前 JVM SQLite 测试与设备 SQLCipher 测试职责不同；前者不能证明加密，后者也不能自动证明生产 Keystore、升级和取消路径正确。

## 必须修改

### 1. 云端模型适配不能只增加 `purpose`

**位置：** [AnswerModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/AnswerModel.kt:64)、[HearthViewModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:167)。

**输入与错误：** 用户选择云端人物，发送普通闲聊并点击停止。现有适配器固定发送 `response_format=json_object`，忽略传入的 `GenerationParameters`，整段响应后才 emit，`stop()` 则直接返回。若照现有炉边收集位置替换本地引擎，同步 HTTP 还会在主线程执行，导致主线程网络异常或界面阻塞。

**应修改为：** 明确区分圆桌 JSON 协议与炉边自然语言协议，传递生成参数，安排后台网络执行，并让停止及取消真正终止当前网络操作。仅切到 IO 线程不能解决阻塞请求的取消。保留圆桌原默认行为；炉边的流式要求应有对应实现，单次 emit 不能当作逐步生成验收。

**验证：** 假服务检查请求字段、参数、用途；延迟返回期间停止；停止后无晚到回答；验证多段增量，同时回归圆桌原协议。

### 2. 测试模式必须覆盖所有收尾路径，并固定本轮归属

**位置：** [HearthViewModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:178)。

**输入与错误：** 测试模式生成了一部分后取消。现有 `CancellationException` 分支会在 `NonCancellable` 中调用 `finishTurn()` 或 `replaceTurn()`；只修改正常完成分支仍会写库。若收尾时读取当前 `mode`，测试生成中切到沉浸模式也可能把测试内容保存。

另外，`finishTurn()` 在完成时读取当前 `openConversation`。生成期间新建或切换对话，会把原问题和回答写入错误会话。若数据库写入已完成、协程在返回主线程时收到取消，异常分支再次调用保存，还可能重复提交。

**应修改为：** 发送时固定人物、模式、会话、问题、模型及来源标签；所有完成、停止、取消和异常路径共享一次性收尾规则。测试请求只能更新临时内存，不能追加或覆盖持久消息；沉浸请求最多提交一次。禁止或正确处理生成期间的目标切换。

**验证：** 覆盖正常完成、停止、取消、写入返回前取消、切模式、新对话及切历史；检查完整记录和调用次数，不能只比较行数。

### 3. 测试模式需要独立的临时会话与离页处理

**位置：** [HearthViewModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:123)、[HearthChat.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/HearthChat.kt:13)、[MyceliumApp.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/ui/MyceliumApp.kt:99)。

**输入与错误：** 测试模式没有持久会话 ID，当前 `regenerate()` 会直接返回；保留旧 ID 则会重新读取旧历史。点击底栏离开炉边不会调用 `closeCharacter()`，Activity 范围的 ViewModel 也不因此销毁。即使调用现有 `closeCharacter()`，它仍未清除 `messages`、会话列表或停止任务。

**应修改为：** 临时状态至少保存最后问题、回答及生成所需上下文。停止后可以在当前页面保留部分回答，以便阅读或重新生成，但不得落库；离页时清除。进入测试模式需隔离已打开的持久历史；返回沉浸模式时不得把测试内容补存。返回按钮、系统返回、底栏切换及切人物都要收口，旧异步回调不得恢复已清除的内容。测试文本不要进入可持久恢复的页面状态。

**验证：** 从有旧历史的页面切入测试模式，发送、停止、重新生成，再通过各类出口离开；重进后临时消息消失，旧历史完整且未被读取进测试提示。

### 4. 云端确认必须绑定接收目标，不能只有人物级布尔值

**位置：** 方案第 1、4 项；[CloudStore.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/CloudStore.kt:33)。

**输入与错误：** 人物确认发送给服务 A 后改选 B，或者在云端设置中保留同一 `service_id`、把接口地址改为 B。若 `cloud_confirmed=true` 继续有效，内容就会发送给原确认未覆盖的接收方。复制人物时照搬确认状态，也绕过新人物的首次确认。

**应修改为：** 确认状态至少绑定“人物 + 服务 ID + 规范化接收端点”，并在实际发送前校验。接收方变化时重新确认；拒绝时不启用云端。新副本不得凭原人物的确认直接出网。单纯换模型且接收目标不变，不必一律重复弹窗。

说明应覆盖人物设定和可能发送的既有历史。`service_name` 只能作展示快照，不能充当接收目标身份。

### 5. 数据库升级要保留旧选择语义，并保证初始化和迁移原子性

**位置：** [Schema.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Schema.kt:3)、[Sql.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Sql.kt:21)、[HearthDatabase.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/hearth/HearthDatabase.kt:27)。

**输入与错误：**

- 旧人物指定模型 B，升级统一补 `model_kind=default`，而登记列表首个是 A：旧人物静默改用 A。
- 若若干次 `ALTER TABLE` 独立提交，中途失败会留下部分新列。
- 两个会话同时读取列清单，都认为缺少某列，随后重复添加。
- 首次打开库时并发生成口令，可能出现数据库采用的口令与 prefs 最终保存的口令不一致。

**应修改为：** 新人物默认 `default`；旧记录保留指定本地模型语义，空模型 ID 的转换规则单独定义。只补新列及迁移元数据，不覆盖人物内容和原模型字段。列检查、DDL 和迁移完成标记在同一受控事务中执行，失败回滚；初始化入口串行化，覆盖口令创建和迁移。重复打开及已有部分新列的库都应有明确恢复行为。

`PRAGMA table_info` 可以用来实现幂等检查，但本身不提供事务或并发保护。

**验证：** 空库、旧完整库、不同部分迁移状态、故障注入后重开及并发首次打开。另用 SQLCipher 设备环境验证生产升级路径。

### 6. 预设人物和“已初始化”标记必须作为一个原子操作

**位置：** 方案第 7 项；[CharacterStore.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/CharacterStore.kt:56)。

**输入与错误：** 写入五个人物后、写 prefs 标记前崩溃，下次重复插入；先写标记后崩溃则永久缺人。两个入口同时检查到“尚未放过”，也会重复插入，当前 `create()` 使用随机 ID，不能去重。

**应修改为：** 把永久初始化标记放进炉边数据库，与五条预设同事务提交，并串行执行初始化。标记表达“曾完成初始化”，不能用“当前人物数为零”代替。用户删除预设后不能重新补齐；既有自建人物也不能阻止首次放入预设。

**验证：** 并发初始化、各步骤故障、重新打开、删除一个及删除全部后再打开；五个预设均为默认本地，搜索关闭。

### 7. 模型解析、单例引擎操作和来源标签必须保持一致

**位置：** [HearthViewModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:229)、[LocalModelEngine.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:65)、[LocalModelsViewModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/localmodels/LocalModelsViewModel.kt:95)。

**输入与错误：** 打开炉边后去模型管理导入或删除模型，再返回发送，ViewModel 缓存的登记列表可能已过期。`ensureModel()` 与 `generate()` 分别受引擎锁保护，中间仍可能被另一个页面加载或卸载模型，造成实际生成模型与人物选择不同，或生成时报“请先加载模型”。模型管理页的异步离页卸载也可能与炉边开始生成相遇。

**应修改为：** 每轮解析最新可用选择，固定实际模型和展示名；用共享协调机制覆盖本地模型的选择、加载和生成生命周期。停止、卸载只能作用于明确拥有的操作。失效的本地模型、服务或已保存模型应明确报错，不得静默换供应商或转云端。

云端与本地**没有必须相互卸载的技术要求**：云端不占本地引擎。若为释放内存卸载，应等待本地任务结束并确认操作归属。云端停止不能继续调用 `LocalModelEngine.shared.stop()`。

来源标签应取本轮实际解析结果；流式中和保存后保持一致，并放到回答下方。云端状态不能读取本地引擎残留的 `lastStats`。

### 8. 增加请求级“不记正文”选项，同时保留 purpose 的强制保护

**位置：** [Gateway.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:9)。

**输入与错误：** 测试模式调用点遗漏 purpose，适配器沿用默认 `RoundtableChat`，正文就会落审计。“按 purpose 一律不记”只能保护正确标记的请求，不能让网关自动识别真实聊天模式。只修改成功路径还会在连接失败路径写入正文，现有两处审计构造都直接使用 `request.body`。

**应修改为：** 按任务书增加请求选项，例如 `omitAuditBody`；测试请求从固定的本轮模式同时派生用途和该选项。网关采用以下不可放宽规则：

`不记录正文 = 请求要求不记录正文，或 purpose 为 HearthChatTest`

该规则统一应用于成功和失败审计。它只能修改审计内容，不能清空真正发送的 body，也不能把字数变为零。其他用途保持原有默认行为。同步补齐 `AllowedHosts` 和隐私记录标签。

**验证：** 正常响应、非成功状态、连接失败、超时、停止；假服务确实收到原始正文，而审计文件及临时文件没有该正文和认证信息。

### 9. 共享审计文件需要跨实例串行写入

**位置：** [AuditLog.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/net/src/main/kotlin/com/xavierxia/mycelium/net/AuditLog.kt:22)、[AndroidGateway.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/net/AndroidGateway.kt:9)。

**输入与错误：** 炉边、圆桌或模型列表请求同时完成，不同 `AndroidGateway` 实例都对同一文件执行“读旧列表、追加、覆盖”。后写者可能覆盖另一条记录，且共用 `.partial` 文件会产生额外竞争；无法保证每次出网都有审计。

**应修改为：** 使用共享审计仓储或按文件共享的锁，统一保护读取、追加和清空。只在单个新建 `AuditLog` 实例内部加锁不够。

**验证：** 多个独立实例并发记录，最终每条请求恰好一条且 JSON 完整；并发清空的语义也要明确。

### 10. 架构测试必须覆盖实际模块集合及传递依赖

**位置：** [ModuleBoundaryTest.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/contract-lib/src/test/kotlin/com/xavierxia/mycelium/contract/ModuleBoundaryTest.kt:20)、[HearthArchitectureTest.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/test/kotlin/com/xavierxia/mycelium/hearth/HearthArchitectureTest.kt:15)。

**输入与错误：** 当前声明为 `include(":model-api", ":llm", ":hearth")`，通用检查只解析单参数形式，实际没有遍历 `:hearth`。专用检查只禁止直接 `project(":net")` 等文本；若新增 `:roundtable` 依赖，传递连接到 `:net`，可能仍然通过。

**应修改为：** 正确枚举多参数声明和所有 `hearth` 前缀模块，检查生产传递依赖或采用严格允许列表。保留源码检查，但不能只用字符串命中代替依赖边界验证。

**验证：** 单参数、多参数声明均被发现；直接依赖网络模块、经其他模块传递依赖网络模块及直接引入 HTTP 库，都必须失败。

### 11. 上下文预算需要补齐超长当前问题和实际引擎配置的处理

**位置：** [Prompt.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Prompt.kt:14)、[HearthViewModel.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:227)。

**输入与错误：** 当前问题本身已超出预算且没有历史可删，`assemblePrompt()` 仍会返回超长提示，随后本地引擎拒绝。若同一模型在模型管理页以较小上下文加载，炉边仅比较模型 ID，就可能按自己的旧配置组装过大的提示。云端也不能直接假定使用本地引擎的上下文配置。

**应修改为：** 以本次实际模型配置确定预算；历史删尽后仍放不下人物设定和当前问题时，应明确拒绝并提示缩短，不能截掉安全句或人物设定。说明粗略 token 估算的限制及底层超限处理。

**验证：** 空历史下超长问题、超长人物设定、输出长度占满上下文、同模型不同上下文配置、截断后预设安全句仍完整。

### 12. 无痕验收不能只比较行数

**位置：** 方案第 8 项；[ConversationLog.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-plan-review/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/ConversationLog.kt:51)。

**输入与错误：** 测试模式误调用 `replaceLastAnswer()`，旧回答和时间被修改，行数完全不变；计划中的断言仍会通过。本次纯内存实验已复现这种假通过条件。

**应修改为：** 保留任务书要求的行数检查，同时比较消息正文、来源和会话时间等完整快照，并断言持久仓储写方法未被调用。模式控制应能通过注入假模型和仓储测试，不应只在 SQLCipher 无法加载的 Robolectric 环境检查页面存在。

至少覆盖正常完成、部分输出停止、协程取消、重新生成、切模式及离页后的晚到回调；再用设备测试确认真实 app 行为。

## 建议修改

1. **不必为了模型选择重建会话和消息表。** 现有 `messages.model TEXT` 已可保存完整来源标签；`conversations` 不必加模式列，因为测试会话根本不应入库。若以后需要按供应商查询，可另加结构化来源字段。旧回答来源不能随人物当前配置变化。

2. **安全兜底写进预设 `personality` 可以接受。** `systemPrompt()` 会保留该字段，截断逻辑也不裁剪 system。逐个测试五个预设即可；没有必须新增独立安全字段的依据。任务书要求的是所有预设，不能自行扩大为所有自建人物的不可编辑规则。用户主动编辑删除后的行为应与“普通人物、可编辑”语义一致。

3. **将云端服务解析集中在 app 的小型工厂中。** 一次返回实际模型、用途、审计策略和来源标签，减少各发送与重新生成入口遗漏参数。不要直接复用圆桌的全套聊天服务。

4. **明确审计展示与隐私边界。** host 可以展示目标域名，但多个服务共用 host 时无法区分保存的服务配置，可补非敏感服务标识。对沉浸模式正文审计的加密保存，可另行评估；本轮至少不要把它描述成“所有聊天内容仅存在加密库”。

5. **补全交付证据计划。** 七类截图、杀进程恢复、正式包版本前后对照、1500 token 三轮分阶段测速都要单列。测速记录实际 token 和耗时，不记录用户聊天正文；原有短提示测例不能替代本项。

## 无法验证的项目及原因

- 修订功能尚未实现，无法确认最终行为、编译结果和零警告门禁。
- 本次没有执行构建或设备测试，不能把历史工作包的测试数字、截图或正式版状态作为 P2-2 通过证据。
- 纯内存 SQLite 实验验证了迁移与测试断言风险，不能替代 Android SQLCipher、Keystore、真实并发和进程中断验证。
- 未执行真实云端请求；后续本包仍应只用电脑假服务验证。没有证据可以承诺服务端不保留测试模式内容。
- P2-3 尚未实现，当前只能审查“不读写记忆”的接口与模式边界，不能验收未来记忆行为。

额度读数：最新 rollout 中最后一条 `rate_limits` 为 **5 小时窗口已用 23%，周额度已用 19%**；事件时间 **2026-10-18 16:09:17（北京时间）**。