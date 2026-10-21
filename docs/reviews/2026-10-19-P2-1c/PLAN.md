# P2-1c 本地对话提速（记住已读过的上下文）：实施方案

- 分支：`wp/P2-1c-prompt-cache`（从 `main` 的 `286f86a` 拉出；**不从 P2-2b 拉**）。
- 任务书：`docs/work-packages/P2-1c-local-prompt-cache.md`。
- 方案日期：2026-10-19（Asia/Shanghai）。
- 范围：`:llm`（含 `llm/src/main/cpp/engine.cpp`）、`:model-api` 的生成参数、炉边调用处传缓存键、以及对应测试与 `llm/README.md`。
- **不许碰**：llama.cpp 源码与固定提交（`5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`）、圆桌的调用、`contract/`、发布签名、版本号。

## 0. 方案审整改（2026-10-21，对应 `PLAN-REVIEW.md`）

Codex 方案审结论：**不通过**（关键三项：目标模型可能根本无法裁剪、无键轮次归属脱节、`pending` 区分不了临时会话）。
下面是逐条整改。**本节与后面各节冲突时，以本节为准**；E、D 已是本包必做，方案里「本轮不实现」的字样作废。

1. **目标模型的循环状态回退（必修 1）**——已查证固定提交 `5e03bdd` 的源码：
   - `llama-context.cpp:108`：`n_rs_seq > 0` 且架构不支持回退时会被**夹到 0**；默认值是 0（`:3763`）。
   - `llama-arch.cpp:1162`：`LLM_ARCH_QWEN35` / `QWEN35MOE` **在支持名单里**，所以目标模型不会被夹。
   - `llama-memory-recurrent.cpp:189-215`：循环状态「无法在序列末尾被部分擦除」，唯一机制是按 token 的快照，
     且**只有回退距离 ≤ `n_rs_seq` 才成功**；`llama-memory-hybrid.cpp:143` 在循环侧失败时整条返回 false。
   - `llama-memory-recurrent.cpp:261-296`：`seq_cp` 只是让两个 seq 指向**同一个 cell**，不能当快照用。
   - `llama-memory-recurrent.cpp:455`：尾部 `n_rs_seq + 1` 个 token 必须落在同一个 ubatch，快照才有效。
   - **做法**：`load()` 设 `cp.n_rs_seq = ROLLBACK_TOKENS(320)`、`cp.n_batch = cp.n_ubatch = 321`。窗口盖住
     「上一轮生成的 token（默认预算 256）+ 模板多出来的几个」；回退距离超出窗口 → 整段重读（结果仍然正确）。
   - **验收必须要求目标模型出现非零复用且实读量下降**，不能把「永远 fallback」当成做完；真机要记
     `RS buffer size`（`llama-memory-recurrent.cpp:124` 打印）以核对内存代价，内存过高就下调窗口。
2. **无键轮次清掉 Kotlin 归属（必修 2）**——`cacheKey == null` 的轮次进入时 `forgetCachedPrompt()`：单实例
   原生上下文上无键轮会清 KV 整段重读，旧归属已经描述不了引擎手里的提示。测例改成 `A → null(B) → A`，
   第三轮必须 `reuseTokens == 0`。
3. **临时会话身份（必修 3）**——`pending` 不再是固定字面量：每个临时会话拿一个**只驻内存**的唯一标识，
   在新对话、重新进入测试会话等边界更新并冻结进 `Turn`；首次落库后仍切到真实会话 ID（保留统筹批准的
   「新会话第二轮整段重读」）。补「同人物连续新建两段会话」「退出再进入测试模式」两例。
4. **事务边界与异常出口（必修 4）**——`tokenize` 与 `generate` 在**同一个 `runNative`/`begin`** 内完成
   （不能各套一次，否则 `begin()` 会清掉中间落下的停止）；原生加 `PromptGuard`：除非这一轮正常走到结尾，
   否则**所有提前 return 与异常出口**都丢掉记住的提示；decode 中止或状态不明按保守作废处理；Kotlin 在取消/
   失败路径同样作废。
5. **契约收紧（必修 5）**——统一为 `reuse = max(0, min(requested, nativeLcp, count - 1))`，全等时重读
   最后一个 token，负数一律按 0；原生返回**严格 8 项**（多一项「未复用原因」），长度不对、`count` 与本侧
   `tokenize` 结果不一致、复用/实读数超出范围都**立刻失败**，不再兼容旧的 5 项。JNI 只能用真机样例验证
   （编译成功不等于 ABI 对）。
