# P2-2b 夜间晨报（填到「今晚实际结果」为止）

- 整理时间：2026-10-19 04:25 起，04:5x 定稿（Asia/Shanghai；末次门禁与其后补记见第四节）
- 性质：夜间无人值守工作的晨报。本稿已把昨晚到今晨的实际结果填进来：**九步全部落地、两条分支的最终门禁都已填实（第四节末的表）**；仍剩**两处占位**——07:06 之后的第三次复审结论、手机侧实际跳转链域名，见第九节。
- 口径：所有数字都从仓库原文、门禁原文、原始 rollout 日志或现查 `git` 抄来；抄不到的一律写 `〔待填〕`，不编。末节逐项列出哪些已核实、哪些是占位。
- 本稿更正了草稿的两处错口径：P2-2 全量审是 **5 条【P1】+ 4 条【P2】**（不是 4+5）；JVM 用例数用门禁原文 **144 → 154**，草稿里的 **128/128 作废**（那是过期产物里的数字）。
- 本轮只改文档：未跑 Gradle、未联网、未用手机、未 push、未改源码。

## 一、总览

这一夜做完的是：P2-2 走完「全量审 9 条 → 整改 → 复审 r2 判 7 条未过 → 二次整改 → 两条门禁 154 用例全绿」，只等 07:06 之后的第三次复审；P2-2b 走完「方案审（需修改）→ 已提交部分整改 → 第 1、3、4、5 步落地」，九步全部实现，并在全新 worktree 上跑出两条门禁 **316 用例 0 失败**全绿。

两条分支的 tip 与相互位置（`git rev-parse`、`git merge-base`、`git rev-list --count` 现查）：

| 分支 | tip | 与对方的共同祖先 | 差什么 |
|---|---|---|---|
| `wp/P2-2-hearth-chat`（P2-2） | `e6c1d65` | `a196aed` | 本分支独有 13 个提交：整改收尾 5 个（`5314e34`、`757502a`、`ea571f2`、`10fa1b5`、`cb6c2db`）+ 复审 r2 的二次整改 7 个（`6fd00c8`…`c91eaaa`）+ 复审报告 `e6c1d65`。**只差第三次复审结论**（已挂 07:06:40 后自动跑）。最终门禁跑在 `c91eaaa` 上，此后本分支只有 `e6c1d65` 这个文档提交，门禁结论仍适用。 |
| `wp/P2-2b-release-ready`（P2-2b） | `2a40754` | `a196aed` | 本分支独有 30 个提交：第 9/6/7/8/2 步实现 + 方案审整改 + 第 1/3/4/5 步实现 + 各步文档 + 两处预防性洗字面量（`9e5b830`、`2a40754`）。**九步全部实现、最终门禁 316 用例 0 失败全绿**；只差交付报告（仍不存在）与真机项。本行的 tip 是**本稿定稿时**（门禁被验提交）的读数：此后本分支只多了本晨报与开发线的文档提交，门禁结论仍适用。 |

- 两条分支**都没有推送、都没有并入 `main`**（本机 `main` = `0f3dcff`）。
- 一个容易看错的点：`5314e34`（P2-2）与 `6a55d1c`（P2-2b）、`cb6c2db`（P2-2）与 `6ad3899`（P2-2b）分别是**同一次改动在两个分支上的两个提交**，`git patch-id` 两两相同（`9e6ac88a…`、`ac659c6c…`，本轮现算）。

## 二、P2-2：两轮审核与收尾

### 2.1 全量审（9 条，口径更正为 5 P1 + 4 P2）

- 报告 `docs/reviews/2026-10-18-P2-2/REVIEW.md`：审核对象 `wp/P2-2-hearth-chat @ 11e92b3`，**结论「需修改」，共 9 条**；原文逐条标题标注 **5 条【P1】（第 1–5 条）+ 4 条【P2】（第 6–9 条）**（本轮按 `【P1】`/`【P2】` 逐条数过 = 5 / 4）。
- 整改提交链（`git log` 现查）：`313e745`（按 9 条必修整改）、`a196aed`（补第 5 条要求的快照测例）、`5314e34`（消掉 `HearthSpace` 的 lint hint）、`757502a` / `ea571f2`（交付报告与开发线补记）、`10fa1b5`（撤销误入本分支的 P2-2b 方案文件）、`cb6c2db`（清掉文档里的本机用户名）。

### 2.2 复审 r2（结论：需修改，7 条未过）

