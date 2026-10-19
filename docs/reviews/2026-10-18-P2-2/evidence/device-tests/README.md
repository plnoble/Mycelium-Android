# P2-2 真机 androidTest 证据（有界化复跑）

这一目录是「把导航那一族设备测例改成有界、并在真机上复跑 + 归档原始输出」的原始证据。所有 `.txt` 都是
`adb shell am instrument -w -r` 的原始输出（JUnit 的 `INSTRUMENTATION_STATUS` 块一字未改），只在**每行前面
加了一个主机侧到毫秒的时间戳**，逐例耗时就是从这个时间戳算出来的（见第 3 节）。`03*` 的表是同目录下
`*-timed.txt` 的派生结果；去掉时间戳的版本是同名的 `*.txt`。

## 0. 结论速览

| 运行 | 结果 | 挂死 |
| --- | --- | --- |
| 单类复跑 `HearthNavigationDuringTurnDeviceTest`（原挂死的那一例所在类） | **OK (5 tests)**，`Time: 22.187`，0 失败 | 无 |
| 全量 25 例（一次 invocation） | 跑到第 15 例时**进程被既有脚手架问题带走**（`LocalHttp` 的 `Broken pipe`），25 例没跑完 | 无（不是挂死） |
| 分批跑 B（除 `HearthTimelineFollowDeviceTest` 外的 10 个类，15 例） | `Tests run: 15, Failures: 3` | 无 |
| 分批跑 C（`HearthTimelineFollowDeviceTest` 剩下 5 例） | **OK (5 tests)** | 无 |
| 25 例并集 | **通过 19 / 失败 5 / 跳过 1 / 挂死 0**（逐例见 `03e-timings-all-25-cases.md`） | 无 |

**2026-10-19 追加（`04*`，单类复跑 + 一次全量）**：上一轮把 `HearthTimelineFollowDeviceTest` 的两例失败
归因成「缺本地模型」，那是错的（这个类不碰 ViewModel、不碰模型）。单类复跑定位结果：

| 用例 | 判定 | 结果 |
| --- | --- | --- |
| `#11 openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail` | **测试脆弱**（导航后没等重组，断言读到导航前那一帧 → 假红） | 改稳测例后单类两轮 `OK (10 tests)`（`04c`/`04d`），全量里也通过（`04g`） |
| `#15 scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive` | **环境/脚手架**（`LocalHttp` 的 `Broken pipe` 把进程带走，不是它的断言） | 单类复跑三次都通过（`04a`/`04c`/`04d`） |

细节、判别力实证（把判据改回旧语义 → 立刻真红）与更正说明见 `04e-follow-class-rerun.md`。

**原挂死用例**：`com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest#aConfirmationTheUserLeftBehindCannotSendOrWriteAnything`
—— 单类复跑与分批跑 B 里都是**通过**（6119–6121ms），并且它仍然在证明「用户离开之后，这个确认既不会发
出请求、也不会写库」（断言一条没删，见第 5 节）。

## 1. 设备、时间、约束

- 设备型号：**NX809J**（Red Magic，Android 16，arm64）。**设备序列号不记录**（本目录所有文件都扫过一遍，
  没有序列号、没有本机用户名、没有密钥）。
- 时间：2026-10-19 11:36 – 12:03（Asia/Shanghai）。
- 只安装/运行 `com.xavierxia.mycelium.debug` 与 `com.xavierxia.mycelium.debug.test`。
  `com.xavierxia.mycelium`（正式版）与 `com.xavierxia.mycelium.spike` 没有卸载、没有覆盖、没有改动。
- 正式版包状态（开工前与收工后各查一次，完全一致）：
  `versionCode=2`、`versionName=0.1.1`、`lastUpdateTime=2026-10-10 05:23:04`。
- 没有推送、没有改 main、没有联网下载（构建用 `--offline`）、没有提交 APK / 密钥 / 本机用户名 / 设备序列号。

## 2. 怎么跑出来的（命令）

构建（工作树 `p2-2-hintfix`，分支 `wp/P2-2-hearth-chat`，基线 `b6dbc6c`；离线）：

