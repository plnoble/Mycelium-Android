# Per-case timings - 04c

Source: host-timestamped instrument log `run-C-final-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | passed | 1386ms |
| 2 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | passed | 1326ms |
| 3 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | passed | 1146ms |
| 4 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | passed | 775ms |
| 5 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | passed | 1085ms |
| 6 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#longAnswerScrolledBackInsideKeepsTheViewportStillWhileTokensArrive | passed | 883ms |
| 7 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#returningToTheBottomByHandResumesFollowing | passed | 1653ms |
| 8 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackInsideTheSecondConversationKeepsTheViewportStill | passed | 1279ms |
| 9 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail | passed | 904ms |
| 10 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#newTokensAreFollowedWhileTheViewportIsAtTheBottom | passed | 788ms |
