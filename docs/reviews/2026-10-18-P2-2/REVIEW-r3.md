**结论：需修改——第 3、4 条的核心缺陷已修复，第 5、6 条维持通过；第 1、2、7、8 条仍有遗漏，第 9 条仍有超出证据的表述。**

审核提交：`758bbf27d6837aa85f74f42103a71702ba3f5317`，对照 r2 审核基线 `cb6c2db`。全程只读，未修改文件，工作树保持干净。下述竞态结论来自源码时序分析，未在本次重新运行 Android 测试。

**逐条状态**

| 原条目 | 状态 | 当前依据与测例判断 |
|---|---|---|
| 1．停止与网络取消 | **不通过** | [Gateway.kt:130](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt:130) 增加取流后的检查，能挡住随后写正文，但取消状态仍未进入底层建连过程。[GatewayTest.kt:322](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/net/src/test/kotlin/com/xavierxia/mycelium/net/GatewayTest.kt:322) 能抓“取消后继续写正文”，不能证明“不再建连、及时退出”。 |
| 2．导航、历史目标与收尾 | **部分修复，不通过** | [HearthViewModel.kt:557](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:557) 已同步固定目标，594、678 行拒绝历史加载期间发送及重做；906、958 行补齐成功刷新后的检查。但历史失败恢复、重做写库被拒后的分支仍有问题，见下文。 |
| 3．取消与写事务边界 | **通过，有测试表述限制** | [ConversationLog.kt:31](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/ConversationLog.kt:31) 的校验确在事务代码块内；[HearthViewModel.kt:192](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:192) 改用原子令牌。夹具跳过 schema 事务，能抓校验移到写事务外的变异；但闩锁实际停在底层 `BEGIN` **之前**。 |
| 4．确认取消后写库 | **通过，按事务入口裁决** | [CharacterStore.kt:139](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/CharacterStore.kt:139) 在事务内检查，只更新确认字段和时间戳；[HearthViewModel.kt:487](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:487) 已接入 `stillWanted`。取消先于事务检查时不写；检查通过后才取消，允许该事务提交。JVM 变异实证有效。 |
| 5．地址与密钥一致快照 | **维持通过** | [CloudStore.kt:84](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/roundtable/src/main/kotlin/com/xavierxia/mycelium/roundtable/CloudStore.kt:84) 仍一次读取完整端点；发送接线使用该快照，未见本轮退化。 |
| 6．半升级模型语义 | **维持通过** | [Schema.kt:85](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Schema.kt:85) 的迁移标记与条件回填保持不变。 |
| 7．加载末尾停止 | **部分修复，不通过** | [LocalModelEngine.kt:77](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:77) 和 [HearthViewModel.kt:767](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:767) 挡住了 r2 所述“加载成功、主线程尚未恢复”的窗口；但检查后到 IO 真正开始生成之间仍会丢停止信号。 |
| 8．手势监听键与首次保存 | **部分修复，不通过** | [HearthSpace.kt:569](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:569) 的状态对象与手势监听键已一致，旧对象问题已修。536 行将部分真实导航误认为首次保存，形成新回归。新增设备测例未运行。 |
| 9．证据与实物一致性 | **部分修复，需修改表述** | 审计附件哈希、条数、正文长度与 [evidence/README.md:13](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/docs/reviews/2026-10-18-P2-2/evidence/README.md:13) 一致；截图通知已如实说明，缺失附件已标注。但 [DELIVERY.md:157](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/docs/reviews/2026-10-18-P2-2/DELIVERY.md:157) 仍称“每条都……验过判别力”，现有证据不支持。 |

**必须修改**

