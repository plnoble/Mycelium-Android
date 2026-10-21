# P2-2b 交付报告（发版准备：自动检查更新、应用内下载校验安装、发版流程）

分支 `wp/P2-2b-release-ready`。本报告分节写入，每节一个提交；事实都能从本报告写到的提交号、仓库外 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\` 下的原始日志、以及本目录各方案文档复核。

本报告写入时分支尖端是 `0bb81f9`（本报告自身的提交在其后）。**本包不合并进 main、不推送、不打标签**；合并与推送由统筹做。

## 一、九步各自的完成情况和提交号

第 1～9 步**全部已完成**，没有「进行中」也没有「没动」的步。

| 步 | 内容 | 状态 | 提交 | 本步落地后的测例读数 |
|---|---|---|---|---|
| 1 | 网关能下载文件（按流写文件、断点续传、逐跳白名单、审计、通用化，供 P2-5 复用） | 已完成 | `849b885`、`9a500ad` | `:net:test` 43/43（新增 29 例） |
| 2 | 读发布信息（按名找资产、缺资产降级只给发布页、错误说人话） | 已完成 | `e8ade02`、方案审整改 `311fbde` | 随第 2 步提交时的门禁 |
| 3 | 自动检查（开 App 查、24 小时节流、设置开关、红点 + 卡片、忽略本版） | 已完成 | `593b8d9`、`2d6a66b` | `:app` 92 例（新增 48） |
| 4 | 下载和校验（空间公式、`updates/`、进度与取消、四项校验、清理） | 已完成 | `b495146` | `:app` 153 例（新增 61） |
| 5 | 安装（`PackageInstaller` 会话、结果回执、未知来源引导、调试包不自动装） | 已完成 | `6e46aa8`（另有一条施工 worktree 上的 `b982a97`，`git patch-id` 相同=同一次改动） | `:app` 170 例（新增 17） |
| 6 | 发版流程（`scripts/release-guard.sh` + `release.yml`，四项闸门 + 手动试跑） | 已完成 | `fd5ad3c`、方案审整改 `5f539cb` | 见第七节中止演示 |
| 7 | 正式包只带 `arm64-v8a` | 已完成 | `6df413e`、`f47c706` | 改前/改后大小见本节末 |
| 8 | 发布说明、版本号、发版与架构文档 | 已完成 | `caeab85`、方案审整改 `b751b40` | 版本 0.4.0 / versionCode 5 |
| 9 | 公开内容检查脚本 `scripts/check-public.ps1` | 已完成 | `3153f7a`、方案审整改 `9a52117` | 见第五节 |

九步之后在本分支上还落了五条收尾提交（都在本节表格的九步之后）：

| 提交 | 内容 |
|---|---|
| `c1cfe12` | 把 `main` 并进本分支（解冲突见第二节） |
| `f74e63e` | 文件下载复用网关同一套取消闸门与超时归一化（见第三节） |
| `0bb81f9` | 下载复用取消闸门的判别力测例（`DownloadCancelGateTest` 5 例，见第三节） |
| `b3bf3e0` | `check-all.ps1` 的 lint 提示只计数不判失败，并加自测 |
| `d351932` | `check-public.ps1` 支持「已接受的历史提交」清单（`scripts/accepted-history-commits.txt`，13 个已填） |
| `6ef1b2f` | `LocalHttp` 移出生产源码集并接住服务线程异常（修的是「设备测试没法一次跑完」的那条已知问题） |

**本分支 0bb81f9 上的门禁用例分布（348 例 0 失败）**：`:app` 181、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、`:net` 55、`:roundtable` 28。原文见第四节。

**已定稿的三处与简报的偏离**（沿用施工笔记与方案审记录，不是本轮新决定）：

- 空间检查放在下载器里，而不是 `UpdateCenter`（避免用第 4 步的实现去改第 3 步的测例结论）；
- 期望哈希通过本地边车 `.apk.sha256` 传给校验器，校验器不联网；
- 校验器本地那次哈希不给取消句柄（取消由 `:net` 的 `FileDownloader` 负责）。

**第 5 步的关键实现口径**（`6e46aa8`）：`PackageInstaller` 用 `MODE_FULL_INSTALL` + `setSize`，不调 `setAppPackageName`；写 → `fsync` → 关流 → `commit`；结果回执用**显式 component + 清单内 `exported=false` 接收器 + `FLAG_MUTABLE`**（依据：部分版本 `FLAG_IMMUTABLE` 拿不到系统填的 `EXTRA_STATUS`/`EXTRA_INTENT`）；会话阶段、sessionId、目标包名、基线 versionCode、tag 落盘，回调按 sessionId 幂等；`onResume` 用 `longVersionCode` 权威判定，超时或会话消失只标「待核实」，不冒充取消。判别力实证两处（挪 `fsync`、把幂等规则改成恒真）均变红并干净还原。

**第 6 步的中止演示共 5 类**：versionCode、发布说明、签名指纹、标签四类在 `fd5ad3c` 那一轮各跑过一次通过 + 一次中止；第五类「找不到前序标签 / 浅克隆」在 `5f539cb` 之后有实跑日志与旧脚本对照。

**第 7 步大小**：正式包只带 arm64 之前 / 之后的 release 未签名包大小差，见第四节门禁原文里的产物表（本轮门禁实测 **38.9 MiB**）。

各步更细的方案—实现—证据对应，见本目录 `PLAN.md`、`SURVEY.md`、`IMPL-BRIEFS.md`、`PLAN-REVIEW-MUSTFIX.md` 与仓库外施工笔记 `p2-2-work\step1-notes.md`、`step3-notes.md`、`step4-notes.md`、`step5-notes.md`。

## 二、并 main 的冲突怎么解的

- 合并提交 `c1cfe12`（`Merge branch 'main' into wp/P2-2b-release-ready`）。第一父是本分支此前的尖端 `5c33c55`，第二父是当时 `main` 的 `286f86a`（含 P2-2 合并 `0b89bd4` 与 P2-2 六轮复审收口）。
- 提交信息里记下的**真冲突只有两个文件**（其余文件由 git 自动合）：`app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt` 与 `docs/tracks/开发线.md`。
- `HearthSpace.kt`：本分支那侧只改了一处 lint 提示（`dragSessions` 的 `mutableStateOf(0)` → `mutableIntStateOf(0)`），`main` 那侧带的是 P2-2 六轮复审后的整版（会话段身份 `conversationSegment`、`navigate()` 统一入口、`state.canSend` 就绪判据等）。**解决方式：整份取 `main` 侧版本。** 依据可复核：合并结果与该文件在 `main` 侧**逐字节相同**（`git diff c1cfe12^2 c1cfe12 -- <该文件>` 无输出），且 `main` 这一版本身用的就是 `mutableIntStateOf`（`HearthSpace.kt:39`、`:562`、`:566`），本分支那处 lint 修复没有因此丢掉 —— 事后门禁 lint 0/0 也印证了。
- `docs/tracks/开发线.md`：两边都往这一页加了各自的小节，**取并集**。相对本分支侧是 +2/−1（把 P2-2 那节标题更新成「六轮复审收口，最终门禁 169/0 全绿，已并入 main」），相对 `main` 侧是 +32/−8（补上本包的九步落地与方案审整改记录）。
- **不在冲突清单、但两边都改过的**是 `net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt`（git 自动合上了）。合并结果**两边都保留**：`main` 侧的连接/读取超时归一化与取消闸门（`check()` / `startingConnection`）、本分支侧的文件下载入口；并把白名单抽成 `HostPolicy` 接口、由 `AllowedHosts` 实现，好让下载的跳转走查（`HopWalker`）**每一跳用同一套策略**；`Update` 用途补上 GitHub 发布下载真正跳到的域名。
- 合并之后需要一次接线收口：`f74e63e` 把下载路径接到合并进来的同一套取消闸门与超时归一化上（下一节）。
- **冲突解决只动了上面这两个文件**；合并里出现的其余改动都是 `main` 侧原样带入的。合并后的门禁读数见第四节。

## 三、Gateway 取消语义的核对结论

**结论一句话**：文件下载现在走的是与云端请求**同一套**取消闸门与**同一套**超时归一化；「取消就尽快结束、且不在对端留下正文」这条保证在下载路径上有测例支撑，唯一进不去的角落（与 `cancel()` 抢同一把锁的那段竞态）如实记为未覆盖，不当成已覆盖。

- **接线**（`f74e63e`）：下载的跳转走查（`Download.kt` 的 `HopWalker`）此前直接调 `HttpURLConnection` 的裸方法，绕过了网关的 `call.check()` / `startingConnection`。该提交把下载接到 `GatewayCall.bodyStream` 与 `startingConnection` 上，并把连接/读取超时归一化一起套用：**0 / 600000 落到下载的 socket 上是 15000 / 30000**，`0` 不再等于「永远等」。改动只在这两个文件（`Download.kt` 13 行、`Gateway.kt` 26 行），**没有改生产里的并发结构**。
- **判别力测例**（`0bb81f9`，新增 `net/src/test/kotlin/com/xavierxia/mycelium/net/DownloadCancelGateTest.kt`，5 例）：
  - `aStopBeforeTheStatusIsFetchedMeansThePeerNeverAcceptsAConnection`：在走查取状态之前能卡住的最晚时点（连接对象已登记）取消 → 假对端的 `accepts` 与已完成请求都是 0；同一条测例末尾用真 socket「连上但一声不吭」自证夹具（`accepts=1`、`count=0`），把「没连」与「连了但没写」真正分开，而不是只看字节数。
  - `aStopWhileTheBodyStreamIsBeingOpenedLeavesZeroBodyBytesOnTheWire`：卡在「已过检查、正在取正文流」时取消 → 连接已经发起（`connects=1`）而正文仍是零字节、以 `GatewayCancelled` 结束、取消到返回 < 2s；同接线不取消时正文完整送达（阳性对照）。
  - `aStopWhileTheStatusIsBeingFetchedEndsTheWaitAndWritesNothing`：卡在取状态、建连在飞时取消 → `GatewayCancelled`、< 2s 返回、目标文件不存在、连接被断开。
  - `aDownloadThatIsNotStoppedCompletesThroughTheSameGate`：不取消时整包下载完整成功（闸门没有把正常路径堵死）。
  - `theDownloadPutsBoundedTimeoutsOnItsConnection`：上一条超时归一化的读数。
  - 配套：`net/src/testFixtures/.../LocalHttp.kt` **新增** `accepts` 计数（纯新增字段，既有语义不变；原先的 `count` 只在请求行到达后才计数，区分不了「没连」和「连了但没写」）。
- **判别力实证**（同一 worktree、`--offline :net:test --tests "*DownloadCancelGateTest"`、每次单独跑、跑完 `git checkout` 还原）：
  - 两处一起改回裸调用 → **1 failed**：`aStopWhileTheBodyStreamIsBeingOpenedLeavesZeroBodyBytesOnTheWire`，`expected: <0> but was: <36>`；
  - 只改回裸 `connection.outputStream` → 同一例同样失败；
  - 只改回裸 `connection.responseCode` → **5 例全绿**。原因如实记录：走查在它前面紧挨着 `call.check()`，对 GET 而言裸调用只在「与 `cancel()` 抢同一把锁」的那段竞态里才有区别，确定性的测例进不去那段竞态 —— **这一条不当成已覆盖**。
- **本步实测**：`:net:test --offline` **55 例、0 失败 0 错误 0 跳过**（`DownloadCancelGateTest` 5、`DownloadGatewayTest` 29、`GatewayTest` 18、`LocalHttpTest` 2、`AuditLogTest` 1）。
- **已知残留（沿用 P2-2 裁定，本包没有改变它）**：`download()` 发的是 GET、`DownloadRequest` 没有请求正文，`DownloadRun` 交给走查的 body 恒为 `null`，所以「正在取正文流」那一段在下载路径上**不可达**（生产里的 `readFollowingRedirects` 也全是 GET）；它目前只由走查的元数据用途带 body 时到达，测例是直接驱动走查才覆盖到的。同时，**平台限制那条残留同样适用于下载**：停止恰好落在「建连刚启动」的极窄窗口时，可能要等到连接超时才结束（P2-2 已把这条裁定为已知残留，不换 HTTP 库）。

## 四、门禁原文

- **跑法**：全新 worktree，`git worktree add --detach … 0bb81f9`（工作区干净），在该 worktree 里跑 `scripts\check-all.ps1 -LlamaSourceRepository <llama.cpp 源码仓库>`；跑完用 `\\?\` 前缀删除该 worktree 并 `git worktree prune`（`git worktree list` 里已无此项）。
- **原始日志**：仓库外 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\gate-0bb81f9-ps1.txt`（UTF-8 无 BOM，78 行）、退出码文件 `gate-0bb81f9-ps1.exitcode`（内容 `0`）。该文件含本机绝对路径，**不入库**；下表是原文摘录（只去掉本机路径列，其余逐字保留）。
- **结论行（原文）**：

  > **PASS**: 0 failures, 0 warnings, exit code 0 (lint hints: 0, informational, not a failure).

