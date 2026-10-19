# 跟随类两例真机失败的定位（单类复跑 04a–04d，全量 04g）

这一份回答上一轮复跑留下的问题：`HearthTimelineFollowDeviceTest` 里
`openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail`（全量第 11 例）与
`scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive`（全量第 15 例）在真机上失败，
而且**被上一轮归因成「真机上没有能用的本地模型」**——这个类只渲染 `ChatTimeline` + Compose 状态，
不碰 ViewModel、不碰模型，「缺模型」不可能影响它。

原始输出是 04a–04d（四次单类复跑）和 04g（一次全量）；逐例耗时是同名的 `*-timings.md`。
本文件只做归纳与判定。

## 0. 判定

| 用例 | 上一轮的记录与归因 | 本次判定 | 依据 |
| --- | --- | --- | --- |
| `openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail`（#11） | 失败，归因「缺本地模型」 | **测试脆弱**：导航后没等重组，断言读到的是导航**之前**那一帧 → 假红 | 失败现场 `total=1`、`lastSize=3952` 是导航前那条流式长回答（导航后应是 41 项）；同一实现在补上等待后全绿（04c/04d/04g #11）；把判据改回旧语义它立刻真红（04b，`total=41`，见第 3 节） |
| `scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive`（#15） | 失败（随后进程崩溃），归因「缺本地模型」 | **环境/脚手架**：这不是它的断言失败，是别的类泄漏的 `LocalHttp` 线程把进程带走 | 该例在全量里的失败原文是 `SocketException: Broken pipe`（`net` 的测试假服务，04g #15），不是 AssertionError；单类复跑里它三次都过（04a #5 / 04c #5 / 04d #5） |

两例与本地模型无关；上一轮 README 与 `03e` 表里那两行「缺本地模型」的归因是错的，已改。

顺带更正上一轮 README 的「不确定项 2」——它写 #11/#15「修复前的对照全量（`hang-record/02`）里通过、
修复后失败」。这条也不成立：被称为「对照」的那次全量跑的是 b6dbc6c（**已经包含**第 5 条实现的代码），
里面 **#11 是失败的**，失败现场与后来逐字节相同（`total=1`、`lastSize=3952`、同一行 `:362`）；
**#15 在那次是通过的**（`CODE: 0`）。所以「修复前通过、修复后失败」这件事没有发生过（见 `hang-record/02`
第 241–260 行与第 308–316 行）。

## 1. 怎么跑的

设备 NX809J（Red Magic，Android 16，arm64）；构建、安装、运行与上一轮一致：工作树
`p2-2-hintfix`（`bce89e8` + 本次改稳的测例，提交 `f752d4b`；实现一行未改），`gradlew.bat --offline --console=plain
:app:assembleDebug :app:assembleDebugAndroidTest`，`adb install -r` 两个调试包，
`adb shell am instrument -w -r [-e class …] com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner`，
每轮开跑后 `adb shell am unfreeze --sticky`。只安装/运行 `com.xavierxia.mycelium.debug` 与
`com.xavierxia.mycelium.debug.test`；正式版（`versionCode=2` / `versionName=0.1.1` /
`lastUpdateTime=2026-10-10 05:23:04`）与 `spike` 没有卸载、没有覆盖、没有改动。

这几轮用的跑测器与守卫也一并归档了：`scripts/run-timestamped.ps1`（force-stop → sticky 解冻 →
流式读原始输出并加主机时间戳）和 `scripts/guards.ps1`（冻结检测 + 主机侧硬上限）。

| 运行 | 代码 | 结果 |
| --- | --- | --- |
| `04a` | 测例未改（工作树原样）+ 现有实现 | `Tests run: 10, Failures: 1` —— 复现 #11 |
| `04b` | 测例已改 + 判据改回旧语义（一行 mutation，第 3 节） | `Tests run: 10, Failures: 1` —— 判别力实证 |
| `04c` | 测例已改 + 实现还原（`git diff` 只剩测例文件） | `OK (10 tests)`，`Time: 11.234` |
| `04d` | 同 `04c` 重复一次 | `OK (10 tests)`，`Time: 11.278` |
| `04g` | 同 `04c`，跑全量（不带 `-e class`） | #1–#11 与 #12–#14 通过（**#11 通过，1045ms**），#15 跑到 356ms 时进程被 `Broken pipe` 带走，后面 10 例没跑 |

