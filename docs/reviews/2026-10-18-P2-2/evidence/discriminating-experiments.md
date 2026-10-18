# 第二轮整改「判别力实证」原始输出（复审第 2、3、4、7、8 条）

归档时间：2026-10-19 05:09–05:30（北京时间）。
归档人：本次整改执行者（Windows 侧子代理）。
用途：补齐 `DELIVERY.md` 3.5 里此前标「未归档、无法复核」的失败原文。**只补证据，不改源码、不改测试。**

## 0. 结论速览

| 复审条 | 关键守卫 | 改坏后变红的用例 | 失败首行（原始输出原文） |
|---|---|---|---|
| 2 | `HearthViewModel.openConversation` 在切换**开始时**同步固定目标并置 `historyLoading`；读取期间 `send`/`regenerate` 一律拒绝 | `R3Item2NavProbeTest.theTargetIsFixedAndThePageWaitsAsSoonAsTheSwitchStarts`、`R3Item2NavProbeTest.aSendWhileTheHistoryIsStillBeingReadIsRefused`（**本轮临时 JVM 复刻件**，见第 2 条） | `切换开始时就该把目标定到新的一段 expected:<[older]> but was:<[newest]>`；`历史还没就绪时不该开始任何一轮（notice） expected null, but was:<先在「我的 → 本地模型」导入一个模型，再和人物聊天>` |
| 3 | `ConversationLog.writeTurn`：`valid()` 固定在写入事务打开之后、写第一行之前 | `AbandonedWriteTest.aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored`（另 3 例同时红） | `作废的一轮不该被存下来 ==> expected: <null> but was: <5409d3ce-9a12-4757-a9b7-af33362d2870>`；`valid() 必须在写入事务内被问到`；`作废的重做不该算成功` |
| 4 | `CharacterStore.confirmCloudTarget`：在自己的写事务内判定 `valid()` | `CloudConfirmTargetTest.aConfirmationCancelledWhileTheWriteWaitsChangesNothing` | `取消之后的确认不该算数` |
| 7 | `LocalModelEngine.load`：native 加载照常成功返回后复检停止标记 | `LocalModelEngineTest.aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded` | `停止之后这次加载必须以失败收场，实际 null` |
| 8 | `HearthSpace.TimelineFollowState` 与手势协程同键（`LaunchedEffect(listState, follow)`） | `HearthTimelineFollowDeviceTest` 三例（真机） | **本轮没有取得判别力证据**：只有真机用例可跑，本轮不连设备。不是「改坏却不红」，是「没跑成」。详见第 6 条 |

**没有出现「改坏却不红」的情形**：实际跑到的第 2（临时复刻件）、3、4、7 条，改坏后对应用例都变红并已抓到原文；
第 8 条未运行（原因见第 6 条），因此对它既不能断言「有判别力」也不能断言「没有」。

## 1. 做法、环境与边界

- **一次性工作树**（做完即弃，不污染真分支）：
  `git -c core.longpaths=true worktree add D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence d5070e1e59e095bfb031045b234a91404d747e78 --detach`
  （`d5070e1` = 真分支 `wp/P2-2-hearth-chat` 当时的 HEAD，即交付报告所在提交）。
- 环境：`JAVA_HOME=D:\Development\Android-studio\jbr`、`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`、
  Gradle 9.5.0 **`--offline`**；同一时间只跑一个 Gradle。**没有连设备、没有推送、没有联网、没有改 main。**
- 每条的做法：**改坏实现 → 只跑相关测试类（`--tests` 收窄）→ 抓失败原文 → `git checkout -- <文件>` 还原 → 复绿**。
  变异状态**从未提交**；全部还原后一次性工作树 `git status --porcelain` 为空（0 改动、0 未跟踪文件）。
- 「失败原文」取自 Gradle 控制台输出与 JUnit XML 的 `<failure message=...>`（含失败位置 `类.kt:行`）。
  XML 是构建产物、未入库；抽取后的失败摘要与完整控制台输出已入库到 `evidence/r3-logs/`（清单见第 8 条）。
- **复跑命令**（工作树路径按需替换；`--offline` 必需）：
  ```powershell
  $env:JAVA_HOME='D:\Development\Android-studio\jbr'; $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
  .\gradlew.bat --offline --console=plain -p D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence :hearth:test --tests "*AbandonedWriteTest" --tests "*CloudConfirmTargetTest"
  .\gradlew.bat --offline --console=plain -p D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence :llm:testDebugUnitTest --tests "*LocalModelEngineTest"
  ```
