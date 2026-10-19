# P2-2 隐私与出网专项审核报告（Codex）

- 审核对象：`wp/P2-2-hearth-chat @ 01a5ff034d039e7f1644419540634c35220ed4fa`
- 对照基准：`main @ a54c6046c5bcd4e12d0560bdcbbb8619b364d62c`
- 审核工作树：`D:\Agent\Worktrees\Mycelium-Android\p2-2-special-privacy`，与 `D:\Agent\Project\Nivra` 的目标提交一致。
- 审核范围：方案审第 **1、3、4、8、9 条**，以及审计内容、云端告知和 `:hearth` 依赖边界。
- **结论：需修改。**

请求级“不记正文”和审计文件共享锁已落实；当前炉边模块也没有突破网络依赖边界。但停止后的收尾仍可能保存回答、重复写库，测试模式存在旧历史回填及输入文本留存问题，发送前的接收目标检查也存在缺口。现有构建和真机附件不能证明最新提交通过验收。

以下问题均来自源码和已有证据核查，未冒充真机复现。

## 我重跑的验证

全程只读，未修改文件，未运行 Gradle、设备测试或模型请求。

| 命令或检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `git status --porcelain=v1`，审核前后检查 | 工作树干净；目标仓库也无未提交改动 | 当前快照明确 |
| `git rev-parse HEAD main`，核对目标仓库 HEAD | 两处目标均为 `01a5ff0` | `DELIVERY.md` 对应的仍是旧提交 `eecbf7a` |
| `git diff main...HEAD --stat`、专项相关源码及差异检查 | 43 个文件，新增 4825 行、删除 61 行 | 最新修复尚未更新进交付报告 |
| `git diff main...HEAD --check` | 通过，无输出 | 无冲突 |
| 全仓相关调用点搜索 | 炉边云端调用经 `CloudChatModel → OutboundGateway`；未发现其他生产出网或直接写审计的绕行调用 | 与设计一致 |
| 按 `normalizeEndpoint` 源码规则做纯内存对照 | 常见端口、路径区分成立；发现查询参数大小写被错误合并的反例 | 现有测试未覆盖；这不是 Kotlin/JUnit 执行结果 |
| 读取既有构建汇总、日志 | 旧汇总为 113 例、零失败、零警告；日志有 `BUILD SUCCESSFUL` | 数字一致，但对应旧提交且标记工作树有未提交改动 |
| 阅读审计附件及云端确认、测试模式截图 | 附件只能证明旧版部分行为 | 测试模式审计数据与报告描述不一致，见必须修改 7 |

当前审核工作树没有 `build/check-all/`。读取的是目标仓库的 [summary.md](D:/Agent/Project/Nivra/build/check-all/summary.md:1)，其中明确写明提交为 `eecbf7a`、`worktree: has uncommitted changes`，不能作为 `01a5ff0` 的门禁通过证据。

## 范围检查

- 专项相关改动属于任务书修订授权范围：网关、云端适配器、炉边页面及状态管理、人物确认字段、审计与相关测试。
- 未发现 `contract/`、发布工作流的分支差异；变更文件清单中没有 APK、模型、数据库、keystore 或 `.env`。
- 当前仓库及已检查父目录没有 `AGENTS.md`。
- 数据库升级、预设事务、性能和完整 UI 验收不在本次专项结论内。

## 验收逐条

“通过（静态）”仅表示源码检查成立，不代表已执行当前提交的测试或真机验收。