主机侧守卫（`scripts/guards.ps1`，2 分钟日志不增长就 force-stop）在四轮里都判
`DONE … run finished normally`：没有触发冻结器、没有 force-stop（`04f-guards-follow-class.txt`）。

## 2. 两例的失败原文

`04a`（测例未改，复现上一轮那一例）：

```
12:13:38.734 INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: <中文见下>first=0/691 visibleCount=1 total=1 lastIndex=0 lastOffset=-691 lastSize=3952 viewportEnd=2636 afterPadding=52 canScrollForward=true atBottom=false tailVisible=false
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.assertTrue(Assert.java:42)
	at com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest.openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail(HearthTimelineFollowDeviceTest.kt:362)
```

中文原文是「打开已有历史 B 以后必须跟到 B 的末尾」；归档文件里是 GBK 乱码，见第 5 节的编码说明。

`#15` 在上一轮全量与本次全量（`04g`）里的「失败」原文 —— 注意它不是断言失败：

```
INSTRUMENTATION_STATUS: stack=java.net.SocketException: Broken pipe
	at java.net.SocketOutputStream.socketWrite0(Native Method)
	at java.net.SocketOutputStream.socketWrite(SocketOutputStream.java:116)
	at java.net.SocketOutputStream.write(SocketOutputStream.java:144)
	at com.xavierxia.mycelium.net.LocalHttp.serve(LocalHttp.kt:69)
	at com.xavierxia.mycelium.net.LocalHttp.lambda$0$0(LocalHttp.kt:25)
	at com.xavierxia.mycelium.net.LocalHttp$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
	at java.lang.Thread.run(Thread.java:1119)

INSTRUMENTATION_STATUS: stream=
Process crashed while executing scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive(com.xavierxia.mycelium.hearth.HearthTimelineFollowDeviceTest):
```

## 3. 判定 A（#11）：测试脆弱 —— 断言读的是导航前那一帧

测例里 `navigateTo(...)`（模拟上层的 `navigate`：段序号 +1、换 ID、换消息）只写 Compose 状态，
紧接着 `assertTrue(tailVisible())` 直接读 `listState.layoutInfo`。Compose 测试规则要有帧、要等重组
才会有新布局，所以那条断言拿到的是**导航之前**的布局：

- `total=1`：旧列表只有那条流式长回答这一项（导航后应该是 `longHistory(20)` + 1 = 41 项）；
- `lastSize=3952`、`first=0/691`：正是用户上翻以后停在长回答里的位置。

也就是说，这一例当时根本没有检查到「打开 B 以后跟不跟」，它是一次**假红**——同一条断言在
任何实现下都会红一次，这就是它在上一轮每一次全量（`hang-record/02`、`06`、`09`、`10`）里
失败现场逐字节相同的原因。

修法（只动测例，`HearthTimelineFollowDeviceTest.kt`）：

1. `navigateTo` 在写完状态后 `settle()`（等重组 + 等自动滚动落定）；跟在它后面的四处显式
   `settle()` 因此删掉，避免同一个 helper 有两套约定。
2. 这一族里唯一写死像素的滑动换成按控件高度算的 `userScrollsBackAcrossTheScreen`
   （`top + height*0.15f` → `bottom - height*0.15f`），不再依赖「1500px 在 2636px 高的屏上不出界」。
   #15 用它，两次全绿（`04c` 1085ms、`04d` 1081ms）。

判别力实证（`04b`）：把 `TimelineFollowState.claim()` 里「段身份变了就重置」临时改回第 5 条之前的
判据（原 ID 为 null 的这一段被当成首次落库、不重置）：

```kotlin
if (previousSegment != conversationSegment) {
    if (previousId == null && conversationId != null) return   // = 旧判据
    reset(); return
}
```

测例已改的那一版立刻变红，而且这次的现场是**导航之后**的布局：

```
first=4/91 visibleCount=19 total=41 lastIndex=22 lastOffset=2609 lastSize=117 viewportEnd=2636 afterPadding=52 canScrollForward=true atBottom=false tailVisible=false
```