6. **测试判别力（必修 6）**——JVM 用固定 token 向量覆盖中间分歧／全等／缩短／零公共前缀，纯追加样例不算；
   假桥自己保存旧状态并断言 `旧状态.take(reuse) + 新提示.drop(reuse)`（只称为决策/契约测试）；圆桌测例要
   捕获实际传入的参数，不是只看新建的默认对象；真机加「稳定键 + 真实追加问答」并断言复用数 > 0，温度 0 时
   缓存与全清各跑一遍**逐字比较正文**（不是只比 chunk 数）；把原生复用强制改成 0 时提速验收必须变红。
7. **统计口径边界（必修 7）**——`promptTokens` 是完整提示；正常完成时 `reused + read == promptTokens`，
   中断允许更小；`promptMillis` **不含**额外 tokenize、模板、加载与隐藏思考，因此不能当「发送到可见正文」
   的等待时间，另记首字耗时与无正文时长；`generatedTokens/generationMillis/tokensPerSecond` 口径不变，
   隐藏段统计只做追加，既有生成 token 数测例不许改成正文 token 数。
8. **E 的落点（必修 8）**——`TokenCallback` 带上每次原生 token 的身份，`ThinkingFilter` 同时报告隐藏区间
   （跨 token 标签、UTF-8 边界、未闭合段都要算对）；`GenerationStats` 追加隐藏段 token 数与耗时；耗时用
   生成阶段的单调时钟量，不能从 prefill 之前的 `onStart` 起算；取消时在 worker 已退出、仍持锁的收尾里提交
   统计再继续传播取消；日志只记数字与必要标志，不打印提示或隐藏正文。
9. **D 的落点（必修 9）**——在 ViewModel 里为本地轮次起独立计时任务，阈值常量 `20_000L`，**从接受这一轮
   起算**（含加载与 prefill）；结束条件是**过滤后出现非空白正文**，不是原生 token 到达；`HearthState`
   加独立提示字段，避免与加载/错误 `notice` 互相覆盖；用 `Turn` 身份 + generation token 防止旧任务写新页面；
   首字、完成、停止、失败、导航、清理都要取消计时；界面给提示并复用现有停止按钮、引导去人物编辑选云端，
   **不自动切换、不自动发送**。测例用可控时钟，不真等 20 秒。

采纳的建议项：日志记「为什么这一轮仍整段读」（原生第 8 项）；缓存键用**实际选中的**本地 `ModelInfo.id`；
`LongGenerationDeviceTest` 的 `bodyHasThink=false` 不能当作隐藏内容未泄露的证据。

---

## 1. 目标与验收

目标：同一段对话里，从第二轮开始「点发送到开始出字」的等待时间只和**新增内容**的长度有关，和整段历史的长短无关。P2-2 真机测速的痛点是约 1500 token 的提示**读提示要 94–110 秒**（生成 148 token 只要 18–21 秒）。

验收（照抄任务书，实现时逐条对照）：

- JVM 测试（假原生层）：相同前缀被复用；缓存键不同、换模型、截断时整段重读；不传缓存键时行为完全一样；停止和出错后缓存被作废或保持一致。
- 真机（红魔，用手机前先问用户）：1500 token 那段对话连问 3 轮，每轮记复用 token 数、实读 token 数、读提示耗时、生成耗时；温度 0 时有缓存与清空缓存逐字相同；截图。
- 全新 worktree 对已提交版本跑 `scripts/check-all.ps1`，零失败、零警告。

## 2. 现状（今天的行为，逐处点名）

