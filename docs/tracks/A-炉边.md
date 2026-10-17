# A-炉边线交接

## 已通过

- 2026-10-17：独立 worktree `D:\Agent\Worktrees\Mycelium-Android\hearth`，分支 `wp/P2-1-local-llm`，起点 `main @ 41bfd10`。不进 `D:\Agent\Project\Nivra`。
- 新增 `:model-api`（统一模型接口）和 `:llm`（llama.cpp 引擎、模型登记、思考段处理、取消）。`app/.../localmodels/` 有「我的 → 本地模型」页面和真机测试 `LocalModelDeviceTest`。改过 `settings.gradle.kts`、`app/build.gradle.kts`、`MyceliumApp.kt`、`.gitignore`。
- llama.cpp 源码在 `llm/vendor/llama.cpp`，被 Git 忽略，提交 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，由 `scripts/prepare-llama.ps1` 从本地克隆准备。不联网。
- 11:46 的构建：JVM 测试 41 个通过（`:model-api` 3、`:llm` 11、`:app` 27），lint 0 error，调试包和真机测试包都编好了。之后只改过 `llm/README.md`。这个目录里还没跑过 `:contract:test`。
- 真机证据在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P2-1/`：`baseline.txt`、`device-first-run.txt`、`device-first-metrics.txt`，以及截图 `import-progress.png`、`model-list.png`。`LocalModelDeviceTest` 已通过：用持久 SAF 文件描述符直接读取，没有复制；加载 419 毫秒；64 token 约 15.8 token/秒；生成中停止 2 毫秒；提示处理阶段取消 33 毫秒；卸载后 RSS 从 3.35 GB 回落到 350 MB。64 token 太短，不能当作比 P0-7 的 4B@6（约 8 token/秒）更快的结论。
- 环境约定不变：JBR、已装 SDK、Gradle 9.5.0、NDK 30.0.16248370、CMake 3.22.1。离线构建，不清全局缓存，不升级工具链。

## 失败

- Gradle 的 `connectedAndroidTest` 在离线状态下缺 UTP 32.3.0 插件，跑不了。不要为此联网下载，改用 adb。

## 未验证

- 完整真机测试集当时正在用 adb 跑，结果不明。手机上残留 `com.xavierxia.mycelium.debug.test` 进程。用手机前先请用户确认 A-圆桌线没在用。
- 还缺：256 token 的生成速度；「试运行」里一次流式生成的截图（只截知衍（调试），裁掉状态栏）；`docs/reviews/2026-10-17-P2-1/DELIVERY.md`；全新 worktree 对已提交版本的全套检查（新目录没有 `llm/vendor`，先用 `scripts/prepare-llama.ps1` 从本地克隆准备）。
- 交审前看一眼 `main` 有没有新提交，有就先并进来。Codex 共享额度已到 87%，本包不调 Codex，做完交统筹代审。
- 只在本分支提交。不推送、不打标签、不发布、不合并 `main`。`:llm` 和 `:model-api` 不许有联网代码。手机上的原模型文件只读。
