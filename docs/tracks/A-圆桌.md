# A-圆桌线 交接与进度

- 分工见 `docs/work-packages/TRACKS.md` 末尾「2026-10-15 起：A-圆桌线」。顺序：P0-8 → P1-6 → P1-7。合并由统筹决定，本线不推送、不打标签、不发布。
- 环境：JBR 25.0.3 = `D:\Development\Android-studio\jbr`（交接里的 `android-studio-quail4` 路径已不存在）。ANDROID_HOME = `%LOCALAPPDATA%\Android\Sdk`。Gradle Wrapper 9.5.0，不升级。
- 2026-10-15：P0-8 完成并提交 `4caca80`，见 `docs/reviews/2026-10-15-P0-8/DELIVERY.md`。契约来自知衍 `4ff64a4`，版本 0.1.0。`:contract:test` 7 个全过；`:app` 的构建、19 个单测、lint 仍过。
- 2026-10-15 15:16 和 15:47 两次 Codex 审核都没有出报告。程序是带哈希目录里的 `codex-cli 0.162.0-alpha.17.2`。`codex doctor` 写明 ChatGPT 推理地址请求超时（必达端点），WebSocket 也超时。额度没有到停审线：第二次开审前 5 小时窗口 26%、周额度 4%。没有 `REVIEW.md`，不能当成通过或需修改。
- 2026-10-15：P0-8 复审第 2 轮通过，见 `docs/reviews/2026-10-15-P0-8/REVIEW-r2.md`。已合并进本地 main（`a36a5c2`，入档 `8bbdaf0`），没有推送。
- 2026-10-16：P1-6 从 `8bbdaf0` 拉出 `wp/P1-6-roundtable-skeleton`。圆桌、伴侣、我的三个入口，导入、浏览和搜索已接上。坏包、坏数据库、目录条目和超过 64 MiB 的条目都以 `PackRejected` 拒绝。
- 2026-10-16：Codex 审核 `2a2548b`，结论「需修改」，见 `docs/reviews/2026-10-16-P1-6/REVIEW.md`。额度 5 小时窗口 34%，周额度 9%。已改四条：参数绑定不再多一个空位；删除一个版本不删另一个版本还在用的数据库；末尾少 1 或 22 字节的包拒绝；搜索结果显示来源日期。返工后电脑上 `:contract:test` 14 个、`:app:testDebugUnitTest` 27 个、`:app:assembleDebug`、`:app:lintDebug`（0 error）已通过。真机和全新 worktree 仍是未验证。
- P1-7 云端服务商已定为 DeepSeek 官方接口（main `2a2317b`）。P1-6 合并前不开 P1-7。
