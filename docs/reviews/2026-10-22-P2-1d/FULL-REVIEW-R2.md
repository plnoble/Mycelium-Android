# 第二轮全量复审：未审（Codex 额度用尽）

- 时间：2026-10-23 16:5x
- 状态：**未审**。Codex CLI 返回 `You've hit your usage limit ... try again at 5:23 PM`，
  换算本地时间约 17:23 恢复。
- 第一轮全量审（`FULL-REVIEW.md`，10230 字节）指出的 6 个必修项已全部修复，提交 `e31b40b`：
  1. P1 前缀约束：挑选上限改为真实公共前缀 `shared`，并先 `invalidateBeyond(key, shared)`；
  2. P1 失败清空：`invalidate(key)` + `forgetCachedPrompt()` + `requested=0` + `restoredTo=-1`；
  3. P1 原生预算：保存前 300 MiB 准入（超限不分配），`takeCheckpoint`/`clear` 用 vector swap 释放容量；
  4. P1 生命周期：换键 / 无键轮次 / 异常与取消均作废检查点并丢弃在途缓冲；
  5. 必修项 9：`put()` 先全局淘汰再 `getOrPut(key)`，避免追加到悬空 list；
  6. 回归测例 3 条（前缀编辑防恢复、失败从 0 重读、全局淘汰不丢当前键）。
- 自证：`llm` 88 例全过；门禁待本包收工前在全新 worktree 重跑。
- **待办**：额度恢复后补一轮复审，若仍有必修项按任务书「复审最多三轮」处理。
