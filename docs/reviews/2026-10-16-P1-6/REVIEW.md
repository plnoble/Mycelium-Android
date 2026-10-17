# P1-6 审核报告（Codex）

- 审核对象：`wp/P1-6-roundtable-skeleton @ 2a2548b`，对照 `main @ 8bbdaf0`
- 审核日期：2026-10-16
- **结论：需修改**
- 指定构建和 JVM 测试通过，但发现 4 项代码问题，且必做的真机、全新 worktree 验收尚缺证据。
- 未修改源码、配置或记录，未合并、推送、打标签，未运行付费模型，未操作 adb 设备。结束时 `git status --short` 为空。

## 我重跑的验证

使用：

- `JAVA_HOME=D:/Development/Android-studio/jbr`
- `ANDROID_HOME=%LOCALAPPDATA%/Android/Sdk`
- 用户指定镜像脚本在 PowerShell 下的实际位置：`$env:TEMP/mycelium-mirrors.init.gradle`

执行命令：

```powershell
.\gradlew.bat :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug `
  --init-script "$env:TEMP/mycelium-mirrors.init.gradle" `
  --offline "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
```

首次执行使用了 `--rerun-tasks`。过程中遇到 Wrapper 锁文件、调试签名目录、Kotlin daemon 和 Robolectric 锁文件的沙箱写权限问题；通过工作区 `.gradle/` 下的临时构建缓存及进程环境变量解决，没有修改仓库配置。随后重跑失败及未完成任务，最终输出 `BUILD SUCCESSFUL`。

| 命令／验证 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `:contract:test` | **通过：14 个，0 失败、0 错误、0 跳过** | 一致；报告未列数量 |
| `:app:assembleDebug` | **通过**；APK 元数据为 `com.xavierxia.mycelium.debug`、`0.2.0`、versionCode `3` | 一致 |
| `:app:testDebugUnitTest` | **通过：23 个，0 失败、0 错误、0 跳过** | 一致 |
| `:app:lintDebug` | **通过：0 错误、5 警告** | 与“0 error”一致 |
| `git diff --check 8bbdaf0...2a2548b` | 通过 | 补充检查 |
| 当前编译产物的参数绑定、尾部截断、多版本删除探针 | **复现问题，详见必须修改 1–3** | 交付测试未覆盖这些边界 |
| JDBC 浏览探针 | 3 个主题、11 条观点、11 条依据、4 个双向关系展示结果，断言通过 | 支持浏览 SQL 的正确性，不代表安卓适配器通过 |

本次生成的报告：

- [契约测试报告](/D:/Agent/Project/Nivra/contract-lib/build/reports/tests/test/index.html)
- [应用单测报告](/D:/Agent/Project/Nivra/app/build/reports/tests/testDebugUnitTest/index.html)
- [Lint 报告](/D:/Agent/Project/Nivra/app/build/reports/lint-results-debug.html)

## 范围检查

- **越界改动：未发现。**
- 已检查全部 16 个变更文件，合计新增 1217 行、删除 81 行。
- 功能变更位于 `app/` 和允许补充的 `contract-lib/`；`contract/` 无变更。
- 更新实现、发布签名配置和 `release.yml` 无变更；更新界面迁入“我的”。
- `DELIVERY.md` 为必交材料；`docs/tracks/A-圆桌.md` 为指南要求的配套开发记录更新，内容简短。
- 单个提交说明与工作包范围相符。
- diff 未包含密钥、`.env`、keystore、模型、APK、数据库或构建产物。

## 验收逐条

