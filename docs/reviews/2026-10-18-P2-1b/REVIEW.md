# P2-1b 审核报告（Claude 统筹代审）

- 审核对象：`wp/P2-1b-check-all` @ `6f4d6e3`（代码提交 `41b1ace`），加上统筹补改 `d4c5526`；一并审 `wp/P1-7-device-rerun` @ `20602a6`（只改 P1-7 的交付报告）。对照 `main` @ `9009878`。
- 审核者：统筹（Claude）代审。Codex 交付审没有发起：5 小时窗口 13:52 读数 93%，15:54 才重置，等待超过 1 小时。方案审（`PLAN-REVIEW.md`，结论「需修改」，8 条已落实）做过。
- 结论：**通过**。已合并进本地 `main`（`43ee12b`，补跑记录 `94d5bb0`），没有推送。

## 我重跑的验证

| 检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| 全新 worktree，并入 `main` 后从零跑 `scripts/check-all.ps1`（第一次） | **失败**：Gradle 一个任务都没跑。原因是本机命令行设了 `NoDefaultCurrentDirectoryInExePath=1`，`cmd.exe` 不在工作目录里找裸的 `gradlew.bat` | 不一致：开发线的环境没有这个设置，所以没暴露 |
| 统筹补改 `d4c5526`（改成 `.\gradlew.bat`）后，同样从零再跑 | 见下方「补改后的结果」 | — |
| 正式包合并后的清单 | 见下方 | — |
| 真机证据 `device-log.txt` 和 3 张截图 | 桌面只有「知衍」「知衍（调试）」两个图标；正式版前后都是 0.1.1（versionCode 2），最后更新时间 2026-10-10 05:23:04；P1-7 的 `DeviceConversationTest` 补跑 `OK (1 test)` | 一致 |
| 签名相关 | `release.yml` 和 `app/build.gradle.kts` 里与签名有关的行没有任何改动 | 一致 |

## 统筹补改（`d4c5526`）

- `scripts/check-all.ps1` 调用 Gradle 时改成 `.\gradlew.bat`。设了 `NoDefaultCurrentDirectoryInExePath` 的环境（加固过的命令行、部分沙箱和 CI 镜像）里，`cmd.exe` 不在工作目录里找裸的 `gradlew.bat`；原来的写法会让脚本在 Gradle 什么都没跑时就失败。
- 什么输入出什么错：在设了该环境变量的命令行里运行 `check-all.ps1`，结果是 `'gradlew.bat' is not recognized`，所有预期任务「did not run」，退出码 1。改后正常。

## 对开发线提出的 7 件事的裁定

1. lint 实际 9 条（P1-7 带进来 2 条 `UseKtx`）并全清：**同意**。
2. `:llm` 的 2 条 lint 警告：**纳入门禁，放到 P2-5 做**。`UsableSpace` 改成可注入的「可分配空间」查询（安卓上用 `StorageManager#getAllocatableBytes`，JVM 测试用假实现），P2-5 本来就要重写下载和空间检查；`ChromeOsAbiSupport` 在 `:llm` 的 lint 配置里关掉，理由是「只支持手机的 arm64-v8a」。到时 `check-all` 加上全模块 lint（或在 `:app` 的 lint 里打开 `checkDependencies`）。
3. `--enable-native-access` 按测试 JVM 版本加：**同意**。
4. 明文开关改用清单占位符：**同意**。安全语义不变：默认 `false`，只有调试包是 `true`；正式包的合并清单已核对（见下方）。
5. `release.yml` 超时 20 → 60 分钟：**同意**。不改的话发布构建必然超时；签名步骤没动。
6. `:roundtable` 注释改一处字样：**同意**。架构测试是按文字匹配的，注释里出现 `okhttp` 就会误报。
7. 「交付和验收一律用 check-all」写进规则：**同意**，这正是本包的目的。
- 本包没做 STEP 增量审：这一包可以接受。以后每轮 Codex 审核大约要用掉 5 小时窗口的一半以上，所以 STEP 和专项审可以跨窗口做，额度不够时并进全量审的要求里一起审，但方案审和全量审不能省。

## 补改后的结果

- `check-all.ps1` 从零跑通过：执行 249 个任务；82 个测试 0 失败（`:app` 27、`:contract` 15、`:llm` 11、`:model-api` 3、`:net` 8、`:roundtable` 18）；`:app` 的 debug、release lint 都是 0 error 0 warning；警告 0 条；正式包未签名、调试包和测试包已签名；退出码 0。
- 从 APK 读出合并后的清单：正式包 `usesCleartextTraffic=false`，调试包 `true`；两个包都是 `allowBackup=false`，并带有 `dataExtractionRules`、`fullBackupContent` 和图标。
- 正式合并（`43ee12b`、`94d5bb0`）后的树，和验证用的树逐文件对比完全一致。

## 必须修改

- 无（统筹已自己改掉 `d4c5526`）。

## 无法验证

- GitHub CI（没有推送）。首次推送后要专门看一次：装 NDK 和 CMake、联网拉依赖、JDK 17 下跑全套、`prepare-llama.sh` 从 github.com 取固定提交。
- JDK 17 下跑全套：本机只有 JBR 25。
