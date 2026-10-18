# P2-1b 全套检查脚本、零警告、CI 能构建本地模型引擎

- 仓库 / 基准提交：安卓仓库 `main` @ `1143dc4`（交审前并入当时最新的 `main`）
- 分支：`wp/P2-1b-check-all`
- 工作目录：`D:\Agent\Project\Nivra`（2026-10-18 起只有一条开发线）。`wp/P2-2-hearth-chat` 上已经有 6 个提交，先放着；本包合并后，再把新的 `main` 并进去继续做。
- 依赖：P2-1 已合并。
- 规模：1–2 个工作日。
- 背景：
  - 2026-10-18 用户定下验收规矩：交付要在全新 worktree 里对已提交版本跑「全套检查」，测试构建和正式构建都要零警告。现在每个包各写一串 Gradle 命令，口径不一，也没人管警告。
  - P2-1 合并后，构建前必须先准备 llama.cpp 源码（`scripts/prepare-llama.ps1`），而这个脚本只能从本机已有的克隆拷贝。GitHub 上的 `android-ci.yml` 和 `release.yml` 都没有这一步，所以**下次推送时 CI 和发布构建都会失败**。
  - 现有警告（`main` 状态）：
    - lint 7 条：`GradleDependency` 2 条（sqlcipher、androidx.sqlite 有新版本）；`DataExtractionRules` 1 条（`allowBackup` 已弃用）；`MissingApplicationIcon` 1 条（App 至今没有图标）；`UseKtx` 3 条（`MyceliumApp.kt:412`、`LocalModelsScreen.kt:116`、`LocalModelsViewModel.kt:65`）。
    - Gradle：「Deprecated Gradle features were used in this build, making it incompatible with Gradle 10」1 条，来源未查。
    - 测试运行：sqlite-jdbc 在 JBR 25 上报 `System::load` 受限访问警告。
    - Kotlin 编译、自家的 `engine.cpp`：目前没有警告。

## 目标（用户能得到什么）

- 一条命令跑完全部检查，最后给一张汇总表；有任何失败或警告，就以非零退出码结束。以后所有交付和统筹验收都用它。
- 推送后，GitHub 上的 CI 和发布构建能把本地模型引擎编出来。

## 步骤

1. **全套检查脚本** `scripts/check-all.ps1`：
   - 检查 `JAVA_HOME`、`ANDROID_HOME`；按参数或环境变量拿到本地 llama.cpp 克隆的位置，调用 `prepare-llama.ps1`。
   - 离线跑：所有模块的单元测试、`:app` 的 debug 和 release lint、`:app:assembleDebug`、`:app:assembleRelease`（没有签名环境变量时出未签名包，不碰签名）、`:app:assembleDebugAndroidTest`。用 Gradle 的聚合方式写，**以后新增模块（比如 P1-7 的 `:net`、`:roundtable`）不用改脚本也能被覆盖**。
   - 汇总：每个模块的测试数和失败数、lint 的 error 和 warning 数、各个 APK 的路径和大小。交付报告直接贴这张表。
   - 输出里出现编译警告、lint 警告、Gradle 弃用警告，都算失败。
2. **把警告变成错误，防止以后再长出来**：Kotlin 编译 `allWarningsAsErrors`；lint `warningsAsErrors`；自家的 JNI 代码（`engine.cpp`）加 `-Wall -Wextra -Werror`。llama.cpp 源码固定在某个提交、不能改，它的警告不算在内，但要在 `llm/README.md` 写明这条例外。
3. **清掉现有警告**：
   - `UseKtx` 3 处：改成 KTX 写法。
   - `DataExtractionRules`：加 `dataExtractionRules`（Android 12 以上）和 `fullBackupContent`（旧版本），**明确排除全部数据**。这和隐私要求一致：炉边和圆桌的数据不进系统备份，不跟着换机迁移。`allowBackup="false"` 保留。
   - `MissingApplicationIcon`：做一个简单的占位矢量图标（单色、不用图片文件），正式图标以后由用户定。交付时附一张桌面图标的截图。
   - `GradleDependency`：离线环境下不升级依赖。在 lint 配置里关掉这一项，并写明理由「依赖升级要单独评估」。
   - Gradle 弃用：用 `--warning-mode all` 找出来源。来自我们自己构建脚本的就改掉；来自插件本身、我们改不了的，把原文贴进交付报告，报统筹裁定。
   - sqlite-jdbc 的受限访问警告：给用到它的测试任务加 `--enable-native-access=ALL-UNNAMED`。
