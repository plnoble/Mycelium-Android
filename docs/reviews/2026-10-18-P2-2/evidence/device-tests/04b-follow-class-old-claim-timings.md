# Per-case timings - 04b

Source: host-timestamped instrument log `run-B-mutated-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | FAILED | 1199ms |
| 2 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | passed | 1383ms |
| 3 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | passed | 1110ms |
| 4 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | passed | 777ms |
| 5 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | passed | 1078ms |
| 6 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#longAnswerScrolledBackInsideKeepsTheViewportStillWhileTokensArrive | passed | 859ms |
| 7 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#returningToTheBottomByHandResumesFollowing | passed | 1642ms |
| 8 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackInsideTheSecondConversationKeepsTheViewportStill | passed | 1271ms |
| 9 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail | passed | 899ms |
| 10 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#newTokensAreFollowedWhileTheViewportIsAtTheBottom | passed | 805ms |
