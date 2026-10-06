# P0-6 审核报告（Codex）

- 审核对象：`phase-0-bootstrap @ 3ca8596`，对照 `90e551a`；前一基点 `ba55dbc`。
- **结论：需修改。**
- 阻断项：`gradlew` 缺少可执行位；Wrapper JAR 未按要求统一到 9.5.0；验收文档存在事实错误。
- 本次仅审核，未修改文件、提交、推送或触发发布。

## 我重跑的验证

按本次审核要求，采用读取现有 XML 的快速验证方式，未重新运行 Gradle 全套任务。

| 命令或检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `git diff 90e551a..3ca8596 --stat`及逐文件差异 | 26 个文件，新增 1398 行、删除 23 行 | 一致 |
| PowerShell 解析 `app/build/test-results/testDebugUnitTest/*.xml` | 5 个测试类，19 tests，0 failures、0 errors、0 skipped | 一致 |
| 读取现有构建、测试日志末尾 | 分别显示 `BUILD SUCCESSFUL`，exit 0，耗时 2m13s、1m37s | 一致；属于历史日志核对 |
| 读取 `lint-results-debug.txt` | 0 errors、5 warnings；其中只有 3 条为版本提示 | **警告分类不一致** |
| `aapt dump badging`、APK 大小及 SHA-256 | `com.nivra.app / 0.0.1 / 1`；启动 `MainActivity`；含 INTERNET；29,882,755 字节；哈希与报告一致 | 一致 |
| `git ls-files --eol`、`git check-attr` | `gradlew` 索引及工作区均 LF；`.bat` 工作区 CRLF；JAR 标记 binary | 一致 |
| `git ls-tree 3ca8596 gradlew` | **100644** | 不满足 Linux 直接执行要求 |
| Wrapper 校验 | distribution checksum 正确；JAR 对应官方 9.7.x，而非 9.5.0 | **“Wrapper 一致性已通过”不成立** |
| `git diff --check 90e551a..3ca8596` | 无输出，通过 | 一致 |

测试数量分布：MainActivityTest 1、NivraAppTest 2、GitHubReleaseClientTest 6、UpdateManagerTest 6、VersionComparatorTest 4。

## 范围检查

- 已检查指定的 26 个文件，包括应用源码、测试、构建配置、两个 workflow、Wrapper 和文档。
- 未发现应用功能越界。新增接口、取消传播和 UI 状态恢复属于更新检查收尾；没有实现 APK 下载、校验或安装。
- 对接说明、P0-7/8/9 任务书及 tracks 文档属于题述保留、补档范围，没有提前实现对应功能。
- `TRACKS.md` 已在基点 `90e551a` 入档，不属于本次 26 文件差异。
- 工作区没有已跟踪文件改动；仅存在未跟踪的 `docs/reviews/2026-10-05-P0-6/codex-prompt.md`，未纳入审核提交。

## 验收逐条