摘要原文（`check-all summary (2026-10-19 22:25:12)`）：

```
- commit: 0bb81f9dbee1d04e9baebe41eb5f2587c22599a6
- worktree: clean
- run: full check
- mode: offline --offline
- gradle tasks: test :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
- gradle exit code: 0
- tasks: 255 executed, 21 up-to-date
```

单元测试原文表：

| module | test task | cases | failures | errors | skipped |
|---|---|---|---|---|---|
| :app | testDebugUnitTest | 181 | 0 | 0 | 0 |
| :contract | test | 22 | 0 | 0 | 0 |
| :hearth | test | 46 | 0 | 0 | 0 |
| :llm | testDebugUnitTest | 13 | 0 | 0 | 0 |
| :model-api | test | 3 | 0 | 0 | 0 |
| :net | test | 55 | 0 | 0 | 0 |
| :roundtable | test | 28 | 0 | 0 | 0 |
| **total** | | **348** | **0** | **0** | |

lint 原文表与警告原文行：

```
| :app | debug   | 0 | 0 | 0 |
| :app | release | 0 | 0 | 0 |
| **total** |     | **0** | **0** | **0** |
- warnings to fix: 0
- llama.cpp warnings (agreed exemption): 0
- Kotlin compiler error lines: 0
```

产物原文表（去掉绝对路径列）：

| APK | size | signature |
|---|---|---|
| app-debug-androidTest.apk | 2.8 MiB | signed |
| app-debug.apk | 59.7 MiB | signed |
| app-release-unsigned.apk | **38.9 MiB** | **unsigned** |

- 12 个期望任务**全部 `executed`**（没有 `UP-TO-DATE`/`FROM-CACHE` 混进来），llama.cpp 校验到 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`。
- **本次只跑了 `check-all.ps1`**（本轮要求就是它）；`check-all.sh` 本轮未跑。以往两条脚本同 worktree 先后跑时，SH 那一次多数任务会 `UP-TO-DATE`、结论继承前一次 PS1 的产物，所以本报告不拿 SH 的读数当独立证据。
- 第 7 步「正式包只带 arm64-v8a」的改后大小即上表的 **38.9 MiB**（改前大小见第 7 步提交 `6df413e`/`f47c706` 当轮门禁记录）。

## 五、check-public 结果

- **跑法**：仓库工作区干净，`scripts\check-public.ps1 -Full`。原始日志在仓库外 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\public-0bb81f9.txt`（**只打印类别、文件与行号，从不打印命中的原文**，仍不入库）。
- **范围读数（原文）**：被跟踪文件 390（383 扫描、7 个二进制跳过）；基线 `origin/main 3c7074c2b62b2a1f045434673414e6476b5ce977`；HEAD `0bb81f9dbee1d04e9baebe41eb5f2587c22599a6`；范围里 178 个提交；已接受清单 13 个提交、13 个都在范围内；本地词表**未配置**（统筹自己写 `.local/public-denylist.txt`）。这些读数取自 `0bb81f9`；交付报告入库后的尖端经全量审独立复算为 186 个提交、392 个被跟踪文件（385 扫描、7 跳过，见 `REVIEW.md`）。
- **退出码 1 —— 推送门禁仍然 FAIL**（这一条是预期内的，理由见下）。
- **阻断项：`[user-path]` 共 2 条，全部落在历史改动里**，两条指向同一个位置：
  - `9763503` → `docs/reviews/2026-10-18-P2-2b/PLAN-REVIEW.md:185`（第一次把这一行写进去的那次提交）
  - `9e5b830` → 同一文件同一行（**把这一行洗掉**的那次提交；命中来自它的删除行）
  - **这两条不在 13 个已接受提交里**，所以脚本按阻断处理。
