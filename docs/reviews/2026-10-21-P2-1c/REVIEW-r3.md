**结论：通过——按你限定的“答错、崩溃、失败当成功”阻断门槛。五项并非全部闭环：第 3 项部分到位，仍有一个影响等待提示的跨轮次竞态。**

审核对象已核对为 `f8ad3b0`，全程只读，未运行 Gradle。

| 第二轮必修 | 判定 | 证据 |
|---|---|---|
| 1．decode 失败必须失败 | **到位** | [engine.cpp:269](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/llm/src/main/cpp/engine.cpp:269) 直接抛错；`PromptGuard` 在异常退出时清除缓存及原生内存。 [LocalModelEngine.kt:230](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:230) 明确拒绝原因码 4。 |
| 2．统一严格读数契约 | **到位** | [readingsOf:186](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:186) 覆盖所列长度、停止位、预算、完整性及原因码关系；正常路径在 318 行调用，异常收尾在 344 行调用。解析失败被收尾的 `runCatching` 吸收，标记 incomplete 后重新抛出原异常。 |
| 3．D 的清理、线程与归属 | **部分** | 空准备结果、空白答案和重做收尾均补了清理；[正文回调:1121](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:1121) 回 Main 并检查身份；[计时:265](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:265) 等待 busy 后起算。但重做 `finally` 的清理仍无轮次检查，见下文。 |
| 4．E 的开标签 token 归属 | **到位** | [TextStream.kt:43](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/llm/src/main/java/com/xavierxia/mycelium/llm/TextStream.kt:43) 每次 push 重置归属，57 行在进入隐藏状态时置 true，因此单发开标签被计入；拆分开标签实际归于补齐标签的 token。 |
| 5．指定测试整改 | **到位（静态核对）** | [PromptCacheContractTest.kt:184](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/llm/src/test/java/com/xavierxia/mycelium/llm/PromptCacheContractTest.kt:184) 起有四条新增契约测试，147、162 行断言两轮实际请求序列；[PromptCacheTest.kt:118](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/llm/src/test/java/com/xavierxia/mycelium/llm/PromptCacheTest.kt:118) 将未读完提示的生成读数改为 0。 |

**实质缺陷检查：**未发现仍会让错误缓存影响回答、引发崩溃，或把非取消的 decode 失败保存为正常答案的明确路径。炉边普通异常分支显示失败，不调用正常答案提交逻辑。

D 的确定残留是：[重做 finally:984](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:984) 在检查 `token == generationToken` **之前**无条件调用 `clearVisibleTextHint()`。[abandonTurn:625](D:/Agent/Worktrees/Mycelium-Android/p2-1c-review-r3/app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:625) 取消旧任务后立即允许新轮次开始；旧重做延迟退出时，便可能取消新轮次的计时器，使新轮次迟迟无正文却不再出现提示。其影响限于提示，不改变生成、停止或答案保存。

**必须现在改的清单：按本轮限定的三类阻断门槛，无。**若要求第二轮五项必修全部闭环，唯一剩余改动是把上述重做 `finally` 的提示清理移入轮次身份检查内。

残余不确定处：本轮没有运行测试或真实 JNI，不能据静态审查确认真机缓存裁剪、温度 0 输出一致性及实际提速已经通过。