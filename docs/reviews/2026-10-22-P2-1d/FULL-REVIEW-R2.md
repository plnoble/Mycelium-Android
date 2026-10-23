【不可交付（列出必修项）】

修复尚未闭环，并引入了异常清理覆盖原异常的问题。当前 HEAD `6284cf1` 仅比 `e31b40b` 多文档，代码一致。

1. **P1：关闭检查点期间的分叉仍可导致错误恢复。**  
   `invalidateBeyond` 放在 `checkpointWanted` 内；关闭开关或传入 `checkpointPos=-1` 时，旧快照保留，但 `cachedPrompt` 会更新。本轮复现：保存位置 15 → 同键编辑后真实公共前缀仅 5、该轮不启用检查点 → 再次启用，**仍恢复旧分支位置 15，并报告复用 15**。必须在每个有键轮次按真实公共前缀失效旧快照，不能受是否保存的条件控制。[LocalModelEngine.kt:517](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:517)

2. **P1：内存修复只覆盖单份上限和成功取走路径；新增异常清理还会再次大分配。**  
   300 MiB 准入没有计算 JNI 数组、`copyOf()` 及旧快照的峰值；保存分配失败仍会中断生成。尤其 `takeCheckpoint()` 在 `NewByteArray` 失败时直接返回，尚未释放原生缓冲；新增 `catch` 又调用它“丢弃”，再次尝试分配。假桥注入验证：**原生成异常被清理阶段的 OOM 替换，`lastStats` 留为 null**。必须增加无需分配、不会抛错的原生丢弃接口，并落实峰值预算和保存失败降级。[engine.cpp:289](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:289)、[engine.cpp:444](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:444)、[LocalModelEngine.kt:625](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:625)

3. **P1：正常停止仍未执行检查点失效。**  
   `stop()` 可以让原生正常返回 `stopped=true`，不会进入新增的异常清理。保存分支没有检查停止标志。本轮复现：**停止后仓库仍为一份，下一轮恢复位置 15**。必须在正常停止收尾时清仓、丢弃在途快照，并阻止重新入库。[LocalModelEngine.kt:588](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:588)

4. **P2：取消收尾丢失回退原因，新增清理又提前清零诊断。**  
   `forgetCheckpoints()` 清掉原因字段后，收尾直接使用未经原因合成的原生统计。本轮复现“恢复失败后取消”：**最终原因变为 1，旁路原因变为 0，原来的 6 丢失，读数仍标为完整**。关闭开关时原因 7 分支也仍不可达。应保存本轮原因，统一正常与取消路径的合成、校验，再清理缓存。[LocalModelEngine.kt:625](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:625)、[LocalModelEngine.kt:643](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:643)

5. **P2：低内存回收仍没有完整接通。**  
   生产代码未接入系统回调；标记只在下一轮入口消费，空闲时不回收，生成中收到标记后仍会保存。本轮复现：在途调用 `onLowMemory()`，结束后仓库仍有一份。需要异步协调空闲回收，并在保存提交前处理 trim、释放原生缓冲。[LocalModelEngine.kt:443](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:443)、[LocalModelEngine.kt:588](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:588)

6. **P2：诊断入口绕过引擎锁的问题未修。**  
   `measureCheckpoints()` 会恢复原生状态，却未取得 `gate`，与生成、卸载并发会竞争；`checkpointUsage()` 也在锁外遍历仓库。必须统一串行化状态操作，并在锁内取得占用快照。[LocalModelEngine.kt:432](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:432)

7. **P2：交付测试仍不足以阻止假通过。**  
   应补以上跨轮切换、正常停止、清理失败、取消原因和在途 trim 回归。真机逐字测试仍允许“恢复失败、原因 6”通过，第三轮没有关闭组对照；十轮测试仍不覆盖原生内存和分配峰值。这些第一轮指出的验收缺口没有修改。[CheckpointEqualityDeviceTest.kt:115](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointEqualityDeviceTest.kt:115)、[CheckpointTenRoundsDeviceTest.kt:119](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointTenRoundsDeviceTest.kt:119)

已确认有效的修复：持续启用时恢复上限受 `shared` 约束；普通恢复失败归零重读；成功取走及卸载释放 vector 容量；仓库追加到悬空 list 的问题已消除。

另有一项非阻断新退化：先全局淘汰再单键替换会多淘汰其他键。实测每键一份、预算 100，写入 `A:40、B:40、B:40` 后 A 被无须地删除。[CheckpointStore.kt:67](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointStore.kt:67)

验证范围：只读复审，使用现有编译产物重跑 **84 条相关 JVM 测试，全部通过**，另以内存假桥驱动真实引擎复现上述编排问题；未重新构建 JNI、运行真机或全新 worktree 门禁。