- **HEAD 里被跟踪的文件：本轮脚本读数 0 条阻断命中，但这不是「HEAD 真的干净」**（全量审推翻，见 `REVIEW.md` M9）。脚本把 `docs/reviews/2026-10-19-P2-2b-wording/check-all.txt` 当二进制跳过了：它是 UTF-16 LE（BOM `FF FE`）、含 NUL；解码后**第 12 行有真实的本机账户名路径**，该文件在 `3dc17e1`、`2076456`、`0bb81f9` 和当前尖端都存在。**我独立验证**：该文件 5356 字节，按 UTF-16 解码后 1 行命中账户名路径（HEAD 与历史同一条）。所以这个 0 只能读作「脚本能看见的文本里 0 条」。
- **已接受清单生效情况**：13 个提交**全部**被读到并命中（`accepted-history hits: 72`），按类别 `[accepted-history]` 只列不拦：`user-path 19、local-path 53`。
- **只列不拦**：`[local-path] 183 条`（本机盘符路径，交统筹逐条看）。
- **范围对照（原文）**：`findings by scope: HEAD 103, history 154`；`accepted-history hits: 72; history hits outside the list: 82`。这 82 条里 **80 条是 `local-path`（本来就不拦）**，只有上面那 2 条是 `user-path` 阻断项。
- **对这 2 条的判断（供统筹裁定，本轮没有自己动手）**：命中段落是一段 **3 个 ASCII 字符的示例目录名**（此处原写「4 个」，经全量审实测为 3 个，已回改），**不是本机账户名**，也不等于本仓历史上被接受公开的那批用户名；这一行本身就是「描述该类模式」的那句话（这次洗刷就是 `9e5b830` 干的事）。也就是说，它是**模式形状的误报**，但按脚本现有规矩它照样阻断。
- **实际做法（此处按实回改）**：用户 2026-10-20 裁定「接受公开、不改写历史」，于是这 2 个提交连同 `3dc17e1`、`2076456`、`0f9d680` 一并补进了 `scripts/accepted-history-commits.txt`（清单 13 → 18 个，见第八节与 `28d6253`）。**本节上面那段原写「没有加进清单、三选一交统筹」，与后来实际做法相反，特此更正**：没有放宽 `check-public.ps1` 的规则，也没有为了过检查去删证据文本。
- 另有三类只能靠重写未推送历史才能清掉的东西：**(a) 本次新发现、HEAD 仍在的那一条** —— `docs/reviews/2026-10-19-P2-2b-wording/check-all.txt` 里（UTF-16，见上）的账户名路径，涉及 `3dc17e1`、`2076456`、`0bb81f9` 与当前尖端；**(b)** git 提交元数据里的作者邮箱本地部分含本机账户名；**(c)** `9ef4eb9` 树里的本机账户名/设备型号字面量（HEAD 已洗成占位符）。**(a) 不在脚本的阻断项里 —— 那是脚本自己漏了它（M9）**；三条的处置方式等统筹/用户裁定。

## 六、真机环节

**2026-10-20 白天做了 A 组，B/C 组仍然没做。** 下面是实况。

**已办（2026-10-20 白天，用户在）**

* **前后核对正式版**：开工前只读核对——正式版 `com.xavierxia.mycelium` = `0.1.1` / versionCode `2`，`lastUpdateTime 2026-10-10 05:23:04`。**收尾那次核对还没做**：手机现在与电脑断开（`adb devices` 为空），等手机接回来再核一次。
* **A.2 升级保数据（按统筹 2026-10-20 的要求如实写）**：卸掉旧调试包（`0.3.0`/4，用户已同意）→ 装仓库外的 `v011-baseline-debug.apk`（`0.1.1`/2，截图 `dev1.png`）→ **覆盖安装** P2-2b 调试包（`0.4.0`/5）→ 能打开，`files/profileInstalled` 仍在。**但 v0.1.1 是阶段 0 外壳、没有业务数据，所以这条只能证明「数据目录不被清空」，真正的业务数据保留要等 0.4.0 → 0.4.1 升级时再验。**
* **A.3 模型登记与测例**：用户登记了 `Qwen3.5-4B-Q4_K_M.gguf`。第一次全量 28 例有 5 例失败（3 例「模型没登记」+ 2 例 Compose 20 秒超时）；登记后重跑那 5 个类 → **OK（18 例）**。
* **全量 28 例一次跑完并归档：还没做到。** `gradlew :app:connectedDebugAndroidTest --offline` 因 `com.android.tools.utp:android-test-plugin-host-additional-test-output:32.3.0` 不在离线缓存而解析失败；改用 `:app:assembleDebugAndroidTest --offline` + `adb install -r` + `am instrument -w`（`instrument.txt` 28 例/5 失败、`instrument2.txt` OK 18 例），但最后那份 `instrument-full.txt` 只有 1901 字节，**是被截断的、不作为证据**，需要重跑并可靠归档。
* **B 组（四步走查、系统确认、未知来源往返、校验失败截图、v2-only 签名、哈希耗时、cacheDir 回收、跳转链域名）全部未做**，原因见下条；C 组（P2-1c 测速与温度 0 的对比）同样未做。
* **为什么 B 组现在做不了**：手机上装的是**调试包**，而 `MyceliumApp.kt:140` 用 `BuildConfig.APPLICATION_ID.endsWith(".debug")` 判定 `isDebugPackage`，`UpdateCenter.kt:147` 对调试包直接 `return` —— 调试包**根本没有更新入口**（这正是 M1 的 `debugPackageHasNoInstallEntry` 测例断言的设计）。所以真机更新流程只能用「applicationId 不以 `.debug` 结尾」的包走，即 release 变体，而它的 applicationId 与手机上的正式版 `com.xavierxia.mycelium` 冲突（红线：不碰真机正式版）。**走法需要用户/统筹先定**：(A) 临时建一个走查变体（applicationId 如 `…upcheck`、`versionName` 临时设 `0.1.0` 让线上真实 `0.1.1` 变成「有更新」、用本机调试签名，只在本机改、不提交，走完还原）；(B) 用 release 变体覆盖正式版（会替换正式版数据）；(C) 由统筹在发布侧自己验。我按无人在场的保守做法选 **(A) 的推迟**：不动正式版、不改 build 配置，等用户定。

**走查变体怎么复现（2026-10-20 夜，本机临时做法，不提交）**

1. 临时把 `app/build.gradle.kts` 里 debug 的 `applicationIdSuffix` 由 `".debug"` 改成 `".upcheck"`（不改这一行，`isDebugPackage` 就是 true，更新入口根本不会出现），再把顶层 `versionCode` 临时改成 `1`、`versionName` 临时改成 `"0.1.0"`——这样线上真实的 `0.1.1` 才成为「有更新」。**改前先备份**（本次备份在仓库外 `p2-2-work\app-build-gradle.kts.original`），走查完还原。
2. `gradlew :app:assembleDebug --offline`，得到 `app\build\outputs\apk\debug\app-debug.apk`（包名 `com.xavierxia.mycelium.upcheck`，与手机上的正式版 `com.xavierxia.mycelium` 和调试包 `…debug` 都不同，互不覆盖）。
3. `adb install -r` 装上，`monkey -p com.xavierxia.mycelium.upcheck -c android.intent.category.LAUNCHER 1` 启动；「我的」页出现红点与「发现新版本：0.1.1 / 安装包 21.9 MiB / 下载并安装」。
4. 点「下载并安装」→「下载中 x / 21.9」→ 校验 → 拉起系统确认界面（**「安装」由用户点**）。
5. **走查完要清理**：`adb uninstall com.xavierxia.mycelium.upcheck`（本次载到一半时手机断开，这个包**还留在手机上**，要卸掉），并确认正式版仍是 `0.1.1` / code `2` / `lastUpdateTime 2026-10-10 05:23:04`。

**本次实测到的两点，先记下来**

* **下载速度**：2026-10-20 23:0x，本机（手机经 Wi-Fi）从 GitHub 下 21.9 MiB，55 秒只走了 0.3 MiB，约 **0.3 MiB/分钟**；按这个速度要一个多小时。走查时要么耐心等，要么换网络再看。
* **断了之后能续上**：应用把 `.part` 留在 `updates/` 下，重连后再点「下载并安装」是续传；断线那一刻进度停在 0.3 / 21.9。

**以下三组仍是留给真机、且需要用户在场的事项（编号沿用原清单）**


**A. 前置与共同项**

1. 先问用户能不能用手机；得到同意后，**只读**核对手机上调试包的现状。正式版 `com.xavierxia.mycelium` 全程不碰，**开工前后各核对一次版本号和安装时间**。
2. **升级保数据**：先卸掉手机上现在装着的那份调试包（用户已同意），装 **v0.1.1 调试版基线包**（上个会话已编好并留在仓库外：`D:\Agent\Worktrees\Mycelium-Android\p2-2-work\v011-baseline-debug.apk`），造测试数据，再**覆盖安装** P2-2b 的调试包，确认能打开、数据还在。
3. 请用户在调试包里**登记一个本地模型**，然后跑本地模型相关的设备测例（`#4`、`#10`、`#21`、`#22`、`LocalModelDeviceTest`、`LongGenerationDeviceTest`），**再确认 25 个设备测例能一次跑完**（这一条正是 `6ef1b2f` 要修的已知问题）。这些测例属于「发 0.4.0 前必须补」的两件事之一。

**B. P2-2b 自己的真机项**