```powershell
$env:JAVA_HOME='D:\Development\Android-studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

构建输出留在 `04-build-final.txt`（`BUILD SUCCESSFUL`）。

跑（**不要**用 `:app:connectedDebugAndroidTest`：离线缺 UTP 插件，会失败）：

```powershell
adb shell am force-stop com.xavierxia.mycelium.debug
adb shell am force-stop com.xavierxia.mycelium.debug.test
# 单类
adb shell am instrument -w -r -e class com.xavierxia.mycelium.hearth.HearthNavigationDuringTurnDeviceTest `
  com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner
# 全量（不带 -e class）
adb shell am instrument -w -r com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**退出码怎么读**（这一点容易误读）：`am instrument -w` 的输出里最后一行

- `INSTRUMENTATION_CODE: -1` = 这一轮**跑完了**（跟用例过不过没关系）；宿主 `adb` 退出码 `0`。
- `INSTRUMENTATION_CODE: 0` + `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` = 进程被带走，后面的用例没跑。
- 判定看过不过看最后一行 `OK (N tests)` 或 `FAILURES!!! Tests run: N, Failures: M`。

**时间戳**：`am instrument -w -r` 不打印每例耗时。每次复跑都用主机侧流式读取原始输出、给每一行加
`HH:mm:ss.fff` 前缀（`scripts/extract-timings.ps1` 前缀能还原原始文本）。逐例耗时 = 该例
`INSTRUMENTATION_STATUS_CODE: 1`（开始）到它的 `0 / -2 / -4`（结束）之间的差。

## 3. 挂死根因：不是用例，是**系统把测试进程冻住了**

结论：那一次「16 分钟没有任何输出、两个进程都活着」不是死锁，也不是等不到 UI，而是**Android 的进程冻结器
把跑 instrumentation 的进程整个挂起了**（Red Magic / Nubia 的省电策略会比较激进地冻结后台应用）。

证据链（都在 `hang-record/`）：

1. `01-user-run-16min-hang-b6dbc6c.txt`：用户那次全量跑，最后一例
   `aConfirmationTheUserLeftBehindCannotSendOrWriteAnything` 只有「开始」没有「结束」，之后 16 分钟没有新输出。
2. `02-reproduced-full-run-b6dbc6c.txt`：本次用**修复前**的 APK 复现，同样卡死，只是卡在第 9 例
   `switchingConversationOrModeWhileGeneratingStopsTheTurnAndFreesThePage`——说明**挂哪一例是随机的**，
   跟那一例的代码无关。
3. `03-frozen-process-evidence.txt`：卡住时抓的现场。
   - `dumpsys activity processes`：`mInstr=ActiveInstrumentation{…AndroidJUnitRunner} 1 procs`，
     `mTargetProcesses=[com.xavierxia.mycelium.debug]`，**`isFrozen=true`**（两个进程都是）。
   - `run-as com.xavierxia.mycelium.debug ps -T -p <pid> -o PID,TID,STAT,WCHAN`：该进程**每一个线程**的
     `WCHAN` 都是 **`do_freezer_trap`**（内核侧冻结器把线程停在陷阱里）。
   - 决定性实验：`adb shell am unfreeze com.xavierxia.mycelium.debug` 之后**几秒内**这一轮就继续跑完，
     最后 `Tests run: 25, Failures: 7`。冻住的不是代码，是进程。
4. 因为进程被冻住，**进程内任何超时都不会到点**（看门狗线程自己也冻住了）——这是关键结论：真正的硬上限
   必须在**主机侧**。

## 4. 有界性是怎么保证的（三层）

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 用例内的等待 | `HearthNavigationDuringTurnDeviceTest.kt` 的 `waitUntil` / `expectNothingFor`、`GatedHearthSessions.kt` 的 `HearthStageHold.await` / `awaitCallFinished` | 每个等待都有明确超时；超时失败信息里带上**页面状态**和**全部线程栈**；闸门到点自己兜底放行 |
| 用例级硬上限 | `BoundedDeviceTest.kt`（4 分钟，`@get:Rule`） | 用例超时 → 打印全部线程栈 → 判失败，后面的用例照跑；进程被系统冻住时它也没用（线程一起冻住），这一层只防「用例自己的等待没边界」 |
| 主机侧硬上限 | `scripts/guards.ps1`（默认 3 分钟无增长就 force-stop 并记录停在哪一例） | 防的正是系统冻结：没有日志增长就按探针结果分类、如实记录并 force-stop |

每次 instrument 都用主机侧守卫跑；守卫的报告在 `guards.txt`（`WATCHDOG` 记录，以及 `RUN`/`STICKY` 行）。
注意：`RUN`/`STICKY` 那几行**不是仓库里任何脚本写的**——`guards.ps1` 只写 `FREEZER`/`WATCHDOG`/`DONE`，
`run-timestamped.ps1` 的解冻调用是 `Out-Null` 不落日志，它们来自当时未归档的主机包装命令。本次唯一一次主机侧
判定卡住是**修复后、还没开 sticky 解冻**的那一轮：第 7 例
`comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere` 3 分钟无增长被 force-stop（`hang-record/04`、
`05`）。此后每轮开跑后都对调试包做一次 `am unfreeze --sticky`，单类、分批 B、分批 C 都跑完、无卡住。

### 4.1 冻结守卫脚本：2026-10-19 的修正与验证到哪一步

第四次复审（`REVIEW-r4.md` 必修 4）与第五次复审（`REVIEW-r5.md` 第 12 行、必修 3）都指出：归档的
`guards.ps1` 不能按声明工作。本轮改了三处（第六次复审 r6 的两条「必须修改」后，前两处又各自补齐了一处）：

1. **进程号**：原来给 `$pid` 赋值——PowerShell 不区分大小写，`$PID` 是只读自动变量，赋值拿不到目标进程号
   （取到的是宿主 PowerShell 自己的 PID），后面的 `ps -p` 也就查了个不存在的号。目标进程号现在从
   `Select-TargetPid` 的返回值 `$sel.Pid` 取（`guards.ps1` 里**没有** `$testPid` 这个变量，`-p $sel.Pid` 才
   是维护者该看的地方），`pidof` 的「进程不在」与「adb 报错」两条分支分开；`$sel.Pid` 同时写进报告行。
2. **WCHAN 解析**：原来那条 `ps -o WCHAN` 只打印一列，而代码要求行首是数字，正常输出一行都匹配不上，
   于是静默当成「没冻结」——守卫形同虚设。现在改成 `ps -T -p $sel.Pid -o PID,TID,STAT,WCHAN`，**按表头列位解析**
   （对齐真机摘录的四列形状，见 `hang-record/03` 第 165–170 行），并且**每行列数必须与表头完全一致**：
   少列（`WCHAN` 整格缺失）、多列、`WCHAN` 为空或为 `-`/`?`、`PID` 格不是数字、没有线程行、`ps`/`adb` 报错，
   **一律**判 `PROBE-UNKNOWN` 并写进报告，**绝不**折成「没冻结」。
3. **退出分类**：原来只要出现任意 `INSTRUMENTATION_CODE` 就写 `run finished normally`（连 `CODE: 0` +
   `shortMsg=Process crashed.` 也这么记）。现在按语义分开。另外，**解析前统一剥离每行行首的主机时间戳**
   （`run-timestamped.ps1` 写出的日志每行都是 `HH:mm:ss.fff` 开头；正则 `^\d{1,2}(:\d{2}){1,3}(\.\d{1,3})?\s+`
   只吃行首这一段，行内其它内容不动）：r6 指出不剥离时真实归档 `04c`/`04d` 的成功终止行读不到、整份日志被
   误判成 `RUNNING`，本次已修并接进回归（场景 `12-done-timestamped`）。

| 退出码 | 判定 | 依据 |
| --- | --- | --- |
| 0 `DONE` | 跑完 | 日志出现 `INSTRUMENTATION_CODE: -1`（跑完即止，用例过不过看末尾那几行） |
| 2 `CRASH` | 崩溃 | 终止码非 -1（如 `CODE: 0` + `shortMsg=Process crashed.`）或出现崩溃标记 |
| 3 `FREEZE_TIMEOUT` | 冻结超时 | 日志停涨到点，且探针确认全线程 `do_freezer_trap` |
| 4 `STALL` | 停涨（非冻结） | 日志停涨到点，探针读得出来且不是冻结 |
| 5 `UNKNOWN` | 未知 | 探针不可用/解析失败、进程不在又无终止标记、终止码无法归类、总时限到点仍无终止标记 |

**验证方式（只到文本级）**：把真机摘录的 `dumpsys`/`ps -T`/`am instrument` 文本做成 `guards-samples/` 下的夹具，
用 `scripts/guards-selftest.ps1` 以 `-ReplayDir` 驱动**同一个** `guards.ps1`（确定性时钟、不睡眠、不连 adb），
逐场景断言退出码、必须出现与绝不能出现的报告行、以及本该发出的 `am unfreeze`/`am force-stop` 动作；另有第 2 阶段
用夹具 adb（`guards-samples/fake-adb.ps1`，固定回「没有设备」）把**真的 live 循环**跑几秒，证明 adb 采集 → 探针 →
报告 → 总时限这条通路没有被 replay 绕开，且全程不碰真机。本轮实跑（Windows PowerShell 5.1.26100.9711）：
**15 个 replay 场景 + 1 个 live 冒烟全过**（脚本按 `scenarios/*.json` 实际个数统计，摘要行不会再与实际脱节），
原始输出 `guards-samples/selftest-output.txt`；每个夹具是真机摘录、派生还是构造，以及**每个形状由哪个场景真跑到**，
见 `guards-samples/README.md` 第 1、4 节（该表就是覆盖声明本身，清单里没接场景的夹具不算已验证）。

**哪些分支只做了文本级验证**：`ps -T` 表头/列解析的各个形状（含少列、多列、`-`、整格缺失、`ps` 错误文本、旧
`-o WCHAN` 一列形状）、`pidof` 的两条分支、`INSTRUMENTATION_CODE` 的三种分类（无时间戳与**带主机时间戳**两种
日志形状）、停涨看门狗按探针状态分出的三种退出、报告行与 adb 动作——这些都是样例驱动的**文本级**验证。
**需要真机才能确认的**：真机上 `pidof`/`ps -T` 的真实输出形状是否与摘录一致；真机真的被冻住时探针能否当场判出
`FROZEN`；`am unfreeze --sticky` 是否真能让日志恢复增长；`-Seconds` 总时限与轮询节奏在真机上的实际行为
（replay 用确定性时钟，不睡眠）。

`hang-record/03` 的冻结现场是当时抓的原始文本，**仍然有效**（r5 也这么裁定），不因脚本缺陷作废；但 `04f` 的
4 条 `DONE` 行是**修复前**的脚本写的，不能当「正常跑完」的证据——其中 `== E ==`（对应 `04g`）那一次实际以
`CODE: 0` 崩溃收场。

## 5. 本次改了什么（只有 androidTest，3 个文件）

1. `app/src/androidTest/.../GatedHearthSessions.kt`
   - 闸门内部的放行等待变成**有界**（60 秒到点自己放行并打一行日志）：测试万一忘了放行，不会再留一个
     永远停住的 IO 线程。
   - 新增 `awaitCallFinished()`：等「被停住的这次调用真的走完（提交/回滚/读返回）」，**用它替代
     `Thread.sleep` 当同步点**；新增 `releaseAll()` 供 `finally` 一次性放行。
   - `await()` / `awaitCallFinished()` 超时的失败信息里带**全部线程栈**（`threadDump()`）。
2. `app/src/androidTest/.../HearthNavigationDuringTurnDeviceTest.kt`
   - **等待必须有判别力和上限**：`waitUntil(timeoutMs, what, state) { … }` 失败时打印「哪一步 + 页面状态 +
     全部线程栈」。
   - **删掉三处 `Thread.sleep` 当同步**：确认那一例（第 8 例）原来用 `Thread.sleep(1_500)` 等确认任务，
     现在用 `hold.awaitCallFinished(20_000)`（这次写事务真的走完）+ `expectNothingFor(...)`（有界观察窗口，
     一违约立刻失败）。第 5、6、7 例里的 `Thread.sleep(500/1000/1500)` 同样换成
     `awaitCallFinished` + `expectNothingFor`。
   - **修掉一个会让 send 被静默丢掉的竞态**：`!historyLoading` 在 ViewModel 刚建出来时就是 `false`，
     只等它会在 `openChat` 的协程还没跑起来时就通过，接着 `model.send()` 因为「还没有打开的人物」直接
     `return`，于是 `busy` 永远不会为真——这正是 `aLateRefresh…` 原来在真机上必失败的原因（现在用
     `awaitOpenCharacter()` 等到「人物已打开 + 这一段历史读完」，并用 `sendOrFail()` 把「被拒/停在确认框」
     两类静默失败变成带页面状态的断言）。
   - **闸门一律在 finally 里放行**（`hold?.letGo()` + `gates?.releaseAll()`）。
   - 断言**没有放宽**：第 8 例仍然证明「用户离开后这个确认不会发请求、不会写库、不会用旧快照覆盖后来编辑
     的名字」；只是把「等多久」和「怎么等」改成有界、可解释的同步点，并补了两条更早、更精确的断言。
3. `app/src/androidTest/.../BoundedDeviceTest.kt`（新增）：用例级 4 分钟硬上限 + 超时打印全部线程栈。

**2026-10-19 追加（只动 `HearthTimelineFollowDeviceTest.kt` 一个测例文件，实现未改）**：

4. `app/src/androidTest/.../HearthTimelineFollowDeviceTest.kt`
   - `navigateTo(...)` 内部等到重组和自动滚动落定（`settle()`）：这一族测例原来靠每个调用点自己
     `settle()`，新加的那一例忘了等，断言读到导航前那一帧 → 假红（`04a`）。四处重复的 `settle()`
     随之删掉。
   - #15 的滑动从写死 1 500px 换成按控件高度算的 `userScrollsBackAcrossTheScreen`
     （`top + height*0.15f` → `bottom - height*0.15f`），不再依赖具体屏幕高度。
   - 断言一条没放宽：`tailVisible()`/`atBottom()` 判据不变；判别力实证见 `04b`（把 `claim()` 的
     判据改回旧语义 → 这一例立刻真红，`total=41`）。

## 6. 残余失败与环境限制（不是本次改动引入）

- **真机上没有一个能用的本地模型**：`files/local-models/registry.json` 里只有一个 7.4GB 的外置 SAF 模型
  （`copied:false`，`content://com.android.externalstorage.documents/...Qwen3.5-9B-...gguf`），应用私有目录
  里没有模型文件。因此 #4 走 assumption 跳过，#10/#21/#22 失败。这一条与本次改动无关（这些文件我一行没动）。
- **`HearthTimelineFollowDeviceTest` 的两例与本地模型无关**（2026-10-19 更正，原先误归因成「缺模型」）：
  - `#11 openingSavedHistoryFromAnUnsavedStreamingConversationFollowsTheNewTail` 是**测例自己假红**：
    `navigateTo` 之后没等重组，断言读到导航前那一帧（`total=1`、`lastSize=3952`）。已经在
    `HearthTimelineFollowDeviceTest` 里改稳（导航 helper 内部等落定；滑动改成按控件高度算）。
  - `#15 scrollingBackAcrossMessagesKeepsTheViewportStillWhileTokensArrive` 在全量里的「失败」是
    `LocalHttp` 的 `Broken pipe` 把进程带走，不是它的断言；单类复跑三次都通过。
  - 同时更正：原先说这两例「修复前通过、修复后失败」——归档原文不支持。被称为「对照」的那次全量
    （`hang-record/02`）跑的是 b6dbc6c（**已含**第 5 条实现），#11 在里面就是失败的（现场逐字节相同），
    #15 是通过的。详见 `04e-follow-class-rerun.md`。
- **`LocalHttp` 的 `Broken pipe` 会把进程带走**（`hang-record/08`）：`net` 模块的测试假服务在请求线程里
  往已关闭的 socket 写，非主线程未捕获异常 → 进程崩溃。全量一次 invocation 会停在当时跑到的那一例
  （三次全量分别停在第 15 例，一次停在第 9 例；`04g` 又在第 15 例），所以 25 例的结果是多段并集。
  这是既有脚手架问题（仓库文档 `docs/reviews/2026-10-18-P2-2/DELIVERY.md` 里也记过同类现象），
  修它要改 `net` 的 main 源集，超出「只改测试文件」的范围，故未改。

## 7. 复跑期间对设备做过的、可回退的一次性设置

| 动作 | 用途 | 收尾 |
| --- | --- | --- |
| 开跑后 `adb shell am unfreeze --sticky com.xavierxia.mycelium.debug` | 防止系统冻结器再冻住这一轮测试进程（第 3 节根因） | 只在内存里、只对调试包；`adb shell am force-stop` 掉两个调试包即失效；进程/UID 级别，不落盘、重启即无 |
| `adb shell pm clear com.xavierxia.mycelium.debug`（一次） | 清掉前面几次崩溃留下的测试数据，让复跑从干净状态开始 | 只清调试包；正式版与 spike 未动 |
| 读取过 `svc power stayon`（值本来就是 `15`）、`cached_apps_freezer`（保持 `device_default`） | 只读，用来判断冻结与息屏/省电设置的关系 | **没有修改**这两个设置 |

## 8. 文件清单

| 文件 | 内容 |
| --- | --- |
| `01-single-class-…-timed.txt` / `.txt` | 单类复跑（`HearthNavigationDuringTurnDeviceTest`）原始输出，`OK (5 tests)` |
| `02b-remaining-classes-timed.txt` / `.txt` | 分批跑 B：除时间线类之外 10 个类，15 例，`Failures: 3` |
| `02c-timeline-remaining-methods-timed.txt` / `.txt` | 分批跑 C：时间线类剩下 5 例，`OK (5 tests)` |
| `03-timings-single-class.md`、`03b-…`、`03c-…`、`03d-…` | 上面几次运行的逐例耗时表 |
| `03e-timings-all-25-cases.md` | **全部 25 例的逐例结果与耗时（并集）** |
| `04-build-final.txt` | 离线构建输出（`BUILD SUCCESSFUL`） |
| `04a-follow-class-baseline-timed.txt` / `04a-…-timings.md` | 2026-10-19 单类复跑（测例未改）：`HearthTimelineFollowDeviceTest`，10 例 1 失败（#11） |
| `04b-follow-class-old-claim-timed.txt` / `04b-…-timings.md` | 测例已改 + 判据改回旧语义（一行 mutation）：#11 真红（`total=41`），判别力实证 |
| `04c-follow-class-green-timed.txt` / `04c-…-timings.md` | 最终代码单类复跑：`OK (10 tests)`，`Time: 11.234` |
| `04d-follow-class-green-repeat-timed.txt` / `04d-…-timings.md` | 同 04c 重复一次：`OK (10 tests)`，`Time: 11.278` |
| `04g-full-suite-after-test-fix-timed.txt` / `04g-…-timings.md` | 修好以后的全量：跑到 #15 被 `Broken pipe` 带走；#11 通过（1045ms） |
| `04f-guards-follow-class.txt` | 04a–04g 几轮的主机侧守卫报告；**这是修复前脚本写的**，4 条 `DONE` 只表示「看到了某个 `INSTRUMENTATION_CODE`」，**不能**当「正常跑完」的证据（`== E ==` 那次 `04g` 实际以 `CODE: 0` 崩溃） |
| `04e-follow-class-rerun.md` | **判定与依据**：两例失败是测试脆弱 / 环境，不是回归；含更正说明与编码说明 |
| `guards.txt` | 主机侧守卫报告：唯一一次 `WATCHDOG` 判定，以及 `RUN`/`STICKY` 行（后者**不是**仓库里任何脚本写的，见第 4 节） |
| `hang-record/01-user-run-16min-hang-b6dbc6c.txt` | 用户提供的原始 16 分钟挂死日志（UTF-16 转 UTF-8） |
| `hang-record/02-reproduced-full-run-b6dbc6c.txt` | 用有界化之前的 APK 复现挂死（卡在第 9 例）；注意它跑的是 b6dbc6c，**已经含**复审 r3 第 5 条实现 |
| `hang-record/03-frozen-process-evidence.txt` | `isFrozen=true` / 全部线程 `do_freezer_trap` / instrumentation 记录 |
| `hang-record/04`、`05` | 修复后仍未开 sticky 解冻的那轮：第 7 例卡住 3 分钟，主机侧 force-stop 与判定 |
| `hang-record/06`、`07` | 修复后全量跑到第 15 例进程崩溃（第一次） |
| `hang-record/08-crash-stack-broken-pipe.txt` | 崩溃栈：`LocalHttp.serve` → `SocketException: Broken pipe` |
| `hang-record/09`、`10` | 同位置的崩溃复现（含 `pm clear` 之后那一次） |
| `scripts/guards.ps1` | 主机侧守卫（冻结检测 + 无增长硬上限 + force-stop + 退出分类）。2026-10-19 修好 `$sel.Pid` 取进程号、按 `ps -T` 表头列位解析并做行/表头**列数一致性**校验、退出分类、以及**解析前剥离行首主机时间戳**；**文本级验证见 4.1**，真机路径仍待复核，不再是「拿来即用」的声明 |
| `scripts/guards-selftest.ps1` | 守卫的样例驱动自检：以 `-ReplayDir` 跑 `guards-samples/scenarios/*.json`（15 个场景），断言退出码、报告行与 adb 动作，另有 live 循环冒烟；场景数按目录实际个数统计。**只做文本级验证**（不连设备），用法见 4.1 |
| `guards-samples/` | 守卫的样例夹具与场景（真机摘录 + 少量构造）、出处与「哪个形状由哪个场景跑到」的对应表（`README.md` 第 1、4 节）、本轮自检原始输出 `selftest-output.txt` |
| `scripts/run-timestamped.ps1` | 主机侧跑测器：force-stop → `am unfreeze --sticky` → 流式读 `am instrument -w -r` 并给每行加主机时间戳（`04*` 的原始输出就是它产生的），可直接复用 |
| `scripts/extract-timings.ps1` | 把带时间戳的原始输出变成逐例耗时表 |

## 9. 不确定项

1. **进程崩溃（`Broken pipe`）**：`LocalHttp` 的假服务在请求线程里往已关闭的 socket 写，谁碰上它谁被
   带走（三次全量都在第 15 例，一次在第 9 例，`04g` 又在第 15 例）。我没有动 `net` 的 `LocalHttp`
   （超出改动范围），所以**一次 invocation 跑完 25 例**目前做不到。要彻底解决，需要在 `LocalHttp.serve`
   里吞掉 `IOException`（或改用 `java.net.http` / 加线程池与关闭握手）。
2. **#11 / #15 在修复前后的说法原来错了（2026-10-19 已更正）**：原先写「修复前的对照全量
   （`hang-record/02`）里它们通过」。对照归档原文：那次跑的是 b6dbc6c（含第 5 条实现），#11 失败
   （现场逐字节相同）、#15 通过。现在的判定是：#11 为测例假红（已在测例侧改稳，单类两轮 + 全量都过）、
   #15 为脚手架崩溃（单类三次都过）。依据见 `04e-follow-class-rerun.md`。
3. **`04g` 里 #2 通过（4424ms），`hang-record/10` 里同一条失败（22155ms）**：云端假服务那一族在真机上
   有偶发性，与本次改动无关，本次没有追。
4. `cached_apps_freezer` 我**没有**改（仍是 `device_default`）；如果后续仍偶发冻住，可以考虑把它临时改成
   `disabled`（可回退），或把 `am unfreeze --sticky` 固化进跑测脚本（本次四轮都用它兜底，都没触发冻结）。
5. **修好的冻结守卫脚本只在文本级验证过**（见 4.1）：真机上 `pidof`/`ps -T` 的实际输出形状、真被冻住时能否当场
   判出 `FROZEN`、`am unfreeze --sticky` 是否真能让日志恢复增长，本轮都没有实测；`guards-samples/` 里
   `ps-not-frozen-*`、`ps-wchan-*`、`log-code0-no-marker-*` 这几份是构造或派生的，不是真机原文（出处见
   `guards-samples/README.md`）。
6. **带时间戳日志的回归只钉了 `04c` 一份**：`12-done-timestamped` 用的是 `04c` 归档末尾 9 行的逐字节副本；
   `04d`（同为成功终止）没有单独夹具，只在本次修正时按同一个 `Get-RunTerminal` 复算过（`DONE`，`04c` 同样
   `DONE`；对照修复前的行首锚定版本，两份都读成 `RUNNING`）。`run-timestamped.ps1` 若以后改时间戳格式或给行首
   加别的前缀，守卫的剥离正则和这个场景要一起改，否则会重新出现「跑完了却被判 `RUNNING`」。
