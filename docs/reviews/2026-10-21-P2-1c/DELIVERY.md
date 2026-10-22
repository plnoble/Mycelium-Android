# P2-1c 交付文档：本地对话提速（复用已读提示的 KV）

- 分支：`wp/P2-1c-prompt-cache`（从 `main` 并入最新后开工；**未推送、未合并**）。
- 提交（尖端 `1230b55`）：`150d732` 第 1 步（契约/缓存/统计）→ `9292869` 第 2 步（失效）→ `96f978a` 第 3 步（炉边键）→
  `f1eb4f5` 第 4 步（停止/取消/出错）→ `ff90e30` 第 5 步（真机测速改写 + 温度 0 对比）→ `fe173c8` E（隐藏段统计）→
  `0562f7f` D（20 秒无正文提示）→ `5775394` 全量审第一轮整改 → `f8ad3b0` 全量审第二轮整改 → `1230b55` 第三轮残余项。
- 任务书：`docs/work-packages/P2-1c-local-prompt-cache.md`；方案与方案审：`docs/reviews/2026-10-19-P2-1c/PLAN.md`、`PLAN-REVIEW.md`。

## 1. 做成了什么

- **原生侧**（`llm/src/main/cpp/engine.cpp`）：按最长公共前缀裁掉提示尾部、只重读变动的部分；8 项读数
  （提示 token、生成 token、读提示毫秒、生成毫秒、是否停止、复用 token、实读 token、未复用原因）；
  `PromptGuard` 在所有提前 return / 异常出口清掉记住的提示**与原生内存**，且只有读数真的交回调用方
  （数组构造成功）才提交；生成期 `llama_decode` 失败一律以失败收场。
- **Kotlin 侧**（`LocalModelEngine.kt`）：缓存归属 `cacheOwner`/`cachedPrompt`，无键轮次（云端）进来先作废；
  严格读数契约 `readingsOf()`（正常路径与取消收尾共用）；取消收尾优先用原生其实已交回的读数，拿不到就标
  `nativeReadingsComplete=false`（不把未知量写成 0）；E 的隐藏段 token 数与耗时进统计。
- **炉边**：每轮冻一个缓存键（人物、模式、会话或临时身份、实际加载的模型 SHA256）；D 的「20 秒还没有
  可显示正文」提示（只对本地轮次、独立状态字段、出正文/停止/失败/收尾/换页都撤、阈值可注入）。

## 2. 全量审（Codex，三轮）

| 轮次 | 对象 | 结论 | 处理 |
|---|---|---|---|
| 1 | `0562f7f` | **不通过**（2 条阻断 + 7 条必修） | `5775394` |
| 2 | `5775394` | **不通过**（1 条 P1 + 4 条 P2） | `f8ad3b0` |
| 3 | `f8ad3b0` | **通过**（按「答错/崩溃/失败当成功」门槛）；五项 4 到位 1 部分 | `1230b55` 补那一处残余 |

第一轮的两条阻断（都是真问题）：

1. `n_batch=n_ubatch=321` 配 `n_rs_seq=320` 触发 `llama-batch.cpp:702` 的 `GGML_ASSERT(n_ubatch > n_keep_tail)`
   —— 321 > 321 不成立，**只要解码就会 abort**。
2. 该版本的循环状态快照按 ubatch 写，而生成阶段每次只解一个 token，较老的槽不会随已生成的 token 后移；
   `seq_rm()` 只核对「回退距离 ≤ 窗口」，核不出槽里的状态对应哪个位置。于是窗口内的「成功回退」也可能
   恢复到位置不对的状态而答错。

整改结论（第二轮审独立确认）：**阻断 1、2 已消除，且把原生复用强制改成 0 只影响提速、不影响正确性**
（会真正清空内存并整段重读）。第三轮按阻断门槛给出**通过**。

## 3. 最重要的取舍：目标模型拿不到提速

为正确性，**不请求无法担保的回退快照**（`n_rs_seq` 保持 0）：

- 纯注意力（普通 Transformer）模型：`seq_rm(reuse, -1)` 是按 token 位置裁剪 KV，语义正确，复用照常提速；
- **混合状态模型（本包的目标 qwen35 就是）**：循环侧没有快照 ⇒ `seq_rm` 返回 false ⇒ 每一轮整段重读。