1. 四步串起来走一遍：自动检查 → 红点与卡片 → 下载进度 → 校验通过 → 拉起系统确认界面。
2. 系统安装确认界面，以及「安装未知应用」引导（未允许时先说明再带路，回来接着装）。**「安装」按钮由用户点**，或经同意后由 agent 点；不碰手机上装着的正式版。
3. 校验失败时的截图（方案审点名要过；`PLAN.md` 只记了取舍：失败一律删文件、用中文文案告诉用户稍后再试）。
4. **v2-only 签名能不能读到**：`AndroidUpdateFacts` 的 `ApkFactsReader`（`getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES`）在各 ROM 上的表现，v0.1.1 的 APK 是 v2-only 签名。
5. **38 MiB 安装包算哈希的耗时**：据此判「边下边算（流式哈希）」值不值得做。
6. **`cacheDir` 被系统回收**以后的表现：`updates/` 下的候选文件被回收、进程重建后的恢复路径（第 3 步 `restore()` 有 JVM 侧测例，真机未验）。
7. **跳转链的实际域名**：本机实测是 `github.com` 302 → `release-assets.githubusercontent.com` 200、**只有两跳**，且本机 `remote_ip` 看着像经过代理/中转；手机上的解析路径可能不同。出现新域名按「失败关闭 + 浏览器兜底」处理。
8. 另两项也留真机：真 `StatFs` 的可用空间取值；第 5 步「建会话前再复查空间」。

**C. P2-1c 的真机项**

1. 本地对话提速的**测速**（同样的提示，改前/改后读提示耗时对比）。
2. **温度为 0 时，有缓存与无缓存的逐字输出对比**，以及截图。

## 七、待统筹或用户确认（每条写清我先选的保守做法）

| 事项 | 我先选的保守做法 |
|---|---|
| 范围补充分支 `wp/P2-2b-persona-pack-wording`（改动 `f1bb2d8`、`4da756c`，门禁证据 `2076456`，界面文字与 `:contract` 的 8 句报错统一成「人物包」） | **已经合入，不是「今晚没有合」**：全量审实测，合并提交 `5c33c55` 是 `0bb81f9` 的祖先，也与当前尖端 `36672cd` 同在一条历史里，`docs/reviews/2026-10-19-P2-2b-wording/` 的证据因此就在本次全量审的范围内。**此处原写「今晚没有合」不属实，已按实测回改**；`docs/tracks/开发线.md` 里那句「待合进 `wp/P2-2b-release-ready`，随本包一起审」也要同步改成「已合入」——见 M12。 |
| `check-public.ps1` 的 2 条 `[user-path]` 阻断项（第五节） | **已按用户 2026-10-20 裁定办**：补进已接受清单（清单 13 → 18，见第八节与 `28d6253`）；**没有**放宽规则、**没有**删证据文本。第五节那句「没有自己加进清单」是当时的状态，已按实际回改。 |
| git 提交元数据里作者邮箱的本地部分含本机账户名（历史里有，**以后的新提交也还会有**，除非改配置或改历史） | 不改历史、不动 git 全局配置；新提交照常提交（沿用 P2-2 的处置），等统筹定。 |
| `GatewayPurpose` 没加「下载」专用枚举：下载复用 `Update`，`sentBody` 恒为 `""`（下载审计没有正文） | 不加（加要动 `AllowedHosts` 的 `when` 与 app 的 `purposeLabel`，本轮没有收益）；如实记「下载审计没有正文」，等统筹定。 |
| 「校验失败截图」要不要单列一条真机项（方案审点名要，但 `PLAN.md` 只记了取舍） | **按单列处理**（第六节 B3），交统筹确认。 |
| 「并发与升级专项审」未跑：审稿要求仓库里有 `codex-prompt-special-concurrency.md`，实际只有 `SPECIAL-隐私出网.md` 一份 | 额度不够就不硬凑、不自己代审，等统筹定「补一轮 / 并进全量审 / 接受」。 |
## 八、全量审必修项处置记录（按用户 2026-10-20 定的顺序逐项记）

> 来源：`REVIEW.md`（Codex，结论「需修改」，M1–M13）。每项一个提交，做完一项记一行，最终以门禁与第二轮复审收口。