- 报告 `docs/reviews/2026-10-18-P2-2/REVIEW-r2.md`，提交 `e6c1d65`（67 行）；审核目标 = P2-2 当时的 tip `cb6c2db`（`review-r2-wait.log` 02:07:36 起跑，02:15:26 收工，报告 13087 字节）。
- **结论原文：需修改——第 5、6 条通过**（地址与密钥一致快照、半升级模型语义），**第 1、2、3、4、7、8、9 条不通过**，要点：取消可能发生在最后检查之后、建连之前；切历史期间旧会话仍能接收新问题；事务内校验依赖跨线程不安全令牌且测试阻塞位置不准；确认取消后仍能写库；加载末尾停止仍可能继续生成；切会话后手势监听引用旧状态；证据更正不完整。
- 一处对照说明：`e6c1d65` 的**提交标题**把未过条目写成「第 1、2、3、4、5、7、8、9 条」（含第 5 条），与报告正文「第 5、6 条通过」不一致 —— **以报告正文为准**。

### 2.3 二次整改（7 个提交）

按复审未过的 7 条做了一轮整改，提交号（`git log` 现查，提交标题里的 1–7 是整改清单自己的编号）：

| 提交 | 内容（提交标题） | 对应复审条目 |
|---|---|---|
| `42fee21` | 复审第 1 条 取消状态延续到建连之前 | 第 1 条 |
| `bfa9217` | 复审第 2 条 切历史先定目标，历史就绪前不发，收尾刷新复检令牌 | 第 2 条 |
| `6fd00c8` | 复审第 3、4 条的 hearth 半边（写入事务内校验、确认字段窄更新） | 第 3、4 条 |
| `e152472` | 复审第 4 条 确认写入在事务内判有效性，取消后一列不改 | 第 4 条 |
| `c91eaaa` | 复审第 5 条 加载返回后先问停止意图，本地回答没产出就不保存 | 第 7 条 |
| `3bfbdc3` | 复审第 6 条 手势监听键与首次保存不重置上翻 | 第 8 条 |
| `b9efc1d` | 复审第 7 条 证据更正与撤回过头表述 | 第 9 条 |

### 2.4 P2-2 最终门禁（两条都绿，154 用例）

在 `p2-2-final` worktree、提交 `c91eaaa`（工作区干净）跑完整门禁：

| 脚本 | 结果 | 出处 |
|---|---|---|
| `scripts/check-all.ps1` | **`**PASS**: 0 failures, 0 warnings, exit code 0.`**；`tasks: 253 executed, 20 up-to-date`；退出码文件 = 0 | `p2-2-work\gate-final-ps1.txt`（2026-10-19 03:08:39）、`gate-final-ps1.exitcode` |
| `scripts/check-all.sh` | **`**通过**：0 失败、0 警告，退出码 0`**（与 PS1 同一 worktree，多数任务 UP-TO-DATE） | `p2-2-work\gate-final-sh.txt`（03:09:46） |
| 用例 | **154 用例 0 失败 0 错误 0 跳过** | 同上 |

154 的模块分布（门禁原文汇总表）：

| 模块 | `:app` | `:contract` | `:hearth` | `:llm` | `:model-api` | `:net` | `:roundtable` | 合计 |
|---|---|---|---|---|---|---|---|---|
| 用例 | 27 | 22 | 46 | 13 | 3 | 15 | 28 | **154** |

- lint：`:app` debug 0 错 0 警告、release 0 错 0 警告；需要处理的警告 0 条、llama.cpp 自身警告（约定豁免）0 条、Kotlin 编译诊断里的错误行 0 条。
- llama.cpp 校验：`llama.cpp verified at 5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`（两条门禁日志同一行）。
- 产物：release 未签名包 52.4 MiB、debug 包 59.5 MiB、androidTest 包 2.7 MiB。

### 2.5 用例数口径：128 → 144 → 154

- `5314e34` 上（修 lint hint 那次）：`**PASS**`，**144 用例 0 失败**（`:app` 27、`:contract` 22、`:hearth` 39、`:llm` 11、`:model-api` 3、`:net` 14、`:roundtable` 28），lint 0/0，`tasks: 17 executed, 256 up-to-date`。出处 `p2-2-work\gate-hintfix-ps1.txt`（2026-10-18 22:51:38）。
- 最终提交 `c91eaaa` 上：**154 用例 0 失败**（上表；`:hearth` 39 → 46、`:llm` 11 → 13、`:net` 14 → 15，都来自二次整改新增的测例与测试类）。
- **此前引用的「JVM 单测 128/128」是过期产物里的数字，权威数字来自门禁原文**（144 → 154）。`HearthSpace.kt` 的 lint hint 早在 `5314e34` 就消掉了，草稿里「1 hint」的说法同时作废。

### 2.6 第三次复审（待续）

复审 r2 判了 7 条未过、二次整改做完并通过两条门禁之后，按流程还要跑第三次复审复核整改。**本轮没有发起**：方案审跑完时 5 小时窗口读数已到 91% → 94%（≥ 90% 即不再发起新审核），须等窗口重置。无人值守任务已挂：`p2-2-work\review-r3-wait.log`（03:04:29 启动）目标 **2026-10-19 07:06:40**，到点在只读 worktree 重跑，报告写 `REVIEW-r3.md`。**截至本稿收尾（04:5x）该任务尚未触发，`REVIEW-r3.md` 也还没生成**（`git ls-tree` 在 P2-2 分支上查无此文件）。