4. **CI 和发布构建**：
   - `prepare-llama.ps1` 增加一个只给 CI 用的方式：从 llama.cpp 官方仓库拉取固定提交 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，拉完核对提交号。本地仍然只从本机克隆准备，不联网。也可以写一个等价的 `.sh` 给 Linux 跑。
   - `android-ci.yml`：构建前先准备 llama.cpp，装好 NDK 30.0.16248370 和 CMake 3.22.1，然后跑和本地一致的检查（可以用等价的 `check-all.sh`）。CI 的 JDK 是 17，本地是 JBR 25，两边都要能过。
   - `release.yml`：**只许加「准备 llama.cpp 和 NDK」这一步**，签名相关的步骤一行不动。
5. **文档**：
   - `docs/work-packages/README.md` 的「安卓侧补充规则」里写明：交付和验收一律用 `scripts/check-all.ps1`，在全新 worktree 里对已提交版本跑。
   - `llm/README.md`：真机复验改成实际用的 `adb install -r` 加 `am instrument`（P2-1 审核建议 1）；补上第 2 步说的 llama.cpp 警告例外。

## 范围

- **可以改**：`scripts/`、各模块的 `build.gradle.kts` 和 lint 配置、`app/src/main/AndroidManifest.xml`、新增的备份规则和图标资源、上面点名的 3 处 `UseKtx` 代码、`llm/src/main/cpp/CMakeLists.txt`（只加自家目标的警告参数）、`.github/workflows/android-ci.yml`、`.github/workflows/release.yml`（只加准备步骤）、`llm/README.md`、`docs/work-packages/README.md`。
- **不许碰**：发布签名配置和签名步骤；版本号；依赖版本；`contract/`；llama.cpp 源码；P1-7 正在改的 `:net`、`:roundtable`（P1-7 合并后，如果它带进来新的警告，由本包在并入 `main` 后一起清掉）。

## 验收标准

- [ ] 在全新 worktree 里，对已提交版本跑 `scripts/check-all.ps1`：0 失败、0 警告，退出码为 0。贴出汇总表。
- [ ] 故意制造一个警告（比如临时加一个没用到的变量），脚本能报出来并以非零退出码结束。演示完撤掉，不提交。
- [ ] `assembleRelease` 在没有签名环境变量时能出未签名包；签名配置的 diff 是空的。
- [ ] 真机（红魔）：桌面上能看到新图标；正式版 0.1.1 不动。用手机前先问用户。
- [ ] CI 的改动在本地能验证的部分都验证过。推送前跑不了 GitHub 上的 CI，交付报告里把它记为未验证；推送由用户决定。

## 必交证据

- `docs/reviews/<日期>-P2-1b/DELIVERY.md`：汇总表、故意制造警告的那次输出、Gradle 弃用警告的来源和处理、图标截图的位置。
- 图标截图放 Windows 仓库 `.omx/development/reviews/<日期>-android-P2-1b/`。

## 风险

- 打开 `warningsAsErrors` 以后，P1-7 合并进来的代码可能编不过。本包交审前要并入最新 `main`，把它带进来的警告一起清掉；清不掉的报统筹。
- GitHub 上的 runner 不一定预装 NDK 30 和 CMake 3.22.1，要在工作流里显式安装。没推送就验证不了，所以首次推送后的 CI 结果要专门看一次。
- Gradle 弃用警告如果来自 AGP 本身，「零警告」可能做不到，由统筹裁定是否作为已知例外。

## 审核

- 先用 Codex 审（共享额度低于 70% 时）；额度不够就交统筹代审。截图交统筹看。通过后由统筹合并进本地 `main`。
