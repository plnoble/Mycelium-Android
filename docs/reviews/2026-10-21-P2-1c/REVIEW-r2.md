**结论：不通过。** 审查对象为分支尖端 `57753947b6402cf0dea7a1306a30390a71749dbd`。上一轮两条原生阻断已消除，但仍有以下必修项，前三条最关键。

1. **[P1] 生成期 decode 失败仍可能被当作成功，并重新登记已失效的缓存。**

   位置：[engine.cpp:270](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/llm/src/main/cpp/engine.cpp:270)、[LocalModelEngine.kt:274](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:274)。

   原生失败后确实清了内存及 `cachedPrompt`、返回原因码 4；但未收到停止时仍返回 `stopped=0`。Kotlin 只检查原因码范围，随后因提示已读完整而重新提交 `cacheOwner/cachedPrompt`，Flow 正常结束。炉边会把已经输出的半截正文作为正常答案保存。

   这里确实出现了“原生没有缓存、Kotlin 认为有”的不一致。**当前原生自己的前缀检查会让下一轮退回整段重读，因此尚不能据此认定下一轮会复用错误状态；但本轮失败被吞掉是实际缺陷。**

   **怎么改：** 原因码 4 一律禁止提交 Kotlin 缓存；未停止的 decode 失败必须传播为失败。停止／取消时保留停止语义，同时记录缓存失效原因，不能把错误只放进日志字段。

2. **[P2] 严格契约仍有漏洞，异常／取消收尾还会绕过已有校验。**

   位置：[LocalModelEngine.kt:249](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:249)，尤其 257、271 和 300–315 行。

   存在以下可直接从代码推导的反例：

   - `stopped=2` 或 `-1` 被当作停止，从而允许未读完提示的读数通过。
   - 提示尚未读完，却声称已经生成 token，也会通过。
   - 原因码只检查 `0..4`，没有检查一致性：例如“裁尾失败，原因 3”同时声称实际复用了 token，仍能通过。
   - `generatedTokens=Long.MAX_VALUE` 能通过；没有限定为本轮 `maxTokens` 以内。
   - 正常路径已经拒绝的七项数组，会在 `catch` 中被重新包装成 `nativeReadingsComplete=true`，缺失原因码还被填成 0。
   - 数组不足五项时，`catch` 的 `values[4]` 再次越界，掩盖原来的契约错误。取消路径取得的原生读数则完全未经校验。

   **溢出方面有一项修复是成立的：** 正常路径先把 `count` 绑定到 `IntArray.size`，再限制 `reused`、`read`，随后相加，不存在靠 `reused + read` 的 Long 溢出绕过正常检查的路径。问题主要是检查缺项及异常分支绕行。

   **怎么改：** 提取统一的读数解析／校验函数，正常返回和取消收尾共用；保存本轮请求上限以供校验。限定停止位为 0/1、生成数不超过预算、提示未读完时生成数必须为 0，并检查原因码与复用状态的必要关系。坏读数不得发布为有效测量，收尾也不得覆盖原异常。

3. **[P2] D 的重做收尾仍会留下或重新弹出提示，并存在跨轮次竞争。**

   位置：[HearthViewModel.kt:934](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:934)，另见 973–978、1096–1110、1273–1280 行。

   最直接的路径：重做只产生隐藏内容，随后正常结束。`replaceTurn()` 遇到空白答案直接返回，没有调用 `clearVisibleTextHint()`；`regenerate()` 的 `finally` 也没有清理。于是提示会在任务结束后继续存在，或者稍后才弹出来。`regeneration()` 返回 null 的提前退出也有同样问题。

   另外，正文回调运行在 IO 线程，却直接修改页面级 `visibleTextSeen`、取消当前 `visibleTextWatch`，没有核对本轮 generation token。旧轮次已经进入回调后，主线程换页并启动新轮次，旧回调仍可能取消新轮次的计时任务；只给布尔字段加 `volatile` 不能解决归属问题。

   D 还从受理时开始计时，但 `busy=true` 要等历史读取结束才设置；历史读取超过阈值时，提示会叫用户点“停止”，界面却仍显示“发送”。重做在这个阶段尚未建立 `activeTurn`，停止意图也没有可靠的轮次载体。

   **怎么改：** 从受理时建立轮次状态和可停止状态；所有出口在核对轮次归属后统一清理计时任务。正文到达与提示状态更新统一回到主线程，并同时检查轮次身份和 generation token。

