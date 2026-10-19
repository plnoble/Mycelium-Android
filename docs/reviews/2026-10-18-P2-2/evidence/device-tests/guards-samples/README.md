# 冻结守卫的样例夹具与文本级验证（2026-10-19）

这一目录给 `../scripts/guards.ps1` 提供**样例夹具**和驱动场景，用来在不上真机的前提下，用**真实摘录**的
`dumpsys` / `ps -T` / `am instrument` 文本证明守卫的判定分支各自走对，并证明修复前的三个缺陷不再复现：
`$pid` 与 PowerShell 只读自动变量 `$PID` 冲突；`ps -o WCHAN` 的输出形状根本匹配不上；以及第六次复审
（`REVIEW-r6.md`）指出的两条——终止解析读不出配套跑测器 `run-timestamped.ps1` 写出的**带主机时间戳日志**
（真实归档 `04c`/`04d` 被误判成 `RUNNING`），和 WCHAN 只查「短到缺列」、不查行列数是否与表头一致。

**这里的结论只到文本级**：`adb` 调用本身、真机上 `ps -T` 的实际输出形状、真机上真的被系统冻住，都不由本目录验证
（见第 4 节）。真机复核仍待做。

## 1. 样例清单（逐文件出处）

| 文件 | 类别 | 出处 / 说明 |
| --- | --- | --- |
| `ps-frozen-5-threads.txt` | **真机摘录（逐字）** | `hang-record/03-frozen-process-evidence.txt` 第 165–170 行（`ps -T -p 21272 -o PID,TID,STAT,WCHAN`，5 个线程全在 `do_freezer_trap`） |
| `dumpsys-activity-processes-frozen.txt` | **真机摘录（逐字）** | 同文件第 160–164 行（`mInstr` / `mTargetProcesses` / `Proc # 5`）。守卫**不解析**它，只作现场上下文留档 |
| `ps-wchan-only-derived.txt` | 派生 | 由上面那份真机 `ps` 的 `WCHAN` 列拼成，形状即修复前那条 `ps -o WCHAN` 的输出（只有一列，没有 PID/TID） |
| `log-running.txt` | **真机摘录（逐字）** | `01-single-class-HearthNavigationDuringTurnDeviceTest.txt` 第 1–20 行：跑到一半、没有终止行、没有崩溃行 |
| `log-running-longer.txt` | **真机摘录（逐字）** | 同文件第 1–40 行（同一段日志的更长前缀，用来模拟「日志还在增长」） |
| `log-done-timestamped.txt` | **真机摘录（逐字，带时间戳）** | `04c-follow-class-green-timed.txt` 末 9 行（从 `INSTRUMENTATION_RESULT: stream=` 到 `== adb exit 0 ==`），**保留主机时间戳前缀与 LF 行尾**，逐字节等于归档尾部。这是 `run-timestamped.ps1` 实际产出的形状，用来钉住「守卫直接读跑测器日志」这条通路（场景 `12-done-timestamped`） |
| `log-crashed.txt` | **真机摘录** | `04g-full-suite-after-test-fix-timed.txt` 第 262–311 行；只去掉每行行首的主机时间戳，末两行是 `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` 与 `INSTRUMENTATION_CODE: 0` |
| `log-done.txt` | **真机摘录（逐字）** | `02c-timeline-remaining-methods.txt` 第 70–79 行：`OK (5 tests)` + `INSTRUMENTATION_CODE: -1` |
| `log-code0-no-marker-constructed.txt` | **构造** | 由 `log-running.txt` 加一行 `INSTRUMENTATION_CODE: 0` 拼成。真机崩溃日志里总会有 `Process crashed` 行，构不出「只有 CODE: 0、没有任何崩溃标记」的真实样例，故这份是构造的 |
| `ps-not-frozen-constructed.txt` | **构造**（形状照真机） | 表头与列布局照上面那份真机 `ps`，`WCHAN` 换成普通等待值。归档里没有「未冻结」进程的 `ps -T` 原文 |
| `ps-wchan-dash-constructed.txt` | **构造**（形状照 toybox） | `WCHAN` 整列为 `-`（读不到 `/proc/<pid>/wchan` 时的形状） |
| `ps-wchan-blank-constructed.txt` | **构造**（形状照 toybox） | 行尾对齐空格被去掉、`WCHAN` 整格缺失的形状（表头 4 列、线程行只有 3 格） |
| `ps-wchan-extra-column-constructed.txt` | **构造** | 表头仍是真机那 4 列（`PID TID STAT WCHAN`），首行线程多出一格（`do_freezer_trap XXX`）：列数**比表头多**的形状。归档里没有这种输出，构造出来是为了钉住「列数不匹配一律 PROBE-UNKNOWN」，见场景 `13-ps-extra-column` |
| `selftest-output.txt` | 本轮原始输出 | `scripts/guards-selftest.ps1` 的一次完整运行（Windows PowerShell 5.1，15 个 replay 场景 + 1 个 live 循环冒烟，全过，exit 0） |
| `fake-adb.ps1` | **构造的假 adb**（固定夹具） | 只回一行 `error: no devices/emulators found` 并退出 1；给自检第 2 阶段用，驱动**真的** live 循环，又不碰任何设备 |