- `llm/src/main/cpp/engine.cpp:144`：`llama_memory_clear(llama_get_memory(engine.context), true);` —— 每次生成前无条件清空 KV/循环状态，所以每轮都从第一个 token 重新解码整段提示。这就是 94–110 秒的来源。
- `engine.cpp:138-143`：先 `llama_tokenize(..., true, true)` 量出 `count`，再真正 tokenize 一次；`count + maximum > llama_n_ctx` 时直接抛「Messages and token limit exceed context length」。
- `engine.cpp:146-153`：提示按 128 一段 `llama_decode`，`prompt_ms` 量的是这一段。
- `engine.cpp:185-188`：回给 JVM 的是 5 个 long：`{count, generated, prompt_ms, generation_ms, cancelled}`。
- `engine.cpp:132-135`：从格式化后的提示里找最后一段 ` thinking`/`</think>` 判断是否处于思考段，并把它交给 `onStart(thinking)`。
- `llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:110-140`：`generate` 在一个 `gate` 互斥里跑原生；`runNative` 负责 begin/stop/join；`values[0..4]` 组成 `GenerationStats`。
- `EngineBridge`（`LocalModelEngine.kt:33-39`）就是本包所说的「假原生层」边界：JVM 测试用假实现替掉它。
- `model-api/.../ChatModel.kt:9-20`：`GenerationParameters(maxTokens, temperature, topP, seed)`；`ChatModel.generate(messages, parameters)`。
- 调用方：炉边 `app/.../hearth/HearthViewModel.kt:1019`（`model.generate(messages, turn.character.parameters())`，本地模型走这条）、圆桌 `roundtable/.../AnswerModel.kt:217`、本地模型测试页 `app/.../localmodels/LocalModelsViewModel.kt:82`、设备测例 `LocalModelDeviceTest` / `LongGenerationDeviceTest` / `HearthPromptSpeedDeviceTest`。
- 上下文窗口：`hearth/.../Prompt.kt` 的 `assemblePrompt` 永远是「人物设定（system）在最前 + 保留下来的完整轮次 + 当前问题」，装不下时**从最老的轮次开始丢**。
- 会话身份：`HearthViewModel` 的 `Turn(character, mode, conversationId, question, chosen)`（`HearthViewModel.kt:145-150`）；测试模式的 `conversationId` 恒为 `null`（`:856`），新会话的 ID 是**第一次落库时**由 `ConversationLog.append` 现生成的（`ConversationLog.kt:71`）。
- `llm/README.md:51` 写着「每次生成先清空 KV/循环状态」——本包要把它改掉。

## 3. 设计

### 3.1 分两层：决策在 Kotlin，执行在 C++

决定「这一轮复用多少 token」的逻辑放在 **Kotlin（`LocalModelEngine`）**，C++ 只负责「按给定数量裁剪 KV、只读剩下的」。理由：验收要求 JVM 测试（假原生层）能证明「相同前缀被复用 / 键不同整段重读 / 停止与出错后作废」，而前缀比对如果写在 C++ 里，JVM 测例就只能测到「键有没有传对」，证明不了核心逻辑。

- 新桥方法：`tokenize(messages: ByteArray): IntArray` —— 返回「按聊天模板格式化之后的整段提示」的 token 序列（与 `generate` 内部用的是同一条格式化+tokenize 路径，决定论下逐字节一致）。
- `generate` 增加一个入参 `reuseTokens: Int`（Kotlin 算出「前多少个 token 已经在 KV 里」），并**在原生侧再夹一次**（见 3.3）。
- 缓存键**不过 JNI**：它只是 Kotlin 侧的失效策略（比字符串），这样键的语义完全可测，也少一处跨语言契约。

### 3.2 缓存键怎么定

- 接口：`GenerationParameters` 增加 `val cacheKey: String? = null`（**默认 null**）。`null` 的语义是「**不要复用**，每轮整段重读」——与今天完全一样。
- 校验：`require(cacheKey == null || cacheKey.isNotBlank())`；并加长度上限（≤ 200 字符），避免把这个字段当自由文本用。
- 炉边传什么（`HearthViewModel.kt:1019` 一带）：`"${turn.character.id}|${turn.mode}|${turn.conversationId ?: "pending"}|${modelInfoId}"`。
  - 人物 ID：换了人物一定换键。
  - 模式：测试模式与沉浸模式各用一套（测试模式的会话是临时的）。
  - 会话 ID：`openConversation` 有值时用它；**新会话在第一次落库前还没有 ID**，用字面量 `pending` 占位（详见 3.4 的已知小损失）。
  - `:model-api` 的 `ModelInfo.id`：多一层保险（换模型本来就会 `load()` 作废缓存，见 3.4）。
  - 键里**不放** maxTokens/temperature/topP/seed：它们不影响提示的 token 前缀，采样参数变了照样可以复用。