## 三、P2-2b 方案审与已提交部分的整改

- 报告 `docs/reviews/2026-10-18-P2-2b/PLAN-REVIEW.md`（提交 `9763503`，246 行）：审核基线 `9d5bc3b`，**结论「需修改」**。逐项状态表里**第 7 步通过**，**第 1–6、8、9 步都标「需修改」**；报告 02:23:37 起跑、02:34:13 收工，31570 字节。
- **已提交部分照报告直接改掉**：
  - **第 6 步 `5f539cb`**：闸门不再静默跳过 versionCode 增长检查 —— 已发布上界改成「本仓所有正式版本标签里最高的 versionCode」，找不到任何前序标签 / 前序标签读不出 versionCode 一律 `abort`。**有实跑对照**：同一浅克隆场景里，旧脚本输出「全部检查通过：v0.4.0 可以发布 / 退出码 0」，新脚本输出「**发布中止**：找不到任何前序版本标签… / 退出码 1」（`p2-2-work/guard-demo/run-final.log` ②与②对照 + `guard-demo/logs/step6-no-prev-tag.txt`、`step6-old-skipped.txt`）。
  - **第 2 步 `311fbde`**：资产名收紧为忽略大小写的**精确**文件名匹配，删掉前缀/包含回退；新增 5 条反例测例，`:app` 44 用例 0 失败（`GitHubReleaseClientTest` 19 例全绿）。
  - **第 8 步 `b751b40`**：修正兼容性与 0.1.1 升级路径的文档承诺（正式包要求 arm64、32 位系统不能覆盖升级、旧版打开的是 APK 下载地址且不跑四项校验；发布说明加「待发布草稿」）。
  - **第 9 步 `9a52117`**：扫描源改成 HEAD（`git ls-tree -r --name-only HEAD`）、脏工作树直接拒绝出结果、报告补基准与 HEAD 与提交数、用户名正则支持非 ASCII 开头并加「中文用户名」自测。
- **未实施部分**写成清单 `docs/reviews/2026-10-18-P2-2b/PLAN-REVIEW-MUSTFIX.md`（首版 `94e70cd` 178 行，`9a52117` 再改；现 **183 行**，`ReadAllLines`/`git show` 两处一致）：第 1/3/4/5 步的必须修改项（C0/C1/C3/C4/C5）+ 已提交步骤的遗留项（第 9 步扫描契约、第 8 步文档、第 7 步待补项）+ 非阻塞建议附录。**其中属第 1/3/4/5 步的必须修改项（C1/C3/C4/C5）已随第 1、3、4、5 步的实现逐条落实**（详见第四节；各步施工笔记 `step1-notes.md`/`step3-notes.md`/`step4-notes.md`/`step5-notes.md` 与门禁测例为证）。清单正文自身仍写着「待实施」，尚未回改。

## 四、P2-2b 九步全部落地（第 5 步已完成）与最终门禁

