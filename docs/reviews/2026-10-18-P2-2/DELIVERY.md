# P2-2 交付报告：炉边（人物与对话，2026-10-18 修订）

- 分支：`wp/P2-2-hearth-chat`（已并入 `main`，最新基线 `a54c604`）；本报告本轮在 detached 工作树 `p2-2-r3-doc` 上更新，**改写前的分支 tip 为 `758bbf2`**（第三次复审审的即这一提交）
- 本报告对应的提交：见第八节「提交与证据对应表」
- 本报告已于 2026-10-19 更新到**第二轮整改、最终门禁与第三次复审结论**（见 3.5、3.6、第十节）
- 任务书：`docs/work-packages/P2-2-hearth-characters-chat.md`（以文末「2026-10-18 修订」「附录：预设人物」「2026-10-18 统筹裁定」为准）
- 审核报告：`PLAN-REVIEW.md`（方案审，结论需修改）、`SPECIAL-隐私出网.md`（专项审，结论需修改）、`REVIEW.md`（全量审，9 条）、`REVIEW-r2.md`（复审，本分支已入库 `e6c1d65`；判第 5、6 条通过、其余七条不通过）、**`REVIEW-r3.md`（第三次复审，审 `758bbf2`、对照 r2 基线 `cb6c2db`；已产出并随本次提交入库，结论见 3.6）**
- 本包当前状态：**待审**——第三次复审 `REVIEW-r3.md` 判**仍需修改**（第 3、4 条核心缺陷已修复、第 5、6 条维持通过；第 1、2、7、8 条仍有遗漏，第 9 条表述需改），**仍不得交付、不得推送**（第二轮整改见 3.5，最终门禁见第十节，审核链见 3.6）

## 一、这一包做了什么

1. **人物三选一模型**：`ModelKind` = `default` / `local` / `cloud`；选 `local` 必须带登记模型，选 `cloud`
   必须带云端服务。老库用 `PRAGMA table_info` 查缺列 + `ALTER TABLE` 补列，**列检查、改表与完成标记
   （`meta.schema-version`）在同一个事务里**，`BEGIN IMMEDIATE` + 进程内锁保证同时首次打开不重复改表；
   老人物保留原来指定的本地模型（空 `model_id` 的留在 `default`）。
2. **预设人物 5 个**：阿远、年糕、老K、外婆、老陈，第一次打开炉边时写入；**5 条预设与「已放过」标记
   同一个事务提交**，失败则一行都不留、标记也不落，之后再放一次仍然补齐；删掉的不再自动加回来。
   每个人物的设定里都带安全兜底句（提示里一定含有、编辑页可见可改），默认「默认本地模型」，搜索关闭。
3. **测试模式 / 沉浸模式**：规则在模块里（`HearthChat`）；测试模式只在内存的临时会话里读写，
   重新生成也在其中做；离开炉边（底栏、切人物、切模式、关闭对话页）统一停掉进行中的生成并清空临时会话，
   晚到回调用「本轮令牌」丢弃。测试模式的输入草稿只放在 `remember`（不进 saved state），切模式即清。
4. **云端走 P1-7 的网关**：`:net` 新增 `HearthChat` / `HearthChatTest` 两个用途与请求级 `omitAuditBody`
   选项；**「不记正文 = 请求要求不记正文 或 purpose 为 HearthChatTest」**，成功与失败两条审计路径统一，
   字数照记、真正发出的正文不变。`:roundtable` 的 `CloudChatModel` 把用途、审计策略、协议参数化
   （默认值保持圆桌原样），改用 `GrantwayAbort` 句柄支持真正中止请求。
5. **隐私可见**：云端人物在卡片与对话页标「云端」；确认绑定「人物 + 服务 ID + 规范化地址」
   （`cloud_confirmed_target`），**发送前从设置里重新读取服务快照**做校验，换服务、改地址都要重新确认，
   复制人物不继承确认；弹窗写明会发出人物设定、本轮问题、历史对话（P2-3 后还有记忆），并写明
   「测试模式 · 不留记录」只管手机上的记录；测试模式 + 云端时对话页常驻同一提示。
6. **本轮归属快照**：发送瞬间固定人物、模式、会话、问题与模型；一轮只写一次（`written` 一次性开关），
   先校验令牌再写库；**云端整段结果被取消就丢弃**，本地已流式生成的部分保留。

## 二、门禁结果

在提交后的干净工作区跑 `scripts/check-all.ps1 --offline`（跳过 llama 准备）：

| 项目 | 结果 |
|---|---|
| 结论 | **PASS**：0 failures, 0 warnings, exit code 0 |
| 用例总数 | 133 |
| lint | `:app` debug 0 错 0 警；release 0 错 0 警 |
| 产物 | debug APK / androidTest APK 已签名；release 未签名 |
| 证据 | `build/check-all/summary.md`、`gradle.log`（gitignore，只在本机） |

**全新 worktree 的从零验证**（交付要求的那一条）：在 `D:\Agent\Worktrees\Mycelium-Android\p2-2-delivery`
干净检出提交 `f614d36`，跑 `scripts/check-all.ps1 -LlamaSourceRepository <本地 llama.cpp 克隆>`：

