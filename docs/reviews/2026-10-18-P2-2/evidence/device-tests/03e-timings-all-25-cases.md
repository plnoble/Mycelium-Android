# 全部 25 例的结果（逐例）

一列一例，`#` 是这个仓库里 androidTest 的固定执行顺序（与基线日志一致）。每一例的耗时取自「主机侧打了
时间戳的那份原始输出」里该例 `CODE: 1` 到结果行之间的差（`03*` 系列的表就是它）。

- 用例总数：**25**
- 通过：**19**
- 失败：**5**（`#10`、`#11`、`#15`、`#21`、`#22`，全部是需要真机上有一个**能用的本地模型**的用例）
- 跳过（JUnit assumption）：**1**（`#4`，真机上没有登记本地模型时自己跳过，不假装通过）
- 挂死：**0**（有界化之后，任何一次复跑都没有再出现「无输出地挂住」；主机侧还有一道 6 分钟硬上限兜底）
- 进程崩溃：**1 次**，每次都发生在同一处：`#15` 断言失败后 `net` 模块的测试假服务 `LocalHttp.serve` 往已经
  关闭的 socket 里写，非主线程未捕获 `SocketException: Broken pipe` 把进程带走（见 `hang-record/08`）。
  这是既有脚手架问题，不在本次改动范围内；它让「一次 invocation 跑完 25 例」变得不可能，所以下面的
  结果来自 3 次 invocation 的并集。

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
| 11 | HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | 失败 | 847ms | 全量跑（第 11 例） |
| 12 | HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | 通过 | 1200ms | 全量跑（第 12 例） |
| 13 | HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | 通过 | 1030ms | 全量跑（第 13 例） |
| 14 | HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | 通过 | 738ms | 全量跑（第 14 例） |
| 15 | HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | 失败（随后进程崩溃） | 648ms | 全量跑（第 15 例） |
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
| #11 | `打开已有历史 B 以后必须跟到 B 的末尾：… total=1 …` | 需要真的生成出回答才会「跟到末尾」；没有可用模型 → 环境问题（未改动这个类） |
| #15 | 同类视口/跟随断言失败，之后进程被 `Broken pipe` 带走 | 同上（未改动这个类） |

这 5 例都不在本次改动涉及的文件里（本次只改了 `app/src/androidTest/.../hearth/` 下的 3 个文件）。作为
对照：**修复前**在设备上跑全量的那次（`../hang-record/02-reproduced-full-run-b6dbc6c.txt`）里，#11 与 #15
是通过的——那次的 #4/#5/#9 失败，#10/#21/#22 也失败；#11/#15 的差别来自真机上的库/模型状态在多次复跑
之间被反复写的差异（那两次崩溃没有走清理路径），这一点列为不确定项，见 README 末尾。
