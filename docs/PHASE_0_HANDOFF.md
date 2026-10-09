> 更名说明：本文件为历史记录（原名 Nivra，2026-09-29 更名为 Mycelium（知衍）安卓版）。正文未改。

# Nivra Phase 0 开发任务与审核计划

更新时间：2026-09-28。此文档只覆盖 Phase 0 基础设施收尾。

## 1. 协作方式

用户分发任务；开发 agent 完成指定工作；主审核 agent 审核代码和验证证据，
审核通过后统一提交并推送到 `phase-0-bootstrap`，检查 PR #1 的最新 CI。

按 A → B → C → 主审核的顺序执行。当前使用同一工作区，避免多个 agent
同时执行 Gradle、切换分支或修改公共配置。其他 agent 的变更必须保留。

## 2. 交接起点

- 工作区：`D:\Agent\Project\Nivra`。
- 仓库：<https://github.com/plnoble/Nivra>。
- 分支：`phase-0-bootstrap`，跟踪 `origin/phase-0-bootstrap`。
- PR：<https://github.com/plnoble/Nivra/pull/1>，目标分支为 `main`。
- 远端交接基准：`ba55dbc86532beeb9a7dea8e05e84ec078c31bb7`。
- 本轮修改尚未提交、尚未推送。**必须从当前工作区接手**；只克隆远端不会获得这些修改。
- 原 CI 错误：`NivraApp.kt` 的 `TopAppBar` 缺少 `ExperimentalMaterial3Api` opt-in。
- 当前已加入 opt-in、完整 Wrapper、更新来源接口和错误/取消处理、更新检测与页面测试，
  并调整 CI/Release workflow 和构建说明。这些修改仍需完成验证。
- 使用本机 Gradle 9.7.1 已产出 debug APK，APK 元数据确认：
  `com.nivra.app`、versionName `0.0.1`、versionCode `1`、启动 Activity `com.nivra.app.MainActivity`。
- Gradle 9.5.0 发行包已下载并通过官方校验，`gradlew.bat --version` 成功；
  **尚未完成使用 9.5.0 Wrapper 的完整构建与测试**。
- 单元测试和 Lint 未获得完整成功结果；为交接停止了本轮构建。
- Compose 测试已改用 `androidx.compose.ui.test.junit4.v2`，这一变更尚未重新编译验证。
- GitHub Releases 列表为空。交接时 PR CI 仍是原提交的失败结果。
- `docs/PRODUCT_SPEC.md` 和 `docs/UPDATE_ARCHITECTURE.md` 未修改。

## 3. 所有开发 agent 的共同约束

1. 开始前读取本文件、`docs/PHASE_0.md`、`docs/UPDATE_ARCHITECTURE.md`，并检查 `git status`。
2. 保留当前未提交和未跟踪的源码、测试、Wrapper 文件；不要执行 `git reset --hard`、
   `git clean` 或强制 checkout 来清空工作区。你不是唯一开发者，不得回退其他人的修改。
3. 保持 package/applicationId/namespace 为 `com.nivra.app`，版本为 `0.0.1` / `1`。
4. 不删除产品规划文档。不开发 Character、Memory、LLM、ASR/TTS，不下载模型。
5. 不提交密码、token、keystore、本机 SDK/代理路径、构建缓存、APK 或模型文件。
6. 本轮保留 `UpdateManager` 架构和现有浏览器交接，不实现自动 APK 下载、校验和安装。
7. 只修改自己负责的文件。跨边界问题写入交接报告，由对应负责人处理。
8. 开发 agent 不提交、不 push、不合并 PR、不打 tag、不创建 Release、不配置签名密钥。
   主审核 agent 在验收后统一执行已获授权的提交与推送。
9. 报告必须区分“已通过”“失败”“未验证”，不能把发起构建当作构建成功。

## 4. 任务 A：构建与 CI 收尾

### 文件责任