1. **【P1，第 1 条】取消后仍可能启动连接，并等待建连超时。**  
   `bodyStream()` 的前置检查返回后、`open()` 真正开始建连前，取消仍可能只做一次无效的 `disconnect()`。恢复后继续建连，后置检查必须等取流返回才能执行。[AOSP 实现](https://android.googlesource.com/platform/external/okhttp/+/refs/heads/main/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java)中，`httpEngine == null` 时断开直接返回，而 `getOutputStream()` 会调用 `connect()`。新增包装没有改变这一点。  
   `FrozenPeer` 的 `disconnect()` 只计数；测试主动释放取流闩锁后才计退出时间，因此“两秒内返回”不能证明取消能够中断建连。应让底层请求本身持有持续取消状态，并测试取消能否结束尚未完成的连接启动。

2. **【P1，第 2 条，新增失败恢复风险】历史读取失败后，可能用新人物向旧人物会话发送。**  
   [openChat():386](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:386) 切人物时保留原 `openConversation`；新增 [loadHistory():280](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:280) 异常分支仅清除 `historyLoading`。例如关闭人物 A 后打开 B，B 的历史读取暂时失败，随后发送就可能捕获 **B 人物＋A 会话 ID**。`chat.window()` 和 `append()` 没有校验会话属于该人物。  
   读取失败不能等同“历史已就绪”；切人物开始时应清除旧目标，并保持明确的失败／重试状态。

3. **【P1，第 2 条】重做写库被拒后仍缺令牌检查。**  
   [replaceTurn():940](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:940) 将 `stored == null` 都当成可以更新当前屏幕的情况，却没有复检令牌。若本地重做带部分内容进入 `NonCancellable` 异常收尾，等待写事务期间发生导航，数据库会正确拒写，但返回后仍可把旧回答替换到新页面。应区分“测试模式无需写库”和“本轮已作废”，并在更新前复检。  
   新增 [迟到刷新设备测试:462](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthNavigationDuringTurnDeviceTest.kt:462) 使用普通云端成功路径；导航取消本身就能阻止 `withContext` 返回，即使删除新增的最终令牌检查，该时序也可能继续通过。它没有验证 r2 强调的 `NonCancellable` 路径。

4. **【P2，第 7 条】生成启动前仍能丢停止信号，新增 `produced` 也不能补救。**  
   加载后的检查通过，再进入 [IO 调度:803](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:803)，此时用户停止：只设置轮次标记和引擎停止标记，没有取消本地任务。随后 [runNative():132](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:132) 清掉引擎标记，仍可开始生成；迟到 token 又把 `produced` 设为 true，于是允许保存。已加载模型还会完全跳过 767 行检查。  
   需要把本轮停止状态延续到生成启动边界，并明确保存的是停止前已有的输出。现有 JVM 实证只验证引擎 `load()` 内检查；设备用例只是轮询“正在加载”，没有控制这个后续窗口。

5. **【P2，第 8 条，新回归】未保存的新对话切到历史，被误判为首次落库。**  
   [claim():530](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthSpace.kt:530) 仅凭“原 ID 为 null、原消息列表为空”保留跟随状态。首轮长回答仍在流式生成时恰好满足这些条件；用户上翻后打开已有历史 B，同样是 `null → B`，因此不会重置 `followTail=false`，B 的最新内容不再自动跟随。  
   应使用明确的会话段身份或保存事件区分首次落库与导航。新增三例覆盖了非空 ID 之间切换和首次保存，没有覆盖这个混淆场景。

6. **【P2，第 9 条】收窄判别力结论。**  
   撤回或改写 `DELIVERY.md:157–158` 的“每条都验过”。现有实证支持特定变异，不能覆盖全部原缺陷：第 2 条是临时 JVM 探针，第 7 条只覆盖引擎层，第 8 条未运行；第 1 条证明的是阻止正文写入，不是持久取消建连。

**建议**

- [GatedWriteSession.kt:65](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/hearth/src/test/kotlin/com/xavierxia/mycelium/hearth/GatedWriteSession.kt:65) 在调用底层事务前就设置 `writeOpen` 并释放闩锁。它现在能抓目标变异，但应将“已拿到写锁／停在事务内”的注释改准，或增加真实事务状态观测。
- 设备测试应在主线程驱动 ViewModel，等待任务完成而非固定睡眠。返回再进入测试调用的是 `openChat()`，实际页面入口调用 `refresh()`；后者在 `reload()` 返回前尚未设置历史等待状态，值得单独覆盖。
- [confirmSend():493](D:/Agent/Worktrees/Mycelium-Android/p2-2-review3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:493) 的“人物不存在”查询返回后，也应再检查 `stillWanted()`，避免迟到结果清掉新弹窗。
- 首次保存测试还应比较回答中的实际阅读位置；目前主要断言“不在底部”，不足以证明内容位置保持。

**实证核查与未能验证项**

- 已独立核对三份审计 JSON：分别 **6／7／8 条**，SHA256 全部一致；测试模式记录为 **206 字、正文长度 0**。目视确认 20 号截图仍含“旁路充电”通知，与更正后的说明一致。
- 已核对第 2、3、4、7 条变异日志及失败摘要：分别出现 **2／1、4、1、1 个失败**，与归档说明一致。现存复绿 XML 合计 **17 例，0 失败、0 错误、0 跳过**。
- 最终门禁日志记录 `c91eaaa` 上 **154 例通过、lint 零错误零警告**；该提交到当前仅有文档变化。但对应 `p2-2-final` 工作树已不存在，无法重新解析那一批完整 XML；这是已有日志核查，不是本次重跑。
- 遵守只读要求，本次未运行 Gradle、JUnit、lint、变异实验或设备测试。新增／改写设备测例的执行结果、第 8 条动态判别力、真实 Android 建连取消时序仍未验证。
- P2-2 装前装后包信息原文、三次测速明细、SQLCipher 设备升级验证仍缺可独立核验的完整证据。

Codex 额度读数：5 小时已用 **39%**；每周已用 **69%**（最新可读 `rate_limits`：2026-10-19 08:43:30，北京时间）。