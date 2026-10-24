**总体结论：现稿不可直接通过；修正下列契约后，可进入 S2/S3。** 本次基于 `dcc804e` 只读审查，未修改文件、未运行构建或真机。已核实本地 llama.cpp 为锁定提交 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，源码无修改。

**A. 落点：1116 可作为本组数据的恢复边界；现稿对边界的解释与推导不可通过。**

- `1120 > 1116`，所以 **1120 的状态不能用于这一次续聊**；1116 表示已经读完前 1116 个共同 token，作为检查点位置成立。
- 但 **1116 并非已证明的“助手角色头之前”**。方案列出的 `248068`、`248069` 实际是 `<think>`、`</think>`。锁定源码内的 Qwen3.5 模板先输出助手角色头，再输出思考预填充段；现有证据指向“助手头之后、思考预填充之前”。[官方词表](https://huggingface.co/Qwen/Qwen3.5-4B/raw/main/tokenizer_config.json)、[本地模板](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/models/templates/Qwen3.5-4B.jinja:147)
- 两次 tokenize 的**长度差不能单独证明边界可靠**：模板开关可能改变其他内容，分词也可能在拼接边界发生变化。该公式实际上令 `checkpointPos = tokensWithoutGenerationPrompt.size`，尚未证明它等于 1116。
- 探针只打印分叉后的前三个 token，因此“剩余四个 token”却只列三个 ID 有明确原因；此外 `candidate_A = t2.size - 1` 只是占位值，并没有测出用户消息边界。[探针代码](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/app/src/androidTest/java/com/xavierxia/mycelium/hearth/HybridCheckpointDivergenceDeviceTest.kt:75)

**必修：**纠正边界命名及“与 server 用户开始位置相同”的说法；补齐实际模型的完整尾部 token、解码文本及两次 tokenize 的结果。实现必须比较 token 内容，确认无 generation prompt 序列是完整提示的前缀；不成立时使用经验证的更早边界或放弃保存。恢复时仍须满足 `cp ≤ 本轮真实公共前缀`，不能写死 1116。

**B. 恢复顺序：方案的“先 set_data，再 seq_rm”可；API 成功判据及状态前提必须补齐。**

正确顺序是：

1. 选择与当前缓存状态相容的检查点。
2. `set_data_ext(ctx, bytes, bytes.size, 0, PARTIAL_ONLY)`，检查返回值**等于 `bytes.size`**。
3. `llama_memory_seq_rm(memory, 0, cp, -1)`，必须返回 `true`。
4. 从 `cp` 续读，至少保留一个待 decode 的 token。

**不要求外部先 `seq_rm`。** 混合实现先尝试裁剪循环状态；尚未恢复时，这一步可能失败。恢复循环状态后，其最后位置应是 `cp - 1`，再删除 `[cp, end)` 就不会要求循环状态倒退。循环状态加载器内部会清理自己的目标序列。[混合内存实现](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/src/llama-memory-hybrid.cpp:143)

**必修：**

- `get_data_ext`、`set_data_ext` 返回的是字节数，不能写成“返回 false”或仅判断非零。保存须在准确读到 `cp` 后，以相同序列和 flags 调用 `get_size_ext → 分配 → get_data_ext`，验证大小一致；`get_size_ext` 不是成功恢复的判据。
- `PARTIAL_ONLY` 不包含 Qwen3.5 的普通注意力 KV，必须保留同一模型、同一上下文中正确的前缀 KV；不能先清空整个序列再期待部分状态恢复一切。
- **增加旧分支检查点作废规则。** 当前 Store 只认键和位置。若本轮在 400 分叉，旧分支的 700 检查点未删除，后续公共前缀增长后可能重新选中错误状态。至少删除超过本轮有效公共前缀的旧检查点，或保守删除恢复位置之后的检查点；全量重置时清除失效检查点。上游 server 也显式删除回退点之后的检查点。[参考实现](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/tools/server/server-context.cpp:3619)
- 恢复或裁剪失败后清理不可信状态，从 0 重读并报 6，不能在部分恢复后的状态上直接继续。

**C. 只对 hybrid/recurrent 启用：可。**

两个 API 在锁定版本中均有声明和实现；`QWEN35`、`QWEN35MOE` 也确实被归类为 hybrid，无需改依赖版本。[锁定头文件](https://github.com/ggml-org/llama.cpp/blob/5e03bdd8700948b9c41c54dd1b00f28a2aebc03f/include/llama.h#L667)、[架构判定](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/src/llama-arch.cpp:1124)

本条无独立必修项；纯注意力的实际分流还需按 D 修正。

**D. 原因码：5/6/7 可，不复用 4 可；纯注意力不新增原因码可，但当前分流契约不可。**

建议固定为：

| 情况 | 原因码 |
|---|---:|
| 检查点路径已启用，但无可用检查点 | 5 |
| 检查点恢复或恢复后裁剪失败，已转全量重读 | 6 |
| 混合/循环模型的检查点开关关闭 | 7 |
| 纯注意力模型 | 沿用 P2-1c 的原有码 |

“不带新码”不等于取消原来的 `reuseFallbackReason`。

当前 [CheckpointPlan.kt](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointPlan.kt:60) 有两个问题：

- 先检查开关，因此 `checkpointable=false && enabled=false` 仍返回 7，与第 7 节“不占新码”的口径冲突。
- 纯注意力返回 `FullReread`，还写着“与 P2-1c 一样整段重读”；实际上 P2-1c 会正常复用、裁剪注意力 KV。

**必修：**先按模型适用性分流，或由调用方保证纯注意力不进入此决策器；增加明确的“走 P2-1c”结果，避免将其解释为强制全量重读。覆盖纯注意力在开关开、关两种情况下的行为。

**E. 原因码校验：扩展上限是必改之一；“只改这一行就够”不可。**

全仓相关源码搜索中，原因码范围硬编码 `0L..4L` 只找到 [LocalModelEngine.kt:240](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:240) 这一处；其后的禁止原因 4 应保留。

但还有三个联动点：

- **第 242 行**强制 `(reason == 1) == (requested == 0)`。首轮 `requested=0` 返回 5，或关闭检查点返回 7，即使扩大范围仍会被拒绝。
- **第 245 行**仅检查原因 2/3 时 `reused=0`，必须补上 5/6 的一致性约束；7 按最终确定的 P2-1c 回退行为校验。
- **第 205 行**固定统计数组为 8 项。S2 添加检查点统计时，JNI 数组、解析、正常结束及取消收尾、假桥测例必须同步更新。

**必修：**按执行路径重写原因码一致性校验，保留旧路径校验强度；增加首轮原因 5、关闭原因 7、恢复失败原因 6、非法码 4/8，以及“报失败却声称复用”等跨层契约测例。

**F. 上限：每键 3 份、总量 256 MiB、按插入序号淘汰，可作为初始参数；内存安全论证不足。**

Store 的淘汰实现符合要求，“选择位置最大”与“淘汰插入最旧”也正确区分了。代码实际单位是 **MiB**，不是十进制 MB。

按官方 Qwen3.5-4B 配置及锁定源码的 F32 循环状态布局估算，单序列一份数据约：

`24 × [128×4096 + 3×(4096 + 2×16×128)] × 4 = 50.25 MiB`

加少量序列化元数据，三份约 **150.75 MiB**。这是推算，不能替代用户这份 GGUF 的实测；权重量化为 Q4_K_M 不意味着循环状态也按 4 bit 保存。[官方配置](https://huggingface.co/Qwen/Qwen3.5-4B/raw/main/config.json)、[锁定状态类型](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/vendor/llama.cpp/src/llama-model.cpp:2772)

256 MiB 约为 22 GiB 的 1.1%，作为保留数据上限合理，但手机总内存不能代表应用可用堆。当前 `put()` **先 `copyOf()` 再淘汰**，还可能叠加原生缓冲、JNI 字节数组及等待 GC 的旧数组；`totalBytes` 不代表峰值占用。[Store 实现](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/main/java/com/xavierxia/mycelium/llm/CheckpointStore.kt:55)

**必修：**明确 256 MiB 限制的是保留数据，并补充临时分配预算与字节所有权设计；分配前预检、必要时先腾出空间，避免不受控的多份复制。最终份数和预算可待真机调整。

**G. 真机逐字比对通过前默认关闭：可，与任务书一致。**

任务书明确要求逐字比对不通过就关闭交付；验证通过前默认关闭是相容的保守实施方式。

但目前 PLAN 只声明两个开关，**没有写明默认值和组合规则，生产代码也尚未接入**。

**必修：**明确正式默认值为 `false`、测试如何受控开启、两侧最终生效值如何确定。开关关闭时应停止检查点保存和恢复，并走 P2-1c；对照测试必须断言开启组确实出现 `fromCheckpoint=true`。

**H. 验收覆盖：现有测例不足以宣称六条已覆盖；补齐集成测例后可验。**

当前 Store 和 Plan 尚未接入 `LocalModelEngine`，现有测试主要证明容器规则和决策返回值。

| 验收项 | 当前能证明什么 | 必须补什么 |
|---|---|---|
| 选中不超过公共前缀的最近检查点 | 已覆盖位置最大、同位置取新 | 旧分支作废、原生 KV 相容性、至少留一个 token |
| 无检查点→原因 5、全量重读 | 只证明返回决策和码 | 引擎实际从 0 decode、统计被正常接受 |
| 恢复失败→全量重读 | 目前只断言函数返回枚举 6 | 注入短读/恢复失败/裁剪 false，断言清理、重读及原因 6 |
| 换键/换模型/停止/出错作废 | 只证明手动 `invalidate/clear` 有效 | 经真实引擎生命周期触发，包括无键请求、取消和失败 |
| 超限淘汰最旧 | 已覆盖基本规则 | 乱序位置的淘汰、实际存取链路预算与分配失败 |
| 纯注意力不走检查点 | 只覆盖开关开启时的决策 | 两种开关状态下不调用检查点 API，且保留原 KV 复用 |

停止尤其不能直接“沿用就算通过”：现有 [PromptCacheTest](/D:/Agent/Worktrees/Mycelium-Android/p2-1d/llm/src/test/java/com/xavierxia/mycelium/llm/PromptCacheTest.kt:362) 明确允许“提示已读完、生成中停止”后保留旧缓存。必须明确本包检查点路径的作废行为，同时守住纯注意力原路径。

**必修：**将 PLAN 中“S1 覆盖六条”改为真实覆盖状态，补充上述假原生编排测试及原生状态集成测试。真实状态是否正确、目标手机上的逐字一致和收益，不能由返回枚举的测试代替。

**必修项清单**

1. 修正 1116 的边界解释，补齐 token 证据及内容级边界校验。
2. 写全状态存取的字节数判据、准确位置、KV 前提和失败清理流程。
3. 增加同键分叉后的旧检查点作废规则，并接入生命周期清理。
4. 修正纯注意力分流、开关优先级及 `FullReread` 契约。
5. 同步修改原因码范围、一致性校验及 JNI 统计协议。
6. 补充临时内存预算与字节所有权设计。
7. 固化默认关闭及可验证的测试开启方式。
8. 补齐六条验收的实际编排/原生覆盖，纠正现有覆盖声明。

**可留待真机/后续的项**

- **S5 必须完成，不能当作可省略项：**真实 GGUF 的检查点字节数、存取耗时；三轮温度 0 开关对照逐字一致；第二、三轮提示耗时收益；十轮应用内存、托管堆/原生峰值及检查点占用；截图。
- 可按这些读数调整 3 份、256 MiB；同位置检查点替换、最小保存间距等优化可后续再做。
- 合并交付前仍须完成任务书要求的全新 worktree 门禁。