- **本轮新增/改写的真机用例（androidTest）仍然没有归档运行输出**：本轮不连设备，`HearthNavigationDuringTurnDeviceTest`、
  `HearthLocalStopDuringLoadDeviceTest`、`HearthTimelineFollowDeviceTest` 一条都没有跑过。复跑命令见 `DELIVERY.md` 4.3/4.4。

## 2. 复审第 2 条：切历史期间目标固定 + 历史就绪前拒发

### 2.1 为什么这里用「临时 JVM 复刻件」

该条已入库的用例是**真机用例** `app/src/androidTest/.../HearthNavigationDuringTurnDeviceTest.kt`
的 `aQuestionSentWhileAnotherConversationIsStillLoadingIsRefusedAndNeverRedirected`
（方法从该文件第 250 行起；与判别力有关的那一段是第 304–318 行）。
本轮不连设备，且该用例要的 `HearthDatabase` 是 SQLCipher（.so 在 JVM 上装不起来），
**入库的那条真机用例本身本轮没有跑，也就没有它的失败原文**。

为了至少把「守卫本身有没有判别力」测出来，本轮在**一次性工作树里另写了一个等价断言的最小复刻件**
`app/src/test/java/com/xavierxia/mycelium/hearth/R3Item2NavProbeTest.kt`（Robolectric + sqlite-jdbc 会话 + 停读闸门），
把上面那条真机用例的**两条断言行**原样搬过来：

- 设备用例第 310 行 `assertEquals("切换开始时就该把目标定到新的一段", older, model.state.value.openConversation)`
- 设备用例第 311 行 `assertTrue("读取期间页面必须处于历史未就绪状态", model.state.value.historyLoading)`
- 外加设备用例第 317–318 行的两条断言——`assertEquals("历史还没就绪时一次都不该发出请求", 0, calls.get())`
  与 `assertEquals("历史还没就绪时目标不该被改回上一段对话", older, model.state.value.openConversation)`。
  真机那边「有没有发出去」数的是本地假 HTTP 服务的命中次数 `calls`，JVM 侧没有这个计数，
  复刻件换成 `state.notice`：复刻件里的人物是普通（本机）人物、模型登记又是空的，
  所以**只要真的开始了这一轮**，`startTurn` 就会写下
  `先在「我的 → 本地模型」导入一个模型，再和人物聊天` 这条 notice，assert 它为 null 即等价于「这一轮没开始」。

**这不是入库的用例**，只在一次性工作树里存在过，跑完即删（源码原文归档在 `evidence/r3-logs/item2-probe-source.kt`）。
它证明的是「这两条断言对这段守卫有判别力」，**不**等于「已入库的真机用例在本机跑红过」。

### 2.2 变异 1：把「切换开始就固定目标、置 historyLoading」和「读取期间拒发」一起改回旧写法

`app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt`：

```diff
     fun openConversation(id: String) {
         abandonTurn()
-        mutable.update { it.copy(openConversation = id, messages = emptyList(), droppedTurns = 0, historyLoading = true) }
+        // MUTATION (r3 evidence run): the target is only changed once the read has come back, and
+        // the page is not marked as still loading history.
+        mutable.update { it.copy(messages = emptyList(), droppedTurns = 0) }
         viewModelScope.launch {
@@
     fun send(text: String) {
-        if (mutable.value.historyLoading) return
+        // MUTATION (r3 evidence run): the "history not ready yet" refusal is gone.
         startTurn(text, mutable.value.mode, mutable.value.openConversation)
     }
```

命令与结果：

```
gradlew.bat --offline --console=plain -p D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence :app:testDebugUnitTest --tests "*R3Item2NavProbeTest"
退出码：1
R3Item2NavProbeTest > aSendWhileTheHistoryIsStillBeingReadIsRefused FAILED
    java.lang.AssertionError at R3Item2NavProbeTest.kt:157
R3Item2NavProbeTest > theTargetIsFixedAndThePageWaitsAsSoonAsTheSwitchStarts FAILED
    org.junit.ComparisonFailure at R3Item2NavProbeTest.kt:141
2 tests completed, 2 failed
```

失败原文（JUnit XML 的 `message`，已 HTML 反转义）：