| 验收项 | 结果 | 证据 |
|---|---|---|
| 三个底部入口，圆桌与伴侣视觉区分，伴侣仅说明页 | 通过 | `MyceliumApp.kt` 的主题和导航实现；`MainActivityTest` 通过 |
| 首页人物信息、空态、系统文件选择器、不申请存储权限 | 通过（代码层面） | `PersonaList`、`OpenDocument()`、`*/*`；Manifest 未新增存储权限。真机选择流程未验证 |
| manifest、版本兼容、哈希、条目顺序及常见坏包校验 | **不通过** | 常规测试通过，但尾部截断包仍被接受，见必须修改 3 |
| 非数据库、目录条目、单条目大小上限 | 通过（JVM 层面） | `PersonaPackTest`；安卓错误展示未做真机验证 |
| 同版本重复导入识别、新版本新增／移除计数 | 通过（JVM 层面） | `PackLibraryTest` |
| 旧版本保留，删除一个版本不影响其他版本 | **不通过** | 共用数据库文件时删除旧版本连带丢失新版本，见必须修改 2 |
| 人物元数据、主题顺序、观点列表 | 无法完整验证 | JDBC 浏览探针通过；安卓带参数查询受必须修改 1 阻断，滚动流畅度未验证 |
| 观点详情、依据、来源信息及关系 | **不通过** | 数据查询本身经 JDBC 验证；SQLCipher 参数绑定错误影响详情加载 |
| 搜索结果和顺序符合契约，显示短查询提示、片段、来源标题及日期 | **不通过** | JDBC 契约测试通过；安卓参数绑定错误，且界面缺少来源日期，见必须修改 1、4 |
| “我的”显示版本、更新入口、包信息及删除确认 | 部分通过 | 更新和导航测试通过，删除确认存在；删除隔离存在问题 |
| SQLCipher 4.9.0、空口令、只读打开 | 通过（静态检查） | `SqlCipherPackDatabase.kt`；设备运行未验证 |
| 无新增联网，包中文字不写日志，新增主要界面文案为中文 | 通过（本次 diff 检查） | 新增代码及依赖检查 |
| JVM 和真机 instrumented test 全部通过 | **无法验证** | JVM 为 14＋23 个通过；真机检索向量未运行 |
| 真机走查截图齐全且符合规则 | **无法验证** | 交付报告明确未走查、无截图 |
| debug 与正式版同时安装，正式版版本号前后不变 | **无法验证** | `.debug` 后缀和调试应用名已配置；无真机安装证据 |
| 版本号为 0.2.0、versionCode 3 | 通过 | `app/build.gradle.kts` 和本次 APK 元数据 |
| 全套检查在全新 worktree 对已提交版本通过 | **无法验证** | 本次在现有工作区验证；交付报告未提供全新 worktree 路径及对应提交的验证记录 |

## 专项检查

- **花钱**：本包无模型调用；审核未运行付费模型。
- **真实语料**：仅使用契约虚构人物样例和由其构造的合成变体；未读取真实人物语料。
- **隐私**：未发现新增联网、正文日志、云端提示或密钥处理。伴侣目前只有说明页。现有 `ArchitectureTest` 检查的是契约模块依赖，不能作为伴侣隔离、唯一出网网关及审计已通过的证据；这些在总计划中列于后续工作包。
- **秘密与大文件**：diff 检查未发现违规内容。
- **测试质量**：新增断言有效；旧界面测试因导航迁移调整合理，更新重试断言保留。但 JDBC 测试绕过了新增 SQLCipher 适配器；截断测试仅截半包；删除测试仅覆盖一个版本，因而漏掉下述问题。
- **契约**：`contract/` 未改动，校验和测试通过；本次仅补充消费端。
- **提示词与质量档位**：不涉及。
- **记录与提交**：交付报告如实标注真机未验证，但不能据此满足任务书的必做验收。

## 必须修改

1. **[P1] 修复 SQLCipher 参数绑定多出一个位置的问题。**

   位置：[SqlCipherPackDatabase.kt:57](/D:/Agent/Project/Nivra/app/src/main/java/com/xavierxia/mycelium/roundtable/SqlCipherPackDatabase.kt:57)。

   `put()` 先补空值到 `size == index`，随后又追加参数，导致参数整体错位且数量多一个。对本次编译产物直接调用所得：

   ```text
   BIND_ONE=[null, LC-T1]
   BIND_TWO=[null, LC-T1, 20]
   ```

   主题下观点、观点详情、搜索及搜索结果定位均使用带参数 SQL，会受影响；详情加载路径也没有异常处理。

   **期望结果**：参数按 SQL 的一基索引准确放入零基数组，数量正确；补充单参数、连续参数及重复赋值回归测试，并在安卓 SQLCipher 路径跑完检索向量。

