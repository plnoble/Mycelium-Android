【不可交付（必修项按严重程度排序）】

审查对象：`wp/P2-1d-hybrid-checkpoint`，HEAD `d7ddf51`。本次只读审查，并用现有编译产物重跑 **74 个相关 JVM 测例，全部通过**；另外通过内存中的假桥驱动真实 `LocalModelEngine`，复现了以下编排缺陷。未重新构建或重跑真机。

1. **P1：恢复位置没有受真实公共前缀约束，会恢复另一条历史的状态。**

   引擎算出了 `reuse`，但挑选检查点时传入的是 `parameters.checkpointPos`；原生收到 `restoredTo` 后直接跳过前缀核验。复现结果：**真实公共前缀只有 2 个 token，仍恢复到 15，并报告复用 15**。同键编辑、截断历史都能触发错误状态复用。

   必须区分“本轮保存位置”和“允许恢复的上限”，恢复前按真实公共前缀作废旧快照，并保证 `0 < checkpoint.pos <= shared`、`checkpoint.pos < prompt.size`。[LocalModelEngine.kt:507](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:507)、[LocalModelEngine.kt:515](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:515)、[engine.cpp:234](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:234)

2. **P1：恢复失败没有落实“清干净、从 0 重读”。**

   原生先清序列，`set_data_ext` 失败后直接返回 `false`，没有同步清掉 `engine.cachedPrompt`；Kotlin 也只设置原因 6，保留原来的 `requested` 和坏快照。随后旧路径仍可能根据过期 token 元数据请求复用；对已经清空的序列，裁尾成功并不能证明前缀状态存在。

   假桥复现中，恢复失败后仍收到 `reuseTokens=19`，最终统计甚至是 **`reason=6, reused=19`**。必须在失败路径清原生状态及两侧缓存元数据、作废坏快照，明确令 `requested=0`、`restoredTo=-1`。[engine.cpp:408](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:408)、[engine.cpp:248](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:248)、[LocalModelEngine.kt:530](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:530)

3. **P1：300 MiB 仅限制仓库账面字节，不能约束分配峰值；原生缓冲还会长期保留。**

   保存顺序是先分配原生 `vector(size)`，再分配 JNI `ByteArray`，最后才进入仓库检查预算并 `copyOf()`。**单份超过 300 MiB，也会先完成前两次大分配才被拒绝**。更新时还有旧快照及临时副本；“每键一份”不能证明峰值安全。

   此外，取走、下一轮开始和卸载都只对 `pendingCheckpoint` 调用 `clear()`，**没有释放 vector 容量**。仓库显示零份、模型已经卸载，仍可能保留一整份原生缓冲。

   必须在分配前按真实大小准入，计入托管堆余量及临时副本；明确所有权，释放待取缓冲，保存失败应跳过优化而非拖垮生成。[engine.cpp:284](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:284)、[engine.cpp:438](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:438)、[engine.cpp:39](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:39)、[CheckpointStore.kt:59](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointStore.kt:59)

4. **P1：检查点生命周期清理只接好了换模型和卸载。**

   换键、无键轮次、停止、生成异常没有同步作废检查点；异常处理只清 `cachedPrompt`，正常停止还可能进入保存分支。复现中，**A→B→A 保留两份并恢复旧 A；生成抛错后仓库仍保留一份**。仓库没有逐键 token 前缀或上下文代际，无法证明这些留存快照仍适用。

   必须按既定契约接齐失效路径；如要保留多键，则需要逐键状态归属校验。[LocalModelEngine.kt:496](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:496)、[LocalModelEngine.kt:575](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:575)、[LocalModelEngine.kt:606](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:606)

5. **P2：trim 标记线程安全，但完整的回收协调没有成立。**

   生产源码没有系统回调注册或调用接线。标记只在下一轮生成入口消费：空闲时不会清；生成中收到 trim，本轮结束仍会取快照并入库。复现中，在途 trim 后仓库仍为一份。它也没有处理原生待取缓冲。

   必须接入实际回调，在不阻塞主线程的前提下处理空闲与在途回收，并在提交快照前检查是否应丢弃。[LocalModelEngine.kt:204](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:204)、[LocalModelEngine.kt:443](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:443)、[LocalModelEngine.kt:583](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:583)

