**结论：需修改——第 5、6 条通过，其余条目仍有取消边界、状态归属、测试覆盖或交付证据问题。**

审核对象：`cb6c2db746d4aad7d902de0191db7938596c360f`，对照上次审核提交 `11e92b3`。全程只读，未修改文件。

逐条复核如下；“通过”限于源码和已有证据，不代表本次重新运行了测试。

| 原条目 | 状态 | 依据 |
|---|---|---|
| 1．停止与网络取消 | **不通过** | 永久取消标记、登记窗口测试及 `invokeOnCancellation` 已补上；但最后检查与真正启动连接之间仍有窗口。[Gateway.kt:192](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:192)、[AnswerModel.kt:180](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/AnswerModel.kt:180)。 |
| 2．生成中导航及 busy | **不通过** | `abandonTurn()` 已统一停止并清 busy；但切历史读取期间仍保留旧会话作为发送目标，保存后的异步刷新也未全部补齐最终令牌检查。[HearthViewModel.kt:363](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:363)、[同文件:453](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:453)、[同文件:756](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:756)。 |
| 3．取消与写库边界 | **不通过** | `valid()` 已进入事务，问答一起提交；但 IO 检查读取的 `generationToken` 仍是未同步的普通字段，新增测试也未锁定真正的写入事务。[ConversationLog.kt:49](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/ConversationLog.kt:49)、[HearthViewModel.kt:149](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:149)、[同文件:715](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:715)。 |
| 4．确认任务生命周期 | **不通过** | 已固定模式、会话、目标及令牌，能挡住多数迟到发送；但确认状态的数据库更新仍可在取消后完成。[HearthViewModel.kt:383](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:383)、[同文件:402](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:402)。 |
| 5．地址与密钥一致快照 | **通过** | `endpoint()` 从同一配置读取地址、名称和密钥；发送校验、请求参数使用同一快照。测试有读取次数断言及旧做法的反例。[CloudStore.kt:84](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/CloudStore.kt:84)、[HearthViewModel.kt:644](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:644)、[CloudEndpointSnapshotTest.kt:36](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/roundtable/src/test/kotlin/com/xavierxia/mycelium/roundtable/CloudEndpointSnapshotTest.kt:36)。 |
| 6．半升级模型语义 | **通过** | 未完成迁移的 `default + 非空 model_id` 会回填为 local；已完成迁移后用户明确选择的 default 得到保留。新增夹具覆盖原故障状态及再次打开。[Schema.kt:85](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Schema.kt:85)、[CharacterModelChoiceTest.kt:43](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/hearth/src/test/kotlin/com/xavierxia/mycelium/hearth/CharacterModelChoiceTest.kt:43)、[同文件:86](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/hearth/src/test/kotlin/com/xavierxia/mycelium/hearth/CharacterModelChoiceTest.kt:86)。 |
| 7．加载期停止恢复问题 | **不通过** | `Loading stopped` 异常分支已修；加载成功返回与开始生成之间仍不检查停止意图，下一阶段会重置引擎停止标记。没有新增回归测试。[HearthViewModel.kt:530](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:530)、[同文件:635](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:635)、[LocalModelEngine.kt:125](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:125)。 |
| 8．长消息自动跟随 | **不通过** | 真实底部判定和同会话上翻保持已改善，但新增状态的记忆键与手势监听协程的键不一致，换会话后会操作旧状态；首次保存还会重置跟随。[HearthSpace.kt:521](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:521)、[同文件:526](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:526)、[同文件:551](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:551)。 |
| 9．交付证据与表述 | **不通过** | 仍声称“9 条全部整改”；审计原文被错误描述为缺失；20 号截图仍含系统通知。[DELIVERY.md:113](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/docs/reviews/2026-10-18-P2-2/DELIVERY.md:113)、[同文件:281](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/docs/reviews/2026-10-18-P2-2/DELIVERY.md:281)、[同文件:285](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/docs/reviews/2026-10-18-P2-2/DELIVERY.md:285)。 |

仍存在的**必须修改**问题：

1. **【P1，原第 1 条】取消可能发生在最后检查之后、连接实际建立之前。**  
   `call.check()` 返回后，线程可以在执行 `connection.outputStream` 前被暂停；此时取消调用 `disconnect()`，尚未建立的连接不会记住这次断开。恢复后仍会开始连接，直到输出流返回、写入完成才再次检查。至少“取消后不启动连接、及时结束”没有保证，不能据此断言正文一定已送达。[AOSP 实现](https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java)明确存在这一空操作条件。  
   新增登记窗口和仅 `job.cancel()` 的测试有判别力，但阻塞点没有覆盖这里。需要让底层请求在启动连接前就具有持续有效的取消状态，并补“进入输出流获取、尚未建立连接时取消”的受控测试。

2. **【P1，原第 2 条】切历史期间，旧会话仍能接收新问题。**  
   `openConversation(B)` 清掉 busy 后等待 `log.messages(B)`，此时 `openConversation` 和消息仍属于 A。若立即发送，`startTurn()` 捕获 A，并增加令牌，使 B 的读取结果被丢弃：用户选择 B，新问题却发给并保存到 A。应在切换开始时固定新目标，并在历史就绪前阻止发送。  
   此外，`finishTurn()` 第 757–759 行、`replaceTurn()` 第 805–808 行在最后读取后没有复检令牌；新增的 `NonCancellable` 收尾路径不能依赖普通协程取消替代这项检查。现有导航测试只覆盖新对话和切测试模式，没有覆盖切历史、返回再进入及这些刷新窗口。

