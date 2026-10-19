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
| 主机侧硬上限 | `scripts/guards.ps1`（6 分钟无增长就 force-stop 并记录停在哪一例） | 防的正是系统冻结：没有日志增长就认定卡住，如实记录并 force-stop |

每次 instrument 都用主机侧守卫跑；守卫的报告在 `guards.txt`（每一轮的开跑时间、`STICKY` 解冻记录、
若有则 `WATCHDOG` 记录）。本次唯一一次主机侧判定卡住是**修复后、还没开 sticky 解冻**的那一轮：第 7 例
`comingBackToThePageRefusesASendUntilTheStoredHistoryIsThere` 3 分钟无增长被 force-stop（`hang-record/04`、
`05`）。此后每轮开跑后都对调试包做一次 `am unfreeze --sticky`，单类、分批 B、分批 C 都跑完、无卡住。

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

## 6. 残余失败与环境限制（不是本次改动引入）

- **真机上没有一个能用的本地模型**：`files/local-models/registry.json` 里只有一个 7.4GB 的外置 SAF 模型
  （`copied:false`，`content://com.android.externalstorage.documents/...Qwen3.5-9B-...gguf`），应用私有目录
  里没有模型文件。因此 #4 走 assumption 跳过，#10/#21/#22 失败，#11/#15 这类需要真的生成出 token 的
  时间线用例也失败。这一条与本次改动无关（这些文件我一行没动）。
- **`LocalHttp` 的 `Broken pipe` 会把进程带走**（`hang-record/08`）：`net` 模块的测试假服务在请求线程里
  往已关闭的 socket 写，非主线程未捕获异常 → 进程崩溃。全量一次 invocation 会在第 15 例停在这里
  （两次全量尝试、位置完全相同），所以 25 例的结果是三段并集。这是既有脚手架问题（仓库文档
  `docs/reviews/2026-10-18-P2-2/DELIVERY.md` 里也记过同类现象），修它要改 `net` 的 main 源集，超出
  「只改测试文件」的范围，故未改。

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
| `guards.txt` | 主机侧守卫报告：每轮开跑时间、`STICKY` 解冻记录、唯一一次 `WATCHDOG` 判定 |
| `hang-record/01-user-run-16min-hang-b6dbc6c.txt` | 用户提供的原始 16 分钟挂死日志（UTF-16 转 UTF-8） |
| `hang-record/02-reproduced-full-run-b6dbc6c.txt` | 用修复前 APK 复现挂死（卡在第 9 例） |
| `hang-record/03-frozen-process-evidence.txt` | `isFrozen=true` / 全部线程 `do_freezer_trap` / instrumentation 记录 |
| `hang-record/04`、`05` | 修复后仍未开 sticky 解冻的那轮：第 7 例卡住 3 分钟，主机侧 force-stop 与判定 |
| `hang-record/06`、`07` | 修复后全量跑到第 15 例进程崩溃（第一次） |
| `hang-record/08-crash-stack-broken-pipe.txt` | 崩溃栈：`LocalHttp.serve` → `SocketException: Broken pipe` |
| `hang-record/09`、`10` | 同位置的崩溃复现（含 `pm clear` 之后那一次） |
| `scripts/guards.ps1` | 主机侧守卫（冻结检测 + 6 分钟硬上限 + force-stop），可直接复用 |
| `scripts/extract-timings.ps1` | 把带时间戳的原始输出变成逐例耗时表 |

## 9. 不确定项

1. **第 15 例的进程崩溃**：位置固定、`Broken pipe` 明确，但它是「第 15 例先断言失败 → 清理时关掉假服务 →
   还有请求线程在写」的时序；我没有动 `net` 的 `LocalHttp`（超出改动范围），所以**一次 invocation 跑完 25 例**
   目前做不到。要彻底解决，需要在 `LocalHttp.serve` 里吞掉 `IOException`（或改用 `java.net.http` / 加线程池
   与关闭握手）。
2. **#11 / #15 在修复前后不一致**：修复前的对照全量（`hang-record/02`）里它们通过，修复后失败。这两个用例
   在 `HearthTimelineFollowDeviceTest`（我未改动），且真机上的库/模型状态在多次复跑与两次崩溃之间被反复
   写过——最可能是环境/遗留数据差异，但我没有把它查到「铁证」级别，列在此处。
3. `cached_apps_freezer` 我**没有**改（仍是 `device_default`）；如果后续仍偶发冻住，可以考虑把它临时改成
   `disabled`（可回退），或把 `am unfreeze --sticky` 固化进跑测脚本。
