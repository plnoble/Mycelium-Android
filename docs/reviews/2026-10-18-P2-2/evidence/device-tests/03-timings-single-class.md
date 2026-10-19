# Per-case timings - HearthNavigationDuringTurnDeviceTest (single class)

Source: host-timestamped instrument log `01-single-class-HearthNavigationDuringTurnDeviceTest-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aLateRefreshAfterAStoredTurnNeverOverwritesTheConversationTheUserOpened | passed | 7612ms |
| 2 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected | passed | 3402ms |
| 3 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere | passed | 3856ms |
| 4 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aConfirmationTheUserLeftBehindCannotSendOrWriteAnything | passed | 6106ms |
| 5 | com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage | passed | 1133ms |
