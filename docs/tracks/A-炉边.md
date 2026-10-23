# A-炉边线交接

## 已通过

- 2026-10-17：独立 worktree `D:\Agent\Worktrees\Mycelium-Android\hearth`，分支 `wp/P2-1-local-llm`，起点 `main @ 41bfd10`。不进 `D:\Agent\Project\Nivra`。
- `7623fa1`：`:model-api`、`:llm`、「我的 → 本地模型」、`LocalModelDeviceTest`。llama.cpp 在 `llm/vendor/llama.cpp`（Git 忽略），提交 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，由 `scripts/prepare-llama.ps1` 从本地克隆准备，不联网。
- 11:46 的构建：JVM 测试 41 个通过（`:model-api` 3、`:llm` 11、`:app` 27），lint 0 error，调试包和真机测试包都编好了。那个目录当时没跑 `:contract:test`。
- 真机（红魔，用户已确认 A-圆桌线没在用；只动调试包）：完整 instrumented 套件 3 个测试、0 失败。持久 SAF 直接读，没有复制。这次加载 400 毫秒；64 token 约 15.98 token/秒；生成中停止 1 毫秒；提示处理阶段取消 18 毫秒；卸载后 RSS 回到约 346 MB。更早一次是 419 毫秒、约 15.83 token/秒。64 token 不拿来和 P0-7 比。
- 256 token：1 次，6 线程、8192 上下文，11.71 token/秒，加载 1478 毫秒。界面另一次加载显示 450 毫秒。P0-7 4B@6 的 256 token 是 8.73/8.74/8.73 和重编后 8.17/8.16/7.77。本包只跑了一次、提示只有 44 token，不能写成已经确认更快。
- 流式截图 `stream-generation.png`，加上 `import-progress.png`、`model-list.png`，都在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P2-1/`。只截了知衍（调试），裁掉状态栏。原模型 SHA256 未变。正式版仍是 0.1.1，最后更新时间 2026-10-10 05:23:04。
- 交付报告：`docs/reviews/2026-10-17-P2-1/DELIVERY.md`。六个 `.so` 的 SHA256 是真机那一包去掉符号后的。换目录重编会把编译路径写进二进制，所以校验值会变，源码没变。Codex 因额度 87% 没有发起。
- 全新目录对 `7623fa1` 离线重跑：先用 `scripts/prepare-llama.ps1` 从本地克隆准备 llama.cpp（提交核对通过），然后 `:contract:test` 14、`:model-api:test` 3、`:llm:testDebugUnitTest` 11、`:app:testDebugUnitTest` 27，加上两个打包和 lint，0 失败，lint 0 error。sqlite-jdbc 在 JBR 25 上有 `System::load` 警告，测试仍通过。
- 交审前看过 `main`：仍是 `41bfd10`，没有要并进来的提交。
- 环境约定不变：JBR、已装 SDK、Gradle 9.5.0、NDK 30.0.16248370、CMake 3.22.1。离线构建，不清全局缓存，不升级工具链。

## 失败

- Gradle 的 `connectedAndroidTest` 在离线状态下缺 UTP 32.3.0 插件，跑不了。不要为此联网下载，改用 adb。

## 未验证

- 没有。交统筹代审。
- 只在本分支提交。不推送、不打标签、不发布、不合并 `main`。`:llm` 和 `:model-api` 不许有联网代码。手机上的原模型文件只读。

## P2-2 炉边：创建自己的人物，用本地模型对话

- 2026-10-18：开工。分支 `wp/P2-2-hearth-chat`（起点 `1143dc4`，含 P1-6a 改名与 P2-1）。五个提交：
  - `6b4863c`：`:hearth` 模块——人物与对话仓储、提示拼装。数据库接口是模块自己的 `HearthSession`（不引 JDBC；测试用普通 sqlite，真机由宿主传 SQLCipher 会话）。架构测试另压两条：`sqlite-jdbc` 只能是测试依赖，`src/main` 里不许出现 `java.sql`。
  - `d2f63c0`：llm/README 真机复验步骤改成实际用的 `adb install -r` 加 `am instrument`（P2-1 审核建议）。
  - `fe7b70f`：对话窗口助手 `HearthChat`、消息上的模型标记、最近活跃时间。
  - `c09f3e8`：人物头像字字段。
  - `136e279`：炉边三页（人物列表、编辑、对话）接入炉边标签；SQLCipher 加密库（Keystore 包装口令，与圆桌库分开）；本地模型流式对话、停止保留已写出的部分、重新生成；真机加密测例 `DeviceHearthTest`（按字节比较，防空转）。
- 电脑上（hearth worktree，离线）：`:hearth:test` 13 个、`:app:testDebugUnitTest` 27 个、`:contract:test` 14 个、`:llm:testDebugUnitTest` 11 个、`:model-api:test` 3 个，0 失败；lint 0 error；调试包和真机测试包都编出。`MainActivityTest` 的炉边占位断言已换成新页面；Robolectric 下加密库加载失败会显示提示而不是崩溃。
- 未做：真机走查（手机已从电脑断开）。走查项：新建虚构人物「老周」、Qwen3.5-4B 聊 3 轮、生成中停止一次、重新生成一次、杀掉重开、飞行模式重复；另按 P2-1 审核建议用约 1500 token 的提示测 3 次并记录读提示与生成速度。手机上的调试包没有本地模型登记（P1-7 走查收尾清过数据），走查前要重新导入 Qwen3.5-4B。

## 2026-10-23：P2-1d 实测后的待裁定事项

- 已通过：只读核对当前 P2-1d `428f4a3`、P2-5 `27caf51` 和 main `24d94e3`；历史 P2-1 与 P1-6a 已在后续主线完成，不重建旧 hearth/P1-6a 工作目录。
- 失败：用户报告应用第 2 轮复用未达成；另有两轮因堆余量不足跳过快照保存。引擎测例结果不能代替应用验收。
- 未验证：六项建议与 ARM 指令集兼容风险已写入 `docs/reviews/2026-10-22-P2-1d/DECISIONS-PENDING-2026-10-23.md`，请用户转统筹裁定。当前工作目录为 `Mycelium-Android/p2-1d`，分支 `wp/P2-1d-hybrid-checkpoint`。本轮没有更改代码、开关、线程或手机状态，也没有新增下载、合并或发布授权；下一步先定位应用第 2 轮与引擎测例的差异。
