**结论：需修改——②历史就绪与草稿处理、③本地生成取消已修复；①可作为已知残留提交统筹裁定，但结案文档仍夸大取消保证和超时上限；④证据表述及冻结守卫脚本仍有未修项。**

审核提交：`803abb6bcf341d5aa6d683a587ae7f4605f2feaf`，对照 r4。全程只读，未修改文件，工作区干净。

| 核对项 | 状态 | 依据与判断 |
|---|---|---|
| ① 建连闸门、超时归一化 | **部分通过，残留未封闭** | [Gateway.kt:186](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:186) 仍是锁内检查、锁外调用；只能保证取消先于该检查时拒绝启动。[351 行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:351) 正确将非正连接超时回退为 15 秒、正值截到 30 秒；读取超时非正值回退为 60 秒，正值保持原值。 |
| ① 夹具及结案处置 | **夹具改善；结案陈述需改** | [GatewayTest.kt:659](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/net/src/test/kotlin/com/xavierxia/mycelium/net/GatewayTest.kt:659) 已删除提前 `disconnect()` 的粘性取消语义，也不再读取 `call.isCancelled`；`holdConnect` 仍模拟可被断开的在途调用，但已明确标注是假设。不能据其全绿推出残余窗口消失。 |
| ② 历史失败、重试、草稿与模式切换 | **通过** | [HearthViewModel.kt:274](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:274) 共用状态判据；[658 行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:658) 同时发布 IMMERSIVE 与加载态；[HearthSpace.kt:457](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:457) 接入重试、禁用与原因提示，[552 行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:552) 仅在 `send()` 接受后清空草稿。 |
| ③ 本地轮次取消 | **通过，真实时延未验证** | [HearthViewModel.kt:925](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:925) 对已交给引擎的轮次取消 Job；[1015 行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:1015) 在交接前置位。取消沿 `channelFlow` 传到 [LocalModelEngine.kt:143](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:143)，由 `ensureActive()`、取消收尾中的 `stop()` 和 `join()` 控制启动与退出，不再只靠可能被重置的标记。 |
| ④ 第 9 条表述 | **部分修复** | [DELIVERY.md:389](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/DELIVERY.md:389) 已区分跨次并集，模型失败归因也已更正；但门禁来源、r4 条目转述和部分附件说明仍不准确，见下文。 |
| r4 另列的冻结守卫缺陷 | **未修复** | [guards.ps1:35](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:35) 的 `$pid`、WCHAN 解析，以及第 56 行退出分类均原样保留。 |

**必须修改**

1. **【P2】收窄结案文档的取消保证，纠正平台结论。**

   [CANCEL-DURING-CONNECT.md:57](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/CANCEL-DURING-CONNECT.md:57) 的保证 A 应限定为“取消先于闸门检查”。实际时序仍可为：检查通过 → 取消、`disconnect()` 空操作 → 调用底层建连。因此第 113 行“消除取消后仍发起建连”与第 20、61 行承认的残留直接矛盾。

   保证 B 也应限定时点：取流期间发生的取消能被后置检查拦住正文；不能泛称建连开始后任意时刻取消都保证零正文，已经写出或正在写出的数据无法撤回。

   AOSP 确实支持“`httpEngine == null` 时 `disconnect()` 无效”，但不能推出“所有在途 `connect()` 都无法中断”：同一实现的非空分支调用 `httpEngine.cancel()`，下层提供取消连接并关闭原始 socket 的路径。准确结论应是**当前实现不能保证覆盖所有启动窗口或立即结束在途建连**。[HttpURLConnectionImpl](https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java)、[StreamAllocation](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/internal/http/StreamAllocation.java)、[RealConnection](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/internal/io/RealConnection.java)

