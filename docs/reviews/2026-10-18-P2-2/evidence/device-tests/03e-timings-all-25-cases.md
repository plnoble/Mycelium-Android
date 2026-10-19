# 全部 25 例的结果（逐例）

一列一例，`#` 是这个仓库里 androidTest 的固定执行顺序（与基线日志一致）。每一例的耗时取自「主机侧打了
时间戳的那份原始输出」里该例 `CODE: 1` 到结果行之间的差（`03*` 系列的表就是它）。

- 用例总数：**25**
- 通过：**19**
- 失败：**5**（`#10`、`#21`、`#22` 需要真机上有一个**能用的本地模型**；`#11` 是测例自身的假红、
  `#15` 是被 `LocalHttp` 的 `Broken pipe` 带走的——这两例与本地模型无关，2026-10-19 单类复跑后更正，
  见 `04e-follow-class-rerun.md`）
- 跳过（JUnit assumption）：**1**（`#4`，真机上没有登记本地模型时自己跳过，不假装通过）
- 挂死：**0**（有界化之后，任何一次复跑都没有再出现「无输出地挂住」；主机侧还有一道 6 分钟硬上限兜底）
- 进程崩溃：**1 次**，每次都在同一处：`net` 模块的测试假服务 `LocalHttp.serve` 往已经关闭的 socket 里写，
  非主线程未捕获 `SocketException: Broken pipe` 把进程带走（见 `hang-record/08`）——谁碰上它谁被带走
  （三次全量恰好都在 `#15` 那一格）。这是既有脚手架问题，不在本次改动范围内；它让「一次 invocation
  跑完 25 例」变得不可能，所以下面的结果来自 3 次 invocation 的并集。

| # | 用例 | 结果 | 耗时 | 来自哪一次 |
| --- | --- | --- | --- | --- |
| 1 | DeviceHearthTest#plaintextCharacterAndDialogueAreAbsentFromTheDatabaseFile | 通过 | 211ms | 分批跑 B |
| 2 | HearthCloudReconfirmDeviceTest#changingTheSavedAddressMakesTheNextSendAskAgain | 通过 | 4375ms | 分批跑 B |
| 3 | HearthCloudStopDeviceTest#aStoppedCloudTurnNeverShowsOrStoresTheLateAnswer | 通过 | 8105ms | 分批跑 B |
| 4 | HearthLocalStopDuringLoadDeviceTest#stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer | 跳过（assumption，code=-4） | 28ms | 分批跑 B |
| 5 | HearthNavigationDuringTurnDeviceTest#aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened | 通过 | 7612ms | 单类 / 分批跑 B |
| 6 | HearthNavigationDuringTurnDeviceTest#aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected | 通过 | 3581ms | 单类 / 分批跑 B |
| 7 | HearthNavigationDuringTurnDeviceTest#comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere | 通过 | 3594ms | 单类 / 分批跑 B |
| 8 | HearthNavigationDuringTurnDeviceTest#aConfirmationTheUserLeftBehindCannotSendOrWriteAnything（原挂死的那一例） | 通过 | 6119ms | 单类 / 分批跑 B |
| 9 | HearthNavigationDuringTurnDeviceTest#switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage | 通过 | 1133ms | 单类 / 分批跑 B |
| 10 | HearthPromptSpeedDeviceTest#threeRunsWithAnAbout1500TokenPrompt | 失败 | 10ms | 分批跑 B |
| 11 | HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | 失败（**测例假红**：导航后没等重组） | 847ms | 全量跑（第 11 例） |
| 12 | HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | 通过 | 1200ms | 全量跑（第 12 例） |
| 13 | HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | 通过 | 1030ms | 全量跑（第 13 例） |
| 14 | HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | 通过 | 738ms | 全量跑（第 14 例） |
| 15 | HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | 失败（**进程被 `Broken pipe` 带走**，不是它的断言） | 648ms | 全量跑（第 15 例） |
| 16 | HearthTimelineFollowDeviceTest#longAnswerScrolledBackInsideKeepsTheViewportStillWhileTokensArrive | 通过 | 1424ms | 分批跑 C |
| 17 | HearthTimelineFollowDeviceTest#returningToTheBottomByHandResumesFollowing | 通过 | 2070ms | 分批跑 C |
| 18 | HearthTimelineFollowDeviceTest#scrollingBackInsideTheSecondConversationKeepsTheViewportStill | 通过 | 1447ms | 分批跑 C |
| 19 | HearthTimelineFollowDeviceTest#switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail | 通过 | 960ms | 分批跑 C |
| 20 | HearthTimelineFollowDeviceTest#newTokensAreFollowedWhileTheViewportIsAtTheBottom | 通过 | 820ms | 分批跑 C |
| 21 | com.xavierxia.mycelium.localmodels.LocalModelDeviceTest#loadStreamStopUnloadWithUserSelectedModel | 失败 | 586ms | 分批跑 B |
| 22 | com.xavierxia.mycelium.localmodels.LongGenerationDeviceTest#generate256Tokens | 失败 | 15ms | 分批跑 B |
| 23 | com.xavierxia.mycelium.roundtable.DeviceConversationTest#plaintextDialogueIsAbsentFromTheDatabaseFile | 通过 | 103ms | 分批跑 B |
| 24 | com.xavierxia.mycelium.roundtable.DeviceRetrievalTest#samplePackMatchesEveryRetrievalVector | 通过 | 19ms | 分批跑 B |
| 25 | com.xavierxia.mycelium.roundtable.DeviceRetrievalTest#boundQueriesReachTheSamplePack | 通过 | 4ms | 分批跑 B |

## 5 例失败与本次改动的关系

| 用例 | 失败信息（摘要） | 判断 |
| --- | --- | --- |
| #10 | 需要真机上有一个能用的本地模型 | 真机上只登记了一个 7.4GB 的外置 SAF 模型（`copied:false`），应用私有目录里没有模型文件，加载不了 → 环境问题 |
| #21 | 同上 | 环境问题 |
| #22 | 同上 | 环境问题 |
| #11 | `打开已有历史 B 以后必须跟到 B 的末尾：… total=1 …` | **测例自身假红**：`navigateTo` 之后没等重组/等帧，断言读到的是导航前那一帧（`total=1`、`lastSize=3952` 就是那条还没被换掉的流式长回答）。2026-10-19 补上等待后同一实现全绿，把判据改回旧语义则真红 → 见 `04e-follow-class-rerun.md` |
| #15 | `SocketException: Broken pipe`（`LocalHttp.serve`），随后 `Process crashed` | **不是它的断言失败**：`LocalHttp` 是 `net` 的测试假服务，这一例自己没有网络代码；单类复跑三次都过（`04a`/`04c`/`04d`）→ 环境/脚手架 |

这 5 例都不在本次改动涉及的文件里（本次只改了 `app/src/androidTest/.../hearth/` 下的 3 个文件）。作为
对照：被称为「修复前」的那次全量（`../hang-record/02-reproduced-full-run-b6dbc6c.txt`）跑的就是 b6dbc6c，
**已经包含第 5 条实现**，里面 **#11 是失败的**（现场与后来逐字节相同：`total=1`、`lastSize=3952`、
同一行 `:362`）、**#15 是通过的**（`CODE: 0`）。原先「#11/#15 修复前通过、修复后失败」的说法与归档
原文不符，已更正，详见 `04e-follow-class-rerun.md`。
