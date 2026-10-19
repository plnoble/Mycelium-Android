# Per-case timings - 04a

Source: host-timestamped instrument log `run-A-baseline-timed.txt` (timestamps added while streaming; the run's own output is otherwise unchanged).

| # | case | result | duration |
| --- | --- | --- | --- |
| 1 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail | FAILED | 1158ms |
| 2 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingConversationIdsWithinTheSameSegmentStillResets | passed | 1398ms |
| 3 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#openingAnotherConversationFollowsItsNewestMessageAgain | passed | 1170ms |
| 4 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#theFirstSavedAnswerInTheSameSegmentKeepsAnUpScrolledViewport | passed | 775ms |
| 5 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive | passed | 1094ms |
| 6 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#longAnswerScrolledBackInsideKeepsTheViewportStillWhileTokensArrive | passed | 852ms |
| 7 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#returningToTheBottomByHandResumesFollowing | passed | 1662ms |
| 8 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#scrollingBackInsideTheSecondConversationKeepsTheViewportStill | passed | 1333ms |
| 9 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail | passed | 912ms |
| 10 | com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest#newTokensAreFollowedWhileTheViewportIsAtTheBottom | passed | 818ms |