- `worktree: clean`、`commit: f614d36…`、**253 个任务实际执行**（20 个无需执行）、llama.cpp 校验到固定提交 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`；
- 七个模块 **133 用例 0 失败 0 错误**（`:app` 27、`:contract` 22、`:hearth` 34、`:llm` 11、`:model-api` 3、`:net` 12、`:roundtable` 24）；
- lint `:app` debug/release 均 **0 错 0 警**；`app-debug.apk` / `app-debug-androidTest.apk` 已签名、`app-release-unsigned.apk` 未签名；
- 结论 **PASS：0 failures, 0 warnings, exit code 0**（原始输出在 `p2-2-work/checkall-freshwt2.txt`）。

同一条腿再用 **CI 用的脚本**验一遍（`android-ci.yml` 跑的就是它）：在同一个全新 worktree 里用 Git Bash 跑
`bash scripts/check-all.sh --skip-llama-prepare` → **通过：0 失败、0 警告、退出码 0**（`p2-2-work/checkall-sh4.txt`）。
两条门禁脚本在 P2-2 的改动上都验过了；GitHub 上的远端 CI 仍需推送后才能看到（推送由用户决定）。

> **本节的记录早于第二轮整改**（133 用例、提交 `f614d36` / `5005bc4` 等）。第二轮整改后的从零门禁结论见
> **第十节「最终门禁」**：提交 `c91eaaa`、**154 用例 0 失败**、lint 0 error / 0 warning，`check-all.ps1` 与
> `check-all.sh` 都给出通过结论。

## 三、三轮审核与逐条落实

### 3.1 开工前方案审（`PLAN-REVIEW.md`，Codex，审基线 `e94599b`）：需修改，12 条

| 条 | 内容 | 结果 |
|---|---|---|
| 1 | 云端适配（协议、参数、线程、可取消） | **已做**：自然语言协议 + 传 temperature/max_tokens/top_p + IO 上收集 + `GatewayAbort` 真中止 + 取消绑定网络取消 + 登记前停止整条不发 |
| 2 | 测试模式覆盖所有收尾路径、固定本轮归属 | **已做**：`Turn` 快照 + 令牌 + 一轮只写一次；云端取消丢弃、本地保留部分 |
| 3 | 测试模式独立临时会话与离页处理 | **已做**：临时会话只在内存；离页停生成、清会话、丢弃晚到回调；续读历史也受令牌约束 |
| 4 | 云端确认绑定接收目标 | **已做**：人物 + 服务 ID + 规范化地址，发送前用权威快照校验，复制不继承 |
| 5 | 迁移保留旧语义 + 事务与并发 | **已做**：版本标记与改表同事务、口令串行、进程内锁；老人物保留本地模型 |
| 6 | 预设播种与标记原子 | **已做**：同事务 + 稳定 id + `INSERT OR IGNORE`；失败回滚有测例 |
| 7 | 模型解析、引擎生命周期与标签一致 | **部分**（按裁定挪到 P2-5）：本包做到「所选模型/服务失效时明确提示、不崩溃、不静默换模型」 |
| 8 | 请求级「不记正文」 | **已做**：成功/失败同一规则，`purpose` 与请求级选项双保险，测例覆盖正常返回与失败两种；**超时与取消的矩阵没有单独成例**（见第九节） |
| 9 | 审计文件跨实例串行 | **已做**：按文件路径进程内共享锁，读/追加/清空串行；并发测例（8 线程 × 25 条） |
| 10 | 架构测试覆盖真实模块集合与传递依赖 | **已做**：支持一个 `include(...)` 写多个模块，检查生产依赖传递闭包；直接依赖 `:net`、间接依赖、直接引入 HTTP 库、经别的模块继承 HTTP 库四种失败情形都有测例 |
| 11 | 上下文预算与超长当前问题 | **已做**：装不下人物设定 + 当前问题时明确拒绝，不截设定或安全句 |
| 12 | 无痕验收不能只比行数 | **已做**：比较完整快照（消息正文/来源/会话/人物），并断言 `redo` 改不掉已存回答 |

### 3.2 隐私与出网专项审（`SPECIAL-隐私出网.md`，Codex）：需修改，7 条

| 条 | 内容 | 结果 |
|---|---|---|
| 1（P1） | 连接登记前停止、单独取消协程仍可能出网 | **已做**：停止与登记共用一把锁（登记前停止→句柄一到就中止）；协程取消绑定中止；生成入口先看停止状态；`onCleared` 也停云端 |
| 2（P1） | 云端回答进 buffer 后取消仍显示/保存/重复提交 | **已做**：令牌先行 + 一轮只写一次；云端取消丢弃整段结果；不再在取消分支无条件保存 |
| 3（P1） | 未受令牌约束的历史读取把持久历史带回测试模式 | **已做**：历史读取、切人物/模式/会话、生成收尾共用令牌；晚结束任务不再清新任务的生成状态 |
| 4（P1） | 测试文本进可恢复状态、切模式不清输入框 | **已做**：测试草稿只 `remember`（不进 saved state），切模式/切人物/关闭/离页一起清 |
| 5（P1） | 发送前校验用缓存服务，可能「旧地址 + 新密钥」 | **已做**：发送时从设置重新取服务快照，地址、密钥、确认校验来自同一次读取 |
| 6（P2） | 手工规范化错误合并 query 大小写 | **已做**：只折叠允许忽略大小写的部分，query/fragment 原样保留 |
| 7（P2） | 补齐当前提交的验证证据 | **已做**：见第二、四、八节（在修复后的提交上重跑门禁、补跑受影响真机走查、修正审计附件错配、补 naturalChat 与取消时序测例） |

建议修改 1（测试模式 + 云端常驻告知）已做；建议修改 2、3（审计清空语义、更多失败矩阵）留待下一轮。

### 3.3 全量审（`REVIEW.md`）与 9 条整改

2026-10-18 **21:05–21:19** 在只读 worktree 上跑完（Codex），产出 `REVIEW.md`：**结论「需修改」，9 条**
（**5 条【P1】+ 4 条【P2】**，即 `REVIEW.md` 第 107、120、134、148、162、176、190、202、216 行的九个标题）。
该轮结束时的额度读数：**5 小时窗口 59%、每周 41%**。第一轮（17:23–17:27）曾撞上
用量上限（139,068 token、报告 0 字节，空文件已删），本轮是重置后的重跑。

**逐条整改状态表**（被验证提交 `313e745`；第 5 条的补验测例在 `a196aed`）

| # | 问题（审稿原话要点） | 改在哪里 | 证据 |
|---|---|---|---|
| 1 | P1 停止与网络取消协议不完整：`disconnect()` 在连接建立前空转，登记前停止仍会写正文；`invokeOnCompletion` 要等协程结束才触发 | `:net` 新增请求级**永久**取消标记 `GatewayCall`（`cancel` 置标记 + 断开已登记连接，`attach` 登记时若已取消则立刻断开），`exchange()` 在 open 前 / 登记后 / 取输出流前 / 写完正文后四个检查点 `check()`；`:roundtable` 改为 `suspendCancellableCoroutine { invokeOnCancellation { stop() } }`，删掉 `invokeOnCompletion` 与「send 返回即撤注册」 | `net/GatewayTest` 新增 2 例（`aCancelledRequestNeverOpensAConnection`、`aStopInTheRegistrationWindowNeverWritesTheBody`，含正向对照）；`roundtable/HearthCloudModelTest` 新增 2 例（登记窗口前停止不发正文、只 `job.cancel()` 也须 2 秒内断开 5 秒慢服务）。判别力实证：把新守卫改坏后窗口用例报 `expected: <0> but was: <1>`（假服务真收到正文） |
| 2 | P1 生成中导航留下永久 busy：`abandonTurn()` 不清 `busy`，换会话只换令牌 | `HearthViewModel.abandonTurn()` 收口：bump token、停云端/引擎、取消 operation，并清 `busy/streaming/streamLabel/sendConfirm`；`newConversation()`/`openConversation()` 先调它 | 设备测例 `HearthNavigationDuringTurnDeviceTest`（生成中换会话 / 切测试模式：`busy` 清掉、页面还能继续发、晚到回答不上屏不入库） |
| 3 | P1 令牌检查在 IO 写库之外：检查完到写库之间还能被作废 | `:hearth` `ConversationLog.append/replaceLastAnswer` 把 `valid()` 放进写事务首行，失败抛 `AbandonedTurn`，`HearthChat.finish/redo` 收敛成 null/false；`HearthViewModel.stillCommittable` 传令牌 + 「云端且用户已停」判定 | `hearth/AbandonedWriteTest` 2 例（写锁被占住期间作废的一轮不落库；仍有效则同一事务里一起提交） |
| 4 | P1 确认发送任务可越过取消/离页：`confirmSend()` 没绑操作 | `PendingSend(character, question, mode, conversationId, target, token)`；`confirmSend` 每步异步后用 `stillWanted()`（令牌 + `sendConfirm` 实例身份）复检，人物没了就拒发，地址变了重新弹确认 | 设备测例 `HearthNavigationDuringTurnDeviceTest` 第 2 例（先弹确认、期间 0 请求；离页后再跑确认任务不得发请求、不得改写确认状态、不得留测试模式文字） |
| 5 | P1 地址与密钥来自两次读取 | `CloudStore.endpoint()` 一次读取返回地址+名称+密钥，`:app` 侧 `CloudSettingsStore.service/endpoint` 委托；`stream()` 用它同时做校验与建请求 | `roundtable/CloudEndpointSnapshotTest` 2 例（快照一致；分开读确实会拼出「旧地址 + 新密钥」） |
| 6 | P2 半升级库把指定本地模型静默改成 default | `:hearth` `Schema.kt` 拆开「加列」与「回填」，新增 `MODEL_KIND_SINCE = 3` 与 `legacyModelKindNeedsRepair`，回填只认 `default` 且 `model_id` 非空 | `:hearth:test` 39 例全绿，含 3 条新例（真实半升级夹具、已打标记库不被覆盖、阈值边界） |
| 7 | P2 本地加载期间停止，问题仍消失 | `Turn.userStopped` + `stop()` 先置 `activeTurn?.userStopped`；加载期的普通异常按停止收尾（问题回输入框、不入库） | **代码修复，无新增测例**（审稿未要求补验，如实标注） |
| 8 | P2 最后一条长消息可见 ≠ 用户在底部，流式更新把用户上翻重置 | `HearthSpace.kt` 抽出 `ChatTimeline`；`isAtContentBottom()` 按真实视口（`viewportEndOffset`/`afterContentPadding` + 容差 `min(末项高, 视口/16)`）判定，`DragInteraction` 区分「谁在滚」，`followTail` 只在松手后确实落到底部才恢复 | 设备测例 `HearthTimelineFollowDeviceTest` 5 例；对照实验：换回旧逻辑后「长回答内上翻」必失败（用户停在 691 被拽到 1433） |
| 9 | P2 证据不足与过头表述 | 见本报告**第九节**（逐条列了缺失的原始附件、撤回的表述与更正后的说法）与 **9.1**（复审第 7 条要求的二次更正） | 第九节各行的「现在的事实」列、9.1 的更正清单、`docs/reviews/2026-10-18-P2-2/evidence/` 下的审计原文附件 |

**复审状态**：另起的 Codex 复审第一次在 **22:11** 起跑，5.5 分钟 / 131 万 token 后撞上限（**5h=99%、每周=47%**，
重置 **2026-10-19 02:06**，credits 0），**那一次报告未生成**。挂的**无人值守任务已按点执行并产出报告**：
`p2-2-work/review-r2-wait.log` 记到 02:07:36 开始、审核目标 `cb6c2db746d4aad7d902de0191db7938596c360f`、
02:15:26 结束、报告 13087 字节；结论为**需修改**，第 5、6 条通过，**第 1、2、3、4、7、8、9 条不通过**。
报告 `REVIEW-r2.md` 现已在本分支**入库**（提交 `e6c1d65`，当时本分支 tip 即 `e6c1d65`；此前「未入库文件、
位置在 `D:\Agent\Project\Nivra\…`」的说法作废）。它的审核目标是 `cb6c2db`，其后另有七笔提交
（`6fd00c8` 复审第 3、4 条的 hearth 半边；`3bfbdc3` 第 8 条；`42fee21` 第 1 条；`bfa9217` 第 2 条；
`e152472` 第 4 条；`c91eaaa` 令牌原子化＋第 7 条；`b9efc1d` 只更正证据表述），**复审结论未覆盖这些
提交**——所以本报告既不把复审的「不通过」当成对第二轮整改的评语，也不把第二轮整改写成「已通过」。
逐条整改见 **3.5**。

**因此本包现在的状态是：方案审 12 条与隐私与出网专项审 7 条已按裁定整改（逐条状态见 3.1/3.2）；
9 条整改已提交**（`313e745`、`a196aed`），**复审 `REVIEW-r2.md` 判第 1、2、3、4、7、8、9 条仍不通过，
第二轮已按复审意见整改**（逐条见 3.5，提交 `42fee21`、`bfa9217`、`6fd00c8`、`e152472`、`c91eaaa`、
`3bfbdc3`、`b9efc1d`），**第三次复审 `REVIEW-r3.md` 已产出并随本次提交入库**（审 `758bbf2`，见 3.6）：
判**第 3、4 条核心缺陷已修复、第 5、6 条维持通过**，**第 1、2、7、8 条仍有遗漏**，**第 9 条表述需改**——
即**仍需修改，本包仍属「待审」**。**「9 条已全部整改并通过整树与
真机复验」的表述在此撤回**：3.4 的整树与真机复验只说明那批改动构建、单测、lint 与设备测例通过，
不构成「复审通过」，也不等于这 9 条的问题已消失。

### 3.4 整改后的整树与真机复验

- **JVM 单测 128/128 —— 此口径作废**：这一行取自**过期的结果产物**（`:net` 14、`:roundtable` 26、
  `:contract-lib` 22、`:hearth` 39、`:app` 27；结果 XML 时间 2026-10-18 22:14），既没有覆盖第二轮整改后的
  用例，模块名与数字也和现在的门禁表对不上。**不能把它当作第二轮整改后的单测结论**；二轮整改后的真实
  数字见 3.5 与第十节（**合计 154**：`:app` 27、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、
  `:net` 15、`:roundtable` 28）。
- **lint（debug）0 error / 0 warning，「1 hint」的说法作废**：`HearthSpace.kt` 的 `AutoboxingStateCreation`
  提示早在提交 `5314e34` 就已消掉；此后（含最终门禁）debug/release lint 都是 **0 error / 0 warning**。
  此前「纯性能提示、本包未整改、如实写出」的写法与实物不符，撤回。
- **真机四类九例全过**（runner `com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner`）：
  `HearthCloudStopDeviceTest` `OK (1 test)` 8.316 s、`HearthCloudReconfirmDeviceTest` `OK (1 test)` 3.537 s、
  `HearthNavigationDuringTurnDeviceTest` `OK (2 tests)` 3.339 s、`HearthTimelineFollowDeviceTest` `OK (5 tests)` 5.804 s。
  **这四段输出当时没有落盘成文件**（复审 `REVIEW-r2.md` 第 7 条已指出）：仓库与证据目录里搜不到，只能按 4.3
  的复跑命令自行复现；本报告这一行属**当时会话转述、无法独立复核**。
- **正式版对照**：装调试包前后 `dumpsys package com.xavierxia.mycelium` 的读数一致——`versionName=0.1.1`、
  `versionCode=2`、`lastUpdateTime=2026-10-10 05:23:04`、`firstInstallTime=2026-10-10 05:14:52`。但这次
  **同样没有把 `dumpsys` 原文落盘归档**：仓库、`p2-2-work` 与正式证据目录里都搜不到任何 P2-2 时点的
  `dumpsys package` 原文，只有 P2-1b 那次走查的 `device-log.txt` 留了同组读数。故本条**只能算当时读数转述，
  不能独立复核**——此前「这次是真的把原文读出来了（第九节里那条据此更新）」的说法撤回，详见 9.1 第 4 项。
- **提交对应关系**：`313e745`（9 条整改）、`a196aed`（第 5 条补验测例）；第二轮整改的提交见 3.5。

### 3.5 第二轮整改（复审第 1、2、3、4、7、8、9 条，2026-10-19）

复审 `REVIEW-r2.md` 判第 1、2、3、4、7、8、9 条不通过。第二轮逐条整改如下——**判别力实证按条分级，此前「每条都
验过判别力」的说法与现有证据不符，撤回**：**第 1、3、4、7 条有归档的实证**（第 1 条的失败原文按提交 `42fee21`
的提交信息与笔记抄录、未另存独立证据文件；第 3、4、7 条为 2026-10-19 在一次性（detached）工作树里
「改坏 → 只跑相关用例 → 抓失败原文 → 还原 → 复绿」重跑并归档的原文，用的都是**已入库的 JVM 用例**）；
**第 2 条只有临时 JVM 复刻件**（Robolectric + JDBC 的最小复刻件，本轮临时新写、**未入库到源码树**，源码归档在
`evidence/r3-logs/item2-probe-source.kt`；它证明那两条断言对这段守卫有判别力，**不等于已入库的真机用例在本机
跑红过**）；**第 5、6 条为原则通过、无额外实证**（r3 维持通过，本轮没有另做变异实验）；**第 8 条本轮没有取得
判别力证据**（只有真机用例，`TimelineFollowState` 是 `HearthSpace.kt` 的文件私有类、判别点是 Compose 协程的 key，
JVM 侧无对应测例可跑；不是「改坏却不红」，是「没跑成」）；**真机 androidTest 的运行输出仍未归档**。
上述归档文件为 **`docs/reviews/2026-10-18-P2-2/evidence/discriminating-experiments.md`**（原始控制台输出与失败
摘要在同目录 `evidence/r3-logs/`），逐条边界与原因如实记在该文件。本节各行已改为指向归档；仍拿不到原文的
（真机 androidTest 运行输出）在各行内单独标明。

| 复审条 | 问题要点（复审原话要点） | 改在哪里 | 提交 | 判别力实证 / 证据 |
|---|---|---|---|---|
| 1（P1） | `call.check()` 返回后、`connection.outputStream` 真正建连之前仍可被取消；`disconnect()` 对还没建连的连接是空操作，恢复后照样建连并写完正文 | `:net` `GatewayCall.bodyStream` **把取流本身变成检查点**：取流前读一次开关（已取消就根本不建连），取到流后再读一次；返回的流包成**每次 `write`/`flush` 都复验开关**的写入流，取消后 **`close` 不 flush**，交给 `finally` 的 `disconnect()` 收尾。开关只从 false 变 true，所以任何后续动作都必须重新验证，被挂起期间到达的取消不会被落掉 | `42fee21` | 新增受控用例 `net/GatewayTest.aStopWhileTheBodyStreamIsBeingOpenedWritesNoRequestBodyByte`（`FrozenPeer` 把 `getOutputStream()` 卡在「已进入取流、尚未建连」处），断言原文 `assertEquals(0, service.bytes, "取消之后假服务收到了正文：${service.body}")`，并断言网关以 `GatewayCancelled` 在 2 秒内返回、连接被断开；同一接线另有放行正向对照（`正常正文` 确实送达）。**判别力实证原文（提交 `42fee21`）**：临时去掉 `bodyStream` 的后置检查与写入流守卫 → 该用例失败：`取消之后假服务收到了正文：{messages:[...]} ==> expected: <0> but was: <64>`，随后还原。`:net:test` 15、`:roundtable:test` 28、`:contract:test` 22，合计 65，**0 失败 0 错误 0 跳过** |
| 2（P1） | `openConversation(B)` 等 `log.messages(B)` 期间目标仍是旧会话 A，此时发送会把新问题发给并保存到 A；`finishTurn` 第 757–759 行、`replaceTurn` 第 805–808 行在最后一次读取后没有复检令牌 | `openConversation` 在切换开始时**同步把目标定到新会话**并标记 `historyLoading`；历史读取期间 `send`/`regenerate` 一律拒绝；`loadConversation`/`setMode` 复用 `loadHistory`（读取失败也会解除等待，不会把发送键永久禁用）；`finishTurn`/`replaceTurn` 在最后两次读取之后**各复检一次令牌**（`NonCancellable` 收尾不能靠普通协程取消兜底）；`HearthViewModel` 增加可注入的会话开关（生产行为不变），供真机测试把某一次读停住 | `bfa9217` | 新增真机三例：`HearthNavigationDuringTurnDeviceTest.aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected`、`comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere`、`aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened`；`HearthSpace` 的发送键/重新生成在读取期间禁用。**设备输出仍未归档，无法独立复核**（复跑命令见 4.3/4.4）。**判别力实证已补**（`evidence/discriminating-experiments.md` 第 2 条）：一次性工作树里用 Robolectric + JDBC 停读夹具复刻该设备用例的那两条断言，改回旧写法后两条都红——`切换开始时就该把目标定到新的一段 expected:<[older]> but was:<[newest]>`、`历史还没就绪时不该开始任何一轮（notice） expected null, but was:<先在「我的 → 本地模型」导入一个模型，再和人物聊天>`；只去掉「历史就绪前拒发」时，目标断言仍绿、拒发断言单独红。**注意**：该复刻件是本轮临时新写的 JVM 件、没有入库到源码树（源码归档在 `evidence/r3-logs/item2-probe-source.kt`），所以它证明的是「这两条断言对这段守卫有判别力」，不等于「已入库的那条真机用例在本机跑红过」 |
| 3（P1） | 事务内校验读的 `generationToken` 是未同步的普通字段；且 `AbandonedWriteTest` 的 latch 第一次放行的是 schema 检查事务，**把 `valid()` 移出写入事务该测试仍可能通过** | `:hearth` `ConversationLog` 抽出 `HearthSession.writeTurn(valid, block)`：**`valid()` 固定在写入事务打开之后、写第一行之前**；`append`/`replaceLastAnswer`（**`redo` 走同一条路**）统一走它；KDoc 写明边界规则（事务一旦提交就以提交为准，返回值不再撤回）。`HearthViewModel` 的 `generationToken` 改 **`AtomicInteger`**（`generation.get()` / `incrementAndGet()`），并把「开始先发布新令牌、取消/离页先递增再取消、最后一个递增者赢、已提交的写不回滚」写进注释 | `6fd00c8`（hearth 半边，含 `redo` 路径）+ `c91eaaa`（令牌改原子变量） | 夹具 `GatedWriteSession` **只拦真正的写入事务、放过每次开会话先跑的 schema 检查事务**（正是复审第 3 条要求的阻塞位置），断言原文：`assertNull(writing.get(20, TimeUnit.SECONDS), "作废的一轮不该被存下来")`、`assertTrue(gate.validSawWriteTransaction, "valid() 必须在写入事务内被问到")`、`assertEquals(0, gate.writes, "作废的一轮一行都不该写")`；另补 `redo` 作废/成功、完整内容与时间戳比较、写入次数、提交完成后才取消四类用例。判别力：把 `valid()` 挪到写入事务之前 → 该用例失败。**判别力实证原文已补**（`evidence/discriminating-experiments.md` 第 3 条，2026-10-19 复跑）：`:hearth:test --tests "*AbandonedWriteTest"` 5 例中 4 例红——`作废的一轮不该被存下来 ==> expected: <null> but was: <5409d3ce-9a12-4757-a9b7-af33362d2870>`（`AbandonedWriteTest.kt:52`）、`valid() 必须在写入事务内被问到`（`:98`/`:198`）、`作废的重做不该算成功`（`:145`）；还原后 5 例全绿。`:hearth:test` **46 用例，0 失败 0 错误 0 跳过** |
| 4（P1） | 确认状态的数据库更新仍可在取消后完成，甚至用旧人物快照覆盖后来编辑的字段 | 新增 `CharacterStore.confirmCloudTarget(id, target, now, valid)`：在**该方法自己的事务边界内**判定 `valid()`，为 false 就**一列不改**并返回 false；只写 `cloud_confirmed_target` 与 `updated_at`，不再整条人物快照 `update`（`update` 语义不变）。`HearthViewModel.confirmSend` 改走它：`characters.confirmCloudTarget(pending.character.id, pending.target, now(), ::stillWanted)` | `6fd00c8`（store 半边）+ `e152472`（接线与设备用例重写） | `hearth/CloudConfirmTargetTest` 四类：取消、窄更新（含 SQL 列断言）、null 目标、人物不存在；真机用例重写为 `HearthNavigationDuringTurnDeviceTest.aConfirmationTheUserLeftBehindCannotSendOrWriteAnything`（进测试模式 → 用 `GatedHearthSessions` 把确认写事务停住 → 取消弹窗/离页并在停住期间改名字 → 放行 → 断言没有请求、确认字段未被改写、改过的名字仍在、没有建出对话）。**设备输出仍未归档，无法独立复核**；**JVM 用例的判别力实证已补**（`evidence/discriminating-experiments.md` 第 4 条）：去掉事务内的 `valid()` 判定后 `aConfirmationCancelledWhileTheWriteWaitsChangesNothing` 变红，失败原文 `org.opentest4j.AssertionFailedError: 取消之后的确认不该算数`（`CloudConfirmTargetTest.kt:51`），同轮 `AbandonedWriteTest` 5 例 0 失败；还原后 4 例全绿 |
| 7（P2） | JNI 加载已经成功、主线程尚未恢复时点停止：`userStopped=true` 但 `engine.load()` 不一定抛异常，随后仍会 `generate()`，`runNative()` 清掉停止标记后继续生成并入库 | `LocalModelEngine.load`：native 加载「照常成功」返回后**复检停止标记**，命中就抛 `Loading stopped` 并卸掉模型/关掉描述符（别让下一次 `generate` 的 `runNative` 把标记清掉后照常生成）；`HearthViewModel.stream` 本地分支在**加载返回、开始生成之前再问一次本轮停止意图**（`turn.userStopped`），命中即中止这一轮；`startTurn` 同步登记 `activeTurn`，使「发出后、任务第一行之前」按下的停止也落在本轮；`Turn.produced` 记录是否产出过 token，`stillCommittable` 明确「`userStopped` 且没有产出过的本地回答不入库」，已流式产出的部分仍然保留 | `c91eaaa` | 新增 JVM 用例（`:llm`）：`LocalModelEngineTest.aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded`（加载期间停止但加载仍成功 → 加载失败 + 不留已加载模型 + 描述符关闭 + 生成到不了 native）、`aNativeLoadingStoppedFailureReleasesTheModelAndTheFile`（native 抛 `Loading stopped` → 同样清干净并原样上抛）。新增真机用例 `HearthLocalStopDuringLoadDeviceTest.stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer`（需要已登记模型，否则 `assume` 跳过）→ **设备输出仍未归档，无法独立复核**；**JVM 用例的判别力实证已补**（`evidence/discriminating-experiments.md` 第 5 条）：去掉 `load` 返回后的停止复检，`aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded` 变红，失败原文 `java.lang.AssertionError: 停止之后这次加载必须以失败收场，实际 null`（`LocalModelEngineTest.kt:98`），同轮其余 5 例通过；还原后 6 例全绿。`:llm:testDebugUnitTest` **13 用例** |
| 8（P2） | 记忆键与手势监听协程的键不一致，换会话后仍操作旧状态；首次保存（会话 ID 从 null 变成真实 ID）被当成切换会话而重置上翻 | `HearthSpace.kt` 新增 **`TimelineFollowState`**：跟随状态由一份跟着「人 + 这一段对话」走的对象持有，手势协程引用的是**对象本身**（状态与键同源），不再抓住上一次组合的局部状态；会话身份用 `claim` 判定——真人 ID → 另一段真人 ID、或真人 ID → null（新对话/清空/切测试模式）才重置，`null -> 真实 ID`（这一段对话第一次落库）**保留上翻位置**；跟滚动的协程按 segment 重跑，条数一样的两段对话之间切换也会跟到新的一段 | `3bfbdc3` | `HearthTimelineFollowDeviceTest` 补三例：`scrollingBackInsideTheSecondConversationKeepsTheViewportStill`、`theFirstSavedAnswerKeepsAnUpScrolledViewport`、`switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail`（**设备输出仍未归档，无法独立复核**）。**本条本轮没有取得判别力证据**：`TimelineFollowState` 是 `HearthSpace.kt` 的文件私有类、判别点又是 Compose 协程的 key（`LaunchedEffect(listState, follow)`），JVM 侧没有可跑的对应测例，本轮又不连设备——如实记在 `evidence/discriminating-experiments.md` 第 6 条（不是「改坏却不红」，是「没跑成」，所以既不能说有判别力也不能说没有）|
| 9（P2） | 仍声称「9 条全部整改」；审计原文被错误描述为缺失；`20-testmode-turn.png` 仍含系统通知 | 撤回「9 条已全部整改并通过整树与真机复验」；审计原文按实物更正并归档到 `docs/reviews/2026-10-18-P2-2/evidence/`；`20-testmode-turn.png` 改标「仍含系统『旁路充电』通知，只作进入测试模式的辅助证据」；正式版包信息与设备输出原文改标「未归档，无法复核」；1500 token 测速继续标「无法验证」 | `b9efc1d` | 本报告 3.3、3.4、4.5、第九节与 9.1 的对应改写；`evidence/` 下 `audit-raw.json`/`audit-raw2.json`/`audit-raw3.json` + `README.md` |

**第二轮整改后的测试真实数字**（最终门禁，见第十节）：`:hearth` **46**（此前 39）、`:net` **15**、
`:roundtable` **28**、`:app` **27**、`:contract` **22**、`:llm` **13**、`:model-api` **3**，**合计 154 用例 0 失败**。

**判别力实证的边界（2026-10-19 更新，如实记，按条分级）**：**第 1 条（取消后假服务仍收到正文）的失败原文可抄录**
（见上表，提交 `42fee21`），但**没有另存独立证据文件**。**第 3、4、7 条已于 2026-10-19 在一次性（detached）工作树
`D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence`（起点 `d5070e1`，即真分支当时的 HEAD）里逐条「改坏 → 只跑相关
用例 → 抓失败原文 → `git checkout --` 还原 → 复绿」重跑并抓到失败原文**，用的是**已入库的 JVM 用例**
（`AbandonedWriteTest`、`CloudConfirmTargetTest`、`LocalModelEngineTest`）。**第 2 条不是重跑入库用例**：它只有真机
用例，本轮另写了一个**临时 JVM（Robolectric + JDBC）复刻件**来验那两条断言，该复刻件**没有入库到源码树**（源码归档在
`evidence/r3-logs/item2-probe-source.kt`），所以它证明的是「这两条断言对这段守卫有判别力」，**不等于「已入库的真机
用例在本机跑红过」**。**第 5、6 条为原则通过、无额外实证**：r3 维持通过，本轮没有另做变异实验。**第 8 条本轮没有取得
判别力证据**（只有真机用例；`TimelineFollowState` 是 `HearthSpace.kt` 的私有类、判别点是 Compose 的 key，JVM 侧无对应
测例可跑）——不是「改坏却不红」，是「没跑成」，因此对它既不能说有判别力也不能说没有。上述原文见
**`docs/reviews/2026-10-18-P2-2/evidence/discriminating-experiments.md`**（原始控制台输出与失败摘要同目录 `evidence/r3-logs/`）。
**本轮新增/改写的真机用例（androidTest）运行输出仍未归档**（含第 2 条对应的那条用例）：本轮不连设备、不推送，
复跑命令见 4.3/4.4。本节此前「每条都验过判别力」以及「第 2、4、7、8 条都找不到、标未归档无法复核」的说法均作废。
本轮**没有出现「改坏却不红」的情形**：实际跑到的第 2（临时复刻件）、3、4、7 条改坏后都变红；第 8 条是没跑成，不是不红。

### 3.6 审核状态（截至本报告更新时）

审核链：全量审 `REVIEW.md`（审 `11e92b3`，结论需修改，**9 条＝5 条【P1】+4 条【P2】**）
→ 第一轮整改（`313e745` 九条整改 + `a196aed` 第 5 条补验测例；其间 `5314e34` 消掉 lint hint）
→ 复审 `REVIEW-r2.md`（审 `cb6c2db`）：**第 5、6 条通过，第 1、2、3、4、7、8、9 条不通过**
→ 第二轮整改（3.5 的七条：`42fee21`、`bfa9217`、`6fd00c8`、`e152472`、`c91eaaa`、`3bfbdc3`、`b9efc1d`）
→ **第三次复审 `REVIEW-r3.md` 已产出**：任务挂在 2026-10-19 07:06:40（额度重置）后自动执行（等待任务见
`D:\Agent\Worktrees\Mycelium-Android\p2-2-work\review-r3-wait.log`），报告审 `758bbf2`、对照 r2 基线 `cb6c2db`，
全程只读；**该报告已随本次提交入库**（提交信息 `docs(P2-2): 复审 r3 结论与判别力表述按证据分级`，
路径 `docs/reviews/2026-10-18-P2-2/REVIEW-r3.md`）。

**r3 的结论（`REVIEW-r3.md` 第 1 行）**：**结论：需修改**——**第 3、4 条核心缺陷已修复**（第 3 条「通过，有测试
表述限制」：夹具已能抓「校验移到写事务外」的变异，但闩锁实际停在底层 `BEGIN` 之前；第 4 条「通过，按事务入口
裁决」，JVM 变异实证有效），**第 5、6 条维持通过**；**第 1、2、7、8 条仍有遗漏**（第 1 条：取流后的检查能挡住写
正文，但取消状态仍未进入底层建连过程，取消后仍可能启动连接并等到建连超时；第 2 条：历史读取失败后可能用新人物
向旧人物会话发送、重做写库被拒后仍缺令牌复检；第 7 条：检查后到 IO 真正开始生成之间仍会丢停止信号；第 8 条：
未保存的新对话切到历史被误认为首次保存，形成新回归），**第 9 条仍有超出证据的表述、需改**——即 r3 所指
`DELIVERY.md:157`（本次改写前的位置）的「每条都……验过判别力」，本次已按 3.5 的按条分级说法改写。r3 同时指出：
新增/改写设备测例的执行结果、第 8 条动态判别力、真实 Android 建连取消时序仍未验证。

**结论：本包仍属「待审」——不得交付、不得推送。** 本报告以及第十节的最终门禁只说明「第二轮整改后的提交能构建、
154 个 JVM 用例与 lint 通过」，**不构成复审通过**；r3 判第 1、2、7、8 条仍有遗漏、第 9 条表述需改，须再整改后再审。

另外两点如实记：

- **「并发与升级专项审」因额度限制没有执行。** 它的审核要求早已写好
  （`docs/reviews/2026-10-18-P2-2/codex-prompt-special-concurrency.md`），`docs/tracks/开发线.md` 第 76 行
  记的做法是「额度够就补一轮，不够就并进全量审的要求里」；实际是本包**没有跑这一轮专项审**，其要点
  并进了全量审（`REVIEW.md` 第 5、6、9 条）一起审。
- 复审第一次尝试（22:11 起跑）撞额度上限、未产出报告；第二次由无人值守任务在 02:07:36–02:15:26
  产出 `REVIEW-r2.md`（13087 字节，结论需修改），现已随 `e6c1d65` 入库。

## 四、真机走查

设备：红魔 NX809J（Android 16，SDK 36，1216×2688）。只装 `com.xavierxia.mycelium.debug`。
正式版 `com.xavierxia.mycelium` 在 P2-2 两次走查前后一致（0.1.1 / versionCode 2 / lastUpdateTime
`2026-10-10 05:23:04` / firstInstallTime `2026-10-10 05:14:52`），但**本包没有归档这两次走查的
`dumpsys package` 原文**，所以这一条只是「按当时读数转述」，不能独立复核；最近的原始凭据是 P2-1b 的
`device-log.txt`（13:38，同一组读数，在 `.omx/development/reviews/2026-10-18-android-P2-1b/`）。
截图在 Windows 仓库 `.omx/development/reviews/2026-10-18-android-P2-2/`：第一轮 `01`–`14` 与第二轮
`20`–`22` 都已按同一规则裁掉顶部 160 像素（尺寸 1216×2528），只在知衍（调试）前台截。
本轮的门禁日志也归档在同一目录：`p2-2-gate-summary-freshworktree.md`、`p2-2-gate-checkall-ps1.txt`、`p2-2-gate-checkall-sh.txt`。

### 4.1 第一轮（改动前的提交，`01`–`14`）

预设 5 个与安全兜底、老周本地多轮、重新生成、杀掉重开、云端确认弹窗与云端标记、云端回答、
测试模式不留历史、飞行模式本地能聊、正式版不变 —— 这些都有截图或日志证据（细节见 `01`–`14` 号证据）。
1500 token 测速只有测例返回 `OK (1 test)` 的转述，**没有留下 token 数/耗时明细文件**，按第九节标为无法复核。

### 4.2 第二轮（本轮修复后的提交，`20`–`22`）

| 项目 | 结果 | 证据 |
|---|---|---|
| 测试模式离开后清空 | 已通过：测试模式聊一轮 → 切「我的」→ 回炉边，测试那轮消失、模式回到沉浸；随后重进人物，沉浸历史完整 | `20-testmode-turn.png`、`21-after-leave.png` |
| 停止后问题回到输入框 | 已通过：发送后 1.5 秒停止 → 提示「已停止，还没有回答」、问题回到输入框、这一轮没入库 | `22-stop-question-back.png` |
| 自动滚动 | 已通过（截图可见最新一轮在最下方；代码里「用户上翻时不再强行拉回」的逻辑同批实现） | `22-stop-question-back.png` |

### 4.3 两项缺口的自动化补测（手机上跑的 Compose instrumented 测试）

手工点不进的两项，改成在真机上跑的 Compose 界面测试补上（进程内假服务，**不动手机输入法与系统设置**）：

| 测试 | 证明了什么 | 断言到的具体值 |
|---|---|---|
| `HearthCloudStopDeviceTest` | 云端慢回答（进程内 `LocalHttp` 睡 5 秒）在途时点「停止」：晚到的回答**不上屏、不入库**，但这次请求在隐私记录里留痕 | 界面 `onAllNodesWithText("晚到的回答 …")` = **0 个节点**；`log.conversations(id)` = **0 段**、`log.messages()` = **0 条**；审计 = 跑之前 + **正好 1 条**，`purpose = HearthChat`、`succeeded = false`、`status = null`、`sentBody` 含本轮问句 |
| `HearthCloudReconfirmDeviceTest` | 用界面（`performTextReplacement`）把已确认服务的接口地址换成另一个端口后，回炉边发送会**先弹确认框、且不发请求**；取消后仍不发、确认状态不被改写 | 存储里的 `baseUrl` = 新地址；弹窗「这个人物改用云端模型？」`assertIsDisplayed()` 通过且此时 `server.count == 0`、`calls()` 空；取消后提示「已取消，这一轮没有发出去」显示、弹窗节点 0、输入框仍有问句、`count == 0`；`cloudConfirmedTarget` 仍是**旧地址**的目标；0 段对话 |

我的复核：在手机上重跑两条 → `OK (2 tests)`（11.797 s）。子代理侧另有三次重复（8.1–8.2 s / 3.5 s 各三次）与两条一次跑 `OK (2 tests)`。

**这两条测试只在真机上跑，不在门禁里**：`check-all.ps1` / `check-all.sh` 按任务契约只编译 androidTest
（`assembleDebugAndroidTest`）并跑 JVM 单测与 lint，不跑 instrumented 测试（CI runner 上没有设备/模拟器）。
运行方式记在这里，方便以后复验：
`adb install -r app/build/outputs/apk/debug/app-debug.apk` 与 `…/androidTest/debug/app-debug-androidTest.apk`，
然后 `adb shell "am instrument -w -e class com.xavierxia.mycelium.hearth.HearthCloudStopDeviceTest,com.xavierxia.mycelium.hearth.HearthCloudReconfirmDeviceTest com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner"`。

**构建改动（如实记）**：`app/build.gradle.kts` 的 `androidTestImplementation` 加了三行——`composeBom`、
`androidx.compose.ui:ui-test-junit4`（这两行经用户同意）、`androidx.test.espresso:espresso-idling-resource:3.7.0`
（`ui-test-junit4` 的传递依赖，本机离线缓存只有 3.7.0，不显式写会解析不到）。三行都是**已缓存制品**，
没有引入新制品；`src/main` 与其他模块一行未改。

**测试脚手架的取舍（如实记）**：`HearthCloudStopDeviceTest` 临时接管了未捕获异常处理器。原因是被停掉的连接上，
进程内假服务写回答会抛 `SocketException: Broken pipe`，Android 上任意线程的未捕获异常会杀进程。
现在只咽「来自 `com.xavierxia.mycelium.net.LocalHttp` 栈帧且非主线程的 `IOException`」，其余异常照旧交给系统处理器。
另外该测试不断言「已停止，还没有回答」的文案（取消分支与干净返回分支是竞态、文案会不同），只断实质。

### 4.4 整改后的设备测试（2026-10-18 22:35 复跑，逐类单跑）

全量审 9 条整改之后，设备上共 4 个类 9 例。**必须逐类分开跑**：被中止的连接会让进程内假服务在
`LocalHttp.serve` 抛 `SocketException: Broken pipe`，非主线程未捕获会杀掉进程——这是既有脚手架问题
（一次跑多类时在第 3 例崩过），不是产品缺陷；分开跑等于每类一个全新进程。

| 测试类 | 覆盖 | 结果 |
|---|---|---|
| `HearthCloudStopDeviceTest` | 云端停止后晚到的回答不上屏、不入库，审计仍留这次请求 | `OK (1 test)` 8.235 s |
| `HearthCloudReconfirmDeviceTest` | 改地址后发送先弹确认框、期间 0 请求，取消后仍 0 请求且确认状态不被改写 | `OK (1 test)` 3.478 s |
| `HearthNavigationDuringTurnDeviceTest`（本包新增） | 生成中换会话 / 切测试模式：旧任务停下、`busy` 清掉、页面还能继续发；晚到回答不上屏不入库 | `OK (2 tests)` 3.386 s |
| `HearthTimelineFollowDeviceTest`（本包新增） | 长回答内部上翻、跨消息上翻、手动回到底部、换对话、底部时自动跟随 | `OK (5 tests)` 5.84 s |

新加的两个类**直接驱动 `HearthViewModel` / `ChatTimeline`**，不点屏幕（不受输入法影响）；假服务是进程内的
`LocalHttp`，只监听 `127.0.0.1`、密钥是假值。

### 4.5 1500 token 测速（第一轮，设备测例）

30 条消息、估算 1576 token；Qwen3.5-4B-Q4_K_M 只读打开；线程 6、上下文 8192：

| 次数 | 读提示 token | 读提示耗时 | 生成 token | 生成耗时 | 生成速度 |
|---|---|---|---|---|---|
| 1 | 1284 | 93.8 s | 148 | 18.1 s | 8.16 token/s |
| 2 | 1284 | 108.7 s | 148 | 21.1 s | 7.01 token/s |
| 3 | 1284 | 109.8 s | 148 | 21.0 s | 7.06 token/s |

模型加载 0.97 s。**读一条 1300 token 的提示要 1.6–1.8 分钟**，已按裁定排入 P2-1c（复用已读过的上下文）。

**上表的可核验状态：无法验证。** `p2-2-work` 里与这次测速有关的原始文件只有三份：`speed-run1.txt`（当时类名
写错，`ClassNotFoundException`，末尾 `Tests run: 1, Failures: 1`）、`speed-run2.txt`（空文件，5 字节 `\r\n`）、
`speed-run3.txt`（102 字节，只有 `HearthPromptSpeedDeviceTest` + `Time: 373.865` + `OK (1 test)`）。
**token 数、读提示耗时、生成耗时的明细在任何归档文件里都找不到**，上表只能算当时会话转述，
**标为「无法验证」，不作为本包交付证据**（复审 `REVIEW-r2.md` 第 7 条要求继续这样标）。

## 五、隐私与出网

- **默认本地**：不开云端不出网；飞行模式下本地模型照常对话（第一轮走查）。
- **云端逐人物确认**：绑定「人物 + 服务 ID + 规范化地址」，发送前从设置重新读取并校验；
  「取消」不生效（第一轮走查验证）；复制人物不继承确认。
- **测试模式不记正文**：设备审计实测 `HearthChatTest` 记录字数、**正文长度 0**；沉浸 `HearthChat` 记正文
  （这是设计：用户可在「我的 → 隐私记录」看到自己发出去的内容）。规则对成功与失败两条路径统一。
- **只发炉边自己的内容**：审计看到的正文只有人物设定 + 该人物历史 + 当前问题，没有圆桌内容。
- **只 :net 碰 HTTP**：架构测试按真实模块集合与生产依赖传递闭包检查，四种失败情形都有测例。
- **缺口**（按裁定挪到 P2-5）：本地引擎的跨页面加载/卸载/生成协调与登记列表过期处理。

## 六、已知问题

1. 云端回答是**整段返回**（不是逐字流式）：界面显示「生成中」，结果一次性出现。这是本包的既定范围
   （裁定允许），但不是流式体验。
2. 「停止」在**第一个字还没出来**时不再丢问题（问题回到输入框），但这一轮不会入库 —— 与「已生成的部分
   才留下」一致。
3. 本地模型有时把整段预算花在隐藏的思考段上，界面长时间没有正文（第一轮走查观察过一次约 47 秒）；
   与 P2-5 的上下文/长度策略一起复验。
4. 走查输入受限：`adb` 不能注入中文（英文会被中文输入法当拼音吞掉）。
5. **两项真机复验已用自动化测试补上**（见 4.3）：手工点不进去的两件事，现在由手机上跑的 Compose
   instrumented 测试证明。仍未覆盖的：点「知道了，用云端」之后真的把这一轮发出去、以及「编辑页重选云端模型也弹窗」
   这条旧入口；测试 2 也无法观测「有没有偷偷发到新地址」（新端口上没有服务），它证明的是「被确认过的那个服务 0 请求」
   加「弹窗先出现」（源码里这条路径在发请求前就 return）。
6. 云服务编辑页没有单独的「保存」按钮：点「拉取模型」会先落盘再拉取（读 `CloudSettingsScreen` 确认），
   所以改地址在界面上要靠这个动作落盘；拉取本身对新地址会失败，但地址已经存进去了。

## 七、额度读数

**本轮新增两条**：2026-10-18 **21:19** 全量审结束 **5h=59% / 每周=41%**；**22:30** 复审撞上限
**5h=99% / 每周=47%**（credits 0，Codex 明确答复「try again at Oct 19th, 2026 2:06 AM」）。

| 时点 | 5 小时窗口 | 每周 |
|---|---|---|
| 方案审前（12:12 事件） | 93.0%（重置 15:54） | 14.0% |
| 方案审后（16:09 事件） | 23.0% | 19.0% |
| 专项审后（17:00 事件） | 65.0% | 26.0% |
| 全量审尝试后（17:26 事件） | **99.0%（上限，重置 21:04）** | 31.0% |

全量审那一轮消耗 139,068 token 后撞上限、未产出报告；重跑安排在 21:10。建议统筹提前知悉：
如果希望更早拿到结论，可以在重置前代审（审稿要求已在仓库里：`codex-prompt-review.md`）。

## 八、提交与证据对应表

| 提交 | 内容 | 对应证据 |
|---|---|---|
| `e0d3f7f` | P2-2 实现（四批：hearth 模块、网关与审计、页面接线、审稿要求） | 第一轮门禁（111 用例）、第一轮真机走查 `01`–`14` |
| `eecbf7a` | 方案审报告入库 + 第 8/11/12 条与第 2/3/7 条核心的落实 | 门禁 113 用例 |
| `097862c` | 交付报告与开发线交接 | — |
| `d390c24` | 并入 `main` `a54c604`（统筹裁定） | — |
| `01a5ff0` | 裁定里的本包内必修项（第 1 剩余、3、4、5、6、9、10 与体验①②） | 门禁 133 用例、第二轮真机走查 `20`–`22` |
| `d4fbea0` | 专项审 7 条整改 | 门禁（在修复后提交上重跑） |
| `5005bc4` | 回到炉边时重新载入沉浸历史 | 最终门禁：PASS，133 用例（提交号已记入 `summary.md`，工作区干净） |
| `5f64b99` | 改地址/换服务后的重新确认挪到发送时（对话页直接弹确认框） | 编译 + lint + 三模块测试 |
| （真机补测提交） | 两个 Compose instrumented 测试 + androidTest 三行依赖 | 设备 `OK (2 tests)`（我自己重跑 11.797 s；子代理侧各三次重复） |
| `ddafb69`、`24ff0cd`、`4756ebf` | 交付报告与开发线交接更新（docs only） | — |
| — | **全量审尝试（17:23–17:27）撞用量上限，未产出** | 额度 99%，21:04 重置；重跑挂在 21:10 |
| `11e92b3` | 交付报告：说明两条 instrumented 测试只在真机跑、不在门禁任务契约内，并记下复验命令 | — |
| `be2e013`、`11e92b3` | 截图与门禁日志归档到正式证据目录 | `.omx/.../2026-10-18-android-P2-2/` |
| `0f28bd0`、`11e92b3`、`be2e013` | `check-all.sh`（CI 那条）在全新 worktree 上同样通过 | `p2-2-gate-checkall-sh.txt` |
| `11e92b3` | 全量审（21:05 发出，21:05–21:19 在只读 worktree 跑完） | `REVIEW.md`（需修改，9 条；结束时 5h=59%、每周=41%） |
| `313e745` | 全量审 9 条整改：网关请求级取消协议、导航时清 busy、校验进事务、确认任务绑定操作、地址与密钥同一快照、升级判定不再静默改模型、加载期停止算停止、按真实视口判定跟随、证据更正 | 当时的整树复验（**「JVM 128/128」一行来自过期产物，已作废**，见 3.4/第十节）与真机 4 类 9 例转述 |
| `a196aed` | 第 5 条要求的快照测例（两次读取之间配置被改，地址与密钥不得跨版本拼接） | `roundtable/CloudEndpointSnapshotTest` 2 例通过 |
| （复审第一次） | 22:11 起跑，5.5 分钟 / 131 万 token 后撞上限（5h=99%、每周=47%，02:06 重置） | **未产出** |
| （复审第二次·无人值守） | 02:07:36 开始（`p2-2-work/review-r2-wait.log`），审核目标 `cb6c2db`，02:15:26 结束、报告 13087 字节 | `REVIEW-r2.md`：**结论需修改**，第 5、6 条通过，**第 1、2、3、4、7、8、9 条不通过**（该报告已随 `e6c1d65` 入库） |
| （本轮证据更正） | 归档审计原文附件、撤回「9 条全部整改」与「当时没存下来」、更正截图与包信息表述、测速继续标无法验证 | `docs/reviews/2026-10-18-P2-2/evidence/`（`audit-raw.json`/`audit-raw2.json`/`audit-raw3.json` + `README.md`）、本报告 3.3 / 3.4 / 4.5 / 第八、九节与 9.1 |
| `6fd00c8` | 复审第 3、4 条的 hearth 半边：`writeTurn` 把 `valid()` 放进写入事务（含 `redo` 路径）、`confirmCloudTarget` 事务内判有效性且只写确认列 | `hearth/AbandonedWriteTest`、`hearth/CloudConfirmTargetTest`；`:hearth:test` 46 用例 0 失败 |
| `3bfbdc3` | 复审第 8 条：跟随状态与手势协程同键（`TimelineFollowState.claim`），首次保存不再重置上翻 | `HearthTimelineFollowDeviceTest` 补 3 例（设备输出未归档） |
| `42fee21` | 复审第 1 条：取消状态延续到建连之前（`GatewayCall.bodyStream` 取流检查点 + 写入流每次 write/flush 复验、取消后不 flush） | `net/GatewayTest.aStopWhileTheBodyStreamIsBeingOpenedWritesNoRequestBodyByte`；判别力原文见 3.5 |
| `bfa9217` | 复审第 2 条：切历史先固定目标、历史就绪前拒绝发送、收尾刷新复检令牌 | `HearthNavigationDuringTurnDeviceTest` 补 3 例（设备输出未归档） |
| `e152472` | 复审第 4 条：`confirmSend` 改走 `confirmCloudTarget(id, target, now, ::stillWanted)`，取消后一列不改 | `HearthNavigationDuringTurnDeviceTest.aConfirmationTheUserLeftBehindCannotSendOrWriteAnything` 重写（设备输出未归档） |
| `c91eaaa` | 复审第 3、7 条：`generationToken` 改 `AtomicInteger`；加载返回后先问停止意图、本地回答没产出就不落库 | `llm/LocalModelEngineTest` 新增 2 例；`HearthLocalStopDuringLoadDeviceTest`（设备输出未归档） |
| `b9efc1d` | 复审第 9 条：证据更正与撤回过头表述（docs only） | 本报告 3.3 / 3.4 / 4.5 / 第九节与 9.1、`evidence/` |
| `e6c1d65` | 复审报告 `REVIEW-r2.md` 入库（docs only），当时本分支 tip | `docs/reviews/2026-10-18-P2-2/REVIEW-r2.md`（13087 字节） |
| （第二轮整改后·最终门禁） | 全新 worktree detached `c91eaaa`，`check-all.ps1` 与 `check-all.sh` 均通过 | **154 用例 0 失败**、lint 0 error / 0 warning；`p2-2-work/gate-final-ps1.txt`、`gate-final-sh.txt`（第十节） |
| （第三次复审·已产出） | 等待任务在 2026-10-19 07:06:40（额度重置）后自动执行，审 `758bbf2`、对照 r2 基线 `cb6c2db`，全程只读 | `REVIEW-r3.md`：**结论需修改**——第 3、4 条核心缺陷已修复、第 5、6 条维持通过，第 1、2、7、8 条仍有遗漏，第 9 条表述需改（**该报告已随本次提交入库**，见 3.6） |
| （判别力证据归档提交·本轮） | 一次性（detached）工作树里复跑第 3、4、7 条的「改坏 → 变红 → 还原」，并为第 2 条另写临时 JVM 复刻件、补上此前缺的失败原文；第 8 条如实记为没有取得证据。**本轮不连设备、不推送、未改源码** | `docs/reviews/2026-10-18-P2-2/evidence/discriminating-experiments.md`、同目录 `evidence/r3-logs/`（15 个文件）；本报告 3.5 与 9.1 第 5 项 |

## 九、全量审提出的证据缺口与更正（2026-10-18 21:10 审后）

这一节只写「哪些原始证据不存在、哪些表述被撤回」，方便复核者按图索骥。

| 审稿指出 | 现在的事实 | 处置 |
|---|---|---|
| 正式审计目录里找不到测试模式的记录 | **记录确实存在，此前「没存下来」的说法与实物不符，撤回**：`D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw2.json`（7 条）与 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw3.json`（8 条）里各有一条 `HearthChatTest`：`at=2026-10-18T08:05:55.032494Z`、`host=127.0.0.1`、`sentCharacters=206`、`sentBody=""`（正文长度 **0**）、`status=200`、`succeeded=true`、`promptTokens=12`、`completionTokens=8`；同两文件里另有沉浸 `HearthChat`（07:25:15，377 字；`raw3` 还有 08:07:09，470 字）。`09-audit-hearth-entries.txt` 只是当时摘录的 1 条，不是唯一凭据 | **已归档为仓库内可定位附件**：`docs/reviews/2026-10-18-P2-2/evidence/audit-raw.json`、`audit-raw2.json`、`audit-raw3.json`（复制前逐份查过：字段只有 `at/host/purpose/sentCharacters/sentBody/status/succeeded/promptTokens/completionTokens` 九个，`host` 全是 `127.0.0.1`，无本机用户名、设备序列号、密钥或真实地址；详见 `evidence/README.md`） |
| 仅凭 `speed-run3.txt` 的 `OK (1 test)` 复核不了三次测速 | `p2-2-work` 里与测速有关的只有 `speed-run1.txt`（类名写错、`ClassNotFoundException`、末尾 `Tests run: 1, Failures: 1`）、`speed-run2.txt`（空文件）、`speed-run3.txt`（102 字节，只有 `Time: 373.865` 与 `OK (1 test)`）；**token 数与读提示/生成耗时明细在任何归档文件里都没有** | 已撤回「1500 token 测速通过」的写法，**继续标「无法验证」**（4.5、本节、9.1 第 6 项） |
| 找不到两个 Compose 设备测试的原始成功输出 | 运行输出只留在会话里，没有归档成文件；仓库、`p2-2-work` 与正式证据目录里都搜不到 `OK (n tests)` 的原始输出 | 标**原始输出未归档**；给出复跑命令（4.3、4.4），复核者可自行复现 |
| 正式版前后包信息缺本包原文 | P2-2 两次走查只读了值，`dumpsys package` 原文始终没有落盘：仓库、`p2-2-work`、正式证据目录三处都搜不到 P2-2 时点的原文，**未归档，无法复核**；最近的同组原始记录是 P2-1b 的 `D:\Agent\Worktrees\OMNIX-Mycelium\main\.omx\development\reviews\2026-10-18-android-P2-1b\device-log.txt`（13:38，含 `dumpsys` 原文片段） | **撤回**此前「本轮补上了装前/装后原文」的说法（3.4 已改写）：P2-2 这两次读数只能算当时转述，标注**未归档、无法复核** |
| `20`–`22` 保留状态栏、`20` 含系统通知，「状态栏已裁」不实 | **复审 `REVIEW-r2.md` 第 44–46 行目视确认：`20-testmode-turn.png` 仍保留大块「旁路充电」系统通知，裁掉顶部 160 像素并没有清除它。**本次无法目视（本会话模型不接受图像输入，`read_image` 直接报错），改用逐行像素统计复算，结论一致：图 1216×2528；y=0–266 是一整块浅色卡片（众数色 `(208,208,209)`，x=62–1153 共 1092 px 宽，左右各留 62 px 深色边），而同图应用本体是深色（y=600–700 众数色 `(20,18,24)`，占 93%）；卡片内 y=0–37、64–106、171–212、254–266 有内容行，其中 y=171–212 含强调色 `(121,118,252)` 3625 px，而该色在整图稀疏采样里只有 163 px。**裁掉 y=0–159 后仍留下 y=160–266 共 107 行**（含整个强调色块与卡片下边缘），故「裁掉 160 像素＝界面干净」不成立 | 改写为：`20-testmode-turn.png` **仍含系统「旁路充电」通知**，该图**只作「进入测试模式」的辅助证据，不作「界面干净」的证据**；且本会话无图像输入，卡片上的文字**未 OCR、未逐字确认**，上述结论只依据几何与色彩统计 |
| 「12 条 + 7 条全部整改」「成功/失败/超时/取消另有测例」过头 | 12 条与 7 条是**逐条状态**（3.1/3.2 有表），不是笼统「全部通过」；超时/取消矩阵确实没有单独成例 | 已按事实改写（3.1 第 8 条、本节、3.3） |