| 验收项 | 结果 | 证据 |
|---|---|---|
| §3.1 开始前阅读文档、检查状态 | 无法追溯验证 | 本次审核已读取指定文档并检查状态；不能据此证明前任执行顺序 |
| §3.2 保留改动、未 reset/clean | 通过，限历史证据 | 父链为 `ba55dbc → 90e551a → 3ca8596`；本地 reflog 显示接手后仅两次追加提交，无 reset 记录 |
| §3.3 包名及版本保持 | 通过 | Gradle 配置及 APK 均为 `com.nivra.app / 0.0.1 / 1` |
| §3.4 保留规划、不实现后续模块 | 通过 | 无删除文件；`PRODUCT_SPEC.md`、`UPDATE_ARCHITECTURE.md` 无差异；无新增 Character、Memory、LLM、ASR/TTS 实现 |
| §3.5 不入库秘密和构建产物 | 通过 | 提交树及差异中未发现实际凭据、模型、APK、构建缓存或本机 SDK/代理配置；Wrapper JAR 属于交付要求 |
| §3.6 保留更新架构及浏览器交接 | 通过 | `UpdateManager → ReleaseSource`；UI 仍通过 `ACTION_VIEW` 打开链接 |
| §3.7 文件责任及范围 | 通过 | 变更符合题述继承改动和文档补充范围 |
| §3.8 提交、推送、发布边界 | 本地提交符合后续规则；远端行为无法验证 | `TRACKS.md` 明确要求验证后提交；本次未操作远端 |
| §3.9 报告区分真实结果 | **不通过** | 真机和 CI 已标未验证，但 Wrapper 一致性及 Lint 分类错误 |
| 单元测试证据 | 通过 | 独立汇总现有 XML：19/19 |
| `gradlew` LF、属性规则 | 通过 | `i/lf w/lf`，`eol=lf` |
| 发行包 checksum | 通过 | properties 为 `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`，与[官方校验表](https://gradle.org/release-checksums/)一致 |
| Wrapper 版本一致性、Linux 可执行性 | **不通过** | 见必须修改第 1、2 项 |

## 专项检查

- **秘密、大文件**：未发现实际 token、密码、keystore、模型或构建产物入库；workflow 使用 secrets 引用。
- **测试质量**：新增测试具有实际断言，覆盖 404、解析、HTTP 错误、连接释放、取消传播、版本判断及 UI 恢复；未删除旧测试断言。HTTP 使用假连接，不能视为真实联网验证。
- **隐私、契约、提示词、真实语料**：本次未实现相关模块或改动契约、提示词；规划文档不等于实现。
- **花钱**：本次审核未调用付费模型命令或下载模型。

## 必须修改

1. **[P1] 在提交中设置 `gradlew` 可执行位，不能推迟到合并后。**

   `git ls-tree` 明确显示 `100644`，而 [android-ci.yml:34](/D:/Agent/Project/Nivra/.github/workflows/android-ci.yml:34) 和 [release.yml:53](/D:/Agent/Project/Nivra/.github/workflows/release.yml:53) 在 Ubuntu 上直接执行 `./gradlew`，没有补充 chmod 步骤。按此配置，Linux 检出后会因无执行权限而失败。

   应由开发方使用 `git update-index --chmod=+x gradlew` 并提交，确认树中为 `100755`。同步纠正 [DELIVERY.md:35](/D:/Agent/Project/Nivra/docs/reviews/2026-10-05-P0-6/DELIVERY.md:35) 和 [PHASE_0_VERIFICATION.md:49](/D:/Agent/Project/Nivra/docs/PHASE_0_VERIFICATION.md:49) 中“合并后设置”“Windows 无法设置”的说明；`--chmod=+x` 也不是 `git merge` 参数。

2. **[P2] 按交接要求，用 Gradle 9.5.0 重新生成 Wrapper。**

   [交接书:59](/D:/Agent/Project/Nivra/docs/PHASE_0_HANDOFF.md:59) 明确要求重新生成对应版本 Wrapper。当前 JAR 实测 SHA-256 为：

   `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`

   该值对应官方 9.7.0/9.7.1；官方 9.5.0 Wrapper JAR 应为：

   `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

   依据：[Gradle 官方校验表](https://gradle.org/release-checksums/)。当前 JAR 属于官方版本，未发现被篡改证据；但启动的发行包是 9.5.0，不能证明 Wrapper 本身已统一到 9.5.0。应重新生成、核对脚本与 JAR，并更新“一致性已通过”的证据。

3. **[P2] 修正交付、验收报告中的事实错误。**

   [PHASE_0_VERIFICATION.md:16](/D:/Agent/Project/Nivra/docs/PHASE_0_VERIFICATION.md:16)、该文件第 70 行及 [DELIVERY.md:22](/D:/Agent/Project/Nivra/docs/reviews/2026-10-05-P0-6/DELIVERY.md:22) 将全部 5 条 Lint 警告归为版本提示。实际还包括 `MissingApplicationIcon` 和 `UseKtx`。应如实记录；本项不要求为了通过审核消除这些警告。

   验收报告第 53 行也应区分“本次仅审核 Release 配置、未触发发布”与 workflow 的实际行为：Release 配置运行 `testDebugUnitTest / lintRelease / assembleRelease`，并在 tag 触发时发布 GitHub Release。

## 建议修改

- 验收报告 §1 标题写“同一条命令”，正文证据实际为分步执行。建议改成“分步执行三项任务”，使验证方式明确。

## 无法验证的项目及原因

- 未重新编译或执行 Gradle；按用户允许的快速路径核对现有 XML、日志及 APK。
- 未验证该 SHA 的 GitHub CI、JDK 17 环境、签名 Release、真机或模拟器运行、真实联网更新。
- Git log/reflog 支持追加提交结论，但不能证明历史上从未执行过 `git clean`，也不能逐字节证明前任未提交快照完整保留。
- 额度记录：最新可读 `token_count`（2026-10-05 11:32:00 UTC）为 **5 小时窗口 11%，周窗口 18%**。