| 项 | 状态 | 提交 | 说明 |
|---|---|---|---|
| M9．扫描器漏报 UTF-16、HEAD 里有真实账户名 | **已修** | `0f9d680` | 扫描器先判编码（UTF-8、UTF-16 LE/BE，含无 BOM 的奇偶判定）再判二进制；历史里被 git 当二进制显示的文本改读 blob。`docs/reviews/2026-10-19-P2-2b-wording/check-all.txt` 由 UTF-16 转 UTF-8 并把账户名替换为 `<user>`。自测 30 项全过（新增 UTF-16 LE / BE / 无 BOM / 干净 UTF-16 / 含模式文本的二进制共 5 项）。**修复后 HEAD 0 条阻断**；历史里多出原先被跳过的 3 条命中（`3dc17e1`、`2076456`、`0f9d680` 的删除行），正是那几行 UTF-16 文本。 |
| M9．已接受清单补充 | **已办** | 随本行 | 按用户 2026-10-20 裁定（理由「用户接受公开，不改写历史」）补入 5 个提交：`3dc17e1`、`2076456`、`0f9d680`、`9763503`、`9e5b830`。前三个是含账户名的证据文件版本与脱敏提交，后两个是 `PLAN-REVIEW.md:185` 那两条历史阻断。 |
| M1．下载与校验跑在主线程 | **已修** | `ce2404e` | `GatewayApkDownloader`、`LocalApkVerifier` 各加可注入的 `io`（真机 `Dispatchers.IO`），把阻塞的取 `.sha256`、下载、空间检查、改名、读边车、算哈希、读 APK 全部放进 IO 边界；界面状态仍在原线程语义里更新。新增 `UpdateThreadBoundaryTest`：**用真实下载器/校验器** + 记录线程的 transport，断言三处都落在 IO 线程；另有一例在下载卡住期间证明主线程仍能立即干活（不切线程即超时）。`UpdateCenterDownloadTest` 那组测状态机，IO 边界显式换回 `Dispatchers.Unconfined`。151 个 update 单测全过。 |
| M3．取消、重入与放弃清理 | **已修** | 本轮 `fix(M3)` | ①退避等待改成可取消的分片睡眠（默认 `cancellableSleep`，50 ms 一片），`FileDownloader.pause` 因此拿到取消句柄；②`ApkVerifier.verify` 增加 `call` 参数，`LocalApkVerifier` 用**下载那一个**句柄算哈希，并让 `GatewayCancelled` 原样上抛（不再被当成失败删掉已下好的文件）；③`UpdateCenter` 引入任务身份 `downloadGeneration`：取消/忽略/新一次下载都会让它加一，旧任务的进度与终态一律作废；④`ignore(tag)` 现在会取消任务并删该 tag 的 `.apk/.part/.sha256`；⑤`onCleared()` 先取消取消闸门再取消 scope。新测例：`CancellablePauseTest`（3 例）与 `UpdateCenterCancelTest`（3 例，含「取消后旧任务迟到返回不许变 Ready」「忽略要连带清缓存」「校验与下载同一个句柄」）。`:net:test` 与 update 单测全过。 |
| M2．「下载并安装」实际要三次点击 | **已修** | 本轮 `fix(M2)` | 四项校验一过就接着装：`verifyDownloaded` 在 `Verified` 分支落 `Ready` 后立刻调 `install()`。已授权时一次 `startDownload()` 就建一次会话；未授权时先落「安装未知应用」说明，从设置页回来（`onResume`）接着装，系统最终确认仍由用户完成。旧契约「下载成功停在 Ready 等第二次点击」的两条测例已按新契约改写（同步返回 `Installed` 的那条改成断言「装完就清理」，另一条改用 `Committed` 以保留文件与待安装路径）。新增 `UpdateCenterAutoInstallTest`（3 例：自动续装 / 未授权先说明再续 / 校验阶段取消不进安装）。`:app` 全量单测通过。 |
| M5．候选版本没和下载状态、文件绑在一起 | **已修** | 本轮 `fix(M5)` | ①`applyResult` 记住「上一个候选 tag」，候选换成新 tag 时取消在跑的任务、把旧 tag 的 `.apk/.part/.sha256` 与 `pendingApkPath` 一起清掉，下载状态回 `Idle`；②检查**失败**时不再清候选（卡片、续传目标与 `.part` 都留着），只有确定没有候选时才清；③`install()` 增加归属校验：`Ready.file` 必须是当前 tag 的 `files.finalFile(tag)`，对不上就删文件回到 `Idle`；④`restoredDownload` 同样按 tag 核对，进程重建后不会把旧包当新版本。新增 `UpdateCenterCandidateBindingTest`（3 例：换 tag 清旧包且回前台也不装 / 断网保住候选与续传目标 / 落盘文件属于别的 tag 时不恢复成可安装）。`:app` 全量 193 例通过。 |
| M6．「一天一次」按回来才算，且能并发重入 | **已修** | 本轮 `fix(M6)` | ①自动尝试时间改为在**请求发出之前**写入（`checkIfDue`），中途退出、重建或被取消都算今天已经试过；②加 `checkGate`（`Mutex.tryLock`）让在途自动检查互斥，第二个入口直接返回；③`performCheck` 不再重复写尝试时间，只写「上次检查成功」的显示时间；④手动检查不受该闸门与 24 小时限制。新增 `UpdateCenterCheckThrottleTest`（3 例：并发自动入口只问一次发布源 / 取消也算今天试过、同小时重建不再发 / 手动检查不受限）。`:app` 全量通过。 |
| M7．安装会话恢复、放弃与原子状态 | **已修（简化口径）** | 本轮 `fix(M7)` | 按用户 2026-10-20 的裁定**不做跨进程恢复安装**：①`restore()` 发现上个进程留下的非终态会话时，凭落盘 `sessionId` 调新增的 `InstallSessionGateway.abandon(id)` 撤掉它并清记录，包还在就回「已下载、待安装」（卡片上就是「继续安装」入口）；②`install()` 增加安装前复查：文件必须存在、长度与校验时一致，这一版已经装上（`installedVersionCode() >= targetVersionCode`）就不再建会话；③`VerifyOutcome.Verified` 带上包里读出的 `versionCode`，随会话落盘为 `InstallSessionRecord.targetVersionCode`；④落盘状态**单调**：`installSessionAccepts` 改成「同 id 且阶段严格前进、终态吸收」，迟到的 `Committed` 盖不掉 `Pending`，重复 `Pending` 不重复落盘；`MemoryInstallSessionState` 与 `UpdatePreferences` 的读/写/清全部 `@Synchronized`，`advance` 的「读—判断—写」在同一把锁里；`UpdatePreferences` 落盘 `installSessionTargetVersionCode`。新增 `InstallSessionRecoveryTest`（6 例：上个进程的会话被放弃且回 Ready / 包没了点安装给明确失败 / 已装版本不再建会话 / 迟到 Committed 不回退 / 重复 Pending 被拒 / 终态吸收）。**「继续安装」入口已核**：`MyceliumApp.kt:668` 在 `download is Ready` 时画「安装」按钮并接 `center.install()`，所以进程重建后落回 Ready 就有可点的入口。`:app` 全量通过。 |
| M4．空间公式错、安装前不复查 | **已修** | `3debe71` | ①`requiredBytes` 去掉重复计入的 `occupied`：改为 `剩余下载 + 会话副本 + 余量`，审核点名的场景（38 MiB 包已下 19 MiB、可用 90 MiB）由「要 108 MiB」变成「要 89 MiB」，正常续传不再被误拒；②新增 `sessionRequiredBytes` / `requireSessionSpace`，`startSession` 在建会话**之前**复查一次空间（下载完到点安装之间空间可能已变化），不够就 `failInstall` 并带上差值；③`UpdateFilesTest` 里锁住旧公式的断言按新口径改写，并新增「半包 90 MiB 放行 / 少一字节拒绝」与「安装步还要一份会话副本」两例；`UpdateCenterAutoInstallTest` 增加「校验后空间被吃掉 → 不建会话」一例。`:app` 全量 205 例通过。 |
| M8．发版闸门对部分不可读标签失败开放 | **已修** | `84895dd` | ①浅克隆在计算上界**之前**就中止（不再只是 warn 后继续）；②任何一个正式标签的 `versionCode` 读不出就中止并点名，不再把「读不出来」当成「没有更高版本」。本地对照实测（Git Bash）：旧脚本在「可读 v0.1.1 + 不可读 v9.9.9」时通过检查 3 并继续到 APK 检查；新脚本直接中止并点名 `v9.9.9`；浅克隆同理（旧：警告后继续 → 新：立即中止）。正常历史仍通过检查 3（`versionCode 5 大于已发布最高 v0.1.1 的 2`）。临时克隆用完已删。 |
| M10．文件名内容与输出脱敏 | **已修（部分）** | 本轮 `fix(M10)` | ①新增 `Test-SensitiveNameWord`：本地词表的词出现在**文件名**里也命中 `local-denylist`，HEAD 循环、历史两处 `diff` 头、单目录扫描四处都接上；②新增 `Hide-SensitiveText`，`Add-Finding` 统一在记账前脱敏——位置里若带 token / 词表词，打印出来是 `*`，报告不再抄原文；③自测新增「词表词在文件名里」一例（同时断言输出里不出现该词）；④补齐审查点名的五类历史形态：多 hunk、重命名（`git mv` 后再改）、UTF-16（git 当作二进制，走 M9 的 blob 读取）、合并提交（`diff --cc`）、空范围（基线=HEAD 不阻断）；自测 **36 项全过**。 |
| M11．审计的「已联系」语义与失败字节数 | **已修** | 本轮 `fix(M11)` | ①`HopWalker` 不再在 `open()` 之后就把域名记进「实际联系」，改到**拿到状态码**之后——建连前被取消、连接根本没建立的那几跳不再被记成已联系；②元数据读取改为**逐块记账**，超限/中断时已经读到的字节如实落盘（原来失败一律记 0）；③隐私页不再把空链回填成单域名：只有「成功 + 空链」（旧格式记录）才按单域名显示，失败且空链显示「未联系任何域名」。`DownloadCancelGateTest` 里那条「取消后仍记域名」的旧断言按新语义改成空链（这是审核点名的缺陷本身，不是放宽）。`:net:test` 与 `:app` 全量单测通过。 |
| M12．回改方案、架构与交付报告 | **大部分已办** | 本轮 `docs(M12)` | ①`docs/tracks/开发线.md` 四处按实测回改：人物包**已合入**（`5c33c55`，不再是「待合进」）、扫描器读数从「18 条阻断、门禁仍 FAIL」改为「M9 修复后 `-Full` PASS、0 条阻断，历史仍含账户名属用户已接受公开」；②`docs/UPDATE_ARCHITECTURE.md` 修正「签名一致 ⇔ 能覆盖安装」这句错的等价关系，改成「签名一致只是必要条件，还要包名/versionCode/签名数量，最终由系统确认」；③`DownloadCancelGateTest` 类注释按实测改正：两处一起改回裸调用才有一条变红，只改 `responseCode` 仍全绿（与交付报告第五节的披露一致）；④`DELIVERY.md` 三处事实错误（人物包未合入、HEAD 干净、示例段 4 字符）已在早前提交里回改。**未做**：`PLAN.md` / `SURVEY.md` / `IMPL-BRIEFS.md` 的 C0 收敛与「改前 / 阶段改后 / 最终大小及对应提交」的数字表（这三个文件是统筹的方案文档，我不改别人的方案；APK 中间大小的历史读数没有留存，只有最终 38.9 MiB 与 `0bb81f9` 门禁可查）。 |
| M13．补有判别力的测试与设备验收 | **部分已办** | 随各项提交 | 已补：`UpdateThreadBoundaryTest`（真实下载器/校验器 + 记录线程，含「主线程不被卡住」）、`UpdateCenterCancelTest`（取消/忽略/同一取消句柄）、`UpdateCenterAutoInstallTest`（自动续装/未授权先说明/空间被吃/校验取消）、`UpdateCenterCandidateBindingTest`（换 tag 清旧包/断网保候选/异 tag 文件不恢复）、`UpdateCenterCheckThrottleTest`（并发只发一次/取消也算试过/手动不受限）、`InstallSessionRecoveryTest`（会话放弃、安装前复查、状态单调）、`CancellablePauseTest`、`UpdateFilesTest` 的空间边界、扫描器自测 36 项（含五类历史形态）。**另补**：`downloadFollowsExactlyFiveRedirects` —— 恰好五跳必须成功（否则「拒绝多于五跳」可以被实现成「拒绝五跳」），并把「六地址」的断言从引用 `MAX_REDIRECTS` 改成字面量 6，限额被改动时不会跟着一起变。**未做（等白天用户在/另行授权）**：真机自动提示→下载→校验→系统确认全链、未知来源返回、成功/取消/校验失败截图、新旧包证书与包名对照、真实数据保留清单。 |
| 全量门禁（13 项修完之后） | **PASS** | 最终尖端 `06a79b1` 的全新工作树 | `check-all.ps1` 原文：`**PASS**: 0 failures, 0 warnings, exit code 0 (lint hints: 0, informational, not a failure).`；347 个用例 0 失败（app 205 / contract 22 / hearth 46 / llm 13 / model-api 3 / net 58）；3 个 APK 全部产出（release 未签名 38.9 MiB）；warnings to fix 0。日志在仓库外 `p2-2-work\gate-final.txt` + `.exitcode`（0）。`06a79b1` 之后只允许 `DELIVERY.md` 与复审要求的记账提交。 |
| 第二轮复审（最多三轮） | **未完成：Codex 额度用尽** | — | 三次尝试（09:56、10:01、10:06）都以 `You've hit your usage limit … try again at 11:58 AM` 结束，第三次跑满 539 秒、消耗 162,869 tokens 后仍 0 字节报告；前两次的 `workspace routing discovery timed out` 是同一额度限制下的表象。提示词与启动脚本本身没问题（第一轮同一套跑通过）。**额度 11:58 恢复后重跑**：`run-review-p2-2b-r2.ps1` + `codex-prompt-review-r2.md` 已就位，工作树 `p2-2b-review-r2` 在 `c6fc08c` 且干净。 |
## 九、第二轮复审（REVIEW-R2.md）处置记录