- **第 1 步 `849b885`（+ `9a500ad`）网关通用文件下载**：`Download.kt` / `FileDownloader.kt` / `Gateway.kt` / `AuditLog.kt` / `LocalHttp.kt` + `DownloadGatewayTest.kt`；白名单补 `release-assets.githubusercontent.com`，**续传时重走跳转并逐跳复查白名单**。**`:net:test` 43/43**（`DownloadGatewayTest` 29、`GatewayTest` 13、`AuditLogTest` 1），其中新增 29 例。判别力实证：临时改成「续传复用上一次解析到的地址」→ `resumeIsRejectedWhenAHopLeavesTheWhitelistAgain`、`downloadRewalksRedirectsOnResume` 变红，`git checkout` 还原后 43 例全绿。实现中还抓出并修掉真 bug：`toEntry()` 把空 `hostPath` 当「老格式」回落成 `[host]`，导致「首次即被拒时空链」表达不出来。
- **第 3 步 `593b8d9`（+ `2d6a66b`）自动检查更新**：状态、节流口径（自动尝试时间一天一次、成功时间只用于显示、手动不受限）、失败不写成功时间、忽略本版本、调试包不自动检查、进程重建恢复、卡片与红点、显式 fake。**`:app` 92 例 0 失败**（新增 48：`UpdateCenterTest` 24、`UpdateFlowTest` 10、`UpdateCheckPolicyTest` 5、`UpdatePreferencesTest` 3、`ReleaseJsonTest` 3、`UpdateFormatTest` 2、`MainActivityAutoCheckTest` 1），`:app:lintDebug`/`lintRelease` 均绿（`realIssues=0`）。判别力实证 3 处突变（节流拿掉 → 5 例红；失败也写成功时间 → 1 例红；`restore()` 不核对 tag/版本/忽略 → 3 例红），全部还原。顺带修掉真缺陷：`isDue` 原来在全新安装（`last=0`）时依赖「当前时间戳够大」，已改成 `last <= 0 || …` 并锁死测例。
- **第 4 步 `b495146` 下载与校验**：`UpdateTransport` / `GatewayUpdateTransport` / `ApkDownloader` / `ApkVerifier` / `AndroidUpdateFacts` + `UpdateFiles`（`partFile/promote/purge/freeBytes/requiredBytes`）+ `ChatService.updateTransport()` + `MyceliumApp` 接真实现。**`:app` 153 例 0 失败**（新增 61），`:app:lintDebug`/`lintRelease` 均绿。判别力实证：改坏后 `42 tests completed, 13 failed`，还原后全绿（`step4-break.log` → `step4-final.log`）。
  - 空间公式（`step4-notes.md` 定稿）：`needed = max(apkSize, existingBytes) + apkSize + margin`，`margin = max(32 MiB, apkSize/4)`；未知大小先用 64 MiB 门槛，拿到长度后与安装前各复查一次；不足抛 `SpaceShortage(needed, actual)`，文案给出「还差多少」。
  - 校验顺序（按任务书 + C4）：`sha256 → 包名 → 签名 → versionCode`；签名只与当前装着的 App 比指纹，且明确拒绝异常多签名；四项任一不过先删文件、不进入可安装状态；**取消下载则保留 `.part`**（`mycelium-<tag>.apk.part`，可续传；`purge` 保当天 `.part`、删跨 tag 与 >7 天的）。
  - 三处与简报的偏离（已定稿记录）：空间检查放在下载器里而不是 `UpdateCenter`（避免用本步实现去改第 3 步测例结论）、期望哈希通过本地边车 `.apk.sha256` 传给校验器（校验器不联网）、校验器本地那次哈希不给取消句柄（取消由 `:net` 的 `FileDownloader` 负责）。
- **第 5 步 `6e46aa8` 安装与未知来源引导**（工作树 `p2-2b-step5` 里的提交是 `b982a97`，`git patch-id` 与本分支的 `6e46aa8` 相同 —— 同一次改动拣入本分支）：`PackageInstaller` 会话（`MODE_FULL_INSTALL` + `setSize`，不调 `setAppPackageName`），**写 → `fsync` → 关流 → `commit`**；结果回执按 C5 定稿用**显式 component + 清单内 `exported=false` 接收器 + `FLAG_MUTABLE`**；会话阶段 `Created/Written/Committed/Pending/Succeeded/Failed/Cancelled` 与 sessionId/target 包名/基线 versionCode/tag 落盘、回调按 sessionId 幂等；`onResume` 用 `longVersionCode` 做权威判定，超时或会话消失只标记「待核实」（不冒充取消）；「安装未知应用」先说明再带路，未允许时不建会话，回来复查后接着装；调试包无入口。**`:app` 170 例 0 失败**（新增 `PackageInstallFlowTest` 17 例）。判别力实证两处：把 `fsync` 挪到关流之后 → 顺序断言红（`expected:<[openWrite, fsync, closeWrite, commit]> but was:<[openWrite, closeWrite, fsync, commit]>`）；把幂等规则改成恒真 → 幂等断言红，两次都干净还原后重跑全绿。同提交改 Manifest、生产装配、界面与 `docs/UPDATE_ARCHITECTURE.md` §5（`step5-notes.md`）。
- 九步**都还没有交付报告**（`docs/reviews/2026-10-18-P2-2b/DELIVERY.md` 仍不存在），目前「方案说怎么测」与「实际测出什么」之间只有提交信息、门禁日志、施工笔记（`p2-2-work/step1-notes.md`、`step3-notes.md`、`step4-notes.md`、`step5-notes.md`）与本晨报可查。

### 4.1 最终门禁（两条分支：全新 worktree + detached 提交 + 双脚本）

| 分支 | 临时 worktree / 被验提交 | `check-all.ps1` | `check-all.sh` | 用例 | lint | llama.cpp | 出处 |
|---|---|---|---|---|---|---|---|
| P2-2 | `p2-2-final` / `c91eaaa`（detached、工作区干净） | **`**PASS**: 0 failures, 0 warnings, exit code 0.`** | **`**通过**：0 失败、0 警告，退出码 0`** | **154 / 0** | 0 / 0 | `5e03bdd` | `p2-2-work\gate-final-ps1.txt`（03:08:39）、`gate-final-ps1.exitcode`（=0）、`gate-final-sh.txt`（03:09:46）；汇总见 2.4 |
| P2-2b | `p2-2b-final` / `2a40754`（detached、工作区干净） | **`**PASS**: 0 failures, 0 warnings, exit code 0.`** | **`**通过**：0 失败、0 警告，退出码 0`** | **316 / 0** | 0 / 0 | `5e03bdd` | `p2-2-work\gate-p2-2b-final-ps1.txt`（04:43:49）、`gate-p2-2b-final-ps1.exitcode`（=0）、`gate-p2-2b-final-sh.txt`（04:44:50）、`gate-p2-2b-final-sh.exitcode`（=0） |