所以：**P2-1c 的机制与正确性交付了，但「本地对话提速」这个用户可见目标在目标模型上没有达成。**
真机测速里的断言已改成混合模型安全回退 + 温度 0 逐字一致；「复用 > 0」的提速证明需要另找纯注意力模型或
单开一个「按位置可恢复的检查点」工作包。**这一条请统筹裁定**（见第 5 节）。

## 4. 门禁（全新 worktree，跑在已提交的代码上）

| 对象 | 命令 | 结果 |
|---|---|---|
| `0562f7f` | `check-all.ps1 -LlamaSourceRepository <P0-7 的 llama-build>` | PASS：435 例 0 失败、lint 0/0、0 警告 |
| `5775394` | 同上 | PASS：443 例 0 失败、lint 0/0、0 警告 |
| `f8ad3b0` | 同上 | PASS：447 例 0 失败、lint 0/0、0 警告 |
| `1230b55`（最后一个代码提交） | 同上 | PASS：447 例 0 失败、lint 0/0、0 警告 |
| `2a48a39`（分支尖端，其后只有文档提交） | 同上 | PASS：447 例 0 失败、lint 0/0、0 警告 |

环境：`JAVA_HOME=D:\Development\Android-studio\jbr`（JBR 25）、`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`、
`MYCELIUM_LLAMA_SOURCE=<spike-P0-7 的 cpp/llama-build>`；本机连不上 Maven Central，所以门禁与所有 Gradle 命令都走 `--offline`。
门禁日志：仓库外 `logs\p2-1c-gate{,2,3,4}.txt`，报告在各自 worktree 的 `build\check-all\summary.md`。
APK：app-debug 59.8 MiB（已签名）、app-debug-androidTest 2.8 MiB（已签名）、app-release-unsigned 39.0 MiB。

## 5. 真机数据（2026-10-22 上午，已跑；第 4 条截图没跑成）

设备：NX809J / Android 16，模型 `Qwen3.5-4B-Q4_K_M.gguf`（2.55 GiB，SHA256 `00fe7986…ef11a4`，SAF 直读）。
调试包 `com.xavierxia.mycelium.debug`（正式版 0.4.0 未动）。`am instrument` 两条例全过（395.7 s）。

**① 三轮 1500 token 对话（`files/p2-1c-speed.txt`，`load_ms=606`）**

| 轮 | prompt_tokens | reused | read | prompt_ms | generated | generation_ms | tok/s | reason |
|---|---|---|---|---|---|---|---|---|
| 1 | 1290 | **0** | 1290 | 65289 | 103 | 10505 | 9.80 | 1 |
| 2 | 1420 | **0** | 1420 | 91411 | 97 | 10113 | 9.59 | **3** |
| 3 | 1544 | **0** | 1544 | 94886 | 97 | 11404 | 8.51 | **3** |

**与预期完全一致**：`reused == 0`，第 2、3 轮 `reuse_fallback_reason == 3`（引擎拒绝只丢掉一段尾巴），
每轮整段重读。**没有出现「reused > 0 但答案不同」的危险情况**，不需要按第 5 节那条立刻回报。
反面也说明：目标模型上确实拿不到提速（读提示 65→91→95 s，随历史增长）。

**② 温度 0 逐字一致（`files/p2-1c-zero.txt`）**：612 token 的提示跑三遍（整段读 / 同键复用 / 无键清空），
`generated=128` 三遍相同，三段 `first_answer` / `reused_answer` / `cleared_answer` **逐字相同**
（复用那遍 `reused` 也是 0，所以实际是三遍整段重读——一致性与预期吻合）。

**③ 隐藏段三个数（`files/p2-1c-hidden.txt`）**

| thinking | prompt_tokens | hidden_tokens | hidden_ms | generated | 正文预算剩余 | 答案字数 |
|---|---|---|---|---|---|---|
| false（默认） | 446 | **0** | 0 | 60 | 7746 | 83 |
| true | 444 | **256** | **28488** | 256 | 7492 | **0** |