```
FAIL  theTargetIsFixedAndThePageWaitsAsSoonAsTheSwitchStarts
  org.junit.ComparisonFailure: 切换开始时就该把目标定到新的一段 expected:<[065e5488-f045-4ffd-8f32-676e54eca21f]> but was:<[98573980-e66f-4202-a780-06c463843e2a]>
  at: ...R3Item2NavProbeTest.theTargetIsFixedAndThePageWaitsAsSoonAsTheSwitchStarts(R3Item2NavProbeTest.kt:141)
FAIL  aSendWhileTheHistoryIsStillBeingReadIsRefused
  java.lang.AssertionError: 历史还没就绪时不该开始任何一轮（notice） expected null, but was:<先在「我的 → 本地模型」导入一个模型，再和人物聊天>
  at: ...R3Item2NavProbeTest.aSendWhileTheHistoryIsStillBeingReadIsRefused(R3Item2NavProbeTest.kt:157)
```

（`[065e5488-…]` 是用户刚打开的旧对话 `older`，`[98573980-…]` 是页面正在离开的 `newest`：**改坏后目标留在了旧会话**，
正是复审说的「用户选了 B，新问题却发给并保存到 A」。）

### 2.3 变异 2：只去掉「历史就绪前拒发」，目标仍然固定（隔离验证两条断言各自的判别力）

```diff
     fun send(text: String) {
-        if (mutable.value.historyLoading) return
+        // MUTATION 2 (r3 evidence run): only the "history not ready yet" refusal is gone.
         startTurn(text, mutable.value.mode, mutable.value.openConversation)
     }
```

```
退出码：1
R3Item2NavProbeTest > aSendWhileTheHistoryIsStillBeingReadIsRefused FAILED
    java.lang.AssertionError at R3Item2NavProbeTest.kt:157
2 tests completed, 1 failed          ← theTargetIsFixedAndThePageWaitsAsSoonAsTheSwitchStarts 通过
```

即两条断言各自都能单独抓住对应的一半守卫：目标固定那一半看 `openConversation`，
拒发那一半看「有没有开始这一轮」。

### 2.4 还原与复绿

`git checkout -- app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt` 后
`git status --porcelain` 为空；随后同一份复刻件两次测试全绿（退出码 0，日志 `r3-logs/final-probe.txt`）。

## 3. 复审第 3 条：写事务内 `valid()`

变异（`hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/ConversationLog.kt`，把 `valid()` 挪到写事务之外）：

```diff
-private fun <T> HearthSession.writeTurn(valid: () -> Boolean, block: () -> T): T = transaction {
-    if (!valid()) throw AbandonedTurn()
-    block()
-}
+private fun <T> HearthSession.writeTurn(valid: () -> Boolean, block: () -> T): T {
+    // MUTATION (r3 evidence run): the check is asked before the write transaction opens.
+    if (!valid()) throw AbandonedTurn()
+    return transaction { block() }
+}
```

命令与结果：

```
gradlew.bat --offline --console=plain -p D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence :hearth:test --tests "*AbandonedWriteTest"
退出码：1
AbandonedWriteTest > redoingAnAbandonedAnswerLeavesTheOldOneAndItsTimeInPlace() FAILED
    org.opentest4j.AssertionFailedError at AbandonedWriteTest.kt:145
AbandonedWriteTest > aTurnThatIsStillWantedIsStoredWholeAfterTheSameWait() FAILED
    org.opentest4j.AssertionFailedError at AbandonedWriteTest.kt:98
AbandonedWriteTest > aRedoThatIsStillWantedReplacesOnlyTheAnswer() FAILED
    org.opentest4j.AssertionFailedError at AbandonedWriteTest.kt:198
AbandonedWriteTest > aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored() FAILED
    org.opentest4j.AssertionFailedError at AbandonedWriteTest.kt:52
5 tests completed, 4 failed
```

失败原文：