4. **[P2] E 仍漏算独立开标签及跨 token 开标签。**

   位置：[TextStream.kt:42](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/llm/src/main/java/com/xavierxia/mycelium/llm/TextStream.kt:42)。

   从非隐藏状态调用 `push("<think>")`：循环先记录 `hidden=false`，消费标签后切换为 true，但 `pending` 已空，循环退出，`sawHiddenText` 仍为 false。

   因而 token 序列 `"<think>"、"秘密"、"</think>正文"` 当前计数为 2，按已经声明的口径应为 3。开标签拆成 `"<thi"`、`"nk>"` 时，标签所属 token 的信息也没有被保留。

   **怎么改：** 消费开标签时立即记录隐藏归属；跨 token 标签需要保留待定文本对应的 token 身份，确认标签后补记且避免重复计数。整个隐藏段包含在一个 token 内的情况此次确实修好了，但不能代替上述边界。

   `hiddenMillis` 当前衡量“首个原生 token 到首段非空白正文的等待”，不累计正文之后的隐藏段；按已明确的口径，我不把这一限制另列缺陷。

5. **[P2] 测试尚不能证明这次整改，部分新增用例的判别力不足。**

   位置：[PromptCacheContractTest.kt:138](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/llm/src/test/java/com/xavierxia/mycelium/llm/PromptCacheContractTest.kt:138)、[HiddenSpanStatsTest.kt:75](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/llm/src/test/java/com/xavierxia/mycelium/llm/HiddenSpanStatsTest.kt:75)、[HearthVisibleTextHintTest.kt:34](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/app/src/test/java/com/xavierxia/mycelium/hearth/HearthVisibleTextHintTest.kt:34)。

   固定向量的零前缀、中间分歧、缩短用例，只断言假桥预填的 `reused/read`，没有断言已经记录下来的 `requested`。例如中间分歧时错误地请求复用 4 个，假桥仍返回预填的 2 个，当前用例仍可通过。

   **必须补齐：** 实际请求值断言；原因 4 后下一轮请求为 0；非法停止位、原因码矛盾、极值和短数组；取消后有效／无效读数的分类；独立及拆分开标签；重做无正文结束、提前退出、历史读取期间停止，以及旧回调不得撤销新轮次提示。耗时测试只有 `>=0`，把耗时实现改成恒零也能通过，需要可控时钟和明确数值断言。

关于两条原阻断和“强制复用为 0”，我的判断如下：

- **阻断 1 已消除。** 现在批次为 128；固定版本的默认 `n_rs_seq=0`，混合内存传给 `split_equal()` 的保留尾部长度为 0，不再进入原来的快照尾部断言。
- **阻断 2 已消除。** 我只读核对了相邻 gate2 中干净且哈希一致的 llama.cpp。其[循环内存实现:201](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-gate2/llm/vendor/llama.cpp/src/llama-memory-recurrent.cpp:201)在需要实际回退时要求 `rollback <= n_rs_seq`；现在正回退量不可能通过。混合内存先检查循环侧，失败后由当前引擎清空并整段重读，不再恢复旧快照。
- **混合模型并非字面上的“每轮一定整段重读”。** 若上一轮未 decode 任何生成 token，例如立即遇到 EOG，而下一轮提示完整追加在旧提示之后，删除起点可以位于现有状态末尾之后。这时 `seq_rm` 可以成功，因为根本没有状态需要回退；保留该前缀是安全的。正常已经 decode 过生成 token 的轮次，以及同提示重做，都会要求实际回退，因而整段重读。
- **普通纯注意力 KV 确实按位置裁剪。** [llama-kv-cache.cpp:400](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-gate2/llm/vendor/llama.cpp/src/llama-kv-cache.cpp:400)检查的是 token 位置区间 `[p0,p1)`，`-1` 被展开为最大位置；不是按物理槽号删除。[后续自动位置:1361](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-gate2/llm/vendor/llama.cpp/src/llama-batch.cpp:1361)从剩余序列最大位置加一开始，所以保留 `0..reuse-1` 后会从 `reuse` 继续。
- **在裁剪决策前把实际复用量强制为 0，从状态正确性上只会退化为整段重读。** 当前代码会真正清空内存，而不是只修改统计。除第 1 项的缓存登记不一致外，本轮没有发现能绕过原生自身前缀检查、继续拿错误前缀答下一轮的确定路径。但静态审查不能代替温度 0 的逐字输出验证。

建议将正确性验收和提速验收分别保留。[真机测试:95](/D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r2/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthPromptSpeedDeviceTest.kt:95)固定使用 Qwen3.5，并要求相同提示第二遍 `reusedTokens>0`；按现在的实现，这条必然无法通过。应增加混合模型的安全回退验证，并用支持裁剪的纯注意力模型验证实际复用。**不能仅删除非零复用断言，就宣称 Qwen3.5 的提速目标已经完成；该目标仍需明确调整范围或后续实现。**

不确定处：没有执行 Gradle、测试或真机推理，也没有修改文件。上述反例和分支行为来自源码推导；JNI 实际运行、纯注意力复用前后的逐字一致性、性能收益及竞态的运行复现仍未验证。