结论：`enable_thinking=false` **被 Qwen3.5 的模板认了**（关掉时隐藏段为 0，正文直接从第一个 token 开始）；
打开思考后 256 个 token 全部落在隐藏段（28.5 s），生成长度 256 被隐藏段吃光、**正文一个字都没剩**——
这正是 D 那条 20 秒提示存在的理由，也说明默认关思考是必要的。

**④ 超时提示截图：没跑成。** 测例 `HearthHiddenHintDeviceTest` 已写好（真实界面：建人物 → 打开思考 →
把提示阈值调到 8 s → 发一句 → 等提示出现 → `uiAutomation.takeScreenshot()` 存 `files/p2-1c-hint.png`），
但**设备在跑到这一条之前掉线了**（`adb devices` 空、重启 adb 与等待后仍未恢复），所以没有截图，也没有
界面级证据。它编译通过、随调试包一起装上了，但**没有运行**。下一次借到手机要补跑这一条。


## 6. 已记录的口径与偏差

- `promptMillis` 只量**实际重读**的那一段（不含复用部分），不是「读完整提示的耗时」。
- 每轮多一次 `tokenize`（Kotlin 要先算出想复用的长度）；这是接受的代价。
- 缓存键里含**实际加载的模型标识**（`LocalModelRecord.sha256`），换模型即换键。
- 新会话第一轮用内存内的临时身份（UUID，在 openChat/setMode/newConversation 时重生成），第二轮起用持久化的
  会话 id —— 因此**第二轮会整段重读一次**，这是有意的。
- E 的归属口径：一个 token 只要它消耗/保留的文本有一段处在隐藏状态就算隐藏 token；**跨 token 的开标签记在
  含标签结尾的那个 token 上**；`hiddenMillis` 是「第一个原生 token → 第一段非空白正文」的等待，**不累计正文
  之后才出现的隐藏段**，也不含读提示/模板/加载 —— 它不能当作「用户多久看到第一个字」。
- 截断提示时复用真实公共前缀（人物设定那部分），不是整段重读。

## 7. 已知缺口（诚实列出）

- D 的**重做路径**只补了代码收尾（空准备结果、空白答案、finally、轮次核对），没有对应的 JVM 专测；
  已有三条 D 测例只覆盖发送路径的「无正文→提示→出正文撤掉／马上出正文不提示／停止与换页撤掉」。
- ~~混合模型「每轮整段重读」这个结论来自源码推导 + 静态审，没有在真机上验证过。~~ **2026-10-22 上午已真机验证**：
  三轮 `reused == 0`、第 2/3 轮 `reason == 3`，与推导一致（见第 5 节 ①）。
- ~~温度 0 逐字一致、真实 JNI 运行、隐藏段耗时数值都还没有真机数据。~~ **已真机跑过**：温度 0 三遍逐字相同；
  隐藏段在 `enable_thinking=false` 时为 0、打开后 256 token / 28.5 s 且正文为 0（见第 5 节 ②③）。
- **仍未验证**：超时提示的界面截图（设备中途掉线，测例已写好但没跑成，见第 5 节 ④）；以及「纯注意力模型上
  reused > 0」这条提速证明——目标模型是混合状态模型，本来就拿不到。

## 8. 证据位置

- 方案：`docs/reviews/2026-10-19-P2-1c/PLAN.md`（第 0 节是两轮整改的逐条映射）。
- 全量审报告：仓库外 `logs\P2-1c-REVIEW.md`（第一轮）、`P2-1c-REVIEW-r2.md`（第二轮）、`P2-1c-REVIEW-r3.md`（第三轮）。
- 门禁日志：仓库外 `logs\p2-1c-gate{,2,3,4}.txt`、`p2-1c-gate-tip.txt`（分支尖端）。
- 真机测例：`app/src/androidTest/java/com/xavierxia/mycelium/hearth/HearthPromptSpeedDeviceTest.kt`（三组读数）
  与 `HearthHiddenHintDeviceTest.kt`（超时提示 + 截图，未跑成）。
- 真机原始输出：设备 `files/p2-1c-speed.txt`、`files/p2-1c-zero.txt`、`files/p2-1c-hidden.txt`（内容已抄进第 5 节）。