- 圆桌、本地模型测试页、三个设备测例**都不传键**（`GenerationParameters()` 默认 null）→ 行为与今天逐字节一样。

### 3.3 前缀比对与 KV 裁剪

`LocalModelEngine` 侧持有：

- `cacheOwner: String?`：上一轮**被接受**的缓存键（null 表示没有可复用的缓存）。
- `cachedPrompt: IntArray?`：上一轮**完整读完**的那段提示的 token 序列。
- `loadedModelId: String?`：`load()` 时记下；`load`/`unload` 一律清空上面两项。

一轮 `generate` 的算法：

1. 取 `key = parameters.cacheKey`。
2. `tokenize(messages)` → `tokens`。
3. `reuse = 0`；若 `key != null && key == cacheOwner && cachedPrompt != null`，则 `reuse = 最长公共前缀(cachedPrompt, tokens)`，再夹三道：不超过 `cachedPrompt.size`、不超过 `tokens.size`、不超过 `tokens.size - 1`（至少留一个 token 让原生解一次；全等时不复用、走整段读，避免「一个 token 都不解」的退化路径）。
4. 调 `generate(..., reuseTokens = reuse, ...)`。
5. 原生回 `{count, generated, prompt_ms, generation_ms, cancelled, reusedTokens, readTokens}`。Kotlin 用**原生回的** `reusedTokens/readTokens` 记账（不是自己算的那个数）。
6. **缓存是否继续可用**的判据（完成、停止、出错各一条）：
   - 正常结束且 `readTokens == tokens.size - reusedTokens`（提示确实读完了）→ `cacheOwner = key`、`cachedPrompt = tokens`；否则作废（见 3.5）。
   - `key == null` 的轮次：**不复用，也不读不写缓存状态**（`cacheOwner`/`cachedPrompt` 原样不动）。这样「不传键 = 完全不影响缓存状态也完全不复用」，最容易论证，也好写测例。

C++ 侧（`engine.cpp`）：

- 新状态：`std::vector<llama_token> cachedPrompt`（上一轮完整读完的提示 token）、`bool promptComplete`。
- `generate(..., jint reuseTokens, ...)`：
  - tokenize 后（沿用现在的两趟写法，第一趟量长度）；
  - `int reuse = std::min({reuseTokens, (int)lcp(cachedPrompt, tokens), (int)tokens.size() - 1})` —— **原生自己再夹一次**，即使 JVM 传错也不会读到不该读的 KV（纵深防御，且这层不受 JVM 测例覆盖，设备测例兜底）。
  - `reuse > 0` 时 `llama_memory_seq_rm(llama_get_memory(ctx), 0, reuse, -1)`；**返回 false 或 `reuse == 0` 时退回 `llama_memory_clear` 并把 `reuse` 记 0**。
  - 从 `reuse` 起按 128 一段 decode，`readTokens` 记实际解了多少；`prompt_ms` 只量这一段（这才是「读提示耗时」的真实改善量）。
  - 收尾：`promptComplete = (readTokens == tokens.size() - reuse)`；`promptComplete` 为真时 `cachedPrompt = tokens`，否则 `cachedPrompt.clear()`（并 `llama_memory_clear`）。
  - 返回 7 个 long：`{count, generated, prompt_ms, generation_ms, cancelled, reuse, readTokens}`。
- `load` 与 `unload`（`Engine::clear()`）里一并清 `cachedPrompt` —— 换模型、卸载、重新加载一律重新开始。
- `tokenize` 新 JNI 函数：返回 `int[]`（同一条 `prompt()` + `llama_tokenize` 路径）。

### 3.4 什么时候整段重读（清单）

| 情况 | 行为 | 谁保证 |
|---|---|---|
| 不传缓存键（null） | 整段重读，**并清掉 Kotlin 侧归属**（第 0 节整改 2） | Kotlin（`reuse = 0` + `forgetCachedPrompt()`） |
| 缓存键与上一轮不同 | 整段重读，并接管缓存 | Kotlin |
| 换模型 / 重新 `load()`（含换线程数、上下文长度）/ `unload()` | 整段重读 | Kotlin 清状态 + 原生 `clear()` |
| 上一轮没把提示读完（停止/取消/出错） | 整段重读 | Kotlin 判 `readTokens` + 原生清 `cachedPrompt` |
| 新提示的前缀与缓存不同（历史被截断、系统提示或人物设定改过、上游换了对话但键没换） | 从第一个不同的 token 起整段重读；**公共前缀仍然复用** | 两边各算一次公共前缀 |
| 底层不支持部分删除（`llama_memory_seq_rm` false） | 退化为整段重读 | 原生 |

