**结论：不通过。**

审核对象为分支尖端 `0562f7f1957408ccd3eb8e7eea8d214fcbd91ea4`。前三项最关键：**321 的批次配置会触发原生断言；320 个快照不构成当前调用方式所需的滚动回退窗口；decode 中止仍可能被提交为可复用状态。**

全程只读，未修改文件，未运行 Gradle、真机或变异测试。下述运行后果来自代码路径核对，不冒充实测结果。

**必修项清单**

1. **【阻断】`n_ubatch=321` 不满足固定版本的严格不等式，会直接终止进程。**

   **位置：** [engine.cpp:142](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/cpp/engine.cpp:142)；底层 [llama-memory-hybrid.cpp:89](D:/Agent/Project/Nivra/llm/vendor/llama.cpp/src/llama-memory-hybrid.cpp:89)、[llama-batch.cpp:702](D:/Agent/Project/Nivra/llm/vendor/llama.cpp/src/llama-batch.cpp:702)。

   **问题：** 混合内存传入 `n_keep_tail=n_rs_seq+1=321`，而 `split_equal()` 明确断言 `n_ubatch > n_keep_tail`。当前配置得到 `321 > 321`，不成立。实际只提交 128 个 token 也绕不过这条断言；`GGML_ASSERT` 调用 abort，不会进入 JNI 的异常处理。

   **为什么必修：** Qwen3.5 的正常 decode 路径无法工作，无键调用也受影响。

   **怎么改：** 保证有效的 `n_batch >= n_ubatch > n_rs_seq+1`，例如重新评估使用 512；增加真实 JNI 的加载、首轮解码验证。仅调整这一处仍不能解决下一项。

2. **【阻断】快照槽没有随逐 token 生成滚动推进，窗口内也可能恢复错误状态。**

   **位置：** [engine.cpp:225](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/cpp/engine.cpp:225)、同文件 264 行；底层 [delta-net-base.cpp:587](D:/Agent/Project/Nivra/llm/vendor/llama.cpp/src/models/delta-net-base.cpp:587)、[llama-memory-recurrent.cpp:200](D:/Agent/Project/Nivra/llm/vendor/llama.cpp/src/llama-memory-recurrent.cpp:200)。

   **问题：** 固定版本每次只写当前 ubatch 产生的 `min(n_seq_tokens, K)` 个循环状态快照。生成阶段每次 decode 一个 token，只更新槽 0，较老槽并不会整体后移。卷积状态快照也由当前批次构造，不能当作跨调用的历史环形缓冲区。可是 `seq_rm()` 只检查回退距离是否不超过 320，并不知道槽里的状态已经过期。

   一个具体推演：提示位置为 `0…999`，再生成并解码一个 token 到位置 1000。相同提示再次请求，最多保留 999 个 token，需要恢复位置 998，回退距离为 2；槽 2 却仍可能保存 prefill 的位置 997。

   **为什么必修：** `seq_rm()` 可以返回成功、复用读数也大于零，但恢复的循环状态与保留的 token 前缀不一致，直接破坏答案正确性。调大批次上限不能修复这一点。

   **怎么改：** 使用带准确 token 位置、可独立恢复的状态检查点，恢复到不晚于 LCP 的边界后补读；或实现并证明真正有效的快照维护方案。没有有效快照必须全清。增加跨多次单 token decode、重复提示、追加和缩短提示的原生对照。

   **窗口结论：** 回退距离超过 320 时，该循环实现返回 false，当前代码确实会全清重读；问题在于“距离不超过 320”并不保证快照有效。

3. **【高】`PromptGuard` 没有覆盖所有失败收尾，decode 中止仍会提交缓存。**

   **位置：** [engine.cpp:107](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/cpp/engine.cpp:107)、同文件 238、265、274 行；[LocalModelEngine.kt:248](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:248)。

   **问题：** 生成阶段 `llama_decode()!=0` 且 `cancelled` 时直接 `break`，随后仍执行 `guard.committed=true`。提示元数据已经在生成前保存，Kotlin 又只凭 `reused+read==count` 接受它，无法区分安全停止与 decode 中途状态不明。Guard 本身只清 token 向量，没有清内存；并且在 `NewLongArray/SetLongArrayRegion` 成功前就已提交。

   **为什么必修：** 上轮要求明确规定 decode 中止保守作废，当前实现仍依赖底层后续裁剪“碰巧拒绝”来兜底。不能据此宣称所有出口已经闭合。

   **怎么改：** 区分正常结束、检查点停止和 decode 中止；后两类状态不明时统一清元数据及原生内存。JNI 返回数组成功构造后才能提交。增加中止发生在 decode 内部、回调异常、返回数组失败后的下一轮验证。