### 9.1 复审《REVIEW-r2.md》第 7 条要求的二次更正（2026-10-19，只改文档与归档证据）

复审审的是 `cb6c2db`（当时本报告所在工作树的基线是 `3bfbdc3`，它后面还有 `6fd00c8` 等整改；到 `c91eaaa`
共七笔，逐条见 3.5）。逐项更正：

1. **撤回「9 条全部整改」**。3.3 的结论已改写为：「9 条整改已提交（`313e745`、`a196aed`），复审
   `REVIEW-r2.md` 判第 1、2、3、4、7、8、9 条仍不通过，正按复审意见二次整改」。3.4 的整树与真机复验
   只证明那批改动构建、单测、lint 与设备测例通过，**不再当作复审通过的凭据**。
2. **审计原文按实物更正并归档**。测试模式那条记录**确实存在**，「当时没存下来」的说法**与实物不符，撤回**：
   - `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw2.json`（7 条）与
     `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\audit-raw3.json`（8 条）各含一条 `HearthChatTest`：
     `at=2026-10-18T08:05:55.032494Z`、`host=127.0.0.1`、`sentCharacters=206`、`sentBody=""`（正文长度 0）、
     `status=200`、`succeeded=true`、`promptTokens=12`、`completionTokens=8`。
   - 同目录的 `audit-raw.json`（6 条）是更早的一次快照。
   - 三份都已复制进仓库：`docs/reviews/2026-10-18-P2-2/evidence/`（附 `README.md` 记来源、字段、编码与敏感字段排查结果；
     三份都是 **UTF-16LE + BOM 的单行 JSON**，逐字节照抄，故 `git show` 会显示为 `Bin`，读法见该 README）。