| 验收项 | 结果 | 证据与说明 |
|---|---|---|
| 第 8 条：请求选项或 `HearthChatTest` 任一成立即不记正文 | 通过（静态） | `Gateway.kt:79` 统一计算 `recorded`，成功和异常路径均使用它 |
| 字数保留、实际请求正文不变 | 通过（静态） | 字数取原始 body 的 Unicode code point 数；网络写入仍使用原 body |
| 漏传单个保护参数或新建网关是否绕过 | 通过（当前调用链） | 炉边从固定的 `Turn.mode` 同时派生 purpose 和选项；新网关仍执行相同规则。未发现生产调用直接写 `AuditLog.record` |
| 第 1 条：所有停止、取消时序都真正终止请求 | 不通过 | 连接句柄登记前停止会漏掉 abort；协程取消没有绑定网络取消，见必须修改 1 |
| 停止、离页后无晚到回答和写库 | 不通过 | 取消分支仍调用保存；存在重复提交窗口，见必须修改 2、3 |
| 圆桌 JSON 协议保持原样 | 通过（静态） | 默认仍为 `RoundtableChat`、`naturalChat=false`、`response_format=json_object`；原参数行为和单次 emit 保留 |
| 第 3 条：测试模式隔离持久历史、仅内存、离开清除 | 不通过 | 模块层隔离成立；页面异步读取及输入框状态仍会突破隔离，见必须修改 3、4 |
| 测试模式重新生成 | 通过（核心逻辑） | 使用屏幕临时消息组装上下文，测试模式 `redo` 不写库；页面竞态仍需修复 |
| 第 4 条：人物、服务 ID、规范化地址绑定 | 不通过 | 绑定字段及复制清空已实现；实际发送使用缓存服务，规范化也存在碰撞，见必须修改 5、6 |
| 云端内容范围及测试模式限制告知 | 通过（当前源码） | 弹窗写明人物设定、本轮问题、历史、未来记忆，以及测试模式仍发送内容；截图尚是旧文案 |
| 第 9 条：读、追加、清空及 `.partial` 串行 | 通过（静态） | 相同文件路径共用 companion object 中的锁；三个操作均受锁保护 |
| 并发测例是否恒真 | 通过（测例有效性） | 8 个独立实例各写 25 条，检查总数、唯一数及 JSON 可读取，能够发现覆盖和重复；但不是 HTTP 请求端到端证明 |
| `:hearth` 不依赖 `:net` / HTTP | 通过（当前声明与源码） | 生产依赖为 `:model-api`，其下为协程库；未发现网络引用。当前架构测试执行结果未提供 |

## 专项检查

### 审计中到底保存什么

[Gateway.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:71) 和 [AuditLog.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/net/src/main/kotlin/com/xavierxia/mycelium/net/AuditLog.kt:65) 表明：

- **沉浸模式 `HearthChat` 会保存请求正文。** 人物设定、当前问题及纳入上下文的历史会出现在 `privacy/audit.json`；写入过程中也会出现在 `.partial`。这是任务书允许的设计，不能据 SQLCipher 加密而声称应用目录没有聊天明文。
- **测试模式新增审计条目的 `sentBody` 为空**，保留时间、host、用途、字数、状态及可取得的 token 用量。不会因此清除同一文件以前保存的沉浸记录。
- API key 仅进入请求 `Authorization` 头；审计不序列化 headers、响应正文或异常正文，URL 只记录 host。未发现配置密钥、设备序列号、本机路径被自动加入炉边请求或审计。
- 这不是任意正文的敏感信息净化器：如果用户把密钥或路径自己写进聊天，沉浸审计仍按设计保存该文字。
- 炉边提示链只读取人物字段和炉边会话，未发现圆桌检索内容混入。**整个审计文件是共享的**，正常的 `RoundtableChat` 条目本来就可以包含圆桌正文；应检查是否串入炉边条目，不能要求共享文件完全没有圆桌内容。
- 已有审计附件只是摘录，不能据此证明设备上的完整审计文件没有认证信息。

### 测试模式与云端告知

当前 [确认弹窗](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:314) 已明确说明发送内容及“不留记录”只约束手机侧。列表中的“不联网”限定为默认本地模型，未发现把云端测试模式直接宣称为“不联网”或“内容不外发”。

对话页的测试模式提示仍未重复说明云端会接收内容，建议补上，避免用户只看到“不留记录”。

### 审计串行与测试质量

[AuditLogTest.kt](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/net/src/test/kotlin/com/xavierxia/mycelium/net/AuditLogTest.kt:18) **不是恒真测试**：200 条不同标记、总数和去重数量的断言可以发现并发覆盖及重复，最终 `read()` 也要求 JSON 可解析。

它直接调用 `record()`，所以只能证明该并发追加场景，不能单独证明“每个实际 HTTP 请求恰好一条记录”，也未覆盖读、清空与追加交错。当前生产调用都使用同一固定审计路径，跨实例共享锁足以解决原来的 `.partial` 相互覆盖问题。

本次未调用付费模型、未读取真实用户语料；未发现专项相关代码把密钥写入普通日志。契约和蒸馏提示未发生本分支改动。

## 必须修改

### 1. 【P1】连接登记前停止，以及单独取消协程，仍可能继续出网

**位置：** [AnswerModel.kt:87](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/AnswerModel.kt:87)、同文件第 117–141 行；`Gateway.kt:117–128`。

**触发输入与错误：**

云端生成已进入 IO 执行，正在构造 payload 或打开连接，但尚未执行 `onOpen` 时点击停止。此时 `stop()` 设置 `aborted=true`，却因连接引用为空而无法断开；随后 `onOpen` 只保存句柄，不检查已经停止的状态，网关仍会写出 body。

此外，`generate()` 没有把协程取消绑定到 abort 句柄。仅取消收集协程不能主动结束同步网络操作；`onCleared()` 本身也没有调用云端模型的 `stop()`。