3. **【P1，原第 3 条】事务内校验仍依赖跨线程不安全的令牌，测试阻塞位置也不准确。**  
   主线程修改 `generationToken`，IO 线程在 `stillCommittable()` 中读取；该字段没有 volatile、原子变量或共同锁。SQL 事务保证数据库原子性，不能保证这个内存字段的可见性。需要明确线程安全的轮次状态，以及取消与开始提交的先后规则。  
   [AbandonedWriteTest.kt:87](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/hearth/src/test/kotlin/com/xavierxia/mycelium/hearth/AbandonedWriteTest.kt:87)拦截所有事务，而 [Sql.kt:41](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Sql.kt:41)会先执行 schema 检查事务。因此第一次 latch 放行的是 schema 事务：**把 `valid()` 移到 `withSession` 内、实际写入事务外，这个测试仍可能通过。**还缺 `redo`、完整内容及时间戳比较、提交完成但返回前取消和写入次数断言。

4. **【P1，原第 4 条】确认取消后仍能改写持久确认状态。**  
   在第 402–404 行的数据库打开或锁等待处阻塞，再取消弹窗或离页；解除阻塞后，`characters.update()` 仍会写入确认，甚至用旧人物快照覆盖后来编辑的字段。第 405 行检查只能阻止后续发送，不能撤销已经完成的更新。应把有效性判断带入确认更新的事务边界，并只更新需要确认的字段。  
   [新增设备测试:177](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthNavigationDuringTurnDeviceTest.kt:177)没有进入 TEST 模式，没有阻塞确认 IO，也没有调用取消弹窗；只是 `confirmSend()` 后立即离页并睡眠。它可能在确认任务尚未读取 pending 时就结束，不能证明原竞态已修复。

5. **【P2，原第 7 条】加载末尾停止仍可能继续生成。**  
   JNI 加载已经成功，但主线程尚未恢复、页面仍显示加载中时点击停止：`userStopped=true`，但 `engine.load()` 不一定抛异常。随后仍执行 `generate()`，其 `runNative()` 清掉停止标记。本地回答还允许在 `userStopped` 时保存，因此可能继续生成并入库。需要在加载返回后、生成开始前检查本轮停止意图，并补这个时序及原 `Loading stopped` 异常路径测试。

6. **【P2，原第 8 条，新回归】切会话后手势监听引用旧状态。**  
   `followTail/userDragging/dragSessions` 按人物和会话 ID 重新创建，但收集手势的 `LaunchedEffect` 只以 `listState` 为键，不随会话变化重启。换到 B 后拖动仍修改旧状态，新会话的 `followTail` 可一直保持 true，下一次输出再次拉回底部。  
   另一个窗口是首次回答保存：会话 ID 从 null 变成数据库 ID，被当成切换会话，重置用户上翻状态。[现有切会话测试:231](D:/Agent/Worktrees/Mycelium-Android/p2-2-review2/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthTimelineFollowDeviceTest.kt:231)只检查进入 B 时到底，没有继续在 B 上翻再追加内容；长回答测试也没有覆盖首次持久化转换。

7. **【P2，原第 9 条】证据修正仍不完整，部分说明与实物不符。**  
   我目视确认 [20-testmode-turn.png](D:/Agent/Worktrees/OMNIX-Mycelium/main/.omx/development/reviews/2026-10-18-android-P2-2/20-testmode-turn.png)仍保留大块“旁路充电”系统通知；裁掉顶部 160 像素并未清除通知。  
   工作目录的 [audit-raw2.json](D:/Agent/Worktrees/Mycelium-Android/p2-2-work/audit-raw2.json)及 `audit-raw3.json` 均存在 `HearthChatTest`、206 字、正文长度 0 的记录；正式审计附件仍没有。应归档现有证据，而非写“当时没存下来”。同时撤回“9 条全部整改”，补齐设备输出和包信息原文的可定位附件；测速缺失继续明确标为无法验证。

**建议**：

- 导航测试在主线程驱动 ViewModel，用 latch 控制具体阶段；`busy=false` 不能证明旧请求已经结束，也不能证明迟到回答已被观察。
- 第 5 条的快照测试有效，可再补应用发送接线测试，防止调用方退回分开读取。
- 来源标签仍在正文上方；服务快照更新了 `turn.chosen`，但生成中的 `streamLabel` 仍可能显示旧服务名。

本次实际核查到的验证结果：

- 审核前后工作树干净，`git diff --check 11e92b3..HEAD` 通过。
- 独立解析 `p2-2-hintfix` 已有 JUnit XML：**144 用例，0 失败、0 错误、0 跳过**；debug/release lint XML 均为 0 issue。
- 该门禁记录对应 `5314e34`；它到当前 HEAD 只有文档变化，可以对应当前生产代码。以上是**已有产物核查**，不是本次重跑。

未能验证的项目及原因：

- 未运行 Gradle、JUnit、lint 或设备测试：遵守本次只读要求，避免生成文件或改变设备数据。
- 上述竞态和 Compose 状态回归是源码控制流推导，未在 Android 上动态复现。
- 四类九例设备测试、正式版装前装后信息，未在所查证据目录找到可独立核验的完整原始附件。
- 三次测速明细、SQLCipher 设备升级及真实设备上的全部取消时序，缺少本次可复核的运行证据。

Codex 额度读数：5 小时窗口已用 **32%**；每周已用 **52%**（最新读取的 `rate_limits`：2026-10-19 02:12:43，北京时间）。