3. **截图**。`20-testmode-turn.png` **仍含系统「旁路充电」通知**（复审目视；本次用逐行像素统计复算，见上表），
   裁掉顶部 160 像素没有清除它。该图**只作「进入测试模式」的辅助证据，不作「界面干净」的证据**。
   正式证据目录：`D:\Agent\Worktrees\OMNIX-Mycelium\main\.omx\development\reviews\2026-10-18-android-P2-2\20-testmode-turn.png`。
4. **正式版包信息原文**：**未归档，无法复核**。仓库、`p2-2-work` 与正式证据目录都搜不到 P2-2 时点的
   `dumpsys package` 原文；最近一份同组读数在 P2-1b 的
   `D:\Agent\Worktrees\OMNIX-Mycelium\main\.omx\development\reviews\2026-10-18-android-P2-1b\device-log.txt`（13:38）。
5. **设备输出原文**：四类九例的 instrumented 输出同样**未归档**，只有会话转述；复跑命令见 4.3/4.4。
   （2026-10-19 补：这批真机输出**仍未归档**；但第 3、4、7 条的**判别力实证原文**已在一次性工作树里「改坏 → 变红 →
   还原」重跑并归档，第 2 条由临时 JVM 复刻件补上其两条断言的失败原文（该复刻件未入库，不等于真机用例跑红过），
   归档在 `docs/reviews/2026-10-18-P2-2/evidence/discriminating-experiments.md`（原始日志 `evidence/r3-logs/`）；
   第 5、6 条无额外实证；第 8 条没有取得判别力证据，见该文件第 6 条。）