**没有 logcat 样例**：这一轮归档里没有该应用的 logcat 原文（设备用户态日志此前就读不到，见 `DELIVERY.md`），
而且守卫**不消费 logcat**（只看 `pidof`、`ps -T` 与 `am instrument` 的输出），所以这里不摆放假的 logcat 文本。

## 2. 场景与判定（`scenarios/*.json`）

退出码语义（写死在 `guards.ps1` 头部注释里）：**0** 跑完 / **2** 崩溃 / **3** 冻结超时 / **4** 停涨（探针明确未冻结）
/ **5** 未知。

| 场景 | 用的夹具 | 期望判定 | 期望退出码 |
| --- | --- | --- | --- |
| `01-done` | `log-done.txt` | 真机 `INSTRUMENTATION_CODE: -1` → `DONE` | 0 |
| `02-crash` | `log-crashed.txt`（真机） | `Process crashed.` + `CODE: 0` → `CRASH`（修复前记成「正常跑完」） | 2 |
| `03-freeze-timeout` | `ps-frozen-5-threads.txt`（真机） | 全线程 `do_freezer_trap` → `FREEZER` + 解冻；停涨后 `FREEZE_TIMEOUT` | 3 |
| `04-freeze-recovered` | 冻结样例 + `log-done.txt` | 冻结被解掉、日志继续长、最后跑完 → `FREEZER` + `DONE`，无 `WATCHDOG` | 0 |
| `05-stall-not-frozen` | `ps-not-frozen-constructed.txt` | 探针读得出来且不是冻结 → `STALL`（**不**冒充冻结超时） | 4 |
| `06-probe-unknown-old-shape` | `ps-wchan-only-derived.txt` | 修复前那条命令的输出形状 → `PROBE-UNKNOWN` + `UNKNOWN`（**不**当「没冻结」，也**不**当跑完） | 5 |
| `07-no-pid-stall` | `pidof` 为空 | 进程不在又无终止标记 → `UNKNOWN`（不当「正常」） | 5 |
| `08-time-cap` | 日志一直增长 | 总时限到点仍无终止标记 → `UNKNOWN` | 5 |
| `09-code0-no-marker` | `log-code0-no-marker-constructed.txt` | 只有 `CODE: 0`、没有崩溃标记 → `UNKNOWN`（不当「正常跑完」） | 5 |
| `10-ps-error` | `ps` 返回错误文本 | 探针读不出来 → `PROBE-UNKNOWN` + `UNKNOWN` | 5 |
| `11-pidof-error` | `pidof` 返回 adb 错误行 | 与「进程不在」区分开 → `PROBE-UNKNOWN` + `UNKNOWN` | 5 |
| `12-done-timestamped` | `log-done-timestamped.txt`（**真机归档尾部，逐字带时间戳**） | 守卫按配套跑测器写出的 `HH:mm:ss.fff` 前缀日志读到最后一行 `INSTRUMENTATION_CODE: -1` → `DONE`（修复前这条被行首锚定正则漏掉，整份日志读成 `RUNNING`，守卫继续空等） | 0 |
| `13-ps-extra-column` | `ps-wchan-extra-column-constructed.txt` | 行内**多**一列（表头 4 列、该行 5 格）→ `PROBE-UNKNOWN` + `UNKNOWN`（**不**当「没冻结」） | 5 |
| `14-ps-wchan-dash` | `ps-wchan-dash-constructed.txt` | `WCHAN` 为 `-` → `PROBE-UNKNOWN` + `UNKNOWN` | 5 |
| `15-ps-wchan-blank` | `ps-wchan-blank-constructed.txt` | 表头 4 列、线程行 3 格（缺 `WCHAN` 整格）→ `PROBE-UNKNOWN` + `UNKNOWN` | 5 |

每个场景还断言：报告里必须出现哪些行、**绝不能**出现哪些行（例如崩溃/未知场景里不允许出现
`run finished normally`），以及守卫本该发出的 adb 动作（`am unfreeze --sticky` / `am force-stop`）。

除这 15 个 replay 场景，自检还有**第 2 阶段**：用 `-Adb ..\guards-samples\fake-adb.ps1` 把**同一个** `guards.ps1`
的 **live 循环**真跑几秒（`-Seconds 4 -PollSeconds 1`，日志用 `log-running.txt`）。它证明 adb 采集 → 探针 →
报告 → 总时限这条 live 通路没有被 `-ReplayDir` 绕开；因为假 adb 只回错误行、目标包名是
`com.example.guards-selftest.none`，所以**没有任何真机动作**（不会 unfreeze、不会 force-stop，报告里也没有
`force-stop` 行，这正是断言之一）。

