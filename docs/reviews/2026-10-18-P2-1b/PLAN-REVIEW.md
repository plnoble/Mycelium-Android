# P2-1b 审核报告（Codex）

- 审核对象：`wp/P2-1b-check-all` @ `6daf72597c025020226a6a1d34ec32877a29a730`，对照 `main` @ `9009878`。
- 审核类型：**开工前方案审**。
- 结论：**需修改**。
- 核心原因：JNI 警告策略会覆盖约定豁免的源码；检查脚本尚缺可靠的退出码、报告统计和警告捕获设计；CI 冷缓存及第三方警告例外的处理规则需要明确。

## 我重跑的验证

| 命令／检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `git rev-parse HEAD`、`git log -2 --oneline` | 提交与输入一致 | 不适用，本次无交付报告 |
| `git diff 9009878...HEAD --stat` 及完整 diff | 仅新增 `docs/tracks/开发线.md`，43 行；没有实现改动 | 不适用 |
| 审核前后 `git status --porcelain=v1` | 均为空 | 不适用 |
| 阅读任务书、审核指南、背景和指定代码，并核对其余模块构建配置 | 完成；未修改文件 | 不适用 |
| 用 `javap -p -c` 检查本机缓存的 AGP 9.3.0 任务实现 | 确认 `lint` 依赖默认变体的 lint；Android 单测聚合入口覆盖已生成的各变体单测 | 不适用 |
| PowerShell 5.1 内存探针：`Stop` 模式下，将写 stderr、退出码为 7 的原生命令接入 `2>&1` 管道 | **复现 `NativeCommandError`**，正常的退出码读取语句未执行 | 不适用 |
| 本地 JBR 执行 `--enable-native-access=ALL-UNNAMED -version` | 成功；版本为 JBR 25.0.3 | 不适用 |
| Gradle 构建、单测、lint、故意制造源码警告 | **未执行**；本次只读环境不允许产生构建文件，且工作树尚无 vendor 源码 | 不适用 |

当前进程的 `JAVA_HOME`、`ANDROID_HOME` 均未设置。通过交接文档及本机缓存定位到了 JBR、SDK，但没有据此绕过只读限制执行构建。

## 范围检查

- 越界实现改动：**无**。当前只有用户已说明的交接文档提交。
- 本次没有修改文件、准备 vendor、清缓存、操作手机、推送或修改签名配置。
- 任务书存在过时背景：P1-7 已合并，`:net`、`:roundtable` 已在当前 `settings.gradle.kts` 中；“等待 P1-7 合并”的描述不再适用。
- `STATUS.md:12` 和 P1-7 审核报告写的是 **9 条 lint 警告**，任务书明细只列了 **7 条**。本次没有新跑 lint，不能认定另外两条是什么，也不能把不同来源的警告混加为 lint 数量。

## 验收逐条

| 验收项 | 结果 | 证据及可达条件 |
|---|---|---|
| 全新 worktree、已提交版本执行全套脚本，0 失败、0 警告、退出码 0，附汇总表 | **无法验证** | 脚本未实现。实现时必须解决下述任务覆盖、结果缺失、缓存和例外问题 |
| 故意制造警告，脚本报告并非零退出，之后撤销 | **无法验证** | 尚无脚本；单独演示 Kotlin 警告不足以证明 Gradle 弃用警告也被拦截 |
| 无签名环境变量时生成未签名 release APK，签名配置 diff 为空 | **无法验证；静态可行** | `app/build.gradle.kts:68` 仅在 keystore 路径非空时设置 release 签名；无需修改该配置 |
| 红魔桌面显示图标，正式版 0.1.1 不动，使用手机前取得同意 | **无法验证；方案可行** | debug 有 `.debug` 包名后缀；需要真实安装 debug 包和桌面截图，lint 通过不能替代 |
| CI 本地可验证部分已验证，远端 CI 如实注明未验证 | **无法验证** | 工作流未修改；本次完成静态核对，未执行 Linux 准备脚本或远端工作流 |

## 专项检查

- **花钱、真实语料、秘密、契约、提示词**：本次未调用模型、未读取真实语料或密钥；没有相关实现改动。
- **隐私**：备份规则需要覆盖云备份及设备迁移；详见必须修改第 7 项。
- **测试质量**：重点是检查脚本不能把“没运行”“报告缺失”“读取旧报告”当作通过。`assembleDebugAndroidTest` 只证明测试 APK 能构建，不证明设备测试通过。
- **签名**：普通 CI 的 `lintRelease`、未签名 `assembleRelease` 不需要发布 secrets；原 `release.yml` 的签名校验、恢复 keystore、签名构建和发布步骤应保持原样。

以下技术选择本身可行：

