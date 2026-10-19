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
