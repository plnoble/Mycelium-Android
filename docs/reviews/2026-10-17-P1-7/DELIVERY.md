# P1-7 交付报告（返工后）

- 分支：`wp/P1-7-roundtable-chat`，从 `14e5058` 拉出，返工前先并入最新 main `1143dc4`（合并提交 `c5fd7bc`）。
- 提交：返工 `51612ef`；真机走查发现的修复 `f8c3ca0`；测试改名 `f3e4db0`；设备测例修正 `66e3a5a`。
- 版本号：0.3.0（versionCode 4）。没有推送，没有打标签，没有发布，没有合并。
- 出具日期：2026-10-18。
- 本报告替换首版交付报告。首版按旧任务书实现（含单价、每月上限、预算拦截、单模型名设置），已按 2026-10-17 修订后的任务书返工；下面只描述返工后的状态。

## 返工内容

1. 云端设置改成多服务：「我的 → 云端模型」可添加多个服务，每个填名称、接口地址、API key；新建时地址预填 `https://api.deepseek.com`。密钥由 Keystore 包装保存，保存后只显示末 4 位，不进日志、审计、崩溃报告和截图。
2. 拉取模型：点「拉取模型」调 `GET {接口地址}/models`（带 key），经 `:net` 网关发出并记审计（不含 key）。列表可勾选、多选保存；再次拉取可增减；拉取失败（地址错、401、超时、返回格式不认识）给中文说明，不清掉已保存的模型。
3. 聊天选模型：聊天页从已保存的模型里选一个，记住上次选择（重开应用仍在）；回答下面写「云端 · <服务名> <模型>」。
4. 删除全部预算相关：单价、每月上限、`CostEstimate`、`BudgetExceeded`、`requireWithinBudget`、「本次估算」和对应测试。接口返回 token 用量时只记进审计，不拦截。
5. 对齐回答接口：云端回答 `CloudChatModel` 实现 `:model-api` 的 `ChatModel`（整段发出，非流式）。检索、拼提示、校验、存库不变。
6. 「我的」里的模型页分「本地」「云端」两组。
7. 走查发现并修复：保存模型后已保存服务卡片不刷新（保存不改变服务列表本身，Compose 不重组）。修复 `f8c3ca0`，修复后保存即刷新（截图 08、09）。

## DeepSeek 核对

- 核对日期 2026-10-17，来源 DeepSeek 官方 API 文档 `https://api-docs.deepseek.com`。
- 接口地址 `https://api.deepseek.com`；模型列表用 `GET /models` 拉取；聊天 `POST /chat/completions`，请求带 `response_format: {type: json_object}`，思考用官方 OpenAI 形式关闭（`thinking: {type: disabled}`）。
- 「API 数据是否用于训练」在开放平台文档里没有明文条款，界面照写「未找到明文条款」，并给出隐私政策链接。
- 价格和每月上限不再核对（产品里已删除）。

## 电脑上的结果

全新 worktree，离线，对 `f3e4db0` 复跑：

| 命令 | 结果 |
|---|---|
| `:contract:test` | 已通过（15 个） |
| `:model-api:test` | 已通过（3 个） |
| `:net:test` | 已通过（8 个） |
| `:roundtable:test` | 已通过（17 个） |
| `:llm:testDebugUnitTest` | 已通过（11 个） |
| `:app:testDebugUnitTest` | 已通过（27 个） |
| `:app:lintDebug` | 已通过，0 error |
| `:app:assembleDebug` | 已通过 |
| `:app:assembleDebugAndroidTest` | 已通过 |

合计 81 个测试，0 失败；这次复跑强制重跑了测试任务，所有测试结果都来自这次复跑。

覆盖要点：

- 拉取模型：正常列表、空列表、401、超时、返回格式不认识。
- 多选保存；再次拉取后增减；拉取失败时已保存的模型不丢。
- 拉取模型和聊天都走网关白名单；审计记录里没有 key、没有认证头。
- 没有预算拦截：连续发送不会被拒。
- 一段对话记住开始时的角色包版本，之后追加不改版本；删除后消息和会话都空。
- 架构测试仍通过：除 `:net` 外模块不依赖 HTTP 库；`hearth` 前缀模块不许依赖 `:net` 的规则已写好。

JVM 假服务不是 MockWebServer（离线缓存没有），是 JDK 的 `ServerSocket`；JVM 对话测试用内存里的普通 sqlite，加密在真机验。

## 真机

设备是 P0-7 用的红魔（Android 16）。假服务跑在电脑的 `127.0.0.1:8765`，用 `adb reverse` 转到手机的同一地址；没有向 DeepSeek 发请求，没有使用真实密钥。手机上填的是只用于走查的假密钥。