**应达到：** 停止状态与连接登记形成可靠的并发协议；已停止的请求不得继续发送，已登记连接必须被中止，协程取消也必须触发网络取消。

**补验：** 在句柄登记前、请求已发出等待响应时分别设置可控阻塞点，执行停止和协程取消，验证真实 abort 行为及停止后的发送数量。

### 2. 【P1】云端回答进入 buffer 后取消，仍会显示、保存，甚至重复提交

**位置：** [HearthViewModel.kt:384](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:384)、第 443–450、545–615 行。

**触发输入与错误：**

- 云端完整回答已 emit 到 `buffer`，IO 尚未返回主线程时点击停止。取消异常进入 `NonCancellable`，继续调用 `finishTurn()`；`stop()` 没有使 generation token 失效。结果是停止后仍可能追加测试消息，或保存沉浸消息。
- 数据库已完成 `chat.finish()`，但 `withContext(IO)` 返回主线程前收到取消，异常分支会再次保存。已有会话可能多出一轮，新会话可能生成两份。
- 在上述窗口离页，虽然 `abandonTurn()` 增加 token，`finishTurn()` 却在检查 token **之前**写库，因此仍可能保存离页后的回答。

这与项目所用协程版本的返回取消语义一致：后台操作已完成，也可能在返回原 dispatcher 时抛出取消异常。[协程 1.9.0 源码说明](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.9.0/kotlinx-coroutines-core/common/src/Builders.common.kt)。

**应达到：** 云端取消后未提交的结果直接丢弃；提交前检查轮次有效性，提交具有一次性保证，不能在取消异常分支无条件重试保存。保留本地部分回答的规则应与云端整段结果取消规则分别处理。

**补验：** 覆盖“emit 后、提交前”和“提交完成、返回前”两个窗口，断言消息内容、会话数和保存调用次数。

### 3. 【P1】未受轮次约束的历史读取，会把持久历史重新带入测试模式

**位置：** [HearthViewModel.kt:326](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:326)、第 348–359、399、438 行。

**触发输入与错误：**

沉浸模式点击一段历史，令 `openConversation()` 的数据库读取暂时阻塞；随后切入测试模式。切换先清空消息，但旧读取任务未被取消，也没有检查模式或 token，返回后会把持久历史重新写入 `messages`，同时模式仍是 TEST。

下一次发送时，测试模式 `chat.window()` 把这些 `messages` 当成临时上下文，持久历史便可能随测试请求发给云端。

同类问题还包括生成任务的 `finally`：无论 token 是否过期，都清空 `busy`、`streaming`、`streamLabel`。旧任务晚结束时可能清掉新任务的生成状态，使新请求的停止按钮消失。

**应达到：** 历史读取、切人物、切模式、关闭及生成收尾均受同一个会话生命周期约束；所有晚到回调，包括 `finally`，只能修改自己所属的会话。

**补验：** 阻塞历史读取后切测试模式、离页或切人物，再释放读取；验证旧内容不会恢复、不会进入新提示，旧生成结束也不能改变新生成状态。

### 4. 【P1】测试文本仍进入可保存恢复状态，切模式时输入框也没有清空

**位置：** [HearthSpace.kt:347](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:347)、第 353–356 行；`HearthViewModel.kt:271–275、326–341`。

**触发输入与错误：**

在测试模式输入内容，或停止后让问题回到输入框，再切换到沉浸模式，输入框文本仍然保留。模式切换只清 ViewModel 的部分字段，没有清 Composable 的 `text`。

