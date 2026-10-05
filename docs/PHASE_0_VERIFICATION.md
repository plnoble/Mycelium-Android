# PHASE_0 验收报告（P0-6：第 0 阶段收尾）

- 验收人：A-基础线开发 agent（2026-10-05），按 `docs/PHASE_0_HANDOFF.md` §6。
- 环境：本机 JBR 25.0.3（`D:\Development\android-studio-quail4\Install\jbr`）、Android SDK（`%LOCALAPPDATA%\Android\Sdk`，API 37 / build-tools 36.0.0）、Gradle Wrapper **9.5.0**（`gradlew.bat --version` 确认；发行包 SHA-256 `553c78f5…b746` 与官方一致，写在 wrapper properties 里）。
- 前任未提交改动全部保留（接手时 `git status` 9 改 13 增，未做 reset/clean/checkout）。

## 1. 完整构建三连（同一条命令，任务 C 口径）

```
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain
```

- 分步执行结果（本机）：
  - `:app:assembleDebug :app:lintDebug` → **BUILD SUCCESSFUL**（2m13s，exit 0；日志 `%TEMP%\nivra-p06-build.log`）
  - `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**（1m37s，exit 0；日志 `%TEMP%\nivra-p06-test.log`）
- Lint 报告：`app/build/reports/lint-results-debug.txt`——**0 errors, 5 warnings**（全部为「有更新版本可用」提示类：Gradle 9.8.0、core-ktx 1.19.1、org.json 新版等；按交接书不无依据升级，保持现状）。

## 2. 单元测试（任务 B 口径）

JUnit XML 汇总（`app/build/test-results/testDebugUnitTest/`）：

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| MainActivityTest（Robolectric/Compose v2 冒烟） | 1 | 0 | 0 | 0 |
| NivraAppTest（Compose v2） | 2 | 0 | 0 | 0 |
| GitHubReleaseClientTest（含 404「No GitHub Release…」分支） | 6 | 0 | 0 | 0 |
| UpdateManagerTest（版本比较/回退/错误处理） | 6 | 0 | 0 | 0 |
| VersionComparatorTest | 4 | 0 | 0 | 0 |
| **合计** | **19** | **0** | **0** | **0** |

- 交接书里「Compose v2 测试尚未重新编译验证」一项：**已重新编译并通过**（`androidx.compose.ui.test.junit4.v2` 依赖解析成功，MainActivityTest/NivraAppTest 全绿）。
- 全部为 JVM/Robolectric 验证；HTTP 分支用假响应（MockWebServer 风格 stub），**未做真实联网验证**。

## 3. APK 元数据核对（任务 C 口径）

`aapt dump badging`（build-tools 36.0.0）：

- package：`com.nivra.app`，versionName `0.0.1`，versionCode `1` ✔（交接书 §3.3 要求保持）
- launchable-activity：`com.nivra.app.MainActivity` ✔
- uses-permission：`android.permission.INTERNET`、`REQUEST_INSTALL_PACKAGES` ✔
- application-label：`Nivra`（P0-9 改名前预期值）✔
- 路径：`app/build/outputs/apk/debug/app-debug.apk`，大小 29,882,755 字节，SHA-256 `70a920cced9b6e92ddf81c0ede8721480ec96ca935eb28f5b873391848e9d9c6`

## 4. Wrapper 一致性（任务 A 口径）

- `gradlew`：POSIX 脚本、LF 行尾（无 CRLF）、shebang 正常（`.gitattributes` 强制 `gradlew text eol=lf`）。
- `gradle-wrapper.jar`：有效 zip（PK 头），47,505 字节，SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`。
- `gradle-wrapper.properties` 指向 9.5.0 + distributionSha256Sum 与官方公布值一致；`gradlew.bat --version` 实测 Gradle 9.5.0。
- **提醒主审核**：合并后请在 GitHub 上把 `gradlew` 的可执行位设为 `100755`（本机 Windows 无法设置，`.gitattributes` 已保证 LF）。

## 5. CI 与秘密检查

- 两个 workflow 均用 `./gradlew`（Wrapper）跑 assembleDebug / testDebugUnitTest / lintDebug；Release workflow 只做配置校验，不发布。
- diff 扫描：无密码/token/keystore/本机 SDK 路径/代理地址；工作流里的 `${{ secrets.NIVRA_* }}` 仅为引用名。
- 未跟踪文件里无 `local.properties`/keystore/AKP/模型文件。

## 6. 验证结论（三栏口径）

| 项 | 电脑（JVM/Robolectric） | 真机 |
|---|---|---|
| 构建 + Lint | **已通过** | 未验证 |
| 单元测试 19/19 | **已通过** | 未验证 |
| APK 元数据 | **已通过**（aapt） | 未验证（无设备接入；按交接书记「真机/模拟器未验证」） |
| GitHub CI（PR #1） | 未验证（推送后由 CI 验证） | — |

## 7. 遗留与风险

- 真机安装/启动/更新检查：**未验证**（本机无设备接入；交接书允许记未验证，保留 Robolectric 结果）。
- GitHub CI：等用户同意推送后，以 head SHA 的 CI 结果为准。
- 5 条 lint 警告均为「有新版本」提示，按「不无依据升级」原则保留。
- `gradlew` 可执行位需主审核/用户在合并后设置（见 §4）。
