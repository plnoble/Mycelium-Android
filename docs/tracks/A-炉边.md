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