该字段使用 `rememberSaveable`，还会进入 saved-instance-state 的 `Bundle`，不满足“只在内存临时会话、不进入可持久恢复页面状态”的要求。[Android 官方状态保存说明](https://developer.android.com/develop/ui/compose/state-saving)。

**应达到：** 测试输入及恢复的问题只放在临时会话中；切模式、切人物、关闭及离开时一起清除，不进入 saved state。`pendingQuestion` 也应统一清理。

**补验：** 覆盖未发送草稿、停止后恢复的问题、模式切换和状态保存；检查输入框、可恢复状态及数据库均没有遗留测试文本。

### 5. 【P1】发送前校验使用缓存服务，可能把新密钥发往旧地址

**位置：** [HearthViewModel.kt:505](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:505)、第 126–155、483–487、619 行；`HearthSpace.kt:57`。

**触发输入与错误：**

人物已确认服务 S 的地址 A。到设置中保留 S 的 ID，将接口改成**同 host 的另一端口或路径 B**，并更换 API key。返回炉边，在异步 `refresh()` 尚未完成时发送：

- `choose()` 使用旧的 `state.services`，仍按 A 校验通过；
- 请求使用旧地址 A；
- API key 却从当前设置即时读取，得到 B 的新密钥；
- 网关白名单按 host 判断，同 host 的地址变化不会阻止请求。

因此“每次发送前检查最新接收目标”并未成立，还存在地址与认证信息来自不同配置版本的问题。

**应达到：** 发送前从权威设置取得一致的服务快照，使用该快照完成确认校验、地址选择及密钥获取。刷新未完成时不能凭旧缓存发出请求。

**补验：** 暂停返回页面后的刷新，修改同服务的端口、路径及密钥后立即发送；断言确认前没有请求，且不存在“旧地址＋新密钥”。

### 6. 【P2】手工地址规范化错误合并了大小写不同的查询参数

**位置：** [CharacterStore.kt:251](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/CharacterStore.kt:251)；`CloudStore.kt:33–38`；`AnswerModel.kt:120`。

**触发输入与错误：**

同一个服务 ID，依次使用：

```text
https://api.example.net?tenant=A
https://api.example.net?tenant=a
```

设置层接受这两种地址。规范化函数只按 `/` 分割 authority，随后把包括查询串在内的部分转小写，两者得到相同确认目标。

实际发出的请求查询串却分别是 `tenant=A/chat/completions` 和 `tenant=a/chat/completions`。若服务按区分大小写的参数选择接收租户，实际接收目标已经变化，却不会重新确认。

常见无查询参数地址的 host 大小写、默认端口、尾斜杠处理成立；路径大小写和非默认端口也能区分。问题在于函数没有真正区分 URI 组成部分。

**应达到：** 对支持的地址格式正确规范化，只折叠允许忽略大小写的部分；如果接口根地址不支持 query/fragment，应在保存前明确拒绝，不能静默混入 host 处理。

### 7. 【P2】补齐当前提交的验证证据，并修正交付报告中的证据错配

**位置：** [DELIVERY.md:5](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/docs/reviews/2026-10-18-P2-2/DELIVERY.md:5)、[审计附件](D:/Agent/Worktrees/OMNIX-Mycelium/main/.omx/development/reviews/2026-10-18-android-P2-2/09-audit-hearth-entries.txt:1)、[HearthCloudModelTest.kt:65](D:/Agent/Worktrees/Mycelium-Android/p2-2-special-privacy/roundtable/src/test/kotlin/com/xavierxia/mycelium/roundtable/HearthCloudModelTest.kt:65)。

**当前证据与错误结论的对应关系：**

- 构建汇总是旧提交 `eecbf7a`，不能证明最新取消、确认及审计串行实现通过。
- 报告称审计附件含 `HearthChatTest` 206 字、正文 0；实际附件只有 **1 条 `HearthChat`**，正文 377 字，没有测试模式条目。
- 现有停止测例只检查最终答案为空，没有断开或完成时限断言。即使移除实际 abort、仅保留 `aborted=true`，等待假服务两秒后丢弃回答，该测例仍可能通过。
- `HearthCloudModelTest` 的构造器没有设置 `naturalChat=true`，不能证明炉边实际自然语言请求的参数和协议。
- 未找到覆盖上述 ViewModel 生命周期和取消提交窗口的测试。

**应达到：** 由开发线对修复后的已提交版本补跑门禁，补齐要求的受影响真机走查，并更新交付报告。补充真实测试模式审计证据及可控制时序的取消、离页、历史读取和确认校验测试。所有“通过”应能对应到具体提交与附件。

本次遵守只读要求，没有替开发线执行这些构建和测试。

## 建议修改

1. 云端测试对话页常驻补充“仅不保留手机聊天记录，内容仍发送给服务”，与确认弹窗保持一致。
2. 审计测试增加独立网关并发请求，以及读、清空、追加交错的用例，明确清空的先后顺序语义。
3. 补齐“不记正文”测试矩阵：两个保护分别单独生效，覆盖成功、HTTP 错误、超时和取消，并精确断言服务收到的正文、字数及审计中不存在认证头。

## 无法验证的项目及原因

- **当前提交的构建、单测、lint：** 按要求未运行 Gradle；现有证据只覆盖旧提交。
- **Android 实际网络断开的及时性：** 未连接手机；现有 JVM 停止测例不足以证明。
- **最新真机离页清空、地址变更重确认及取消竞态：** 未提供对应新版走查证据。
- **完整设备审计及 `.partial` 的运行时内容：** 附件只有旧版单条沉浸记录摘录，不能替代完整检查。
- **当前架构测试执行结果：** 只核查了生产依赖、源码和测试逻辑，没有执行测试。
- 本报告仅为隐私与出网专项审，不替代后续全量审。

Codex 额度读数：5 小时窗口已用 **65%**，每周已用 **26%**（最新 rollout 的 `rate_limits`，事件时间 `2026-10-18T09:00:37.405Z`）。