6. **测速**：1500 token 测速的 token 数与耗时明细**没有归档文件**，**继续标「无法验证」**（4.5 已写明）。

方法与局限（如实记）：本次只在 Windows 上读文件、跑 `git` / `Select-String` 与一段只读的 Python/Pillow 像素统计，
**没有连接设备、没有推送、没有跑 Gradle、没有联网、没有改源码**；当时复审报告 `REVIEW-r2.md` 还是
**未入库文件**，本轮（2026-10-19）它已随 `e6c1d65` 入库为 `docs/reviews/2026-10-18-P2-2/REVIEW-r2.md`。

另外记一笔：第一轮 `01`–`14` 里的 `09` 号审计附件是**当时**的读数；此后设备上的审计条数已变——本报告上一轮更新（见 9.1）复核停止测试时又留了 1 条 `HearthChat`（那是真实出网请求的审计记录，按规矩不去改用户的隐私记录）。

## 十、最终门禁（第二轮整改后，2026-10-19）

在**全新 worktree** 里对**已提交的提交**从零跑两条门禁脚本：工作树
`D:\Agent\Worktrees\Mycelium-Android\p2-2-final`，detached 在 **`c91eaaa`**（本分支；其后只有一笔文档提交
`e6c1d65`，故结论对当前 tip 适用），`worktree: clean`。

