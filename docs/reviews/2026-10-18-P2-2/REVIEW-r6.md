**结论：需修改——主要保证与超时口径已收窄，但仍有证据表述遗漏，冻结守卫新增了无法识别配套带时间戳日志正常结束的回归。**

审核提交：`b58d3a049504e4b1a55ad5f65c62d83fc3b8f63b`，对照 r5。全程只读，未修改文件，工作区干净。

| 核对项 | 状态 | 依据与判断 |
|---|---|---|
| ① 保证 A/B | **正文已修，注释仍需统一** | [结案文档:64](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/CANCEL-DURING-CONNECT.md:64) 已分别限定“取消先于闸门检查”“取流期间取消”。但 [Gateway.kt:290](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:290) 仍写 `during body streaming`，范围大于“取流期间”。 |
| ① 平台结论 | **部分修复** | [第30行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/CANCEL-DURING-CONNECT.md:30) 已补非空引擎的取消路径；第6、58行仍有过宽归因，见下文。 |
| ① 15s／30s 与整体截止时间 | **通过** | [结案文档:94](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/CANCEL-DURING-CONNECT.md:94)、[Gateway.kt:36](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:36) 明确没有整体硬上限。“最多再等15s／总体上限30s”仅作为禁止措辞出现，不再作为承诺。 |
| ② 门禁来源及用例增量 | **通过** | [DELIVERY.md:667](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/DELIVERY.md:667) 已按提交区分来源、撤回错误的 r4 归因，并说明 androidTest 不增加 JVM 用例数。 |
| ② r4 转述与附件说明 | **部分通过** | [DELIVERY.md:256](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/DELIVERY.md:256) 的主要转述已更正；[第341行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/DELIVERY.md:341) 如实说明附件在仓库外；[04e:46](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04e-follow-class-rerun.md:46) 已改成12通过、1跳过、1失败、第15例崩溃。仍有旧转述及源码注释未修。 |
| ③ 进程号冲突 | **通过** | [guards.ps1:423](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:423) 已通过 `$sel.Pid` 传递目标进程号，不再赋值只读 `$PID`。 |
| ③ WCHAN 解析 | **部分通过** | [guards.ps1:117](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:117) 已按表头找列；但未完整落实声明的列数校验，自检覆盖也有夸大。 |
| ③ 退出分类 | **旧缺陷已修，新增回归** | 原始无时间戳文本可区分 DONE／CRASH／UNKNOWN；[guards.ps1:168](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:168) 的行首锚定却漏掉配套跑测器输出。 |
| ③ README 验证边界 | **部分通过** | [README:139](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/README.md:139) 已明确文本级验证及真机缺口，但部分声称覆盖的夹具未接入场景。 |
| r5 已通过的历史就绪、草稿、重试及本地取消 | **维持通过** | 本轮 `app`、`llm` 无改动；`Gateway.kt` 去除注释和空白后与 r5 相同，未发现新增业务逻辑回归。 |

必须修改：

1. **【P2，新回归】终止解析必须兼容配套跑测器的时间戳。**  
   [run-timestamped.ps1:24](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/run-timestamped.ps1:24) 明确要求守卫读取同一个 `LogPath`，其输出带 `HH:mm:ss.fff` 前缀。新正则却要求行首直接是 `INSTRUMENTATION_CODE`。

   本次在内存中执行原 `Get-RunTerminal`，结果是：无时间戳成功夹具为 `DONE`；真实归档 `04c`、`04d` 均为 **`RUNNING`**。例如 [04c:150](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04c-follow-class-green-timed.txt:150) 明明包含成功终止码。随后守卫会继续等待，并可能进入停涨、force-stop 路径。应统一解析前的时间戳处理，并加入配套日志回归场景。

2. **【P2】补齐 WCHAN 校验，并纠正自检覆盖声明。**  
   [guards.ps1:133](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:133) 只检查是否短到缺少 WCHAN，没有检查行列数是否与表头一致；多余列仍会被接受，与“列数不匹配一律 UNKNOWN”的声明不符。

   同时，11个场景均未引用 `ps-wchan-dash-constructed.txt`、`ps-wchan-blank-constructed.txt`，但 [样例 README:78](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/evidence/device-tests/guards-samples/README.md:78) 已把这些形状列为验证完成。应接入相应场景，或撤回覆盖声明；已有夹具不等于已测试。

3. **【P2】清除结案材料剩余的过宽表述。**  
   [结案文档:6](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/CANCEL-DURING-CONNECT.md:6) 仍笼统断言“不是本实现的缺陷”，第58行仍将诉求归为“平台没有提供的可中断性”。现有证据支持的是**当前实现不能保证覆盖所有启动窗口，也不能保证立即结束在途连接**，不足以排除实现选择的影响或否定平台已有取消能力。本轮重新读取的 [AOSP 实现](https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java) 确实同时包含空引擎直接返回、非空引擎调用 `httpEngine.cancel()` 两个分支。

   `Gateway.kt:290` 的保证 B 也应明确写成“获取正文输出流期间”，避免把正文已经开始写出的阶段纳入零正文保证。

4. **【P2，r5 遗留】完成尚未落实的证据更正。**  
   [设备测例:559](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthNavigationDuringTurnDeviceTest.kt:559) 仍声称“收尾只能走 NonCancellable、只有令牌能拦住”。[DELIVERY.md:325](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/DELIVERY.md:325) 虽诚实承认未改，但不能据此视为完成 r5 必修项。

   此外，[DELIVERY.md:282](D:/Agent/Worktrees/Mycelium-Android/p2-2-review6/docs/reviews/2026-10-18-P2-2/DELIVERY.md:282) 仍将 `stillWanted()` 复检写成“必修4”；r4 必修4实际是冻结守卫，`confirmSend()` 的该项属于建议，应与前面已更正的转述统一。

建议保留 r5 的真实 `LocalModelEngine` 启动边界测试、停止耗时验证及阅读锚点检查。文档中的 `$testPid` 也宜改成实际采用的 `$sel.Pid`，避免维护者按不存在的变量定位。

**将该取消限制记录为已知残留并提交统筹裁定，是可接受的处置方式。** 现有代码和平台依据足以支持上述限定陈述；无需为此强制换库。但材料仍须先完成这些一致性修正，不能称窗口已修复或保证取消时限。最终是否接受残留由统筹决定。

本次直接读取了本轮 [PS1 摘要](D:/Agent/Worktrees/Mycelium-Android/p2-2-work/gate-final4-ps1.txt:10)、[SH 摘要](D:/Agent/Worktrees/Mycelium-Android/p2-2-work/gate-final4-sh.txt:6)，并复算对应工作树 JUnit XML：**169例，0失败／错误／跳过，lint 0/0**。SH 单测任务为 `UP-TO-DATE`，不能算第二次独立执行全部测试。

未能验证：未重跑构建或连接设备；完整守卫自检会写临时文件和报告，因此未执行，归档的12/12仅作为历史运行证据。WCHAN 动态探测还受当前 PowerShell 受限语言模式限制。真实设备冻结恢复、Android 建连取消延迟、native 启动与停止耗时及当前提交整套设备测试，均未由本轮验证。

额度读数：5小时已用 **52%**；每周已用 **87%**（最新可读 `rate_limits`：2026-10-19 14:56:58，北京时间，非实时面板）。