4. **【高】8 项校验挡住了旧数组长度，但没有形成严格计数契约。**

   **位置：** [LocalModelEngine.kt:224](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:224)。

   **问题：** 目前只检查长度、提示长度一致及非负数之和不超过 count。以下错误读数仍被接受：

   - 正常完成却 `reused+read<count`；
   - 请求复用 0，原生却报告复用大于 0；
   - `reused=count、read=0`，违反至少重读一个 token；
   - 非法停止标志、原因码、负生成数或负耗时；极大整数相加还可能溢出。

   **为什么必修：** 不一致读数可以作为成功返回，部分情况还会建立下一轮缓存归属。“严格 8 项”目前仅部分成立。

   **怎么改：** 保留本轮 requested，验证 `0≤reused≤min(requested,count−1)`、`0≤read≤count−reused`；未停止时必须完整读完。检查其余字段合法范围及原因码一致性，并补 5/7/9 项、错 count、超请求复用、正常但少读等反例。

5. **【高】E 的 token 归属和耗时定义没有实现方案要求。**

   **位置：** [LocalModelEngine.kt:211](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:211)、[TextStream.kt:27](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/java/com/xavierxia/mycelium/llm/TextStream.kt:27)。

   **问题：** `wasHidden` 只读取 push 前状态，因此可见状态下收到 `<think>` 的 token 不计隐藏；一个 token 包含完整隐藏段时甚至记零。拆开的开启标签也没有 token 归属追踪。

   `hiddenMillis` 实际是“第一个 token 至首段非空白正文”，会把纯空白、UTF-8 等待计作隐藏耗时；正文之后再出现的隐藏段则不计时。它也没有原生生成阶段起点。

   **为什么必修：** 得到的数字不能可靠解释隐藏思考消耗，开闭标签均计隐藏的注释与实现直接矛盾。

   **怎么改：** 让过滤器报告隐藏区间，并保留待定标签及 UTF-8 字节对应的 token 身份；明确混合 token 的计数规则。用生成阶段单调时钟记录真实隐藏区间，另记发送到首个可见正文的等待。补开启标签分裂、同 token 跨边界、多个隐藏段和纯空白样例。

6. **【高】取消收尾丢弃真实原生统计，再伪造全零总数。**

   **位置：** [LocalModelEngine.kt:258](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:258)、同文件 276 行。

   **问题：** `runNative` 确实等待 worker 退出，但取消使 `await()` 无法交回其结果。catch 无论什么原因，都写 `promptTokens/generatedTokens/promptMillis/generationMillis=0、stopped=true`，却保留 hiddenTokens。于是可以出现“隐藏 token 为 2，总生成 token 为 0”；普通失败也被标为停止。

   **为什么必修：** 最需要诊断的长思考后取消场景失去了总量和耗时，违反追加统计、不改变既有口径的要求。

   **怎么改：** worker 退出后仍能取得其实际返回读数，在持锁收尾中提交，再传播取消。没有完整原生结果时明确标识统计不完整，不能将未知量当作真实零值。

7. **【高】D 的作用域、正文判定和退出路径不完整。**

   **位置：** [HearthViewModel.kt:256](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:256)、同文件 859、887、935、949、1088、1254 行。

   **问题：**

   - 普通发送及重新生成都无条件开计时，云端也会收到“换成云端模型”的提示。
   - 任意 delta 都设置 `produced=true` 并清提示；仅换行或空白就会让真正无正文的轮次失去提醒。
   - 普通失败的 catch/finally 不清提示；重新生成的 `replaceTurn()` 也不清。提示出现后失败，或重新生成无正文结束，可以留下提示，下一轮甚至立即继承。
   - `stream()` 在 IO 线程调用无轮次参数的 `clearVisibleTextHint()`，未检查 generation token。旧轮正在执行的回调可与导航、新轮计时交错，取消新轮计时。错误 notice 本身有 token 检查，新增的清提示操作没有。
   - 重新生成在历史读取完成后才开始计时，未从接受请求起算。

   **为什么必修：** 会漏报真正的无正文等待，也会在失败结束后显示无效停止提示，存在旧任务干扰新页面的路径。

   **怎么改：** 仅本地轮次计时，单独记录“出现非空白正文”；开始、更新和清理都携带轮次身份及 token，在主线程串行处理。发送和重新生成的所有当前轮次 finally 统一清理，覆盖失败、空结果、导航、停止及销毁。

