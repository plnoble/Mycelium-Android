**结论：需修改——r3 必修第 3、5 项已修复，第 2 项核心防护已补但页面接线有回归，第 1、4 项仍未封闭竞态；真机归档有效，但部分结论仍超出证据。**

审核提交：`5ba43760e72f2cabf82f7b7e1391291a1f2beac7`，对照 r3 基线 `758bbf2`。全程只读，未修改文件，工作树干净；没有重新运行构建、测试或变异实验。

**逐条状态**

| 核对项 | 状态 | 依据与判断 |
|---|---|---|
| r3 必修 1：取消进入建连过程 | **不通过** | [Gateway.kt:145](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:145) 在锁内检查、锁外调用 `connect()`，检查与实际启动之间仍可取消。 |
| r3 必修 2：历史失败态、会话归属复检 | **部分修复** | [HearthViewModel.kt:448](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:448) 切人物清旧目标；320 行读取失败置 `historyFailed`；727、1044、1120 行复检归属。但页面未处理失败态，会丢掉被拒发的输入，见下文。 |
| r3 必修 3：重做拒写与测试模式分开 | **通过，测试覆盖有限** | [HearthViewModel.kt:1104](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:1104) 屏幕替换分支先复检令牌；1128 行拒写直接返回；1137、1141 行刷新后复检。旧回答覆盖新页面的所述分支已修。 |
| r3 必修 4：停止延续到生成启动边界 | **部分修复，不通过** | [HearthViewModel.kt:924](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:924) 增加 IO 内检查且丢弃迟到 token，但 [LocalModelEngine.kt:144](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:144) 随后仍会清除停止标记。 |
| r3 必修 5：首次落库与导航的会话段身份 | **通过，限所述回归** | [HearthSpace.kt:361](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:361) 导航递增段身份，保存不递增；542 行 `claim()` 据此重置。04b 的失败发生在导航后的布局，具有判别力。 |
| 原第 3 条：取消与写事务边界 | **维持通过** | [ConversationLog.kt:31](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/ConversationLog.kt:31) 事务内校验及原子令牌仍在，本轮未退化。 |
| 原第 4 条：确认取消后写库 | **维持通过** | [CharacterStore.kt:139](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/CharacterStore.kt:139) 保持事务入口裁决、只更新确认字段和时间戳。 |
| 原第 5 条：地址与密钥快照 | **维持通过** | [CloudStore.kt:84](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/CloudStore.kt:84) 与发送处完整端点快照接线未退化。 |
| 原第 6 条：半升级模型语义 | **维持通过** | [Schema.kt:91](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Schema.kt:91) 条件回填与迁移标记未变。 |
| 原第 9 条：表述与证据一致 | **部分修复，仍需改** | [DELIVERY.md:161](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/DELIVERY.md:161) 已撤回“每条都验过”；但全量结果、模型失败原因、当前提交门禁适用性仍有不准确表述。 |

**必须修改**

1. **【P1】建连取消仍是检查后执行的竞态，新增测例没有覆盖剩余窗口。**

   `startingConnection()` 在第 148 行退出锁，第 149 行才执行建连。此时插入 `cancel()`，尚未建立底层请求的 `disconnect()` 可以无效，恢复后仍继续建连。AOSP 的实现明确在 `httpEngine == null` 时直接返回，而取输出流会调用 `connect()`。[AOSP 源码](https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java)

   [GatewayTest.kt:507](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/net/src/test/kotlin/com/xavierxia/mycelium/net/GatewayTest.kt:507) 把停点设在 `setDoOutput()`，即**新检查之前**；旧版 `bodyStream()` 的前置检查也能挡住该时序。另一个测例只验证假连接已开始后能被断开。夹具还自行保存 `disconnected` 并读取 `call.isCancelled`，替真实底层提供了它没有的取消行为。因此，这些测例不能证明本轮修好了 r3 的窗口。

   应让实际底层请求持有持久取消状态，并控制“最后检查已通过、底层请求尚未启动”的停点验证。

