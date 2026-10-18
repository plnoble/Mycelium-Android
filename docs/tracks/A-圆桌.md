# A-圆桌线 交接与进度

- 分工见 `docs/work-packages/TRACKS.md` 末尾「2026-10-15 起：A-圆桌线」。顺序：P0-8 → P1-6 → P1-7。合并由统筹决定，本线不推送、不打标签、不发布。
- 环境：JBR 25.0.3 = `D:\Development\Android-studio\jbr`（交接里的 `android-studio-quail4` 路径已不存在）。ANDROID_HOME = `%LOCALAPPDATA%\Android\Sdk`。Gradle Wrapper 9.5.0，不升级。
- 2026-10-15：P0-8 完成并提交 `4caca80`，见 `docs/reviews/2026-10-15-P0-8/DELIVERY.md`。契约来自知衍 `4ff64a4`，版本 0.1.0。`:contract:test` 7 个全过；`:app` 的构建、19 个单测、lint 仍过。
- 2026-10-15 15:16 和 15:47 两次 Codex 审核都没有出报告。程序是带哈希目录里的 `codex-cli 0.162.0-alpha.17.2`。`codex doctor` 写明 ChatGPT 推理地址请求超时（必达端点），WebSocket 也超时。额度没有到停审线：第二次开审前 5 小时窗口 26%、周额度 4%。没有 `REVIEW.md`，不能当成通过或需修改。
- 2026-10-15：P0-8 复审第 2 轮通过，见 `docs/reviews/2026-10-15-P0-8/REVIEW-r2.md`。已合并进本地 main（`a36a5c2`，入档 `8bbdaf0`），没有推送。
- 2026-10-16：P1-6 从 `8bbdaf0` 拉出 `wp/P1-6-roundtable-skeleton`。圆桌、伴侣、我的三个入口，导入、浏览和搜索已接上。坏包、坏数据库、目录条目和超过 64 MiB 的条目都以 `PackRejected` 拒绝。
- 2026-10-16：Codex 审核 `2a2548b`，结论「需修改」，见 `docs/reviews/2026-10-16-P1-6/REVIEW.md`。额度 5 小时窗口 34%，周额度 9%。已改四条并提交 `456156d`：参数绑定不再多一个空位；删除一个版本不删另一个版本还在用的数据库；末尾少 1 或 22 字节的包拒绝；搜索结果显示来源日期。返工后电脑上 `:contract:test` 14 个、`:app:testDebugUnitTest` 27 个、`:app:assembleDebug`、`:app:lintDebug`（0 error）已通过。真机和全新 worktree 仍是未验证。
- 2026-10-16：对 `456156d` 开了 Codex 复审，只查上面四条。它重跑了契约测试 14 个和应用单测 27 个，都是 0 失败，但报告没有落盘，进程停在写报告之前，已停掉。没有 `REVIEW-r1.md`，不能当成通过或需修改。开审时窗口额度 42%，停住前读到 58%，周额度 13%。复审交给统筹。
- 2026-10-16：统筹复审 `456156d`，见 `docs/reviews/2026-10-16-P1-6/REVIEW-r1.md`。代码四条通过，全新 worktree 全套检查通过。结论仍是需修改，因为真机证据还没有。
- 2026-10-17：真机补齐。检索向量在 `456156d` 上第一次失败：`rawQuery` 收到的是整个绑定数组，SQLCipher 把它当成第 1 个参数，抛出数据类型不匹配。已改为展开数组。重跑后 instrumented test 2 个、0 失败。陆川走查 7 步完成，截图在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P1-6/`。调试版 0.2.0（versionCode 3）和正式版 0.1.1（versionCode 2，最后更新时间 2026-10-10 05:23:04）同时安装。这一轮除了证据，还有这一处绑定调用的改动，不是只看 `456156d`。
- P1-7 云端服务商已定为 DeepSeek 官方接口（main `2a2317b`）。P1-6 合并前不开 P1-7。
- 2026-10-17：P1-6 复审第 2 轮通过，见 `docs/reviews/2026-10-16-P1-6/REVIEW-r2.md`。已合并进本地 main（`5496949`，入档 `14e5058`），没有推送。0.2.0 的推送和发布仍等用户决定。
- 2026-10-17：P1-7 从 `14e5058` 拉出 `wp/P1-7-roundtable-chat`。`:net` 是唯一出网模块，检查更新改走网关。`:roundtable` 做检索、拼提示、校验和费用，不依赖 HTTP 库。DeepSeek 按 2026-10-17 官方文档核对：`https://api.deepseek.com`、`deepseek-flash`、非高峰缓存未命中 0.15 / 0.6 美元每百万 token。开放平台没有写明 API 数据是否用于训练，界面写「未找到明文条款」。
- 2026-10-17：电脑上 `:net:test`、`:roundtable:test`、`:contract:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 已通过。真机 `DeviceConversationTest` 1 个已通过。陆川三问走查已通过，用的是电脑上的假服务，没有真实密钥。审计 3 条，都是圆桌聊天。截图在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P1-7/`。调试版 0.3.0（versionCode 4）和正式版 0.1.1（versionCode 2，最后更新时间 2026-10-10 05:23:04）同时安装。见 `docs/reviews/2026-10-17-P1-7/DELIVERY.md`。已提交 `1c708ed`，没有推送。全新 worktree 对这个提交离线重跑全套检查已通过。Codex 审核还没有做。
- 2026-10-17：P1-7 按 10-17 修订的任务书返工（首版的预算与单模型设置作废）。云端设置改成多服务（名称、地址、key，新建预填 DeepSeek）；「拉取模型」走 `GET {地址}/models`，经网关、记审计（不含 key），勾选多选保存，拉取失败不清已保存模型；聊天从已保存模型里选并记住选择；预算相关（单价、上限、`CostEstimate`、`BudgetExceeded`、`requireWithinBudget`、「本次估算」）和对应测试全部删除；云端回答对齐 `:model-api` 的 `ChatModel`（`CloudChatModel`，整段发出）；「我的」模型页分本地、云端两组。先并入 main `1143dc4`（`c5fd7bc`），返工提交 `51612ef`。
- 2026-10-17～18：P1-7 真机重走查（假服务 + `adb reverse`，无真实密钥）已通过。拉取 3 个模型、勾选 2 个保存；聊天用 pro 问两问、中途换 flash 问第三问（拒绝正常）；杀掉重开记得选择；停服务后拉取失败不丢已保存模型。审计 5 条（拉取 2、聊天 3），无 key、无认证头；对话库 20480 字节、无明文。走查发现保存模型后卡片不刷新，修复 `f8c3ca0`。截图 20 张替换旧 15 张；调试版 0.3.0（4），正式版 0.1.1（2，最后更新时间 2026-10-10 05:23:04）未动。
- 2026-10-18：P1-7 收尾。测试改名不再提预算 `f3e4db0`；真机 `DeviceConversationTest` 1 个、`DeviceRetrievalTest` 2 个已通过。全新 worktree（离线）对 `f3e4db0` 复跑：`:contract:test` 15、`:model-api:test` 3、`:net:test` 8、`:roundtable:test` 17、`:llm:testDebugUnitTest` 11、`:app:testDebugUnitTest` 27，共 81 个 0 失败；lint 0 error；两个调试包都出。没有推送。
- 2026-10-18：Codex 额度最近读数 5 小时窗口 99%、周额度 29%（2026-10-17 11:48），超过 70% 停审线，本线没有发起；该窗口此后已重置但没有更新读数。P1-7 交统筹代审，见 `docs/reviews/2026-10-17-P1-7/DELIVERY.md`。
