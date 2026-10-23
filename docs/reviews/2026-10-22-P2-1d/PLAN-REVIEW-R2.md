【现稿不可直接通过（具体必修项如下）】

**改存 `NONE`、先整段清空再恢复的方向成立。**锁定源码确认 `NONE` 同时保存注意力 KV 和循环状态，整序列删除也避开了局部 rollback 限制。问题在于内存峰值、状态前后条件和旧缓存契约尚未闭环，1412 token 的测量不足以覆盖这些风险。[llama-memory-hybrid.cpp:190](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/src/llama-memory-hybrid.cpp:190)、[llama.h:756](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/include/llama.h:756)

1. **600 MiB 必须改成可动态收紧的上限，补齐峰值预算。**  
   [PLAN.md:208](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:208) 用单份 94.4 MiB 推导“全部放内存、约六会话”，依据不足。注意力 KV 随有效 token 增长，94.4 MiB 不是单份最大值；每键一份也不意味着更新时只有一份。当前存在原生缓冲、JNI `ByteArray`、仓库 `copyOf()` 等副本，淘汰还发生在输入数组已经分配之后。[CheckpointStore.kt:59](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointStore.kt:59)  
   六份样本已约 566 MiB；设备若只有 512 MiB 托管堆，尚未达到缓存上限就可能 OOM。Android 的应用堆上限因设备而异。[官方内存说明](https://developer.android.com/topic/performance/memory-overview#RestrictMemory)  
   **必修：**每次保存前按真实 `get_size_ext(NONE)`，结合托管堆余量、模型及原生内存、临时副本和安全余量准入；明确字节所有权与释放时机，超预算跳过保存；补接近上下文上限的峰值测量，统一 MB/MiB 阈值。

2. **`onTrimMemory` 不能作为防 OOM 兜底，直接 `clear()` 还有并发问题。**  
   [PLAN.md:212](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:212) 的设想不适用于 Android 16 的前台内存保障：从 API 34 起，`TRIM_MEMORY_RUNNING_*` 等内存压力级别已不再通知应用。[官方 API 文档](https://developer.android.google.cn/reference/kotlin/android/content/ComponentCallbacks2)  
   同时仓库明确要求只在 `gate` 内访问，回调直接清空会破坏此前提。[CheckpointStore.kt:27](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointStore.kt:27)  
   **必修：**写清回调注册、线程协调、在途快照处理及防止清空后再次回填的规则；不能让主线程等待整轮推理锁。清除引用也不等于立即回收内存，必须处理原生待取缓冲；主动预算检查仍是主防线。

3. **三步恢复顺序可用，但必须补成完整的状态契约。**  
   [PLAN.md:218](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:218) 应明确：保存和恢复均使用 `NONE`，字节数严格相等；快照恰好表示 `[0, checkpointPos)`，所属模型、上下文代际和 token 前缀一致；满足 `0 < checkpointPos ≤ shared` 且小于提示长度，保证继续 decode 后产生本轮 logits。  
   这有现实缺口：当前原生代码按“跨过边界”保存，却仍标记为目标位置，可能把后续 token 一并存入；Kotlin 查询传的又是 `parameters.checkpointPos`，不是已算出的公共前缀。[engine.cpp:258](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:258)、[LocalModelEngine.kt:472](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:472)  
   **必修：**在落点拆批；核验清空成功；恢复成功后绕过 P2-1c 的旧裁尾/清空路径；失败则清理不可信原生状态及缓存元数据、作废坏快照，从 0 重读并报 6。第 7 节“禁止先删”的旧指令也须明确废止。

4. **多键保留与原因码必须接入同一执行契约。**  
   [PLAN.md:211](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:211) 的“六个并发会话”与第 85 行“换会话一起作废”冲突；现有引擎也只有一组 `cacheOwner/cachedPrompt`。应维持换键作废并删除该容量表述，或明确新增逐键前缀及代际校验；每键一份不能代替同键分叉、停止、出错后的失效规则。  
   [PLAN.md:224](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:224) 的 **5/6/7 定义本身合理**，但必须进入最终 `GenerationStats.reuseFallbackReason`。当前 Kotlin 只更新旁路字段，JNI 仍返回旧码。[LocalModelEngine.kt:493](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:493)、[engine.cpp:323](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:323)  
   **必修：**纯注意力沿用旧路径；混合/循环关闭报 7、无可用快照报 5、恢复失败报 6，三者均不得声称复用；成功则报 0、复用数为 checkpointPos。正常及取消收尾共用校验，每轮重置诊断字段。

5. **实测应限定为体积及原生接口耗时证据。**  
   [PLAN.md:204](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:204) 的“全部读回、生成正常”不能证明恢复后跳读正确：探针后生成未传缓存键，会全量重读，并且只断言输出非空。[CheckpointSizeProbeDeviceTest.kt:99](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointSizeProbeDeviceTest.kt:99)  
   **必修：**保留默认关闭，将“真实跳读且复用数正确的三轮逐字对照”、恢复失败注入、长上下文十轮峰值及 trim 竞争测试列入开启门禁；5 ms 不应表述为包含分配、JNI 与仓库复制的端到端耗时。

本次为只读复审，未修改文件或重跑真机。