两条要写进文档的口径：

- 「截断时整段重读」在本设计里**不是逐字成立**：历史从最老的轮次开始丢，人物设定（system）仍在最前，所以截断后**设定那一段照旧复用**，从被保留的第一轮起重读。这正是任务书第 2 步那句「截断时尽量让人物设定这一段保持在最前面不变，这样至少设定部分不用重读」想要的效果。测例按「复用数 = 真实公共前缀长度」来断言，不写成「整段重读」。
- **已知小损失（写进交付报告）**：一个**新建**会话的第一轮用 `pending` 键；那一段对话第一次落库时才拿到真实会话 ID，所以同一个新会话的**第二轮**键就变了（`pending` → 真实 ID），第二轮仍要整段重读；**第三轮起**才复用。有历史的重开对话（`conversationId` 一开始就有值）不受影响，第二轮就复用 —— 而 94–110 秒那类长历史正是这种。要不要为第二轮也优化（例如把会话 ID 提前分配）留给统筹，本轮不做。

### 3.5 停止、取消、出错后的作废规则

- **提示读完之前被停掉**：原生 `readTokens < tokens.size - reuse` → `promptComplete = false` → `cachedPrompt.clear()` + `llama_memory_clear`；Kotlin 看到读数对不上，也清 `cacheOwner/cachedPrompt`。下一轮整段重读。「缓存处在可用状态，或被明确作废」取**明确作废**这一支（更保守）。
- **提示读完、生成中途被停掉**：提示的 KV 是完整的，`promptComplete = true` → 缓存**保持可用**；生成的半截 token 下一轮会被「裁到公共前缀」这一步自然删掉，不会带进下一轮。
- **原生抛错 / JVM 侧异常**：原生走 `catch` 分支时同样 `cachedPrompt.clear()`；Kotlin 在 `catch`/取消路径清 `cacheOwner/cachedPrompt`。两条路径都不留半截内容。
- 作废只影响**下一轮是否复用**，不影响这一轮已经发出去的正文或已经落库的内容。

### 3.6 统计口径

- `GenerationStats` 追加两个字段（放在末尾、带默认值，既有构造点不动）：`reusedPromptTokens: Long = 0`、`readPromptTokens: Long = promptTokens`。
- `promptTokens` 仍 = 本轮整段提示的 token 数（`count`）；`promptMillis` 改口径为**只量实际读的那一段**（真机测速要的就是这个数，交付报告与真机日志都记 `reusedPromptTokens / readPromptTokens / promptMillis / generationMillis`）。
- 兼容：既有的 `GenerationStats(0,0,0,0,stopped=true)`（`HearthJvmHarness.kt:368`）等位置因为默认值不动；`LocalModelEngine` 从原生数组读 7 个值，读不到 7 个（老假实现）时按 0/整段处理并 `error(...)` 提醒测例要更新。

### 3.7 JNI 契约变化（一处改动、三处跟随）

- `EngineBridge`：`tokenize(messages: ByteArray): IntArray`（新增）、`generate(..., reuseTokens: Int, callback: TokenCallback): LongArray`（改签名）。
- `NativeBridge`：对应加/改 `external fun`。
- **所有假实现都要跟着改**：`llm/src/test/.../LocalModelEngineTest.kt` 的两个桥；新增测例自己的桥。
- 原生数组改成 7 项后，`longArrayOf(...)` 那两处（`LocalModelEngineTest.kt:48`、`:74`）必须补齐。

## 4. 分步实施（每一步一个提交 + 该步的 JVM 测例）

对应任务书第 1–5 步。**先把假原生层的新契约和前缀比对做到能测，再接缓存键，最后接炉边**，这样每一步的测例都能独立变红。