| 项 | 状态 | 提交 | 说明 |
|---|---|---|---|
| 复审报告入库 | 已办 | `8232d96` | `docs/reviews/2026-10-18-P2-2b/REVIEW-R2.md`（原文，账户名/本机路径命中 0） |
| M3 缺口 | **已修** | `4ee348b`、`3bef3f7`、`60cd24a` | ①`stateLock` + `writeIfCurrent(generation)`：判代次与写状态在同一把锁里，进度、Ready、失败、取消全部走它；②`startDownload` 先 `previous?.join()` 再开始；③`ignore` 与换 tag 的**删除**改成 join 之后执行（旧任务还在写时不许删）；④`UpdateCenterCancelTest` 改成先断言「旧任务还在写时缓存还在」，放行后才断言已删——正好复现「取消刚发出、旧任务还在写」这个时刻。`:app` 205 例全过。 |
| M1 缺口 | **已修** | `ce41f4c` | 新增 `UpdateRealTransportTest`：真实 `UpdateCenter` + `GatewayApkDownloader` + `LocalApkVerifier` + 本机回环慢服务 + **真实 SHA-256**（无固定返回值）。三例：①网络、读盘、哈希的线程名都不等于主线程；②慢下载中 `cancelDownload()` 在 2 秒内返回、状态回 Idle 且 500ms 内不被迟到的进度写回；③取消后回环服务的请求计数不再增长。另有一例直接比对哈希与服务端字节。 |
| 指纹/版本解析失败关闭 | **已修** | `384dccb` | 指纹：去注释与空行后**必须恰好一行**，整行必须是 64 位十六进制（65 位、两行相同、多行、夹带别的字符一律中止）；版本：`versionName`/`versionCode` 在去注释后**各恰好一处**赋值，零处或多处中止。四条正反例已用真实 `release-guard.sh` 实测（两行相同→中止；65 位→中止；一行有效+注释→放行到 apk 检查；版本两处→中止；注释掉的一处→忽略）。 |
| C0 文档 | **已办** | `1b80399` | `PLAN.md`、`SURVEY.md`、`IMPL-BRIEFS.md` 各加前言「本文是开工前的方案稿，部分内容已被否决或改动，以 `docs/UPDATE_ARCHITECTURE.md` 与 `DELIVERY.md` 为准」；`IMPL-BRIEFS.md` 的「动态接收器 + FLAG_IMMUTABLE」退路加删除线并注明否决理由；`UPDATE_ARCHITECTURE.md` 修正 416 语义（盘上字节就是整份内容，直接进哈希）与空间公式（剩余量 + 会话副本 + 余量，不再重复算已占用）。 |
| 作者邮箱 | **已裁定，不处理** | — | 用户的提交邮箱从 0.1.1 起就已公开，统筹 2026-10-20 裁定这一项不再算问题。 |

### 五类历史自测怎么跑（统筹验收用）

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-public.ps1 -SelfTest
```

期望最后一行 `check-public: self test: 37 checks, 37 passed, 0 failed`（第三轮加了中英混排的 `utf16-mixed-endianness-fails`，已重跑确认）。其中与历史形态有关的六例：
`multi-hunk-history-hit-fails`、`renamed-file-history-hit-fails`、`utf16-binary-history-hit-fails`、
`merge-history-hit-fails`、`empty-range-does-not-block`，以及接受清单相关的
`history-hit-outside-list-fails` / `head-hit-fails-even-if-listed`。
自测会在临时目录里建一次性 git 仓库（多 hunk、重命名、UTF-16、合并、空范围），跑完自动清掉，不动本仓库。
## 十、第三轮复审（REVIEW-R3.md）逐条处置记录

统筹 2026-10-20 15:18 裁定：复审到此为止，不再跑第四轮 Codex，余下由统筹代审。下表每条都给出「修复提交 → 证明它的测例 → 真机证据」。

| 复审条目（第三轮） | 修复提交 | 证明它的测例 | 真机证据 |
|---|---|---|---|
| M3 等待链：取消后旧任务的句柄要留住；换 tag 也不能立即删旧文件 | `c60c8e9`（`pendingStop`）、`4ee348b`/`3bef3f7`/`60cd24a`（第二轮） | `UpdateCenterWaitChainTest.cancellingAndRetryingImmediatelyWaitsForTheOldTaskToLeave`、`.cancellingAndIgnoringImmediatelyDeletesOnlyAfterTheOldTaskLeaves`、`.aSecondStartWhileVerifyingIsRefused`（事件序列断言 `enter#1,exit#1,enter#2,exit#2`） | 取消→立即重试、取消→立即忽略 各一张（待真机） |
| M4/M7 从「安装未知应用」授权回来也要走安装前复查（文件长度、目标版本） | `11c09ab` | `PackageInstallFlowTest.startsTheSessionAfterTheUserGrantsUnknownSources`、`.pendingUserActionWithoutTheConfirmIntentIsStillRecorded` | 未知来源往返截图（待真机） |
| M7 进程重建后恢复的 Ready 不许拿当前文件长度当「已验证」 | `3fbcb58`（`restoredNeedsVerify` → 重算哈希后再装） | `PackageInstallFlowTest.aSessionThatDisappearedWithoutAVersionChangeIsNotCancelled`、`.processDeathAfterSuccessIsRecognisedFromTheStoredCandidate` | 恢复后重校验截图（待真机） |
| M7 系统的确认界面没拉起来时：处理返回值，前台给「继续安装」入口 | `3fbcb58` | 同上（记录清掉、回到「可以安装」，卡片按钮按 `installReady && installable && 非 Installing/Verifying` 判定） | 「继续安装」入口截图（待真机） |
| M7 状态锁全进程唯一、安装端与接收端共用；空记录只允许从初始阶段写 | `3fbcb58`（`installSessionLock`）、`dafc249`（空记录规则 + 正反例）、`d3fa186`（落盘实现的读/写/清也进同一把锁，自审发现它当时并没有真进锁） | `InstallSessionRecoveryTest.anEmptyStoreOnlyAcceptsTheInitialPhase`（Pending/Succeeded 被拒、Created 通过）、`.aLateCommittedCannotRollBackAPendingRecord`、`.aRepeatedPendingIsRejected`、`.aTerminalRecordIsAbsorbing`；`InstallSessionLockTest.aReadOfTheDiskStoreWaitsForTheOneSharedLock`（测试自己拿锁，落盘实现的读必须等在外面——**把锁去掉这一条就失败**，已实测）、`.concurrentWritersCannotLetATerminalSessionGoBackwards`（8 线程 × 500 轮，终态不能被写回更早阶段）、`.bothImplementationsApplyTheSameFirstWriteRule` | — |
| M5 候选版本消失也算候选变了；安装结果回来要核对是否当前候选 | `4a004a2`（两条逻辑）、`8071b42`（两例） | `UpdateCenterCandidateBindingTest.aCandidateThatDisappearsAlsoDropsTheOldPackageAndItsCache`、`.aTerminalRecordForAnOlderCandidateIsIgnoredInsteadOfApplied` | — |
| M8 读不出基线标签就中止，删掉「退回写死版本号」那条路 | `3ef146a` | 无 JVM 测例：用临时仓库跑真实 `release-guard.sh`，反例（基线 tag 里没有 `versionCode`）→ 中止「这些正式标签的 versionCode 读不到」；正例（基线 `versionCode = 2`）→ 检查 3 通过 | — |
| M9 无 BOM 的 UTF-16 不要求 NUL 同奇偶位置；NUL 比例高时按 LE/BE 都解码再扫 | `dd0d9a3` | `check-public.ps1 -SelfTest`：新增 `utf16-mixed-endianness-fails`（UTF-8 头 + 无 BOM UTF-16LE 尾，中英混排），连同 `utf16le-without-bom-fails`、`utf16be-user-name-path-fails`、`clean-utf16-passes` | — |
| M11 元数据读到一半失败按已读字节记账；没建立连接的记录落盘后保持空链 | `60b9853` | `AuditByteAccountingTest.aHalfReadMetadataBodyIsRecordedWithTheBytesThatActuallyArrived`（声明 4096、实发 100 → 台账记 100）、`.aRequestThatNeverConnectedKeepsAnEmptyHostChainAfterItIsPersisted` | 台账界面「未联系任何域名」截图（待真机） |

### 按已知问题接受、本包不改

* **M10 历史自测夹具不完全等于五类真实历史形态**：自测里的五个仓库是一次性建出来的近似形态，能挡住「多 hunk / 重命名 / UTF-16 / 合并 / 空范围」这几类漏扫，但不等于真实历史的完整形态。推送前由统筹自己再扫一遍真实历史（`check-public.ps1 -Full`）。
* **复审建议第 2～4 条**（历史扫描器的进一步加固）留到 0.4.0 之后再做。
* **作者邮箱**：统筹已裁定不处理。
## 十一、按第三轮修复后的尖端重跑：门禁 + 公共扫描