## 3. 怎么复跑

```powershell
cd docs\reviews\2026-10-18-P2-2\evidence\device-tests\scripts
powershell -NoProfile -ExecutionPolicy Bypass -File .\guards-selftest.ps1
```

本轮实跑（Windows PowerShell 5.1.26100.9711）：**15 个 replay 场景 + 1 个 live 冒烟全过**，
原始输出留在 `selftest-output.txt`（脚本自己数 `scenarios/*.json` 的个数，摘要行与实际跑的场景数不会再对不上）。

单个场景也可以直接跑（不需要 adb）：

```powershell
& .\guards.ps1 -ReportPath "$env:TEMP\r.txt" -ReplayDir ..\guards-samples\scenarios\03-freeze-timeout.json
# 报告里会写 FREEZER / WATCHDOG / VERDICT 行；$LASTEXITCODE 就是退出码
```

## 4. 文本级验证到了什么、还缺什么

**覆盖声明与实际场景一一对应**（下表只列「有场景真跑到」的形状；夹具清单里存在但没接进场景的，一律不算覆盖）：

| 形状 / 分支 | 由哪个场景真跑到 | 文本级还是真机 |
| --- | --- | --- |
| 终止码 `-1`（跑到一半 → 完成） | `01-done`、`12-done-timestamped`（带主机时间戳的真实归档尾部） | 文本级 |
| 终止码 `0` + `shortMsg=Process crashed.` | `02-crash` | 文本级 |
| 终止码 `0`、无崩溃标记 | `09-code0-no-marker` | 文本级（构造夹具） |
| 表头 `PID,TID,STAT,WCHAN`，全线程 `do_freezer_trap` | `03-freeze-timeout`、`04-freeze-recovered` | 文本级（夹具是真机摘录） |
| `WCHAN` 为普通等待值（未冻结） | `05-stall-not-frozen` | 文本级（构造夹具） |
| 旧 `ps -o WCHAN` 形状（只有一列，没有 PID 列） | `06-probe-unknown-old-shape` | 文本级（派生夹具） |
| 行列数**比表头少**（`WCHAN` 整格缺失） | `15-ps-wchan-blank` | 文本级（构造夹具） |
| 行列数**比表头多** | `13-ps-extra-column` | 文本级（构造夹具） |
| `WCHAN` 为 `-` | `14-ps-wchan-dash` | 文本级（构造夹具） |
| `ps` 返回错误文本 | `10-ps-error` | 文本级 |
| `pidof` 空（进程不在） | `07-no-pid-stall` | 文本级 |
| `pidof` 返回 adb 错误行 | `11-pidof-error` | 文本级 |
| 停涨看门狗按探针状态分三种退出 | `03` / `05` / `06`+`10`+`11`+`13`+`14`+`15` | 文本级 |
| 总时限到点仍无终止标记 | `08-time-cap` | 文本级（确定性时钟） |
| live 循环本身（adb 采集 → 探针 → 报告 → 总时限）在 adb 报错下不绕路 | 第 2 阶段 `live-adb-error`（夹具 adb） | 文本级 |

**仍未验证（需要真机才能确认）**：

1. 真机上 `adb shell pidof <pkg>` 与 `adb shell run-as <pkg> ps -T -p <pid> -o PID,TID,STAT,WCHAN` 的**真实输出
   形状**是否与真机摘录的样例一致（不同 ROM/不同 toybox 版本可能少列、改列序或改表头大小写）。
2. 「真机上真的被冻住时，这个探针能当场判出 `FROZEN`」——样例来自 2026-10-19 11:47–11:51 那一轮（RUN 02）的
   现场摘录，不是本轮实测。
3. `am unfreeze --sticky` 在冻结现场是否真的让日志恢复增长（`04-freeze-recovered` 只是文本级模拟）。
4. `-Seconds` 总时限与 `Start-Sleep` 轮询节奏在真机上的实际行为：replay 用确定性时钟、不睡眠；第 2 阶段的 live
   冒烟只跑了 1 秒轮询 / 4 秒总时限，而且假 adb 立即返回——真机上 adb 往返耗时、时钟与真实日志写入节奏都没覆盖。
5. 时间戳归一化只钉了 `04c` 那份归档的尾部（`log-done-timestamped.txt`，逐字节）；`04d` 同为成功终止但现在没有
   夹具，只在本次复审中按同一函数复算过（结果 `DONE`），未落盘成回归。
6. `run-timestamped.ps1` 与守卫的时间戳形状耦合：脚本用 `Get-Date` 的 `HH:mm:ss.fff`，剥离正则按
   `^\d{1,2}(:\d{2}){1,3}(\.\d{1,3})?\s+` 只吃行首这一段；**没有**覆盖毫秒位数变化、前后有 BOM、或将来给日志
   加别的行首前缀（例如 `[12:19:33]`）的情况——那类改动要同时改脚本和这里的回归。
