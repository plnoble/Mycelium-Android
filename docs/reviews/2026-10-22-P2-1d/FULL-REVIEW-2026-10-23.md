**结论：暂不通过。确认 4 项 P1、9 项 P2。**

审查范围：`wp/P2-1d-hybrid-checkpoint`，`d8633d8..a125c8960b888582cfb4a9d3a3b38fe8fd9364ad`。

当前会话的系统文件权限为只读，**未能创建 `docs/reviews/2026-10-23-FULL-REVIEW.md`**。以下为报告正文；工作区保持干净。

**验证范围**

- 检查全部 39 个变更文件，并追踪相关调用方和固定版本 llama.cpp。
- 使用现有编译产物重跑 **104 条 JVM 测试，全部通过**；另以内存假桥驱动真实引擎，验证异常移交、空闲 trim 和关闭原因码。
- 检查现有 APK、CMake 编译记录及快变体反汇编：两份库确实存在，快变体静态包含 llama/ggml，并实际包含 FP16 指令。
- 未重新构建、运行全新工作区门禁或重跑真机；已有产物检查不代表 HEAD 的重新构建验收。

**必修项**

1. **P1：第二个 CMake 构建会阻断现有 Ubuntu CI 和发布流程。**

   位置：[llm/build.gradle.kts:78](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/build.gradle.kts:78)、[llm/build.gradle.kts:94](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/build.gradle.kts:94)。

   SDK 查找固定为 `cmake.exe`；Linux 上回退到 `"cmake"`，随后拼出 `CMAKE_MAKE_PROGRAM=cmake\ninja.exe`。这不是有效的 Linux Ninja 路径。仓库的 [Android CI:17](D:/Agent/Worktrees/Mycelium-Android/p2-1d/.github/workflows/android-ci.yml:17) 和 [发布流程:22](D:/Agent/Worktrees/Mycelium-Android/p2-1d/.github/workflows/release.yml:22) 都运行于 Ubuntu，且打包必经该任务。

   修法：按宿主平台定位 SDK 内的 CMake/Ninja，使用路径 API 拼接；补 Linux 构建验证。关闭检查点不能绕开此问题。

