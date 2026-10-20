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
- **我选的保守做法**：**没有**把这 2 个提交加进 `accepted-history-commits.txt`，**没有**放宽 `check-public.ps1` 的规则，**没有**为了过检查去删证据文本。留给统筹三选一：①把这 2 个提交加进清单（先例：清单就是为「用户接受公开的历史」设的）②放宽规则（例如让「描述模式的示例段」不算命中）③保持 FAIL 并接受「推送门禁过不了」。**无论选哪个，本分支当前的推送门禁都是 FAIL（退出码 1）。**
- 另有三类只能靠重写未推送历史才能清掉的东西：**(a) 本次新发现、HEAD 仍在的那一条** —— `docs/reviews/2026-10-19-P2-2b-wording/check-all.txt` 里（UTF-16，见上）的账户名路径，涉及 `3dc17e1`、`2076456`、`0bb81f9` 与当前尖端；**(b)** git 提交元数据里的作者邮箱本地部分含本机账户名；**(c)** `9ef4eb9` 树里的本机账户名/设备型号字面量（HEAD 已洗成占位符）。**(a) 不在脚本的阻断项里 —— 那是脚本自己漏了它（M9）**；三条的处置方式等统筹/用户裁定。

## 六、真机环节

**未做，等白天用户在。** 本夜全程没有碰手机：没跑设备测试、没装包、没卸载、没改手机设置；正式版 `com.xavierxia.mycelium` 一次也没有接触。以下全部是留给白天、且**需要先得到用户同意**的事项。

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
| `check-public.ps1` 的 2 条 `[user-path]` 阻断项（第五节） | **没有**自己加进已接受清单、**没有**放宽规则、**没有**删证据文本；三选一交统筹（加清单 / 放宽规则 / 接受 FAIL）。 |
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