| 项目 | 核对结论 |
|---|---|
| `warningsAsErrors` 与关闭 `GradleDependency` | 可以共存。被 `disable` 的规则不产生诊断；其余启用规则的 warning 才提升为 error。不要用 `ignoreWarnings=true` 代替。[Lint DSL](https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/Lint) |
| 三处 KTX 替换 | `:app` 已依赖 `androidx.core:core-ktx:1.19.0`，无需新增依赖。两处可用 `toUri()`，偏好设置可用 `edit { … }`，保持原来的异步 `apply` 语义 |
| 本地 JBR 25、CI JDK 17 | 两者处于 Gradle 支持范围；但这不等于插件、测试运行库及警告输出完全一致。[Gradle 兼容表](https://docs.gradle.org/current/userguide/compatibility.html) |
| NDK 固定版本 | `30.0.16248370` 是官方 NDK r30 版本，无需改版本或为此切换 canary。[NDK r30](https://github.com/android/ndk/releases/tag/r30) |

**“零警告”的风险判断：**

- 自家 Kotlin、JNI、资源及构建脚本产生的诊断，应修复并设失败门槛。
- 固定 llama.cpp 的源码、头文件及 CMake 配置可能产生上游诊断，需要按约定精确归类。
- AGP/KGP 调用 Gradle 弃用 API、测试依赖调用受限 JDK API、SDK 工具自身输出警告，可能不来自项目代码。
- 目前尚未定位已有 Gradle 弃用提示的具体来源，**不能预先断言是 AGP，也不能预先豁免**。固定工具及依赖版本后仍无法处理的第三方警告，只能保留原文和来源，交统筹裁定。

## 必须修改

### 1. JNI 警告参数必须落实到 `engine.cpp`，不能直接覆盖整个目标

位置：[CMakeLists.txt:31](D:/Agent/Worktrees/Mycelium-Android/p2-1b-plan/llm/src/main/cpp/CMakeLists.txt:31)。

**触发输入 → 错误：**给 `mycelium_llm` 整个目标加 `-Wall -Wextra -Werror` 后，只要同目标内的 `common/jinja/*.cpp`、`unicode.cpp` 或 `json.cpp` 产生 warning，构建就会失败，违反“llama.cpp 源码警告豁免”的约定。

该目标目前混合了自家和第三方源码，`PRIVATE` 不代表“只作用于自家文件”；目标编译选项适用于其编译单元。[CMake 文档](https://cmake.org/cmake/help/v3.22/command/target_compile_options.html)

应优先给 `engine.cpp` 设置源文件级编译选项，保留目标原有 `-O2 -fexceptions`。还要处理它包含的 vendor 头文件诊断：按实际诊断位置划分例外，不能把整个 `:llm` 任务的警告全部放行。验收时检查实际编译命令。

### 2. 明确 PowerShell 版本及原生命令执行规则，保证失败后仍能汇总

位置：拟新增的 `scripts/check-all.ps1`；现有 [prepare-llama.ps1:3](D:/Agent/Worktrees/Mycelium-Android/p2-1b-plan/scripts/prepare-llama.ps1:3)。

**触发输入 → 错误：**在 Windows PowerShell 5.1 中照搬 `$ErrorActionPreference='Stop'`，再用 `2>&1 | Tee-Object …` 收集 stderr，可能在第一条 stderr 输出处抛出 `NativeCommandError`，导致日志不完整、汇总没有执行。本次已用无文件探针复现。

方案必须明确：

- 支持 Windows PowerShell 5.1，或明确要求并验证 PowerShell 7；不能只在 Linux `pwsh` 下验证。
- 同时保留 stdout、stderr 和原始退出码；每次原生命令结束立即保存退出码。
- 用 `try/finally` 等机制保证失败时仍输出汇总，未完成项目标为未完成。
- Bash 等价脚本使用管道时，不能拿 `tee` 的成功退出码替代 Gradle 的退出码。
- 命令从脚本确定的仓库根运行，并正确处理含空格的路径。

### 3. 测试和 lint 数量应解析报告；缺报告不能等于零

位置：全套脚本的汇总及失败判定。

**触发输入 → 错误：**测试成功时控制台没有完整测试数量；编译提前失败时没有 XML；第二次运行可能留下旧报告。仅匹配控制台或递归累加现有文件，会漏报、重复计数，甚至输出虚假的“0 失败、0 警告”。

应明确：

- 从本次任务对应的 JUnit XML 统计 `tests`、`failures`、`errors`、`skipped`，按**模块和测试任务／变体**列出。
- `:contract` 对应目录是 `contract-lib`，不能按模块名直接拼目录。
- 分别读取 debug、release lint XML。开启 `warningsAsErrors` 后，原 warning 可能体现为 error，必须同时检查两种严重性。
- 根据预期任务清单核对报告和三个 APK；报告缺失、解析失败、APK 缺失均不能通过。
- 区分 `NO-SOURCE`、跳过、缓存恢复和实际执行；交付证据必须对应已提交 SHA。
- 负向验证至少分别覆盖 **Kotlin 编译警告、lint 问题、真实 Gradle 弃用诊断**。普通 `println("warning")` 不能证明 Gradle 弃用门禁有效。

Gradle 提供机器可读测试报告，不必新增统计依赖。[Gradle 测试报告](https://docs.gradle.org/current/userguide/java_testing.html)

### 4. 把“聚合检查”写成确定的任务契约，并覆盖全部 Kotlin 编译任务

位置：[根构建配置](D:/Agent/Worktrees/Mycelium-Android/p2-1b-plan/build.gradle.kts:1)、各模块构建文件及新脚本。

**触发输入 → 错误：**若把 `:app:lint` 当成 debug/release 全变体检查，会漏 release；若在当前根项目直接调用 `:test`，或直接 `tasks.named("test")`，根项目没有该任务时会失败；只配置 `org.jetbrains.kotlin.android` 插件回调，则可能漏掉当前采用 AGP 内置 Kotlin 的 Android 模块。

建议明确采用根目录任务选择器：

```text
test
:app:lintDebug
:app:lintRelease
:app:assembleDebug
:app:assembleRelease
:app:assembleDebugAndroidTest
```

这里的 `test` **不带前导冒号**，会选择各子项目的同名任务；Android 模块再聚合其已启用变体的单测。若另建根聚合任务，应动态连接这些任务并保留新增模块覆盖能力。[Gradle 任务选择器](https://docs.gradle.org/current/userguide/command_line_interface.html)

AGP 的 `lint` 只覆盖默认变体；不能用它或 release 构建中的 fatal 检查替代显式 `lintRelease`。本次也核对了缓存中的 AGP 9.3.0 实现。[Android lint](https://developer.android.com/studio/write/lint)

`allWarningsAsErrors` 应覆盖所有模块的生产、单测、AndroidTest 编译，使用现行 `compilerOptions`，不要重新套用旧 `android.kotlinOptions` 或额外应用 `kotlin-android`。该开关也不自动覆盖 Gradle Kotlin DSL 脚本编译和 Java 编译警告。[内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Kotlin 编译选项](https://kotlinlang.org/docs/gradle-compiler-options.html)

### 5. 补齐离线边界和 CI 冷缓存准备流程

位置：[android-ci.yml:30](D:/Agent/Worktrees/Mycelium-Android/p2-1b-plan/.github/workflows/android-ci.yml:30)、[release.yml:34](D:/Agent/Worktrees/Mycelium-Android/p2-1b-plan/.github/workflows/release.yml:34)、准备脚本。

**触发输入 → 错误：**GitHub runner 首次运行、缓存未命中时，只安装 NDK/CMake 和克隆 llama.cpp，然后原样执行离线检查，会因 Gradle 插件或依赖未缓存失败。`setup-gradle` 不能保证所有依赖已存在。

应明确本地始终离线，CI 则采用“联网准备既有依赖后离线检查”，或显式允许 CI 在线解析既有依赖；检查项目和失败标准保持一致。

工具安装可采用以下包名，分号参数必须加引号：

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  --sdk_root="$ANDROID_HOME" --install \
  "ndk;30.0.16248370" "cmake;3.22.1"
```

还应核对 SDK licenses、`compileSdk=37` 对应 platform 和构建工具。不能只检查环境变量非空。[sdkmanager 用法](https://developer.android.com/tools/sdkmanager)

另外两处必须补足：

- **Robolectric**：`:app` 使用 Robolectric，现有测试指定 SDK 35。Gradle `--offline` 不自动禁止 Robolectric 自己在运行时下载 jar；本地必须明确其离线依赖目录及缺失时的失败行为。[Robolectric 离线配置](https://robolectric.org/configuring/)
- **llama.cpp 准备**：CI 产物必须保留 `.git`，校验完整 SHA 和干净状态。下载源码 ZIP、只写一个 SHA 文本不能通过现有 CMake 的 Git 校验。本地缺源码时必须失败，不能自动转为联网模式。

发布流程的同一准备步骤也应保证 CMake 3.22.1 可用；不得为了复用检查脚本改写现有签名构建步骤。

### 6. `--enable-native-access` 必须传给测试 JVM，并覆盖实际使用 sqlite-jdbc 的模块

位置：`contract-lib/build.gradle.kts:32`、`roundtable/build.gradle.kts:33`、`app/build.gradle.kts:148`。

**触发输入 → 错误：**只给 Gradle daemon 设置 JVM 参数，或只修改 `:contract:test`，在 JBR 25 执行 `:app`／`:roundtable` 的数据库测试时仍可能出现受限访问警告。

应通过实际的 Gradle `Test` 任务配置，例如：

```kotlin
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

作用范围应包含上述模块及 Android 的相关测试变体，保留现有 `--add-opens`。如按版本添加参数，应按**测试进程使用的 Java 版本**判断，不能把 JVM 字节码目标 17 当作运行时版本。CI JDK 17 和本地 JBR 25 都需留下运行证据。

该参数只处理相应进程的 native access，不是 Gradle 弃用、其他 JDK 警告的通用消除开关。

### 7. 将“排除全部备份数据”展开成可检查的资源规则

位置：[AndroidManifest.xml:5](D:/Agent/Worktrees/Mycelium-Android/p2-1b-plan/app/src/main/AndroidManifest.xml:5) 及拟新增 XML。

**触发输入 → 错误：**只写 `<cloud-backup>` 排除规则而遗漏 `<device-transfer>`，设备迁移仍可能包含数据；仅覆盖普通存储而遗漏 device-protected 或 external 域，也无法兑现“全部排除”。

方案应明确：

- 旧版 `fullBackupContent` 与新版 `dataExtractionRules` 都配置完整。
- 新版分别覆盖 `cloud-backup` 和 `device-transfer`。
- 排除覆盖 `root`、`file`、`database`、`sharedpref`、`external` 及四个 `device_*` 域，路径为各域根目录。
- 保留 `allowBackup=false`，并核对 debug、release 合并清单和打包资源。

Android 文档明确说明，缺少某种备份模式的规则不等于禁用该模式。[备份规则](https://developer.android.com/identity/data/autobackup)

### 8. 明确“零警告”与第三方例外的验收关系

位置：任务书“输出出现警告即失败”“llama.cpp 例外”“插件警告报统筹裁定”三处。

**触发输入 → 错误：**固定版本插件仍发出弃用诊断时，若只在报告中写“已知问题”却让脚本返回 0，就违反原验收要求；若无条件扫描所有 warning，又会把已约定豁免的 llama.cpp 警告判失败。

需要在实现前固定规则：

- 未获裁定的插件／工具警告仍然失败。
- 已有 llama.cpp 例外按源码归属精确识别，保留原始诊断及数量。
- 例外不冒充“没有警告”：汇总区分阻断警告和获准例外。
- 排查阶段用 `--warning-mode=all`；正式门禁可以使用 `--warning-mode=fail`，但它不能替代所有编译器和测试 stderr 检查。[Gradle 警告模式](https://docs.gradle.org/current/userguide/command_line_interface.html)
- 若第三方弃用警告需要新增豁免，先由统筹裁定验收口径，再落实精确规则；不得使用全局隐藏 warning 的方式得到绿色结果。

## 建议修改

1. **先更新基线清单。**当前 `MyceliumApp.kt` 的目标调用已在第 466 行，任务书仍写第 412 行。应按诊断 ID 和调用定位，重新分别取得 debug/release lint 清单，核实“7 条／9 条”的差异。

2. **明确 unsigned APK 证据。**在没有四个发布签名环境变量的进程中执行验证，检查实际 APK 及签名状态。当前配置预计生成 `app-release-unsigned.apk`；不能要求普通 CI 上传签名流程使用的 `app-release.apk`，也不能改用 debug 签名冒充未签名包。

3. **扩充 CI 报告上传范围。**当前工作流只收集 app debug 和 contract 报告。应按实际模块／变体收集测试报告、两种 lint 报告、完整日志和汇总表，失败时也上传。20 分钟超时是否足够完成首次 NDK 安装和双变体 native 构建，需在首次远端执行时观察。

4. **图标采用与设备匹配的 launcher 资源。**矢量素材无需新增依赖；可用 adaptive icon 包装并检查圆形裁剪。最终证据必须是红魔桌面上的“知衍（调试）”，同时记录正式包仍为 0.1.1。

5. **真机文档写全两个安装步骤。**先安装 debug APK，再安装对应 AndroidTest APK，然后执行：
   ```text
   adb shell am instrument -w -r com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner
   ```
   保留使用手机前询问、仅覆盖 debug、不得清数据或修改原模型的规则。删除已经过时的“等待另一开发线释放手机”描述。

## 无法验证的项目及原因

- 全套构建、测试及 debug/release lint：本次是只读方案审，不能生成构建文件；当前 worktree 尚未准备 llama.cpp。
- `allWarningsAsErrors` 打开后的完整影响面、现存 lint 准确数量及 Gradle 弃用来源：必须由实现后的真实执行确认；本报告未把历史结果当作本次通过。
- 未签名 release APK、最终签名配置 diff：尚无实现及本次构建产物。
- Linux 脚本、CI 冷缓存、远端 signed release：本次未执行，也未推送或使用发布 secrets。
- 红魔图标截图、正式版保持不变、设备 instrumentation：本次没有操作手机。
- 额度记录：所读取会话事件的 `rate_limits` 为 `null`，无法取得本次窗口及周额度百分比。