| 项目 | 结果 |
|---|---|
| 提交 | `c91eaaafc7c1c72160b0de064e2e3dc56311e565` |
| llama.cpp | 校验到固定提交 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f` |
| Gradle | `--offline`；`test :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`，退出码 **0**，**253 个任务实际执行 / 20 个无需执行** |
| `scripts/check-all.ps1` 结论行（原文） | `**PASS**: 0 failures, 0 warnings, exit code 0.` |
| `scripts/check-all.sh` 结论行（原文） | `**通过**：0 失败、0 警告，退出码 0。` |
| 用例 | **154 用例 0 失败**：`:app` 27、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、`:net` 15、`:roundtable` 28 |
| lint | `:app` debug / release 均 **0 error / 0 warning**（无 hint） |
| 产物 | `app-debug.apk`、`app-debug-androidTest.apk` 已签名；`app-release-unsigned.apk` 未签名 |
| 日志 | `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\gate-final-ps1.txt`、`gate-final-sh.txt`（`.sh` 那条跑在 `.ps1` 之后，多数任务显示 `UP-TO-DATE`，复用同一批结果与产物） |

**口径更正（两条旧说法作废）**：

1. 此前 3.4 写的 **「JVM 单测 128/128」来自过期产物，作废**。第二轮整改后的真实数字是上表的
   **154 用例 0 失败**（`:hearth` 46、`:net` 15、`:roundtable` 28、`:app` 27、`:contract` 22、`:llm` 13、
   `:model-api` 3）。
2. 此前「lint 0 error / 0 warning / **1 hint**」的说法**作废**：`HearthSpace.kt` 的 `AutoboxingStateCreation`
   hint 早在提交 **`5314e34`** 就已消掉，最终门禁是 **0 error / 0 warning**。

**这一节能证明什么、不能证明什么**：它证明第二轮整改后的提交**构建、154 个 JVM 用例、lint 与打包**都通过；
它**不包含** device/instrumented 测试（两条脚本按任务契约只跑 `assembleDebugAndroidTest`，CI runner 上没有
设备/模拟器），也**不是复审通过**——第三次复审（`REVIEW-r3.md`）已出结论、判**仍需修改**（第 1、2、7、8 条仍有
遗漏、第 9 条表述需改），本包仍是「待审」（见 3.6）。