2. **[P2] 删除一个版本不能删除其他版本共用的数据库。**

   位置：[PackLibrary.kt:75](/D:/Agent/Project/Nivra/app/src/main/java/com/xavierxia/mycelium/roundtable/PackLibrary.kt:75)、[PackLibrary.kt:83](/D:/Agent/Project/Nivra/app/src/main/java/com/xavierxia/mycelium/roundtable/PackLibrary.kt:83)。

   文件名仅由 SQLite 字节哈希决定，两个被接受的不同版本可指向同一文件；`delete()` 无条件删除该文件。使用相同数据库、不同版本 manifest 的合成包复现：

   ```text
   TWO_VERSIONS=2 SHARED_FILE=true
   DELETE_V1=true
   REMAINING_VERSIONS=0 V2_FILE_EXISTS=false
   ```

   **期望结果**：按版本隔离文件，或在无其他版本引用时才删除共享文件。增加两个版本共用内容、删除任一版本后另一版本仍可读取的回归测试。

3. **[P2] 完整拒绝尾部截断的 `.mpack`。**

   位置：[PersonaPack.kt:124](/D:/Agent/Project/Nivra/contract-lib/src/main/kotlin/com/xavierxia/mycelium/contract/PersonaPack.kt:124)。

   当前依赖 `ZipInputStream` 读取局部条目，没有验证 ZIP 结束结构完整性。对契约样例包截去末尾 1 或 22 字节，实际均返回成功：

   ```text
   TAIL_TRUNCATION_1=ACCEPTED
   TAIL_TRUNCATION_22=ACCEPTED
   ```

   任务书明确要求截断包以 `PackRejected` 拒绝；现有“截去一半”测试不足以证明这一要求。

   **期望结果**：补齐容器完整性验证，覆盖尾部及中央目录截断，拒绝后不留下已安装文件或记录。

4. **[P2] 搜索结果补齐来源发布日期。**

   位置：[MyceliumApp.kt:289](/D:/Agent/Project/Nivra/app/src/main/java/com/xavierxia/mycelium/ui/MyceliumApp.kt:289)。

   搜索卡片只显示 `hit.title` 和 `hit.excerpt`；`SearchHit` 也没有来源日期字段。任务书要求显示命中片段和来源标题、日期。

   **期望结果**：补充来源信息读取和日期展示，保持契约检索结果及排序不变，并验证有日期、无日期两种情况。

5. **补齐必做验收证据。**

   位置：`DELIVERY.md` 的“电脑上的结果”“真机”章节，以及任务书验收标准。

   **期望结果**：开发方在可用安卓手机上补充 SQLCipher 全部检索向量、规定走查及截图、debug／正式版共存和正式版版本号未变的证据；在全新 worktree 对修复后的已提交 SHA 重跑完整检查，并记录路径、SHA、命令和结果。完成前继续标为未验证。

## 建议修改

1. 为文件选择器读取、版本保存及删除失败增加中文错误提示和状态恢复。`importUri()` 的文件读取异常未转为 `ImportOutcome.Rejected`，导入协程也没有 `finally` 恢复 `busy`。
2. 在 URI 读取阶段限制总输入大小，并尽早拒绝多余条目。当前先对整个文件 `readBytes()`，单条目 64 MiB 限制不能约束这一步的内存占用。

## 无法验证的项目及原因

- **真机 instrumented test、文件选择流程、页面走查、滚动性能、截图及安装共存**：交付报告明确未验证；按用户要求，本次未操作那台非安卓 adb 设备。
- **全新 worktree 验证**：本次使用现有工作区。指定新 worktree 根不在本会话可写范围内，`.git` 也为只读；Git 状态干净不能替代指南规定的全新 worktree。
- **`assembleDebugAndroidTest` 的交付声明**：本次未单独重跑该任务，也未将测试 APK 可构建视为真机测试通过。

审核结束时最近额度读数：2026-10-16 14:34:48（北京时间），5 小时窗口 **34%**，周额度 **9%**。