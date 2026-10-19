# Per-case timings - 04d

Source: host-timestamped instrument log `run-D-repeat-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | passed | 1437ms |
| 2 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | passed | 1356ms |
| 3 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | passed | 1117ms |
| 4 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | passed | 754ms |
| 5 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | passed | 1081ms |
| 6 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#longAnswerScrolledBackInsideKeepsTheViewportStillWhileTokensArrive | passed | 865ms |
| 7 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#returningToTheBottomByHandResumesFollowing | passed | 1658ms |
| 8 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackInsideTheSecondConversationKeepsTheViewportStill | passed | 1298ms |
| 9 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail | passed | 901ms |
| 10 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#newTokensAreFollowedWhileTheViewportIsAtTheBottom | passed | 799ms |