- **P2-2b 316 的模块分布**（门禁原文汇总表）：`:app` 170、`:contract` 22、`:hearth` 39、`:llm` 11、`:model-api` 3、`:net` 43、`:roundtable` 28（= 316）。
- **P2-2b 门禁原文的其余读数**：`tasks: 253 executed, 20 up-to-date`；gradle 退出码 0；`:app` lint debug 0/0、release 0/0；需要处理的警告 0 条、llama.cpp 豁免警告 0 条、Kotlin 编译诊断错误行 0 条；产物 release 未签名包 38.9 MiB、debug 包 59.7 MiB、androidTest 包 2.7 MiB；`llama.cpp verified at 5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`。
- **P2-2 门禁跑在 `c91eaaa`**（154 用例：`:app` 27、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、`:net` 15、`:roundtable` 28），此后本分支只有文档提交 `e6c1d65`，**结论仍适用**。
- **附注（两条分支同一性质，如实写明）**：SH 与 PS1 是**同一个 worktree 里先后跑**的（P2-2：03:08 → 03:09；P2-2b：04:43 → 04:44），SH 那一次多数任务 `UP-TO-DATE`（P2-2b 的 SH 里只有 `:app:lintDebug`、`:app:lintRelease` 实际执行，其余 10 个任务 UP-TO-DATE），因此 **SH 的零失败结论继承自同 worktree 紧前一次 PS1 的构建产物**，不是 SH 独立从零跑出来的 —— 这是两条脚本流程的固有性质，要独立结论须另开 worktree 只跑 SH。
- 两个临时门禁工作树 **`p2-2-final` 与 `p2-2b-final` 已删除**（用 `\\?\` 前缀删除，避免超长路径残留；`git worktree list` 里已无这两条）。

## 五、九步状态表

| 步 | 内容 | 状态 | 提交 / 说明 |
|---|---|---|---|
| 1 | 网关能下载文件（按流写文件、续传、逐跳白名单、审计、通用化） | **已完成** | `849b885` + `9a500ad`；`:net:test` 43/43（新增 29 例） |
| 2 | 读发布信息（按名找资产、缺资产降级、错误说人话） | **已完成** | `e8ade02` + 方案审整改 `311fbde` |
| 3 | 自动检查（开 App 查、24 小时节流、设置开关、红点 + 卡片、忽略本版） | **已完成** | `593b8d9` + `2d6a66b`；`:app` 92 例（新增 48） |
| 4 | 下载和校验（空间、`updates/`、进度与取消、四项校验） | **已完成** | `b495146`；`:app` 153 例（新增 61） |
| 5 | 安装（`PackageInstaller` 会话、未知来源引导、结果提示、调试包不自动装） | **已完成** | `6e46aa8`（工作树里的 `b982a97`，`patch-id` 相同）；`:app` 170 例（新增 17） |
| 6 | 发版流程（`release-guard.sh` + `release.yml`，四项闸门 + 手动试跑） | **已完成** | `fd5ad3c` + 方案审整改 `5f539cb` |
| 7 | 正式包只带 `arm64-v8a` | **已完成** | `6df413e` + `f47c706`（关掉不适用于本产品的 `ChromeOsAbiSupport` lint 检查） |
| 8 | 发布说明、版本号、发版文档 | **已完成** | `caeab85` + 方案审整改 `b751b40` |
| 9 | 公开内容检查脚本 `scripts/check-public.ps1` | **已完成** | `3153f7a` + 方案审整改 `9a52117` |

**九步全部「已完成」（第 1–9 步）；没有「进行中」，也没有「没动」的步。**

第 6 步的中止演示现在共 **5 类**：versionCode、发布说明、签名指纹、标签四类在 `fd5ad3c` 那一轮（提交信息记「四项各通过一次 + 四处中止」），第五类「找不到前序标签 / 浅克隆」在 `5f539cb` 之后有实跑日志与旧脚本对照（见第三节）。**草稿里「第五条找不到证据」的占位作废。**
第 2/6/7/8/9 步已提交时的那次门禁：`check-all.ps1` 在 `f47c706` 上 PASS、156 用例 0 失败、lint 0/0、退出码 0，release 未签名包 38.7 MiB（`p2-2-work\gate-p2-2b-fix-ps1.txt`，23:42:23）；同一次改动前在 `e8ade02` 上两条脚本都 FAIL（同一个 `ChromeOsAbiSupport` lint error）。
九步落地后的**最终门禁**（全新 worktree、双脚本、P2-2b **316 用例 0 失败**全绿）见第四节 4.1 的表。

## 六、只属于白天真机的事（清单）

1. **四步合并后的一次真机走查**：跳转链**实际域名**（`PLAN.md` 开放问题 1 —— 跳转链是从这台电脑实测的 `github.com` 302 → `release-assets.githubusercontent.com` 200、**只有两跳**，本机 `remote_ip` 看着像经过代理/中转，手机上的解析路径可能不同；出现新域名按「失败关闭 + 浏览器兜底」处理）、进度与取消的观感。〔待填：手机侧实际域名〕
2. **升级保数据**：用调试签名分别构建 `v0.1.1` 标签与本分支，装旧的造数据、再覆盖装新的，确认 versionCode 变了、数据还在（本轮全部未做）。
3. **系统安装确认界面与「安装未知应用」引导**：设备测走到「自动检查 → 红点 + 卡片 → 下载进度 → 校验通过 → 拉起系统确认界面」，**截图到此为止**；「安装」按钮由用户点，或经同意后由 agent 点；**不碰手机上装着的正式版 0.1.1**。
4. **校验失败截图**：方案审的必须修改项里点名要「校验失败截图」，但 `PLAN.md` 只记了取舍（校验失败一律删文件、用中文文案告诉用户稍后再试），目前**没有单列的截图项** → 待统筹定要不要单列一条。
5. **`getPackageArchiveInfo` / 签名读取在各 ROM 的表现**：`AndroidUpdateFacts` 的 `ApkFactsReader`（`getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES`）在真机各 ROM 上的行为（v0.1.1 是 v2-only 签名）。
6. **38 MiB 哈希耗时**：判「边下边算（流式哈希）」是否值得做。
7. **`cacheDir` 被回收**：`updates/` 下的候选文件被系统回收后，进程重建恢复路径的表现（第 3 步 `restore()` 已有 JVM 侧测例，真机未验）。
8. 另外两项也留真机：真 `StatFs` 的可用空间取值、第 5 步「建会话前再复查空间」。

## 七、待统筹或用户决定（每条写「我先选的保守做法」）

| 事项 | 我先选的保守做法 |
|---|---|
| `scripts/check-public.ps1` 在本分支上**必然报 18 条 user-path 阻断**（本轮 `-Full` 实跑：HEAD = `2a40754`、基线 `origin/main 3c7074c`、`origin/main..HEAD` 124 个提交、277 个被跟踪文件（274 扫描、3 跳过二进制），退出码 1；另有 `local-path` **121 条只列不拦**，不影响退出码）。`-Full` 逐条比对结论：**18 条全部落在历史 diff 里，HEAD 被跟踪文件 0 条** —— 每条都带提交号：`9e5b830`/`9763503` 的 `PLAN-REVIEW.md:185`、`9a52117`/`9d5bc3b` 的 `NIGHT.md:76`、`6ad3899` 3 条（**正是清用户名那次的删除行**）、`0f3dcff`/`01a5ff0`/`e0d3f7f`/`e94599b`/`0e18b45`/`6daf725` 的提示文件与 `开发线.md`。**此前「HEAD 上有 1 条」的说法作废**（那是 `9e5b830`/`2a40754` 洗字面量之前的读数，当时 `PLAN-REVIEW.md:185` 那处路径模式文本还在 HEAD）。为防日后放宽规则，本轮仍**预防性洗掉了两处自指字面量**：`9e5b830` 改 `PLAN-REVIEW.md` 里描述该类别模式本身的那一行，`2a40754` 改 `PLAN.md` 第 9 步与方案审提示里的两行（原意、三条款与「没有名字不匹配」的结论都保留）。 | 不重写未推送历史、不放宽脚本、也不为了过检查去删证据文本；把「18 条全在历史 diff、不重写历史就过不了」如实写清。**推送门禁在本轮之后仍会 FAIL**（退出码 1），要过只能重写历史或放宽规则，等统筹定（重写 / 放宽 / 接受）。 |
| git 提交元数据里的作者邮箱本地部分含本机账户名（形如 `<本机账户名>093@…`） | 不改历史、不动全局 git 配置，等统筹定处置方式。 |
| 「并发与升级专项审」未跑：审稿要求 `codex-prompt-special-concurrency.md` 在仓库里，但专项报告只有 `SPECIAL-隐私出网.md` 一份 | 额度不够就不硬凑、不自己代审，等统筹定「补一轮 / 并进第三次复审 / 接受」。 |
| 第 3 步的生产卡片暂时只有「忽略这个版本」「打开发布页」两个按钮：第 4/5 步实现前 `isAvailable=false`，故意**不画**「下载并安装」 | 按 C3 第 3 条「不做假按钮」处理：宁少一个按钮，不放一个点了没用的按钮；等第 5 步接上再补。**本轮已收口**：第 4/5 步接上后，卡片按状态给出「下载并安装」（未下载）与「安装」（已下载）两个真按钮（`ui/MyceliumApp.kt`，与 `strings.xml` 的 `update_download_install`/`update_install` 对应）。 |
| `GatewayPurpose` 没加下载专用枚举：下载复用 `Update`，`sentBody` 恒为 `""`（审计正文恒空） | 不加枚举（加了要动 `AllowedHosts` 的 `when` 与 app 的 `purposeLabel`，本轮没有收益）；如实记「下载审计没有正文」，等统筹定。 |
| `check-all.ps1` 的 lint 汇总行解析对「只有 hint、没有 error/warning」的输出不认（两条脚本不等价） | 用改代码解决（`5314e34` 把 hint 消掉），**不动门禁脚本**；等统筹定要不要顺手放宽 ps1 的解析。 |
| P2-2 分支 `9ef4eb9`（归档第二轮各条判别力实证）把**本机账户名与设备型号的字面量**写进了归档文件（`docs/reviews/2026-10-18-P2-2/evidence/README.md` 第 60–61 行、同目录 `discriminating-experiments.md` 第 344–345 行，共 4 行；原句本身正是「没有本机用户名 / 没有设备序列号型号串」那两句，等于在声明「没有」的同时把字面量留了下来）。`758bbf2`（P2-2 分支 tip）已把这两处改成 `<本机账户名>` / `<设备型号>` 占位符，归档目录复查 **0 命中**（`git grep` 现查）；但这两个字面量**仍在历史里**（`9ef4eb9` 的树里查得到；该分支 tip 的被跟踪文件查 0 命中），与上一行 git 作者邮箱同属「只能靠重写未推送历史才能清」的一类。附：裸字面量**不匹配** `scripts/check-public.ps1` 的 `[user-path]` 规则——该规则只匹配 `C:\Users\<名>`、`/home/<名>`、`/Users/<名>` 这类**路径形状**，故**不构成阻断项**。 | 不改历史、不放宽脚本、不删证据文本：本轮只把归档 HEAD 洗成占位符（`758bbf2`）；**是否在可写阶段重写未推送历史**把这两个字面量连同作者邮箱一并处置，等统筹裁定。 |

## 八、Codex 额度读数

| 时点（+08:00） | 5 小时窗口 | 每周窗口 | 出处 |
|---|---|---|---|
| 21:05:58 全量审开工前读到的最新事件（`09:26:41Z`，17:26 那次失败尝试留下的**旧读数**） | 99% | 31% | `review-wait.log` 第 2 行 |
| 21:19:58 全量审收工 | **59%** | **41%** | `REVIEW.md` 末行（最新可读事件 `13:15:46.671Z`） |
| 22:15:40 / 22:16:29 复审途中 | 91% / 94% | 46% | rollout `…T22-11-46-….jsonl` |
| 22:17:22 复审**撞上限**（22:11:46 起跑，约 5.6 分钟后；该轮 1,312,687 token） | **99%** | **47%** | 同上末条 `rate_limits`（文件末行 22:17:23；`reset=1792346771`） |
| 02:12:43 复审重跑（报告引用） | **32%** | **52%** | `REVIEW-r2.md` 正文（报告 13087 字节，02:15:26 收工） |
| 02:15:22 复审重跑末条（该轮 2,017,816 token） | 38% | 53% | rollout `…T02-07-38-….jsonl` 末条 |
| 02:29:41 方案审（报告引用） | **88%** | **61%** | `plan-review.log` |
| 02:31:05 / 02:34:10 方案审途中 → 末条 | 91% / **94%** | 61% / 62% | rollout `…T02-23-38-….jsonl` |
| 03:04:29 第三次复审等待任务启动 | —— | —— | `review-r3-wait.log`（目标 07:06:40） |

- 重置时间：5 小时窗口 `resets_at 1792346771` = **2026-10-19 02:06:11**；每周窗口 `resets_at 1792896851` = 2026-10-25 10:54:11。第三次复审等的是**下一个** 5 小时窗口重置，无人值守任务写的是 **07:06:40**。
- **今晚的规则**：5 小时窗口读数 ≥ 90% 就不再发起新审核，必须等重置。方案审收工时已到 94%，所以第三次复审没有当场发起。
- token 合计（含缓存命中，原始日志 `total_token_usage`）：全量审那一轮 **4,371,678**；复审撞上限那轮 **1,312,687**；复审重跑 **2,017,816**；方案审 **2,811,332**。
- 21:05 那条 99% 是**旧事件**，不是当晚又撞了一次上限 —— 复核时别被它误导。

## 九、待续（占位）

- `〔待填：07:06:40 之后第三次复审（`REVIEW-r3.md`）的结论 + 额度读数〕` —— 任务已挂（`review-r3-wait.log`，03:04:29 启动），**截至本稿收尾（04:5x）仍未触发**，本轮不发起（5h 已 94%）；据此填写。
- `〔待填：手机侧实际跳转链域名〕` —— 只能真机验，本轮不许用手机（见第六节第 1 条）；据手机实测的跳转链填写。

（原第三条占位「最后一次全新 worktree 的双门禁结果」**本轮已消**：两条分支的门禁都已填实，见第四节 4.1 的表。）

## 十、本文件的填写状态

### 已核实事实（都有可查出处）

- 两条分支 tip（`e6c1d65` / `2a40754`）、共同祖先 `a196aed`、各自独有 13 / 30 个提交、两对同改动提交的 `patch-id`：本轮 `git log --oneline -30`、`git rev-parse`、`git merge-base`、`git rev-list --count`、`git patch-id` 现查。
- 全量审 9 条与 **5 P1 + 4 P2**（按 `【P1】`/`【P2】` 逐条数）：`docs/reviews/2026-10-18-P2-2/REVIEW.md` 现读。
- 复审 r2 结论「需修改、第 5、6 条通过」与 7 条未过要点、二次整改 7 个提交：`git show wp/P2-2-hearth-chat:…/REVIEW-r2.md` + `git log` 现查。
- P2-2 在 `5314e34` 上的 144 用例、在 `c91eaaa` 上的 154 用例（模块分布）、lint 0/0、llama.cpp `5e03bdd`、两条脚本的 PASS / 通过末行：`p2-2-work\gate-hintfix-ps1.txt`、`gate-final-ps1.txt`、`gate-final-ps1.exitcode`、`gate-final-sh.txt`。
- P2-2b 在 `2a40754` 上的 **316 用例**（`app170/contract22/hearth39/llm11/model-api3/net43/roundtable28`）、lint 0/0、llama.cpp `5e03bdd`、两条脚本的 PASS / 通过末行与退出码 0：`p2-2-work\gate-p2-2b-final-ps1.txt`、`gate-p2-2b-final-ps1.exitcode`、`gate-p2-2b-final-sh.txt`、`gate-p2-2b-final-sh.exitcode`（SH 与 PS1 同 worktree 先后跑、SH 多数任务 UP-TO-DATE，均见原文）。
- 方案审 246 行 / 结论 / 逐项表（第 7 步通过）、额度 88/61 与 91、94/62：`PLAN-REVIEW.md` 现读 + `plan-review.log` + rollout `…T02-23-38-….jsonl`。
- `PLAN-REVIEW-MUSTFIX.md` 现 183 行（首版 178 行）：`git show --shortstat 94e70cd` + `ReadAllLines` 现数。
- 第 1 步 `:net:test` 43 例（29 + 13 + 1）、第 3 步 `:app` 92 例（新增 48）、第 4 步 `:app` 153 例（新增 61）、**第 5 步 `:app` 170 例（新增 17）**、各步突变与真缺陷、空间公式与校验顺序：`p2-2-work\step1-notes.md`、`step3-notes.md`、`step4-notes.md`、`step5-notes.md`、`step4-test2.log`（153 tests）、`step4-break.log`（42 tests / 13 failed）、`step5-notes.md` 里的两处突变原文 + 各步提交信息与 `git show --stat`。
- 第 6 步第五类中止与旧脚本对照：`guard-demo\run-final.log`、`guard-demo\logs\step6-pass.txt`、`step6-no-prev-tag.txt`、`step6-old-skipped.txt` + `5f539cb` 提交信息。
- `check-public.ps1` 的 **18 条 user-path（`-Full` 逐条比对：全部在历史 diff、HEAD 被跟踪文件 0 条）**与 121 条 local-path，以及两处预防性洗字面量提交 `9e5b830`/`2a40754`：**本轮 `-Full` 实跑**（干净工作树，HEAD = `2a40754`，退出码 1）+ `git show --stat 9e5b830 2a40754`。
- 额度读数与 token 合计、重置时间：四份原始 rollout jsonl 现读；`REVIEW.md` 末行、`review-r2-wait.log`、`plan-review.log`、`review-r3-wait.log`。
- 两条临时门禁工作树已删、`REVIEW-r3.md` 尚未生成：本轮 `git worktree list`、`Test-Path`、`git ls-tree -r --name-only wp/P2-2-hearth-chat` 现查。
- 白天真机项的出处：`PLAN.md`、`step4-notes.md` 第四节「留真机（白天）」、`step5-notes.md`「只能真机验」小节。

### 占位（`〔待填〕`，共 2 处）

1. 第三次复审（`REVIEW-r3.md`）结论 + 额度读数（第九节、第二节 2.6）—— 任务挂在 07:06:40，截至本稿收尾未触发，`REVIEW-r3.md` 尚未生成。
2. 手机侧实际跳转链域名（第六节第 1 条、第九节）—— 只能真机验。

（草稿里另外四个占位已消：复审 r2 结论、第 6 步「第五条中止」、**最后一次全新 worktree 双门禁**、`开发线.md` 更新情况；「校验失败截图要不要单列」移到第七节作为待统筹项。）