**第 1 步（任务书第 1、5 步）引擎保留缓存 + 统计**
- 改 `EngineBridge`/`NativeBridge`：加 `tokenize()`、`generate` 加 `reuseTokens`、数组改 7 项。
- `model-api`：`GenerationParameters` 加 `cacheKey: String? = null` + 校验 —— **复用的闸门就是它**，所以第 1 步就得先把它加上，否则「复用」没有开关、「不传键行为完全一样」也无法从第一个提交起成立。（键本身的校验测例也在本步。）
- `LocalModelEngine`：加 `cachedPrompt`/`cacheOwner`；写「最长公共前缀 + 三道夹」；**键为 null 时 `reuse = 0`，且不读也不写缓存状态**。
- `GenerationStats` 加 `reusedPromptTokens`/`readPromptTokens`；`lastStats` 用原生读数。
- 改 `engine.cpp`：`tokenize` JNI、`generate` 加 `jint reuseTokens`、`llama_memory_seq_rm` 裁剪 + `clear` 退回、`readTokens`/7 项返回、`load`/`clear` 清状态。
- 改 `llm/README.md` 里「每次生成先清空 KV」那句。
- 测例（新文件 `llm/src/test/java/.../PromptCacheTest.kt`，用假桥）：
  - `reusesTheCommonPrefixOfTheSamePrompt`：同一段提示同一个键连发两轮 → 第二轮桥收到的 `reuseTokens` = 公共前缀长度、`readPromptTokens` = 差额、`reusedPromptTokens` = 复用数。
  - `growsOnlyOverTheNewTail`：第二轮多了一问一答 → 复用数 = 上一轮提示的完整长度（前缀没变的部分），实读数 = 新增部分的长度。
  - `neverReusesMoreThanTheCachedPrefix` / `alwaysLeavesAtLeastOneTokenToDecode`：夹子生效。
  - `promptMillisCountsOnlyTheReadPart`：计时口径。
  - `noCacheKeyNeverReusesAndLeavesTheCacheAlone`：不传键连发两轮 → 两轮都整段读；随后带键的一轮仍能复用（证明 null 轮次没搅乱状态）。
- 判别力：把公共前缀算成「整段长度」→ 上面第 2 条变红；把夹子去掉 → 第 3 条变红；把 null 闸门去掉 → 第 5 条变红。还原后重跑全绿。

**第 2 步（任务书第 2 步）什么时候必须整段重读**
- `LocalModelEngine`：键不同 → 清状态并接管；`load()`/`unload()` 一律清；历史被截断（前缀变短）→ 只复用真实公共前缀。
- 测例：
  - `aDifferentCacheKeyStartsFromScratch`：键不同（提示前缀相同）→ 第二轮 `reuseTokens == 0`。
  - `aReloadStartsFromScratch`：`load()` 另一个模型（或同一个模型再 `load`）→ `reuseTokens == 0`。
  - `anUnloadStartsFromScratch`。
  - `aTruncatedPromptReusesOnlyTheUnchangedPrefix`：把最老的轮次丢掉 → 复用数 = 新的公共前缀长度（人物设定那段仍在），不等于上一轮提示长度。
- 判别力：把键比较写成恒真 → 第 1 条变红；把 `load` 的清状态去掉 → 第 2 条变红。

**第 3 步（任务书第 3 步）炉边传键，圆桌不传**
- `hearth`：加纯函数 `cacheKey(character, mode, conversationId, modelId)`（放 `Prompt.kt`），`PromptTest` 断言：同一人物同一会话稳定、换人物/换会话/换模式/换模型立刻不同、测试模式带 `pending`。
- `app`：`HearthViewModel.kt:1019` 改成 `turn.character.parameters().copy(cacheKey = cacheKey(...))`（或给 `parameters()` 加可选参数）；用 `HearthJvmHarness` 写一条：本地模型一轮走完，假引擎收到的 `cacheKey` 与 `cacheKey(...)` 相等；切到另一段会话后收到的是另一个键。
- `roundtable`：加一条测例断言圆桌构造的 `GenerationParameters().cacheKey == null`（并保持 `AnswerModel.kt:217` 一行不改）。
- 判别力：把炉边那一行改回不传键 → 第 2 条测例变红。