统筹 2026-10-20 要求「修完后在全新 worktree 跑 `check-all.ps1`（零失败零警告）+ `check-public.ps1 -Full` 和 `-SelfTest`」。实况如下。

* **跑在哪一个提交**：全新 worktree `D:\Agent\Worktrees\Mycelium-Android\p2-2b-final`，`git worktree add --detach` 定格在 **`d3fa186`**。之后的提交只有 `docs/reviews/2026-10-18-P2-2b/DELIVERY.md`（`git diff --stat d3fa186..HEAD` 实测：只动 `docs/reviews/2026-10-18-P2-2b/DELIVERY.md`，15 insertions / 3 deletions），**代码树与尖端完全一致**，所以下面这份门禁就是尖端的门禁。
* **llama.cpp 准备**：全新 worktree 里 `llm/vendor/llama.cpp` 是不入库的第三方源码，`prepare-llama.ps1` 在本机离线会去 clone 而失败；这次把主工作区里已备好的那棵（`git rev-parse HEAD` = `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，与 `CMakeLists.txt` 里钉的提交一致）整棵拷进去，再用 `check-all.ps1 -SkipLlamaPrepare` 跑。
* **门禁结果：PASS**。`check-all exit=0`；单测 **391 例 / 0 失败 / 0 错误 / 0 跳过**（`:app` 217、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、`:net` 62、`:roundtable` 28）；lint **0 错误 / 0 警告 / 0 hints**（debug 与 release 都 0）；Kotlin 编译错误行 0；warnings to fix 0；三个 APK 都产出（`app-debug.apk` 59.9 MiB signed、`app-debug-androidTest.apk` 2.8 MiB signed、`app-release-unsigned.apk` 38.9 MiB unsigned）。日志：仓库外 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\final-gate2\check-all.txt`（`.exitcode` 同目录）。
  这条门禁是**冷构建**（新 worktree、原生 CMake 从零配），本机跑了约 1 小时 50 分；期间还并行跑过别的 Gradle 构建抢守护进程，所以比正常慢。
* **`check-public.ps1 -Full`：退出码 0（PASS）**。读数：被跟踪文件 406（405 扫描、1 个二进制跳过）；基线 `origin/main 3c7074c2b62b2a1f045434673414e6476b5ce977`；已接受清单 **18** 个提交、18 个都在范围内；本地词表未配置；**阻断项 0**（`user-path` 0、`local-denylist` 0）；`findings by scope: HEAD 117, history 185`；`accepted-history hits: 100`（`user-path 24、local-path 76`）；范围外 85 条全部是只列不拦的类别；**302 条只列不拦，交统筹逐条看**。日志：同目录 `check-public-full.txt`。
* **尖端（`ee46627`）又跑了一遍公共扫描**：`check-public.ps1 -Full` 退出码 0、无阻断项；`findings by scope: HEAD 119, history 187`，`accepted-history hits: 100`、范围外 87 条，**306 条只列不拦**；`-SelfTest` 37/37。日志：`D:\Agent\Worktrees\Mycelium-Android\p2-2-work\public-tip-full.txt` 与 `public-tip-selftest.txt`。
* **`check-public.ps1 -SelfTest`：37 项全过**（`check-public: self test: 37 checks, 37 passed, 0 failed`），含第三轮新加的 `utf16-mixed-endianness-fails`。日志：同目录 `check-public-selftest.txt`。
* **与第五节的关系**：第五节的读数（被跟踪 390、范围 178 个提交、2 条 `user-path` 阻断）取自 `0bb81f9` 那次；按当前尖端重跑，同一脚本**退出码 0、无阻断项**——差别来自两点：清单从 13 个补到 18 个（用户 2026-10-20 裁定），以及 M9 之后 16 位文本按 LE/BE 都解码、多扫出内容。**以第十一节这份为准**。
## 十二、统一任务书 §1.2 / §1.3 处置（2026-10-21）

### §1.2 四处小问题（每处单独提交）

| 项 | 提交 | 做法与判别 |
|---|---|---|
| 1. `ignore()` 里 `return` 之后永远执行不到的赋值 | `159ea43` | 删掉那一行（纯死代码，行为不变）。 |
| 2. `startDownload()` 里多余的 `scope.launch { previous.join() }` | `f0c3a67` | 删掉那一行；`previous` 仍保留给下载任务内部的 `previous?.join()` 用，任务书说的正是「那一条多余的协程」。 |
| 3. 跨域跳转不带凭据类请求头 | `cb60d33` | `HopWalker` 里按「是否仍是最初那个域名」过滤 `Authorization` / `Cookie` / `Proxy-Authorization`；新增 `RedirectCredentialsTest` 两条（同域保留、跨域去掉）。**把过滤条件改成恒真，跨域那条立刻变红**，判别有据。 |
| 4. `.gitignore` 加 `.kotlin/` | `3bb765f` | 构建会生成 `.kotlin/`，此前会让 `check-public.ps1` 认为工作区不干净而拒绝运行。 |

### §1.3 装置（已入库，等真机执行）

* `23f7c6e`：`UpdateFlowDeviceTest`——在 androidTest 里注入本机假发布源（`127.0.0.1` 上的假服务，`releases/latest` JSON + 流式 APK + `.sha256`）与「按正式包对待」的检查策略（`isDebugPackage = false`）。三条场景：校验失败、下载中取消、整条装成功（装完断言 versionCode = 6 且数据目录还在），并在四个节点截图。APK 由测试准备脚本生成，**不入库**。
* `23f7c6e`：`UpdateRealNetworkDeviceTest`——真网关、真网络，下 v0.1.1 的 APK 与 `.sha256`，只下不装；量逐跳域名与耗时、下载耗时、哈希耗时，并读这个 v2-only 包在本机的签名；结果写设备上的 `update-flow/real-network.txt`。
* `23f7c6e`：`scripts/device/run-device-tests.ps1`（一次 invocation 跑完全部设备测例，原始输出落文件并核对行数与 `OK (n tests)` 结尾）与 `scripts/device/update-flow-prepare.ps1`（编出 versionCode = 6 的走查包并推进应用私有目录）。两个脚本都**不写死本机账户名**（用运行时的 `LOCALAPPDATA`）——第一版写死了，`check-public.ps1` 当场拦下 4 条 `user-path` 阻断项，已重写这两个未推送的提交，账户名没有进入历史。

### 门禁（全新 worktree，已提交的版本）

* 跑在 **`23f7c6e`**；worktree `D:\Agent\Worktrees\Mycelium-Android\p2-2b-gate-3`，脚本先断言它确实是 git 检出（`.git` 存在）再开跑——上一版脚本在第二次运行时 `worktree add` 失败、对着一个**陈旧目录**跑，已被这次修正并弃用那次读数。
* `check-all.ps1 -SkipLlamaPrepare`：**PASS**，393 例 / 0 失败 / 0 错误 / 0 跳过（`:app` 217、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、`:net` 64、`:roundtable` 28）；lint 0 错误 / 0 警告 / 0 hints；warnings to fix 0。日志：仓库外 `p2-2-work\final-gate4\check-all.txt`。
* `check-public.ps1 -Full`：**退出码 0（PASS）**，阻断项 0（`user-path` 0）；被跟踪文件 411（410 扫描、1 二进制跳过）；321 条只列不拦。日志同目录 `check-public-full.txt`。
* `check-public.ps1 -SelfTest`：**退出码 0**（37 项全过）。日志同目录 `check-public-selftest.txt`。

### §1.3 真机结果（待填，手机接上后补）

* §1.3.1 28 例一次跑完的归档：**待跑**。
* §1.3.2 三条场景与四张截图：**待跑**（「安装」由用户点）。
* §1.3.3 真实网络读数与签名读取结果：**待跑**。
* §1.3.4 升级保数据的如实写法已在本文件第六节（v0.1.1 是阶段 0 外壳，只证明数据目录不被清空）。
## 十三、真机 §1.3 结果（2026-10-21）

手机：NX809J（序列号不入库）。正式版 `com.xavierxia.mycelium` 开工前后各只读核对一次：`0.1.1` / code `2` / `lastUpdateTime 2026-10-10 05:23:04`，**全程未被碰过**。

### §1.3.1 全部设备测例，一次 invocation 跑完，0 失败