8. **【高】测试还不足以支撑“改坏实现就变红”的结论。**

   **位置：** [PromptCacheTest.kt:77](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/test/java/com/xavierxia/mycelium/llm/PromptCacheTest.kt:77)、[HiddenSpanStatsTest.kt:74](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/test/java/com/xavierxia/mycelium/llm/HiddenSpanStatsTest.kt:74)、[HearthVisibleTextHintTest.kt:33](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/app/src/test/java/com/xavierxia/mycelium/hearth/HearthVisibleTextHintTest.kt:33)、[HearthPromptSpeedDeviceTest.kt:80](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthPromptSpeedDeviceTest.kt:80)。

   **问题与必须补的判别条件：**

   | 当前测试 | 实际能证明／缺口 |
   |---|---|
   | 假桥缓存测试 | 已独立检查旧前缀，能验证 Kotlin 决策；没有模拟真实快照、生成尾部和裁剪。JSON 字节 token 总有公共开头，未覆盖固定向量的零 LCP。 |
   | `promptMillisCountsOnlyTheReadPart` | 只断言假桥常量 `7` 被转发，不能证明 C++ 计时范围。 |
   | 严格契约 | 没有畸形返回数组测试；删除当前校验，现有正常假桥仍可全部通过。 |
   | 隐藏耗时 | 仅断言 `>=0`；实现恒返回零仍通过。隐藏样例主要从 `startHidden=true` 开始，漏掉开启标签错误，也没有真正取消统计测试。 |
   | 首字撤提示 | 放行后生成立即结束，`finishTurn()` 会代为清理；删除首字处的清理仍可能通过。应首字后继续挂住再断言。 |
   | D 生命周期 | 缺云端、空白、失败、重新生成、阈值前、旧回调干扰新轮的用例。 |
   | 真机三轮 | 只断言第二轮；解析 `prompt_tokens=` 会先匹配 `estimated_prompt_tokens=`，比较的是估算数。 |
   | 温度零比较 | 已逐字比较，但仅重复同一提示；没有对真实追加对话的每轮做缓存／全清对照，也未要求正文非空，空串相等不能充分证明正确。 |

   **怎么改：** 补上述反例及原生窗口内／外、decode 中止后下一轮测试。日志用结构化读数，记录首字或无正文时长、RS 内存及隐藏结束时预算。

   静态看，现有断言可以拦住若干换键、LCP、临时身份错误；真机断言也明确要求非零复用，能拦住“恒为零”。但原生实现完全改坏仍可通过 JVM 测试。**本次没有执行变异测试，不能声称已验证变红。**

**上一轮九条必修项逐项结论**

| 上轮项 | 结论 | 证据与判断 |
|---|---|---|
| 1. 目标模型回退 | **未落实** | `engine.cpp:142、225、264`；参数触发断言，快照有效性假设也不成立，见必修 1、2。 |
| 2. 无键清归属 | **落实** | `LocalModelEngine.kt:202` 进入无键轮即清归属；`PromptCacheTest.kt:256` 已验证 A→null(B)→A。停止／失败组合仍可补测。 |
| 3. 临时会话身份 | **落实** | `HearthViewModel.kt:243、543、690、747、852`；唯一身份在相关边界更新并冻结到 Turn；`HearthPromptCacheKeyTest.kt:55、75` 覆盖两段新会话和重进测试模式。 |
| 4. 事务及异常出口 | **部分** | tokenize/generate 已在同一 `runNative`，取消会 join；原生中止提交和 Guard 出口仍有缺口。 |
| 5. LCP／JNI 契约 | **部分** | 两侧有 LCP 和 count−1 限制，8 项长度与 count 已校验；其他计数约束缺失。 |
| 6. 测试判别力 | **部分** | 假桥独立旧状态、圆桌实际参数捕获、真机非零复用和逐字比较已加入；上述反例、原生边界及运行证据不完整。 |
| 7. 统计口径 | **部分** | 正常路径 `generatedTokens/generationMillis/tokensPerSecond` 保持原口径，既有 64／256 token 断言未改；取消统计失真，首字耗时未记录。 |
| 8. E 隐藏段统计 | **部分** | 字段和记录已加入；token 归属、时间区间、取消结果保留未完成。 |
| 9. D 20 秒提示 | **部分** | 有独立状态、计时任务、轮次检查及停止／导航清理；作用域、空白判定、失败与重新生成收尾不完整。 |

缓存隔离方面，**未找到当前正常调用链通过键或临时身份复用另一人物／另一会话 KV 的路径**：键含人物、模式、会话和实际模型 SHA；无键清归属；load/unload 清缓存；取消和异常清 Kotlin 归属；截断限制为真实 LCP。[键构造](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/hearth/src/main/kotlin/com/xavierxia/mycelium/hearth/Prompt.kt:46)与[模型 ID 映射](D:/Agent/Worktrees/Mycelium-Android/p2-1c-fullreview/llm/src/main/java/com/xavierxia/mycelium/llm/ModelRegistry.kt:29)一致。但这不能抵消必修 2 的问题：**同一归属内也会恢复与 token 位置不对应的循环状态。**

**建议项**

- 同步清理文档矛盾：`llm/README.md:54`、`ChatModel.kt:18` 和引擎注释仍称无键“不读不写归属”；PLAN 后文仍保留七项、固定 pending、仅多 32KB 等旧说法。
- 区分未请求复用的原因。当前原因码 1 把首轮、换键、无键和重新加载合并，不能解释实际退回原因。
- `LongGenerationDeviceTest.kt:49` 的 `bodyHasThink=false` 仍是常量，不能作为未泄露证据。正文过滤本身此次仅新增 getter，未发现主动改变正文输出的修改。

**不确定处**

- 本机另一份底层源码已核实为固定提交 `5e03bdd…`，且无已跟踪文件修改；上述断言和快照结论依据该源码。没有读取手机 GGUF 元数据或执行模型。
- 尚不能确认实际内存、首字收益、温度零逐字一致性及完整 JNI 运行结果。循环状态分配使用 `mem_size × (1+n_rs_seq)` 行，320 窗口的内存代价不能按“只增加提示向量”估计。
- 未验证构建、门禁或测试运行结果；本次不通过已有明确代码依据，不依赖这些待验证项。