- `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`app/build.gradle.kts`
- `gradlew`、`gradlew.bat`、`gradle/wrapper/*`
- `.gitattributes`、`.gitignore`、`.github/workflows/*`
- `README.md` 中的构建说明

### 工作内容

1. 保留现有版本组合，优先解决具体错误；不要无依据批量升级或降级依赖。
2. 用 Gradle 9.5.0 重新生成对应版本 Wrapper，保留发行包 SHA-256 校验和合理下载超时。
   当前 Wrapper 是先由本机 9.7.1 生成，再指向 9.5.0；需要完成一致性检查。
3. 确保 Linux `gradlew` 使用 LF，Windows 脚本可运行；在报告中提醒主审核设置
   `gradlew` 的 Git 可执行位 `100755`。
4. 补充忽略 `.kotlin/` 等本轮出现的本机构建缓存，并核对现有秘密/模型排除规则。
5. 检查 JVM 17 目标、AGP 内置 Kotlin、Compose compiler/BOM、SDK 和测试依赖是否一致。
6. 解决 Wrapper 构建、测试配置、Manifest 合并和 Lint 配置方面的错误。
   不通过关闭测试、添加宽泛忽略或设置 `abortOnError=false` 来使 CI 通过。
7. 核对 Android CI 和 Release workflow 都使用 Wrapper；CI 检查 APK、单元测试和 Lint。
   Release workflow 只检查配置，本轮不发布或创建密钥。

### 验收与输出

- `gradlew.bat --version` 显示 9.5.0。
- `gradlew.bat :app:assembleDebug :app:lintDebug --stacktrace --console=plain` 成功。
- 测试依赖能解析，能够启动 `:app:testDebugUnitTest`；若测试源码或断言失败，记录给 B。
- 输出改动文件、真实命令及退出结果、日志/报告路径、剩余问题。

## 5. 任务 B：页面与更新检测验证

### 文件责任

- `app/src/main/java/com/nivra/app/**`
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/**`
- `app/src/test/**`

依赖或 Gradle 配置由 A 负责。先复用现有新增测试，仅在发现具体缺口时补充。

### 工作内容

1. 验证 `MainActivity` 启动后能显示 Nivra、Phase 0 和正确版本信息。
2. 验证按钮经 `UpdateManager` 调用 `GitHubReleaseClient`，网络工作不阻塞主线程。
3. GitHub 返回 404 时，界面正常显示 `No GitHub Release has been published yet.`，
   无异常退出，按钮恢复可用，并可再次检查。
4. 验证 200 响应解析、较新/相同/较旧版本判断、无 APK 时的现有网页回退。
5. 验证超时、403/429/5xx、格式错误能正常报告；检查连接释放和协程取消传播。
6. 运行并修复现有 Robolectric/Compose v2 冒烟测试；UI 测试不依赖实时 GitHub 网络。
7. 保留未来下载、校验和安装的扩展边界，不提前实现这些功能。

### 验收与输出

- `gradlew.bat :app:testDebugUnitTest --stacktrace --console=plain` 全部通过。
- 提供测试数量、失败数量、JUnit XML/HTML 路径。
- 明确哪些是 JVM/Robolectric 验证，哪些是真机或模拟器验证。
- 输出文件变更和遗留问题；不能把 HTTP 假响应测试称为真实联网测试。

## 6. 任务 C：集成验收与证据整理

### 文件责任

- `docs/PHASE_0_VERIFICATION.md`（新建验收报告）
- `docs/PHASE_0.md` 的进度和验证记录

本任务不修改应用代码或构建配置；发现失败时给 A/B 返回可复现记录。

### 验收清单

1. 在 A/B 都完成后执行：

   ```powershell
   .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain
   ```

2. 核对 APK 元数据：包名、版本、启动 Activity、Internet 权限。
3. 记录 APK 路径、大小、SHA-256，以及测试和 Lint 的真实结果；不能只记录任务已启动。
4. 若有可用 Android 设备/模拟器，验证安装、启动、手动检查更新、无 Release 提示和再次检查。
   没有设备时明确记录“真机/模拟器未验证”，保留 Robolectric 结果。
5. 检查规划文档仍存在，diff 没有秘密、模型、构建输出和本机配置。
6. 记录当前分支和远端基准；GitHub CI 一栏先标为“等待审核后推送验证”。
7. 报告中保留风险和未完成事项。签名 Release、发布 tag、自动下载/安装均为后续工作。

## 7. 主审核 agent 的放行标准

- 逐项审查 diff、文件责任与需求符合性，确认没有越过 Phase 0 范围。
- 检查 Wrapper 来源/校验、脚本可执行位、依赖和 CI 配置。
- 审查更新检测分支、资源释放、取消传播、UI 恢复及测试是否覆盖真实风险。
- 根据 A/B/C 证据复现必要检查；有修改或未解决问题时重跑相关验证。
- 明确区分 JVM 启动测试与真实设备联网验证。
- 检查待提交文件清单及规划文档，再提交并 push 到 `phase-0-bootstrap`。
- 确认 PR #1 的 head SHA 对应本次提交，等待该 SHA 的 Android CI 成功，并给出 run 链接。
- CI 若失败，读取失败步骤与日志，回派对应 agent 修复并重新审核；不以旧提交的绿色结果放行。

## 8. 本机环境备注（仅帮助本次接手）

- JDK：Android Studio 自带 JBR，当前验证版本 25.0.3；CI 使用 JDK 17。
- SDK 已安装 API 37，构建命令通过进程环境设置 `JAVA_HOME` / `ANDROID_HOME`。
- Windows 默认沙箱曾因 `.sandbox-bin` ACL 错误无法启动，终端命令使用权限审核执行。
- Java 网络下载较慢；本轮曾临时使用 Windows 已配置的本地代理。不要将代理写入仓库。
- 官方 Gradle 9.5.0 分发包 SHA-256：
  `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`。
- 现有构建日志：`%TEMP%\nivra-phase0-build-installed.log`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；此文件是中间验证产物，不代表全部验收通过。
- 开始接手前确认上一轮 Gradle 客户端已退出；不要停止其他项目的构建进程。