2. **【P2，本轮新回归】历史失败态没有接入页面，点击发送会静默丢输入。**

   失败后是 `historyLoading=false、historyFailed=true`。但 [HearthSpace.kt:457](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:457) 仍只根据 `historyLoading` 禁用发送，并且先 `setText("")`、再调用 `send()`；后者因失败态直接返回。`retryHistory()` 在生产页面中没有调用点。结果是按钮可点、没有请求、输入消失，也没有页面重试入口。

   另有一处新放宽：[historyReady():229](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:229) 对 TEST 无条件返回 true，而 `setMode(IMMERSIVE)` 在历史读取完成前仍保留 TEST。这使模式切换期间的发送守卫可以被绕过。

   应统一页面与 ViewModel 的就绪判据、接入失败重试，并仅在发送被接受后清空草稿。现有 [HearthHistoryFailureTest.kt:38](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/test/java/com/xavierxia/mycelium/hearth/HearthHistoryFailureTest.kt:38) 直接调用 ViewModel，能检查拒发和防串会话，不能检查上述输入丢失。

3. **【P2】本地停止仍可能被引擎启动过程清掉。**

   可行时序仍是：IO 内第 925 行检查通过 → 用户停止 → `channelFlow` 开始执行或取得引擎 mutex → `runNative()` 清除 Kotlin 标记 → `native.begin()` 清除 native 标记 → 开始生成。[LocalModelEngine.kt:114](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:114)、[engine.cpp:77](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/llm/src/main/cpp/engine.cpp:77)

   新的 token 过滤改善了迟到内容的显示和保存，但 `return@collect` 不终止生成；本地 `stop()` 也未取消该任务，所以页面仍可能保持忙碌直到推理结束。

   [HearthStopBeforeGenerateTest.kt:21](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/test/java/com/xavierxia/mycelium/hearth/HearthStopBeforeGenerateTest.kt:21) 的三例停在发送后、读取窗口或加载期间；假引擎没有真实引擎的 mutex、`channelFlow` 和标记重置过程。应补真实引擎启动边界的受控时序，并将本轮停止意图传到该边界。

4. **【P2，本轮新增脚本缺陷】归档的冻结检测脚本不能按声明工作。**

   [guards.ps1:35](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/evidence/device-tests/scripts/guards.ps1:35) 给 `$pid` 赋值，但 PowerShell 不区分大小写，`$PID` 是只读自动变量；本次只读查询也确认其属性为 `Constant, AllScope`。随后取得的 `ps -o WCHAN` 输出又被要求匹配数字开头，正常的 `do_freezer_trap` 行不会匹配。

   无增长看门狗是另一条路径，已有触发记录；但这不能证明自动冻结检测有效。此外，第 56 行只要看到任意 `INSTRUMENTATION_CODE` 就记录 `run finished normally`，连 `Process crashed / CODE: 0` 也如此。[04f:8](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04f-guards-follow-class.txt:8) 因而不能作为“正常跑完”的证据。应修正变量、解析和退出分类，再验证可复用脚本。