2. **P1：快变体准入漏检实际使用的 FP16 指令能力。**

   位置：[NativeLibraryVariant.kt:30](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/NativeLibraryVariant.kt:30)。

   实测 `pick("asimddp i8mm", false)` 返回快变体，未要求 `fphp/asimdhp`。但编译参数包含 `+fp16`，现有快库 `ggml_vec_dot_f16` 中确认存在 `fmla v*.8h`、`fadd v*.8h`。因此“缺任何必需能力就退基线”的保护不成立；若设备缺少对应能力，推理时的 SIGILL 也不受 `System.loadLibrary` 外面的 `runCatching` 保护。

   修法：根据完整编译指令集建立能力掩码，至少补齐 FP16，并逐项测试缺失情况。优先通过基线探测入口读取 `AT_HWCAP/AT_HWCAP2`；相关能力应分别验证。[Android CPU 特性文档](https://developer.android.com/ndk/guides/cpu-features)、[Linux HWCAP 契约](https://www.kernel.org/doc/html/next/arch/arm64/elf_hwcaps.html)

3. **P1：句柄取出后、仓库接管前发生异常，会留下无人释放的原生快照。**

   位置：[LocalModelEngine.kt:757](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:757)、[LocalModelEngine.kt:817](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:817)。

   `takeCheckpointHandle()` 已将字节移入原生句柄表；随后查询大小、构造诊断字符串和仓库入库之间没有临时所有者的 `finally`。外层异常清理只释放已入库句柄及 pending，遗漏这份已取出但未入库的句柄。

   内存假桥在取出后注入异常，结果为：

   ```text
   usage=(0, 0)
   handles=[100:16]
   pending=null
   released=[]
   ```

   修法：让临时句柄从返回开始就受 `try/finally` 管理，仅在仓库成功接管后解除释放责任。补入库前失败和入库分配失败测试，断言原生存活句柄归零。

4. **P1：原生保存失败尚未实现安全降级，新增取句柄入口还会让 C++ 异常越过 JNI。**

   位置：[engine.cpp:308](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:308)、[engine.cpp:466](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:466)。

   保存快照的 `vector(size)` 分配失败会进入整轮生成的异常处理，中断回答。新增 `checkpointStates[handle]` 同样需要分配 map 节点，却完全没有异常保护，`bad_alloc` 可越过 JNI 边界。Android 明确要求 C++ 异常不得跨越该边界。[JNI 官方说明](https://developer.android.com/ndk/guides/jni-tips)

   修法：对快照保存及句柄登记分别捕获分配失败，释放临时数据并跳过优化；不能将保存失败升级为生成失败。准入还应考虑旧快照与新缓冲同时存在的原生峰值，当前 Java 堆余量检查不能证明这一点。

5. **P2：快变体构建的增量输入不完整，配置变化后可能继续打包旧库。**

   位置：[llm/build.gradle.kts:111](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/build.gradle.kts:111)。

   `buildFastNative` 只声明 `src/main/cpp` 输入和最终 `.so` 输出，没有关联 `march`、NDK、配置结果等输入。仅修改 `fastVariantMarch` 或工具链配置时，configure 可以重新执行，而 build 仍满足原来的 up-to-date 条件，Ninja 不会得到执行机会。

   修法：完整声明构建配置输入及配置任务输出依赖，或让该 Exec 每次调用 Ninja，由 Ninja 判断增量；生成库放入 `build` 下并注册为生成的 JNI 目录。增加只改架构配置后的重建验证。

6. **P2：低内存清理没有系统接线，空闲状态也不会实际释放。**

   位置：[LocalModelEngine.kt:578](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:578)。

   生产源码没有 `ComponentCallbacks2` 注册或调用这两个入口的代码。即使手动调用，入口也只置位；清理等待下一轮生成。复现实测：调用 trim 前后均为 `(1,16)`，原生句柄仍存活。生成结束前的 trim 检查已经补上，但不能覆盖空闲回收。

   修法：接入实际回调，异步取得 `gate` 清理空闲快照；保留在途轮次提交前的检查，主线程不等待推理锁。测试必须覆盖“不再生成下一轮也能释放”。

7. **P2：关闭检查点的原因码 7 在实际引擎中不可达。**

   位置：[LocalModelEngine.kt:659](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:659)、[LocalModelEngine.kt:682](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:682)。

   `planPrefix` 只在 `checkpointEnabled=true` 时调用，而且传入的 `enabled` 恒为 true。混合模型关闭开关的首轮，假桥复现最终统计为 **原因 1**，不是约定的 7。纯决策函数测试能通过，但没有验证真实编排。

   修法：将模型适用性、开关和保存位置分开决策，最终统计统一合成原因码；补混合模型关闭和纯注意力关闭的端到端编排测试。

8. **P2：十轮内存及清理测试无法检出当前原生所有权泄漏。**

   位置：[CheckpointTenRoundsDeviceTest.kt:53](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointTenRoundsDeviceTest.kt:53)、[CheckpointFlowTest.kt:390](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/test/java/com/xavierxia/mycelium/llm/CheckpointFlowTest.kt:390)。

   十轮测试测的是 Java 堆和 Kotlin 仓库账面字节；迁移到原生后，即使每轮漏掉一个原生句柄，这两项仍可能通过。清理假桥又没有实现 pending 的 discard 行为，也没有断言存活句柄集合：现有停止场景可呈现 **仓库为零、假桥 pending 仍为 16**，测试仍通过。

   修法：增加原生句柄总数、实际持有字节及 pending 字节的独立读数；覆盖正常覆盖、停止、取消、异常、trim、卸载后的释放断言。真机内存同时观测原生堆/PSS及峰值，不能仅用仓库记账自证。

9. **P2：第三轮逐字验收仍允许“恢复失败后全量重读”假通过。**

   位置：[CheckpointEqualityDeviceTest.kt:148](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointEqualityDeviceTest.kt:148)。

   第一组第二轮已经强制检查真实恢复；重新加载后的三轮组却只收集正文，第三轮只断言与关闭组输出相同。第三轮如果恢复失败并全量重读，仍满足断言。

   修法：在每个预期恢复轮次立即断言 `restoredFrom>0`、原因 0、复用数等于恢复位置、实读数正确，并检查正文非空；关闭组确认确实全量重读。落点计算失败时直接失败，不应替换成 `whole.size-1`。

10. **P2：差异归因测例没有构造出声称的“相同批次形状”对照。**

    位置：[CheckpointDivergenceCauseDeviceTest.kt:73](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointDivergenceCauseDeviceTest.kt:73)、[CheckpointDivergenceCauseDeviceTest.kt:105](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/CheckpointDivergenceCauseDeviceTest.kt:105)。

    第一组关闭检查点，使用 Qwen3.5 混合模型的旧裁尾路径；当前原生配置没有循环回退窗口，裁尾失败后会全量重读。测例没有核验两组实际复用位置和 decode 批次，却直接根据正文是否相同输出“恢复精确／恢复有偏差”，无法排除批次形状因素。

    修法：增加真正同边界、同 decode 批次的无序列化对照，并先验证实验条件；条件不成立时输出“无法归因”，不能给状态正确性的结论。

11. **P2：边界取证解码会静默吞掉超过 8 字节的 token。**

    位置：[engine.cpp:637](D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/cpp/engine.cpp:637)。

    `decodeTokens` 固定使用 8 字节缓冲，只追加正返回值。llama.cpp 在缓冲不足时返回所需长度的负值，因此较长特殊标记会直接消失。方案把部分特殊 token 的空串当作词表性质，证据受到这个解码缺陷污染。

    修法：复用已有 `piece()` 的扩容重试逻辑，保留完整 UTF-8，再重跑边界取证；内容断言应检查切点附近的结构，不能仅检查整段包含 `"user"`。

12. **P2：炉边实际缺省线程数仍为 6，与本次改成 8 的其他入口不一致。**

    位置：[LocalModelsViewModel.kt:32](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/main/java/com/xavierxia/mycelium/localmodels/LocalModelsViewModel.kt:32)，对应 [HearthViewModel.kt:219](D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:219)。

    没有已保存设置时，本地模型页和 `EngineOptions()` 使用 8，但直接进入炉边加载模型仍传 6。因此新用户实际聊天路径没有采用本次宣称的应用默认值。

    修法：集中定义缺省配置，让两个页面共用；保留用户已保存设置，继续在引擎入口按核心数夹取。

13. **P2：方案第 8 节仍与实现不一致，不能作为当前验收依据。**

    位置：[PLAN.md:211](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:211)、[PLAN.md:224](D:/Agent/Worktrees/Mycelium-Android/p2-1d/docs/reviews/2026-10-22-P2-1d/PLAN.md:224)。

    | 第 8 节表述 | 当前实现 |
    |---|---|
    | 总上限 600 MiB | 300 MiB |
    | 约六个并发会话 | 引擎只有一个缓存所有者，换键会作废旧会话 |
    | 全局 LRU | 按插入序号淘汰，查询不更新访问次序 |
    | 系统低内存回调已接入 | 无生产接线，手动入口也仅置位 |
    | 关闭报 7 | 实际引擎分支不可达 |

    修法：按最终实现重写第 8 节，明确持有量与瞬时峰值、原生句柄所有权和清理责任。第 8.1 节的“恢复后生成正常”也只能作为有限测量证据：探针后的生成没有缓存键，会重新清空并全量计算，不能证明恢复状态正确。

**重点路径核对**

- **rekey**：当前调用方限定为本地新会话获得正式编号；引擎在 `gate` 内同步修改缓存所有者和检查点键，后续仍校验真实公共前缀。未发现有证据的跨会话错误恢复。
- **常规作废／覆盖**：仓库移除路径已调用 `onDrop`，并最终释放原生句柄。
- **停止／取消／生成异常**：常规收尾已接入仓库失效和 pending 丢弃；第 3 项的“取出后未接管”异常窗口仍遗漏。
- **换模型／卸载**：Kotlin 清仓后原生清理句柄表与 pending，常规闭环成立。
- **低内存**：在途提交检查已存在；系统接线和空闲清理未完成。
- **整份恢复**：保存、恢复均使用 `NONE`，字节数严格相等；落点拆批、真实公共前缀上限和成功恢复后的续读路径已落实。库与应用保持关闭符合当前逐字验收未通过的处理方式。

**建议项**

- 增加经过 `HearthViewModel → chat.finish → rekey` 的测试。现有 rekey 真机测试直接调用引擎、使用手写键，不能证明应用接线正确。
- SDK 配置读取改用 `Properties.load()`，正确处理标准 Windows `sdk.dir` 转义；当前按行截取会保留反斜杠转义。
- 清理仍描述 `ByteArray`、`PARTIAL_ONLY`、旧恢复步骤的注释；`CheckpointStore.rekey` 的“保留位置最大”说明也应与实际按插入序号保留一致。
- 将临时 token 诊断和文件追加限制在诊断构建中。当前应用关闭检查点后，仍额外计算落点、再次 tokenize 并写入诊断文件。