6. **P2：原因码契约只接通了一部分。**

   正常返回时，5/6 确实会覆盖进 `GenerationStats`；但覆盖发生在校验之后，因此第 2 项中的矛盾统计被放行。关闭开关时根本不调用 `planPrefix`，其原因 7 分支不可达；复现得到的是原因 1。取消收尾若取得有效原生读数，则直接使用 `stats`，遗漏覆盖；复现得到 **旁路原因 5、最终统计原因 1，且读数标为完整**。

   必须统一正常及取消路径的最终原因合成与一致性校验。纯注意力的旧复用算法保留了，但“关闭或不适用时不碰检查点接口”不成立：仍无条件调用两次 `take` 接口。[CheckpointPlan.kt:72](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointPlan.kt:72)、[LocalModelEngine.kt:500](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:500)、[LocalModelEngine.kt:569](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:569)、[LocalModelEngine.kt:625](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:625)

7. **P2：新增诊断入口绕过了引擎锁。**

   `measureCheckpoints()` 直接调用会修改原生状态的探针，没有取得 `gate`；与生成或卸载并发时可破坏状态或访问已释放上下文。`checkpointUsage()` 也在锁外遍历非线程安全仓库，份数和字节数不是一致快照。应统一串行化原生状态操作，并在锁内获取占用快照。[LocalModelEngine.kt:432](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:432)、[LocalModelEngine.kt:455](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:455)

8. **P2：测试存在自证和无效断言，不能作为当前交付门禁。**

   `CheckpointFlowTest` 始终返回相同 token，并把请求复用数直接作为实际复用数回报；恢复失败测试只检查旁路原因，不检查清理、从零重读或最终统计。低内存测试仅断言此前调用列表非空，不能证明线程协调。[CheckpointFlowTest.kt:53](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/test/java/com/xavierxia/mycelium/llm/CheckpointFlowTest.kt:53)、[CheckpointFlowTest.kt:102](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/test/java/com/xavierxia/mycelium/llm/CheckpointFlowTest.kt:102)、[CheckpointFlowTest.kt:182](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/test/java/com/xavierxia/mycelium/llm/CheckpointFlowTest.kt:182)、[CheckpointFlowTest.kt:290](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/test/java/com/xavierxia/mycelium/llm/CheckpointFlowTest.kt:290)

   真机逐字对照允许“恢复失败、原因 6”通过，所以即使从未成功跳读，也可能通过验收；第三轮仅记录，没有对应关闭组比对。十轮测试仅检查最终仓库数量和 Java 堆，容许接近三份快照的增长，不覆盖原生容量或瞬时峰值。必须补真实恢复成功、分叉、失败注入、取消统计和 trim 竞争测试。[CheckpointEqualityDeviceTest.kt:115](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointEqualityDeviceTest.kt:115)、[CheckpointEqualityDeviceTest.kt:137](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointEqualityDeviceTest.kt:137)、[CheckpointTenRoundsDeviceTest.kt:119](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointTenRoundsDeviceTest.kt:119)

9. **P2：仓库在合法的非默认配置下会“返回保存成功，实际丢失”。**

   全局淘汰可能删除当前插入键的映射，随后新检查点仍追加到已经脱离映射的局部 `list`。实测 `maxPerKey=3、预算100`，依次写入 `a:40、b:40、a:40`，最后一次返回 `true`，但 `find(a)==null`。默认每键一份暂时遮住了此缺陷，应修复映射维护并补回归。[CheckpointStore.kt:64](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointStore.kt:64)

已确认正确的部分：**落点拆批会恰好停在目标位置再保存，保存与恢复均使用 `NONE`，字节数按严格相等检查，成功恢复后绕过旧裁尾路径。**你提供的逐字一致和提速数据支持这条成功路径；约 5 ms 的探针计时不包含分配、JNI 和仓库复制，不能作为端到端保存／恢复成本。[engine.cpp:265](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:265)、[engine.cpp:490](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:490)