**第 4 步（任务书第 4 步）停止、取消、出错的作废**
- 原生：`promptComplete` 与 `cachedPrompt.clear()`；Kotlin：`readTokens` 对不上就清状态，取消/异常路径同样清。
- 测例：
  - `aStopBeforeThePromptIsReadInvalidatesTheCache`：假桥在提示读一半时停下（`readTokens` 少）→ 下一轮同键 `reuseTokens == 0`。
  - `aStopDuringGenerationKeepsTheCacheUsable`：提示读完、生成中途停 → 下一轮同键仍然复用（且复用数不超过公共前缀）。
  - `aFailureInvalidatesTheCache`：假桥抛错 → 下一轮 `reuseTokens == 0`。
  - `cancellingTheCollectorInvalidatesTheCache`：`job.cancelAndJoin()` 之后同键不再复用。
  - `reuseNeverChangesWhatTheModelIsAsked`（正确性核心）：同一段提示在「复用」与「不复用」两条路径上，**交给原生的 token 序列完全相同**（假桥把两次收到的 `(tokens, reuseTokens)` 记录下来断言 `tokens.drop(reuseTokens) + 已复用前缀 == 整段`），这是「温度 0 时有缓存与无缓存输出逐字相同」的 JVM 侧对应物；真机那条仍然照任务书做。
- 判别力：把作废条件写成恒假 → 第 1、3、4 条里至少两条变红。

**第 5 步（任务书第 5 步）统计落地**
- 设备测例 `HearthPromptSpeedDeviceTest` / `LocalModelDeviceTest` 里把 `reusedPromptTokens / readPromptTokens / promptMillis / generationMillis` 打进日志（真机证据用）。
- 交付报告写入口径与读数。
- 测例：`statsCarryTheReuseSplit`（`lastStats` 三个数与假桥报回的一致）。

## 5. 测试计划全貌

- **JVM（假原生层）**：上面第 1–5 步的测例，全部在 `:llm`、`:hearth`、`:app`、`:roundtable`、`:model-api` 的现有测试任务里。`EngineBridge` 就是「假原生层」边界，本轮**不新增**任何真实原生依赖。
- **不覆盖的**：`engine.cpp` 里 `llama_memory_seq_rm` 的实际行为、`tokenize` 与 `generate` 内部 tokenize 的一致性、真机上的时间收益 —— JVM 测例结构上测不到（没有原生单测），由**真机测例**兜底（温度 0 逐字相同 + 三轮计时）。
- **真机（白天，先问用户）**：任务书验收那三条。用 P2-2 那段约 1500 token 的对话连问 3 轮；温度 0 时「有缓存 / 清空缓存」逐字对比；截图。
- **门禁**：全新 worktree 对 P2-1c 的尖端跑 `scripts/check-all.ps1`，零失败、零警告。

## 6. 风险与边界

- **缓存键写错会答非所问**（读到别的人物/别段对话的缓存）。三层防护：键不同一律整段重读；两边各算一次最长公共前缀；原生再夹一次。测例覆盖「换人物、换会话、换模型、截断」。
- **多花一次 tokenize**：Kotlin 的 `tokenize()` 与原生 `generate` 内部各 tokenize 一次（决定论下结果相同）。1500 token 的 tokenize 相对 94–110 秒的解码可忽略；换来的是核心决策可被 JVM 测例证明。方案审若判不值得，可退回「原生算前缀、Kotlin 只管键」，代价是核心逻辑不可测（这个取舍写在这里，供审核者判）。
- **内存**：8192 上下文的 KV 本来就分配好了，复用不额外分配；多出来的只有 `cachedPrompt`（最多 8192 个 int，32KB）与一次 JNI int 数组。真机要确认长时间多轮对话后内存不持续上涨（加入白天的观察项）。
- **不许碰的**：llama.cpp 源码与固定提交、圆桌调用、`contract/`、发布签名、版本号；本包不改 `:net`、`:app` 的更新相关代码。
- **实现顺序先 Kotlin 后 C++ 的代价**：第 1 步会同时改 JVM 与原生契约，`check-all` 在设备测例编译前不会发现原生侧拼写错误；所以第 1 步提交前要单独跑一次 `:llm:testDebugUnitTest` + 一次 `:app:assembleDebug`（能编过原生即说明 JNI 名对得上）。

## 7. 隐藏思考段怎么处理（**本轮做 E + D**，见第 0 节整改第 8、9 条）

这是 P2-2 已知问题第 3 条：本地模型把生成预算花在**界面上看不见的思考段**上，界面长时间没有字。