- instrumented test：已通过。`DeviceConversationTest` 1 个（明文对话句子不出现在 SQLCipher 库文件字节里）、`DeviceRetrievalTest` 2 个（真机 SQLCipher 打开样例角色包跑契约检索向量和绑定查询），0 失败。原始记录在 Windows 评审目录的 `device-instrumented.txt`。
- 走查（全部已通过）：
  - 导入虚构人物「陆川」，人物页、主题、观点、搜索正常。
  - 「我的 → 云端模型」新建服务（名称「测试服务」、地址 `http://127.0.0.1:8765`、假密钥）；保存后卡片只显示 key 末 4 位。
  - 拉取模型列出 3 个（`deepseek-flash`、`deepseek-pro`、`deepseek-coder`）；勾选 2 个保存；卡片立即刷新为「已保存：deepseek-flash、deepseek-pro」。
  - 聊天选 `deepseek-pro`：有依据一问（显示原文依据句和引用号），推演一问（显示「【推演】」）；聊天中途把模型换成 `deepseek-flash` 问第三个（包里没有依据，回答「当前材料中没有找到足以回答这个问题的证据」）。引用号点开有依据卡。
  - 杀掉应用重开：聊天的模型选择仍是 `deepseek-flash`。
  - 停掉电脑上的假服务后点「拉取模型」：给中文失败说明（连不上这个地址），已保存的 2 个模型还在。
- 审计（「我的 → 隐私记录」）：5 条，都是走查发出的请求——拉取模型列表 2 条（1 条 HTTP 200，1 条停服务后的失败记录，状态码空）、圆桌聊天 3 条（HTTP 200，各带 token 用量 120 入 / 36 出）。没有更新用途的请求；没有 key，没有认证头。
- 走查对话库：已通过。应用私有目录里的对话库 20480 字节；按字节搜索三句提问和「deepseek」「陆川」「测试服务」都是 0 次。
- 截图：20 张，替换旧的 15 张；裁掉顶部状态栏，逐张核对过。放在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P1-7/`，没有放进本仓库。
- 共存：已通过。调试包 `com.xavierxia.mycelium.debug` 是 0.3.0（versionCode 4）。正式版 `com.xavierxia.mycelium` 走查前后都是 0.1.1（versionCode 2），最后更新时间仍是 2026-10-10 05:23:04。没有卸载，没有覆盖。
- 收尾：手机下载目录里的走查文件夹已删除；`adb reverse` 已撤掉；假服务已停止；输入法仍是原来的默认输入法。

## 没有做

- 没有推送，没有打标签，没有发布，没有合并。
- 没有用真实密钥，没有向 DeepSeek 发请求。
- 没有改 `contract/`，没有改发布签名配置和 `release.yml`。
- 没有做多人圆桌、背景卡、炉边、本地模型单聊、流式输出。
- 走查里没有点「检查更新」，所以审计里没有更新用途的记录。

## 补记（2026-10-18）：设备测例的明文断言修正

- 复核时发现：`DeviceConversationTest` 原来把库文件字节按 ISO-8859-1 解码后，直接和中文原句比较。中文串在这种视图里永远不会命中，这个断言等于没查。已改为字节空间比较（原句也先转成字节，再按同一解码方式比较），并加一条直接断言：库文件不得以 `SQLite format 3` 开头。提交 `66e3a5a`。
- 同一比较法在 JVM 上补了对照测例：未加密的普通 sqlite 文件里能搜到这些句子，证明设备断言真的会失败、不是空转。`:roundtable:test` 对 `66e3a5a` 重新执行，18 个 0 失败（新增 1 个）。
- 走查当晚取回的两份真机库文件（各 20480 字节）重新按字节复检：开头都不是 `SQLite format 3`；三句提问、回答、人物名、服务名、模型名和三条完整发送上下文，按 UTF-8 与 UTF-16LE 搜索命中都是 0 次。记录在 Windows 评审目录 `device-db-bytes.txt`。
- 手机在收尾复查时已断开，修正后的 `DeviceConversationTest` 未能在真机复跑；它的输入（真机库文件）和断言逻辑已在上一条里用真件验证。手机可用时补跑一次。
- 对 `66e3a5a` 的全套检查：`:roundtable:test` 18 个和重新编译的 androidTest 包是本次执行；其余模块与 `:app` 单测、lint 的输入与 `f3e4db0` 相同，沿用同一 worktree 当天早上的执行结果（81 + 1 = 82 个，0 失败；lint 0 error）。

## 补记二（2026-10-18）：修正后的设备测例已在真机补跑通过

- 手机接上后（做 P2-1b 真机走查的同一个窗口）补跑，命令：
  `adb shell am instrument -w -r -e class com.xavierxia.mycelium.roundtable.DeviceConversationTest com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner`
- 结果：**OK (1 test)**，`Time: 0.161`，0 失败。装的是本次重新构建的 `app-debug.apk`（62,081,072 字节，2026-10-18 12:06）和 `app-debug-androidTest.apk`（982,552 字节），所以跑的就是 `66e3a5a` 修正后的断言。
- 原始输出：Windows 评审目录 `.omx/development/reviews/2026-10-17-android-P1-7/device-instrumented-rerun-2026-10-18.txt`。
- 设备：红魔 NX809J（Android 16，SDK 36）。正式版 `com.xavierxia.mycelium` 走查前后都是 0.1.1（versionCode 2），没卸载、没覆盖、没清数据。
- 上一条里「手机可用时补跑一次」这项到此完成。

## 审核

- 最近一次可读的 Codex 额度读数：5 小时窗口 99%、周额度 29%（2026-10-17 11:48）。超过 70% 停审线，本线没有发起 Codex；该 5 小时窗口此后已重置，但没有更新的读数。
- P1-7 交统筹代审。