```
SUITE com.xavierxia.mycelium.hearth.AbandonedWriteTest  tests=5 skipped=0 failures=4 errors=0
FAIL  aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored()
  org.opentest4j.AssertionFailedError: 作废的一轮不该被存下来 ==> expected: <null> but was: <5409d3ce-9a12-4757-a9b7-af33362d2870>
  at: ...AbandonedWriteTest.aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored(AbandonedWriteTest.kt:52)
FAIL  aTurnThatIsStillWantedIsStoredWholeAfterTheSameWait()
  org.opentest4j.AssertionFailedError: valid() 必须在写入事务内被问到
  at: ...AbandonedWriteTest.aTurnThatIsStillWantedIsStoredWholeAfterTheSameWait(AbandonedWriteTest.kt:98)
FAIL  aRedoThatIsStillWantedReplacesOnlyTheAnswer()
  org.opentest4j.AssertionFailedError: valid() 必须在写入事务内被问到
  at: ...AbandonedWriteTest.aRedoThatIsStillWantedReplacesOnlyTheAnswer(AbandonedWriteTest.kt:198)
FAIL  redoingAnAbandonedAnswerLeavesTheOldOneAndItsTimeInPlace()
  org.opentest4j.AssertionFailedError: 作废的重做不该算成功
  at: ...AbandonedWriteTest.redoingAnAbandonedAnswerLeavesTheOldOneAndItsTimeInPlace(AbandonedWriteTest.kt:145)
PASS  aTurnCommittedBeforeTheCallReturnsIsKept()
```

要点：**「作废的一轮不该被存下来」正是复审第 3 条要的那条**——`valid()` 离开写事务后，
夹具的 `beforeWrite` latch 再也没有落在写入事务上，作废的一轮被真的存进了库（`expected: <null> but was: <会话 id>`）。
`aTurnCommittedBeforeTheCallReturnsIsKept` 仍然通过，符合「提交之后就以提交为准」。

还原：`git checkout -- hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/ConversationLog.kt`；
复绿证据见第 4 节同一轮运行里的 `AbandonedWriteTest tests=5 failures=0`。

## 4. 复审第 4 条：`confirmCloudTarget` 的事务内判定

变异（`hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/CharacterStore.kt`，改成不判有效性）：

```diff
     ): Boolean = withSession(connect) { session ->
         session.transaction {
-            if (!valid()) return@transaction false
+            // MUTATION (r3 evidence run): the validity check is gone; the write always happens.
             if (session.query("SELECT id FROM characters WHERE id = ?", listOf(id)).isEmpty()) {
                 return@transaction false
             }
```

命令与结果（同一轮里也复绿了第 3 条）：

```
gradlew.bat --offline --console=plain -p D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence :hearth:test --tests "*AbandonedWriteTest" --tests "*CloudConfirmTargetTest"
退出码：1
CloudConfirmTargetTest > aConfirmationCancelledWhileTheWriteWaitsChangesNothing() FAILED
    org.opentest4j.AssertionFailedError at CloudConfirmTargetTest.kt:51
9 tests completed, 1 failed
```

失败原文：

```
SUITE com.xavierxia.mycelium.hearth.AbandonedWriteTest  tests=5 skipped=0 failures=0 errors=0   ← 第 3 条已还原，复绿
PASS  aTurnAbandonedWhileTheWriteLockIsHeldIsNotStored()
PASS  aTurnThatIsStillWantedIsStoredWholeAfterTheSameWait()
PASS  aRedoThatIsStillWantedReplacesOnlyTheAnswer()
PASS  redoingAnAbandonedAnswerLeavesTheOldOneAndItsTimeInPlace()
PASS  aTurnCommittedBeforeTheCallReturnsIsKept()
SUITE com.xavierxia.mycelium.hearth.CloudConfirmTargetTest  tests=4 skipped=0 failures=1 errors=0
FAIL  aConfirmationCancelledWhileTheWriteWaitsChangesNothing()
  org.opentest4j.AssertionFailedError: 取消之后的确认不该算数
  at: ...CloudConfirmTargetTest.aConfirmationCancelledWhileTheWriteWaitsChangesNothing(CloudConfirmTargetTest.kt:51)
PASS  confirmingACharacterThatIsGoneWritesNothing()
PASS  aNullTargetRecordsNoAgreementAtAll()
PASS  aConfirmationOnlyTouchesTheConfirmColumnAndTheTimestamp()
```

还原：`git checkout -- hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/CharacterStore.kt`；本轮 4 例全绿。

## 5. 复审第 7 条：加载返回后先问停止意图

变异（`llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt`，去掉加载成功返回后的复检）：

```diff
                     native.load(descriptor!!.fd, options.threads, options.contextLength)
-                    check(!stopRequested.get()) { "Loading stopped" }
+                    // MUTATION (r3 evidence run): the stop is not re-checked after a successful load.
                     loaded.value = model
```

（`check(...)` 上面原本还有 4 行解释性注释，变异时原样保留，这里省略未贴。）

命令与结果：

