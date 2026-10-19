# Per-case timings - split run B (all classes except HearthTimelineFollowDeviceTest)

Source: host-timestamped instrument log `02b-remaining-classes-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.HearthPromptSpeedDeviceTest#threeRunsWithAnAbout1500TokenPrompt | FAILED | 10ms |
| 2 | com.xavierxia.mycelium.hearth.HearthCloudReconfirmDeviceTest#changingTheSavedAddressMakesTheNextSendAskAgain | passed | 4375ms |
| 3 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened | passed | 7612ms |
| 4 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected | passed | 3581ms |
| 5 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere | passed | 3594ms |
| 6 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aConfirmationTheUserLeftBehindCannotSendOrWriteAnything | passed | 6119ms |
| 7 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage | passed | 1133ms |
| 8 | com.xavierxia.mycelium.hearth.DeviceHearthTest#plaintextCharacterAndDialogueAreAbsentFromTheDatabaseFile | passed | 211ms |
| 9 | com.xavierxia.mycelium.hearth.HearthCloudStopDeviceTest#aStoppedCloudTurnNeverShowsOrStoresTheLateAnswer | passed | 8105ms |
| 10 | com.xavierxia.mycelium.roundtable.DeviceConversationTest#plaintextDialogueIsAbsentFromTheDatabaseFile | passed | 103ms |
| 11 | com.xavierxia.mycelium.localmodels.LongGenerationDeviceTest#generate256Tokens | FAILED | 15ms |
| 12 | com.xavierxia.mycelium.hearth.HearthLocalStopDuringLoadDeviceTest#stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer | code=-4 | 28ms |
| 13 | com.xavierxia.mycelium.localmodels.LocalModelDeviceTest#loadStreamStopUnloadWithUserSelectedModel | FAILED | 586ms |
| 14 | com.xavierxia.mycelium.roundtable.DeviceRetrievalTest#samplePackMatchesEveryRetrievalVector | passed | 19ms |
| 15 | com.xavierxia.mycelium.roundtable.DeviceRetrievalTest#boundQueriesReachTheSamplePack | passed | 4ms |
