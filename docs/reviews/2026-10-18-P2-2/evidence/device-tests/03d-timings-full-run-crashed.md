# Per-case timings - full-suite run (crashed at case 15)

Source: host-timestamped instrument log `10-full-run-after-clear-crashed-at-test15-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.DeviceHearthTest#plaintextCharacterAndDialogueAreAbsentFromTheDatabaseFile | passed | 166ms |
| 2 | com.xavierxia.mycelium.hearth.HearthCloudReconfirmDeviceTest#changingTheSavedAddressMakesTheNextSendAskAgain | FAILED | 22155ms |
| 3 | com.xavierxia.mycelium.hearth.HearthCloudStopDeviceTest#aStoppedCloudTurnNeverShowsOrStoresTheLateAnswer | passed | 8101ms |
| 4 | com.xavierxia.mycelium.hearth.HearthLocalStopDuringLoadDeviceTest#stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer | code=-4 | 50ms |
| 5 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened | passed | 7583ms |
| 6 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected | passed | 3529ms |
| 7 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere | passed | 3803ms |
| 8 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aConfirmationTheUserLeftBehindCannotSendOrWriteAnything | passed | 6121ms |
| 9 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage | passed | 1111ms |
| 10 | com.xavierxia.mycelium.hearth.HearthPromptSpeedDeviceTest#threeRunsWithAnAbout1500TokenPrompt | FAILED | 18ms |
| 11 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | FAILED | 847ms |
| 12 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | passed | 1200ms |
| 13 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | passed | 1030ms |
| 14 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | passed | 738ms |
| 15 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | FAILED | 648ms |

说明（2026-10-19 更正）：第 11 行与第 15 行的 `FAILED` **不是**「缺本地模型」，也不是这两例的断言：

- 第 11 行：测例自己在导航后没等重组，断言读到的是导航前那一帧 → 假红（`total=1`、`lastSize=3952`）。
- 第 15 行：`net` 的测试假服务 `LocalHttp.serve` 抛 `SocketException: Broken pipe` 把进程带走，
  这一例的断言没有走到。单类复跑里它三次都通过。

依据与更正后的结论见 `04e-follow-class-rerun.md`。