```
gradlew.bat --offline --console=plain -p D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence :llm:testDebugUnitTest --tests "*LocalModelEngineTest"
退出码：1
LocalModelEngineTest > aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded FAILED
    java.lang.AssertionError at LocalModelEngineTest.kt:83
6 tests completed, 1 failed
```

失败原文：

```
SUITE com.xavierxia.mycelium.llm.LocalModelEngineTest  tests=6 skipped=0 failures=1 errors=0
FAIL  aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded
  java.lang.AssertionError: 停止之后这次加载必须以失败收场，实际 null
  at: ...LocalModelEngineTest$aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded$1.invokeSuspend(LocalModelEngineTest.kt:98)
PASS  streamsOrderedBodyAndClosesReplacedDescriptor
PASS  explicitStopEndsGenerationWithoutCancellingCollector
PASS  aNativeLoadingStoppedFailureReleasesTheModelAndTheFile
PASS  collectorCancellationAbortsJniBeforeUnload
PASS  cancellationDuringLoadReleasesTheOnlyModelAndFile
```

还原：`git checkout -- llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt`；本轮 6 例全绿。

（位置口径：JUnit XML 的栈里同时有断言所在的挂起 lambda 帧 `...$1.invokeSuspend(LocalModelEngineTest.kt:98)`
与测试方法帧 `...aStopThatLandsDuringASuccessfulLoadLeavesNothingLoaded(LocalModelEngineTest.kt:83)`；
Gradle 控制台那行打印的是后者，两条都来自同一份原始输出。）

**边界（如实记）**：本轮的 JVM 用例只覆盖 `LocalModelEngine` 那一半（native 加载照常成功 → 必须不留已加载模型）。
复审第 7 条的另一半——`HearthViewModel.stream` 本地分支在加载返回、开始生成之前再问一次 `turn.userStopped`——
**只有真机用例** `HearthLocalStopDuringLoadDeviceTest.stoppingWhileTheLocalModelLoadsNeverStoresAnAnswer`（且需要已登记模型，否则 `assume` 跳过），
本轮**没有跑、也没有它的失败原文**。

## 6. 复审第 8 条：跟随状态与手势协程同键 —— 本轮没有取得判别力证据

**没跑成，不是「改坏却不红」。** 具体原因：

1. 该条入库的用例只有真机三例（`app/src/androidTest/.../HearthTimelineFollowDeviceTest.kt`：
   `scrollingBackInsideTheSecondConversationKeepsTheViewportStill`、`theFirstSavedAnswerKeepsAnUpScrolledViewport`、
   `switchingToAnotherConversationWithTheSameMessageCountFollowsTheNewTail`）；本轮不连设备、不推送，跑不了。
2. JVM 侧没有对应测例可跑：`TimelineFollowState` 是 `HearthSpace.kt` 里的**文件私有类**（`private class`），
   :app 的 JVM 单测够不着；判别点又是 Compose 的 `LaunchedEffect(listState)` 键（去掉 `follow` 键后手势协程仍改旧对象），
   要复刻得在 Robolectric 里驱动 LazyColumn 拖拽手势，本轮没有做这一步。
3. 因此：**第 8 条本轮既没有「改坏→变红」的成功证据，也没有「改坏却不红」的反证**；它的判别力仍属未复核。
   要补的话，最省事的是在真机上跑上面三例（`DELIVERY.md` 4.3/4.4 记的 instrumented 复跑命令；注意 4.4 那张表
   是 `3bfbdc3` 之前跑的那一轮，这三例是其后新增的），或另写一个能驱动
   `ChatTimeline(listState = …)` 滚动的 Robolectric 用例。

## 7. 还原后的复绿证据（`evidence/r3-logs/`）

| 日志 | 内容 | 退出码 |
|---|---|---|
| `final-hearth.txt` | `:hearth:test --tests "*AbandonedWriteTest" --tests "*CloudConfirmTargetTest"` 还原后 9 例 0 失败 | 0 |
| `final-llm.txt` | `:llm:testDebugUnitTest --tests "*LocalModelEngineTest"` 还原后 6 例 0 失败 | 0 |
| `final-probe.txt` | `:app:testDebugUnitTest --tests "*R3Item2NavProbeTest"` 还原后 2 例 0 失败（该复刻件随后已从一次性工作树删除） | 0 |

一次性工作树结束时 `git status --porcelain` 为空（0 改动、0 未跟踪文件），变异与临时用例都没有提交。

