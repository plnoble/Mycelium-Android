# Per-case timings - 04g full suite after the test fix

Source: host-timestamped instrument log `run-E-fullsuite-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.DeviceHearthTest#plaintextCharacterAndDialogueAreAbsentFromTheDatabaseFile | passed | 254ms |
| 2 | com.xavierxia.mycelium.hearth.HearthCloudReconfirmDeviceTest#changingTheSavedAddressMakesTheNextSendAskAgain | passed | 4424ms |
| 3 | com.xavierxia.mycelium.hearth.HearthCloudStopDeviceTest#aStoppedCloudTurnNeverShowsOrStoresTheLateAnswer | passed | 8439ms |
| 4 | com.xavierxia.mycelium.hearth.HearthLocalStopDuringLoadDeviceTest#stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer | code=-4 | 5ms |
| 5 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened | passed | 7675ms |
| 6 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected | passed | 3550ms |
| 7 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere | passed | 3814ms |
| 8 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aConfirmationTheUserLeftBehindCannotSendOrWriteAnything | passed | 6115ms |
| 9 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage | passed | 1209ms |
| 10 | com.xavierxia.mycelium.hearth.HearthPromptSpeedDeviceTest#threeRunsWithAnAbout1500TokenPrompt | FAILED | 10ms |
| 11 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | passed | 1045ms |
| 12 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | passed | 1176ms |
| 13 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | passed | 1054ms |
| 14 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | passed | 762ms |
| 15 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | FAILED | 356ms |