2. **【P2】撤回“取消最多再等默认 15 秒／上限 30 秒”的总体承诺。**

   [结案文档第 61、77、78 行](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/CANCEL-DURING-CONNECT.md:61) 把连接超时参数当成整个调用的截止时间。AOSP 中 TCP 建连使用 `connectTimeout`，随后 TLS 仍有读取/握手过程；连接失败还可能尝试其他路由。因此参数归一化正确，但它没有实现整个调用的 30 秒硬上限。[AOSP 连接实现](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/internal/io/RealConnection.java)、[路由重试逻辑](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/internal/http/StreamAllocation.java)

   应同步修正文档和 `Gateway.kt` 的对应注释，将“有限超时配置”与“停止到任务结束的总体时限”分开。

3. **【P2】冻结守卫仍不能按声明工作。**

   `$pid` 与只读自动变量 `$PID` 冲突；`ps -o WCHAN` 的正常输出不会匹配数字开头；任意 `INSTRUMENTATION_CODE` 都被记为正常结束，包含 `Process crashed / CODE: 0`。[guards.ps1:35](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:35)

   修正脚本并验证分类，或明确撤下“可直接复用／自动检测有效”的声明。已有冻结现场和单类成功日志仍然有效，不因脚本缺陷而作废。

4. **【P2】完成第 9 条的证据与引用更正。**

   - [DELIVERY.md:602](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/DELIVERY.md:602) 将门禁结论归于“r4 的复核转述”，但 r4 明确说未验证当前门禁；“JVM 用例增加来自两笔 androidTest 修改”的归因也不成立。
   - [DELIVERY.md:251](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/DELIVERY.md:251) 将本地停止问题混写成 `stillWanted()` 收尾问题，未准确对应 r4 条目。
   - [04e:46](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04e-follow-class-rerun.md:46) 仍写前 14 例通过；实际为 **12 通过、1 跳过、1 失败**，随后第 15 例进程崩溃。
   - [设备测例:558](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthNavigationDuringTurnDeviceTest.kt:558) 仍声称拒写收尾“只能走 NonCancellable、只有令牌能拦住”，r4 指出的证据越界尚未更正。原始日志应保留，解释文档和源码注释应修正。

**对残留处置的判断**

把无法保证立即取消的建连窗口记录为已知残留，并提交统筹裁定，**是可接受的处置方式**；无需由本审核替统筹决定是否换库或接受风险。但当前结案材料尚不够自洽，应先纠正上述保证范围和时限。最终是否接受残留仍由统筹决定。

**建议**

- 补真实 `LocalModelEngine` 配合受控 `EngineBridge` 的启动边界测试，覆盖 mutex 等待、`native.begin()` 前后取消。新增假引擎测试能证明 Job 被取消，尚不能单独证明真实启动边界。
- “页面立刻退出忙碌”宜改为“取消生成后随引擎安全退出完成收尾”：真实路径仍等待 native worker 的 `join()`。
- 保留 r4 关于 `confirmSend()` 查询返回后复检 `stillWanted()`、首次保存前后比较阅读锚点的建议。

**已核实的证据与未验证项**

已直接读取 `803abb6` 的 [PS1 门禁摘要](D:/Agent/Worktrees/Mycelium-Android/p2-2-work/gate-final3-ps1.txt:10)、[SH 门禁摘要](D:/Agent/Worktrees/Mycelium-Android/p2-2-work/gate-final3-sh.txt:6)，并复算同工作树 JUnit XML：**169 例、0 失败／错误／跳过，lint 0/0**，不是提示中的 163 例。SH 复用了此前单测结果，不能算第二次独立执行全部测试。

设备原文支持导航类 **5/5**、时间线类两轮 **10/10**；[04b:14](D:/Agent/Worktrees/Mycelium-Android/p2-2-review5/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04b-follow-class-old-claim-timed.txt:14) 确实在导航后的 `total=41` 布局失败，支持所述判别力。冻结现场与 `Broken pipe` 崩溃应继续分别记录。

本次未运行构建、测试或变异实验，也未连接设备。真实 Android 建连取消延迟、真实 native 启动边界与停止耗时、当前提交的整套设备测试，均未动态验证；旧设备归档不能替代本轮实测。

额度读数：5 小时已用 **29%**；每周已用 **84%**（最新可读 `rate_limits`：2026-10-19 13:44:28，北京时间，非实时面板）。