5. **【P2，第 9 条】保留限制说明，同时纠正仍超出证据的结论。**

   - [04e:46](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04e-follow-class-rerun.md:46) 写前 14 例通过不成立：04g 实际为 **12 例通过、#4 跳过、#10 失败，#15 进程崩溃，之后未运行**。
   - [DELIVERY.md:375](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/DELIVERY.md:375) 将模型失败归因于“外置 SAF、没有私有文件”，依据不足。三例原始异常是**指定的 Qwen3.5-4B 未登记**；停止用例的 assumption 是登记列表为空。`copied:false` 本身是受支持路径，[AndroidModelStore.kt:56](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/llm/src/main/java/com/xavierxia/mycelium/llm/AndroidModelStore.kt:56) 会打开 SAF 描述符，不能据此判模型不可用。
   - [DELIVERY.md:535](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/DELIVERY.md:535) 仍说 `c91eaaa` 以后只有文档变化、“结论对当前 tip 适用”。当前已新增多笔生产代码和测试修改，旧的 154 例门禁不能外推到当前提交。
   - [设备测例:558](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthNavigationDuringTurnDeviceTest.kt:558) 声称拒写收尾“只能走 NonCancellable、只有令牌能拦住”，仍不准确：它是普通云端成功路径，导航取消可以直接阻断 `withContext` 返回，之后的收尾又在入口令牌检查处退出。新增 JVM 重做测例通过 `retryHistory()` 只换令牌、不取消任务，**对拒写后误更新分支有静态判别力**，但它也没有实际执行所述本地 `NonCancellable` 异常路径。

**对真机归档和判别力的裁定**

归档足以支持**特定测例运行结果及第 8 条的一部分判别力**，不足以支持本轮全部整改通过：

- 独立解析原文，确认导航类 **5/5 通过**；时间线类 **10/10 通过两轮**。三组带时间戳与去时间戳日志逐行一致。
- [04b:14](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04b-follow-class-old-claim-timed.txt:14) 的 `total=41、lastIndex=22、atBottom=false` 确认断言检查的是导航后的 B，而非旧帧。结合还原后两次全绿，支持“未保存对话导航不能当首次保存”的判别力。
- [冻结现场:160](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/evidence/device-tests/hang-record/03-frozen-process-evidence.txt:160) 包含目标 instrumentation/PID 与 `do_freezer_trap`，支持观察到了系统冻结；[04g:288](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/docs/reviews/2026-10-18-P2-2/evidence/device-tests/04g-full-suite-after-test-fix-timed.txt:288) 明确支持另一次失败来自 `LocalHttp` 的 `Broken pipe`。两种现象应分开。
- “一次 invocation 跑不满 25 例”“4 例模型前提未满足”“第 8 条手势键另一半未做变异”的限制披露合理。若合并 **02b 与最终 04c/04d**，可得到跨次运行的 **21 通过／3 失败／1 跳过**，但仍不是一次完整全量通过；文档中的 **19／5／1** 应标明是改稳前的历史并集。

**建议**

- 首次落库测例应比较保存前后**同一回答的实际阅读位置**；[当前断言:344](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthTimelineFollowDeviceTest.kt:344) 主要证明没到底部，尚不能证明内容锚点不变。
- 设备测试应在主线程驱动 ViewModel，并覆盖实际页面入口 `refresh()`；它目前在 `reload()` 返回前尚未进入历史等待态。
- [confirmSend():568](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:568) 的人物不存在查询返回后仍应复检 `stillWanted()`。
- [BoundedDeviceTest.kt:40](D:/Agent/Worktrees/Mycelium-Android/p2-2-review4/app/src/androidTest/java/com/xavierxia/mycelium/hearth/BoundedDeviceTest.kt:40) 超时只 interrupt、不等待工作线程结束，不能保证后续用例不受遗留任务影响；新增 JVM 闸门也仍有无期限 `await()`。建议补可靠清理及超时验证。

**未能验证的项目及原因**

本次遵守只读要求，没有重跑 Gradle、JUnit、lint、设备或变异实验。因此，当前提交的完整门禁、新增 JVM 测例实际执行结果、真实 Android 建连取消延迟、真实本地引擎剩余停止窗口均未动态验证。第 8 条手势键变异、首次保存的阅读锚点保持仍缺直接实证；SQLCipher 真机升级、P2-2 装前装后包信息原文及三次测速明细也未由本轮新增归档补齐。

额度读数：5 小时已用 **77%**；每周已用 **75%**（本地最新可读 `rate_limits`：2026-10-19 12:54:51，北京时间，非实时读数）。