## 8. 原始日志清单（`evidence/r3-logs/`）

| 文件 | 说明 |
|---|---|
| `item2-probe-source.kt` | 第 2 条临时 JVM 复刻件的**完整源码**（一次性工作树里跑完即删，未入库到源码树） |
| `item2-probe-baseline.txt` | 复刻件在**未改坏**时全绿（证明它本身不是恒红的空壳） |
| `item2-probe-mutated.txt` / `item2-probe-mutated-failures.txt` | 变异 1（目标不固定 + 不拒发）控制台输出 / 失败摘要 |
| `item2-probe-mutated2.txt` / `item2-probe-mutated2-failures.txt` | 变异 2（只去掉拒发）控制台输出 / 失败摘要 |
| `item3-mutated.txt` / `item3-mutated-failures.txt` | 第 3 条变异控制台输出（含 `4 failed`）/ 失败摘要 |
| `item4-mutated.txt` / `item4-mutated-failures.txt` | 第 4 条变异控制台输出（含 `1 failed`）/ 失败摘要 |
| `item7-mutated.txt` / `item7-mutated-failures.txt` | 第 7 条变异控制台输出（含 `1 failed`）/ 失败摘要 |
| `final-hearth.txt`、`final-llm.txt`、`final-probe.txt` | 还原后的复绿输出 |

说明：JUnit XML 是构建产物（`*/build/test-results/...`），随一次性工作树一起丢弃，未入库；
上表的「失败摘要」就是把这些 XML 的 `<failure message=...>` 与失败位置逐条抽取出来的文本。

**编码**：`r3-logs/` 里的控制台日志原本是 PowerShell 5.1 `Tee-Object` 默认写出的 **UTF-16LE（带 BOM）**，
入库前逐份转成 **UTF-8（无 BOM）**，只为让 `git diff` / `git show` 能当文本读；**文字内容逐字未改**
（失败摘要与 `.kt` 本来就是 UTF-8）。原 UTF-16 文件留在一次性工作树一侧的工作目录
`D:\Agent\Worktrees\Mycelium-Android\p2-2-evidence-logs\`，不入库。
另外这些日志在 git 里按本仓库的换行归一化存成 LF（工作区是 CRLF），这是仓库既有规则，不是内容改动。

**没有复制 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\evidence-r3\*.txt`**：本轮对
`p2-2-work`（含 `evidence-r3`）逐文件搜过 `作废的一轮不该被存下来` / `取消之后的确认不该算数` /
`valid() 必须在写入事务内被问到` / `停止之后这次加载必须以失败收场` / `历史还没就绪时一次都不该发出请求` /
`切换开始时就该把目标定到新的一段`，**没有任何命中**（唯一命中是 `guard-demo` 下一份被复制的测试源码里的断言字面量），
即那批日志里确实没有这五条的失败原文；它们与本轮五条判别力无关，故不批量入库。

## 9. 入库前的敏感字段排查

对 `evidence/r3-logs/` 全部 15 个文件做了正则扫描，结论是**可以入库**：

- 没有本机用户名（`<本机账户名>` 等）、没有 `C:\Users`、没有邮箱；
- 没有设备序列号/型号串（`<设备型号>` 等未出现）、没有 `android_id`/IMEI；
- 没有密钥、token、`apiKey` 值（第 2 条复刻件里只出现**测试用的假人物名「老周」**与类名 `xavierxia`，
  后者是包名 `com.xavierxia.mycelium` 的一部分，不是本机用户名）；
- 路径只有 `D:\Agent\Worktrees\...`、`D:\Development\Android-studio\jbr` 这类工程路径，不含用户名；
- **JUnit XML 里的 `hostname="XAVIER"` 未被复制**（XML 全部未入库），故机器名不入库。

## 10. 与 `DELIVERY.md` 3.5 的对应关系

- 第 2 条那一行：设备输出仍未归档；但其两条断言的判别力原文，已由本文件第 2 节（含临时 JVM 复刻件源码）补上。
- 第 3 条那一行：此前「那次失败的终端原文没有归档，无法复核」→ 本文件第 3 节。
- 第 4 条那一行：此前只写「设备输出本轮未归档」→ JVM 用例的判别力原文见本文件第 4 节（设备输出仍未归档）。
- 第 7 条那一行：JVM 用例的判别力原文见本文件第 5 节（真机那半仍未归档）。
- 第 8 条那一行：本文件第 6 节，如实记为「本轮没有取得判别力证据」。