现状机制（读代码得到，不是猜的）：`engine.cpp:63-64` 在聊天模板变量里设 `enable_thinking = false`；`:132-135` 再从格式化后的提示里找最后一段 ` thinking`/`</think>` 判断「这一轮是不是思考中」，通过 `onStart(thinking)` 告诉 Kotlin；`llm/.../TextStream.kt` 的 `ThinkingFilter` 把思考段从正文里滤掉。也就是说：**文本被丢掉，但 token 预算和等待时间被照常花掉**。

**先要一条事实**（建议列进白天的真机项，不实现代码）：在同一台手机、同一个模型上，量三件事 —— ①一轮里隐藏段占了多少 token / 多少秒；②`enable_thinking=false` 到底有没有被模板认（把格式化后的提示打出来看有没有 ` thinking`）；③隐藏段结束后正文还有多少预算。**没有这三个数，下面的取舍都只是猜**。

选项（按「改动面」从轻到重）：

| 选项 | 做法 | 收益 | 代价 / 风险 |
|---|---|---|---|
| A. 可见进度 | 隐藏段的字符/token 数当进度信号，界面显示「正在思考…（已 240 字）」，**内容仍然不显示** | 用户知道在动，不像卡死；不动生成语义 | 要接 `onStart(thinking)` 的既有信号；不减少等待 |
| B. 思考段预算 | 隐藏段超过 N token 或 T 秒就强制结束思考（采样层限制/截断），把剩下的预算留给正文 | 直接减少浪费，正文更容易出字 | 会改变生成结果与答案质量；原生采样/停止逻辑要动，风险最高 |
| C. 模板层彻底关掉思考 | 换/改模板参数，确认各模型模板对 `enable_thinking` 的支持 | 从源头不产生隐藏段 | 与部分模型的模板不兼容；可能弄坏本来正常的模型 |
| D. 首字超时兜底 | 超过 T 秒还没有可显示的正文 → 给提示（可停止、可换云端） | 用户有出路，不改生成 | 只是兜底；阈值要真机定 |
| E. 事后诊断 | 隐藏段长度/耗时只记进统计与日志（不进界面） | 零风险，给选型与后续决策供数据 | 用户看不到任何改善 |
| F. 预算分开算 | `maximum` 只算**正文** token，隐藏段不占正文预算 | 正文不会被思考挤没 | 改原生计数口径，同样改变输出 |

**建议（留给统筹裁定，本轮不做）**：

1. **先做 E + D**：E 零风险、立刻给数据；D 是纯体验兜底，两者都不改生成语义。
2. **A 视用户观感决定**：如果真机数据显示隐藏段经常超过十几秒，A 的收益就明显了。
3. **B / C / F 要有真机对比数据再定**：同一问题、温度 0、有/无截断各跑几次，对比答案质量与出字时间；这三条都会改变输出，**建议单开一个工作包**（例如 P2-1d「本地模型思考段预算」）而不是搭在 P2-1c 里 —— P2-1c 的验收是「输出逐字不变」，把改变输出的事混进来会污染这条验收。
4. **不要**先斩后奏地把隐藏段的 token 塞进正文（那等于把 P2-2 的隐私/观感设计推翻）。

## 8. 待统筹或用户裁定（每条写清先选的保守做法）

| 事项 | 先选的保守做法 |
|---|---|
| 缓存键的组成（含不含 `ModelInfo.id`） | 含（多一层保险，`load()` 本来也会作废）；如果裁定去掉，测例相应收窄即可 |
| 新建会话第二轮仍要整段重读（`pending` → 真实 ID 换键） | 接受这个小损失，不提前分配会话 ID（那会动 P2-2 的落库语义）；写进交付报告 |
| 任务书验收写的「截断时整段重读」 | 按**真实公共前缀**复用实现并断言（人物设定那段继续复用），交付报告写明与字面口径的差别 |
| 多一次 tokenize（决策放 Kotlin 换 JVM 可测） | 接受（1500 token 的量级可忽略）；若方案审判不值得，退回「原生算前缀」并如实写明核心逻辑不可测 |
| `promptMillis` 口径改成「只量实际读的那一段」 | 按新口径实现，交付报告与真机日志都注明口径，避免和 P2-2 的 94–110 秒直接对比时误读 |
| 隐藏思考段（第 7 节） | 统筹已裁定：本轮做 **E + D**（E 把隐藏段 token/耗时记进统计与日志；D 是 20 秒没正文的提示）。B/C/F 会改变输出，不放进本包，先取真机数据再单开 P2-1d |