`total=41`、`lastIndex=22`、`atBottom=false`：B 的内容已经换上了，视口就是停在第 4 条、没跟到 B
的第 41 条末尾——这正是第 5 条要防的语义，所以改稳以后的测例仍然有判别力。把那一行删掉还原实现
（`git diff --stat` 只剩测例文件）之后，`04c`/`04d` 两轮全绿，`04g` 全量里 #11 也通过。

## 4. 判定 B（#15）：环境/脚手架

- 单类复跑三次都过：`04a` #5（1094ms）、`04c` #5（1085ms）、`04d` #5（1081ms），其中后两次用的是
  改稳后的滑动方式。
- 两次全量（上一轮 `hang-record/10`、本次 `04g`）里它的「失败」原文都是
  `SocketException: Broken pipe`，栈在 `com.xavierxia.mycelium.net.LocalHttp.serve`。这一例自己一行
  网络代码都没有；进程被带走时它才跑了 356–648ms，断言根本没走到。
- 崩的位置跟着「当时跑到哪一例」走（上一轮有卡在第 7 例、第 9 例、第 15 例的三种记录），是既有
  脚手架问题：`LocalHttp` 的假服务在请求线程里往已关闭的 socket 写，非主线程未捕获异常 → 进程死。
  修它要动 `net` 的 main 源集，超出「只改测试」的范围。

## 5. 归档文件与编码说明

| 文件 | 内容 |
| --- | --- |
| `04a-follow-class-baseline-timed.txt` / `04a-…-timings.md` | 测例未改时的单类复跑：10 例，1 失败（#11） |
| `04b-follow-class-old-claim-timed.txt` / `04b-…-timings.md` | 测例已改 + 旧判据 mutation：10 例，1 失败（#11，`total=41`），判别力实证 |
| `04c-follow-class-green-timed.txt` / `04c-…-timings.md` | 最终代码单类复跑：`OK (10 tests)`，`Time: 11.234` |
| `04d-follow-class-green-repeat-timed.txt` / `04d-…-timings.md` | 同 `04c` 重复一次：`OK (10 tests)`，`Time: 11.278` |
| `04g-full-suite-after-test-fix-timed.txt` / `04g-…-timings.md` | 修好以后的全量：跑到 #15 被 `Broken pipe` 带走；#1–#14 里只有 #10（本地模型）失败，#11 通过 |
| `04f-guards-follow-class.txt` | 上面几轮的主机侧守卫报告（全部 `DONE … run finished normally`） |
| `04e`（本文件） | 判定与依据 |

**编码说明**：`04*` 的原始日志里，中文断言信息是 GBK 乱码（`鎵撳紑宸叉湁鍘嗗彶…`）。这是主机侧
管道把 UTF-8 字节按 GBK 解出来的结果，和归档里既有的 `02`/`06`/`09`/`10` 是同一个现象；同一个
GBK→UTF-8 还原可把原文完整取回（本文件引用的中文就是还原后的原文）。已核实不是测例或装机问题：
反编译测试 APK 的 `classes4.dex`，`打开已有历史 B 以后必须跟到 B 的末尾` 的 UTF-8 字节序列在
里面（1 处），也就是设备拿到的断言信息本身是对的——只有主机侧归档文本受影响，ASCII、数字、
行结构（`INSTRUMENTATION_STATUS*`）一字未改。

## 6. 不确定项

1. 全量一次 invocation 仍然跑不完：崩在 `LocalHttp`（本次 `04g` 又在 #15 被带走，与前几次同因），
   这是既有脚手架问题，本次没有改 `net`。
2. `04g` 里 #2（`HearthCloudReconfirmDeviceTest#changingTheSavedAddressMakesTheNextSendAskAgain`）
   通过（4424ms），而 `hang-record/10` 的同一条是失败（22155ms）——云端假服务那一族在真机上有
   偶发性，与本次改动无关，本次没有追。
3. 主机侧仍用 `am unfreeze --sticky` 兜住系统的进程冻结器；本次四轮都没触发冻结（守卫没记
   `FREEZER`/`WATCHDOG`），但这条一次性设置仍然建议保留在跑测脚本里。