* 归档：`p2-2-work\device-evidence\instrument-28-final.txt`（412 行，结尾 `OK (28 tests)`）。跑法：`adb shell am instrument -w -r -e class <13 个测试类> com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner`（`scripts/device/run-device-tests.ps1` 把同样的动作写成脚本，并核对行数、`OK (n tests)` 结尾与失败标记）。
* 第一次 29 例 3 失败：把 `Hearth.BoundedDeviceTest`（辅助类，没有测试方法）当成测试类列进去，重跑去掉。
* 两条真问题按 §1.3a 裁定修好（各自提交）：
  * `7e616ca` 改址复验：同卡片按钮原来要求「按钮顶边在标题下方」，同行里两者顶边并不齐平 → 改成按垂直中心距离取最近的按钮。真机单例 **OK**，随后 28 例里也过。
  * `3c82bf3` 加载期停止：真因不是加载慢，而是 `HearthViewModel.send` 在 `historyReady()` 为假时**拒收**（返回 `false`，什么都不做），测例等 `!historyLoading` 就发、发了个空炮，于是永远等不到「正在加载」提示。按返回值重试到被接下再等提示（判据修正，没有加时间）。真机单例 **OK（2.99s）**。
  * 另：`a6826a9` 红点要按未合并语义树找；`a164e8d` 走查要等会话真的交给系统（`Committed` 或 `Pending`），否则测例一结束写入就被打断、系统确认界面起不来。

### §1.3.3 真实网络（只下不装）

`UpdateRealNetworkDeviceTest` 在手机上按 `Update` 用途走真网关下载 v0.1.1 的正式 APK 与 `.sha256`：**哈希一致**。设备上的报告 `update-flow/real-network.txt`（已取回 `p2-2-work\device-evidence\update-flow-device\real-network.txt`）：

* apk 23,004,794 字节；`expected == actual == 1a21350d267321a9255c1d252a4eac50fa183e043f67434a7d68a337ce490ead`。
* checksum 取回 2651 ms；下载 11351 ms；整包哈希 34 ms；读包信息 47 ms。
* 逐跳域名：`github.com` → `release-assets.githubusercontent.com`（两跳，与实际一致；审计里两次请求各自一串）。
* **v2-only 签名在这台手机上读得到**：`getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` 返回 package=`com.xavierxia.mycelium`、versionCode=`2`、signerCount=`1`、signerSha256=`bb72a9c7e71137dd…`。
* 顺带一条：真发布的 `.sha256` 是 `sha256sum` 格式（64 位十六进制 + 两个空格 + 文件名，共 85 字符）。生产端 `ChecksumText.parseFirstSha256` 本来就这么取，是我的测例写错了断言，已按同一解析器改（`83f51ce`）。

### §1.3.2 整条更新流程（Compose 设备测试，注入只存在于 androidTest）

* `UpdateFlowDeviceTest` 在本机假服务上走真流程：假 `releases/latest` JSON（走真解析路径）+ 流式 60 MiB APK + `.sha256`；`isDebugPackage = false` 让应用内安装入口出现；调试包本身仍不自动检查、也不提供应用内安装。
* 已过并留证（截图在 `p2-2-work\device-evidence\update-flow-device\`）：
  * 错哈希：走到「哈希不符」，`2-卡片.png`、`6-校验失败.png`；
  * 下载中取消：连接数与缓存都不再长，`3-下载中.png`、`7-取消后.png`；
  * 整条走查：`1-红点与卡片.png`、`3-下载中-进度.png`、第一次的 `4a-未知来源说明.png`、`5-系统确认.png`。
* 走到系统那一步的证据：会话记录 `installSessionPhase = Pending`、`installSessionId = 161908514`、`installSessionTargetVersionCode = 6`，系统界面 `com.android.packageinstaller/.PackageInstallerActivity` 在前台；这台 ROM 还要先勾「已了解该应用存在的风险」，「安装」按钮才会亮（截图 `h2-系统确认.png`）。
* **2026-10-21 10:13 用户在系统界面上点了「安装」（先勾了「已了解该应用存在的风险」）。核对结果：**
  * 调试包 `com.xavierxia.mycelium.debug` 变成 **versionCode = 6**（`lastUpdateTime 2026-10-21 10:13:00`）——应用内下载→四项校验→系统确认→装上，整条通了；
  * **数据目录没被清空**：`files/hearth`、`files/local-models`、`files/privacy`、`files/profileInstalled`、三份 P2-1/P2-2 记录都还在（升级保数据如实口径见第六节：v0.1.1 是阶段 0 外壳，这条只证明数据目录不被清空）；
  * 装成功后的界面截图 `8-装成功后.png`（「我的」页版本行 0.4.0 (6)）；
  * 走查完按 §1.3.2 要求把**本分支正常的调试包**装回去：`0.4.0 / code 5`（`INSTALL_FAILED_VERSION_DOWNGRADE` 用 `-d` 降级安装，数据保留），设备上删掉了走查用的 `files/update-flow-v6.apk`；
  * 收尾再核一次正式版：仍是 `0.1.1` / code `2` / `lastUpdateTime 2026-10-10 05:23:04`。

### 最终门禁（按 §1.2/§1.3 全部提交之后的尖端）

* 全新 worktree p2-2b-gate-3（脚本先断言它确实是 git 检出）跑在 **164e8d**；git diff --name-only a164e8d..HEAD 实测之后只动 docs/reviews/2026-10-18-P2-2b/DELIVERY.md，所以这份门禁就是代码尖端的门禁。
* check-all.ps1 -SkipLlamaPrepare：**PASS**，395 例 / 0 失败 / 0 错误 / 0 跳过；lint 0 错误 / 0 警告 / 0 hints；Kotlin 编译错误行 0。日志：p2-2-work\\final-gate4\\check-all.txt。
* check-public.ps1 -Full：**退出码 0**，阻断项 0（user-path 0），阻断 0 / 323 条只列不拦；-SelfTest：**退出码 0**（37/37）。日志同目录。

### 截图放哪、哪些不能入库

截图全部留在仓库外 p2-2-work\\device-evidence\\update-flow-device\\（设备上 update-flow/ 里的同一批，已 pull 回来）：1-红点与卡片.png、3-下载中-进度.png、4a-未知来源说明.png、5-系统确认.png、6-校验失败.png、7-取消后.png，以及装成功后的 8-装成功后.png（在 p2-2-work\\device-evidence\\）。

**注意**：device-evidence\h1-系统确认.png 是这台手机的「个人热点」设置页（当时抓偏了），里面有热点名称与密码；按 §1.5 第 4 件，这张截图**已于 2026-10-21 删除**（用户已同意），不要找回也不要外发；h2-系统确认.png 是系统安装界面，只有应用名与版本，可以给人看。按 §0.5「证据和日志入库前先脱敏」，本包**没有**把任何截图提交进仓库，只在这里记路径。
## 十四、§1.5 合并前补的四件（每件单独提交）

| 项 | 提交 | 做法与判别 |
|---|---|---|
| 1. 装成功以后还显示「可以安装」 | `a6c7576` | 先查清来路：真机上装的是 versionCode 6、但版本名仍是 0.4.0，重启后老口径只比 `versionName`（待装包 0.9.9 仍「比当前新」）→ 走 `restoredDownload` 把文件当候选恢复成 Ready。改成**版本号口径**：校验通过时把待装包的 versionCode 单独落盘（`pendingVersionCode`），重启恢复与回前台都判「已安装的 versionCode ≥ 待装包的」→ `finishInstalled`（清 Ready、清待装记录、删 `updates/` 里的文件、给「更新完成」）。四条 JVM 测例覆盖统筹点名的三种情况（装成功后进程重建 / 同一个发布还在 / 文件还在）＋一条「还没装上不许误伤」；把判据从 `>=` 改成 `>`，第一条立刻变红。真机复验按统筹要求留到 0.4.0 → 0.4.1。 |
| 2. 卡片里原样显示 Markdown 符号 | `cf58fa3` | 新增 `plainReleaseNotes`：行首 `#`/`>` 去掉、`**`/`__`/反引号去掉、`<网址>` 去尖括号、行首 `- ` → `• `；没点名的写法原样保留。`UpdateNotes` 显示前用它（`remember(notes)`）。八条 JVM 测例含「段中连字符不算列表」与「普通文本不动」两条保守口径。 |
| 3. `docs/releases/v0.4.0.md` 定稿 | `1f93238` | 删掉开头「待发布草稿」整段与写给开发者的内容（源码片段、`apkDownloadUrl` 之类字段名、校验实现细节）；「升级不丢数据」按实写——**0.1.1 是阶段 0 外壳、没有业务数据**，从 0.1.1 升上来只证明「升级不会清空数据目录」，0.4.0 起的数据以后升级会保留；保留：从 0.1.1 怎么升级（浏览器打开发布页或数据线）、只支持 64 位 ARM（32 位 ARM 系统别覆盖升级、别卸载重装）、签名指纹在 `docs/releases/signing-cert-sha256.txt`、0.4.0 以后 App 里两次点击升级。定稿 16 行。 |
| 4. 删掉含热点密码的截图 | 随本行 | 仓库外 `p2-2-work\device-evidence\h1-系统确认.png` 已删除（用户已同意），本节与第十三节的说明同步改成「已删除」；本包从未把任何截图提交进仓库。 |

做完后在全新 worktree 重跑门禁与 `check-public -Full`（见第十五节）。

