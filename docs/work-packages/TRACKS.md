# 安卓仓库的并行分工（2026-10-05 起）

用户决定把安卓这边的任务同时交给两个开发 agent。这份文件规定谁做什么、在哪里做。它和 Windows 仓库的 `docs/work-packages/PARALLEL-TRACKS.md` 是同一套规则。`README.md` 里「同一个开发 agent 接手」的说法，以这里为准。

## 两条线

| 线 | 负责的工作包（按顺序） | 工作目录 | 需要用户配合 |
|---|---|---|---|
| **A-基础** | P0-6 第 0 阶段收尾 → P0-9 改名 → P0-8 契约消费方 | `D:\Agent\Project\Nivra`（P0-9 之后改名为 `Mycelium-Android`） | GitHub 仓库改名、推送、合并 PR |
| **A-探针** | P0-7 真机技术探针 | `D:\Agent\Worktrees\Mycelium-Android\spike-P0-7` | 插上红魔手机并打开 USB 调试；同意下载约 10G 模型 |

## 规则

1. **A-基础线**：
   - 从现有工作区接手。这里有上一个 agent 没提交的改动，必须保留，按 `docs/PHASE_0_HANDOFF.md` 把它们验证完再提交。
   - 这条线负责本仓库 `main` 的全部合并。
2. **A-探针线**：
   - 不进 `D:\Agent\Project\Nivra` 这个目录干活。用 `git worktree add` 在 `D:\Agent\Worktrees\Mycelium-Android\spike-P0-7` 建分支 `spike/P0-7-device`。
   - 探针做成独立的小工程，放在这个 worktree 的 `spikes/p0-7/` 下：自己的 `settings.gradle.kts`，应用 ID 用 `com.xavierxia.mycelium.spike`。不依赖也不改 `:app`。这样就不用等第 0 阶段收尾和改名。
   - 探针代码不合并。最后只交一个结论文件 `docs/spikes/P0-7-results.md`，由 A-基础线合并。
3. **两条线同时在用这台电脑**：
   - Gradle 的全局缓存是共用的，两边不要同时清缓存或升级 Gradle、NDK。
   - 要装或升级 SDK 组件，先在各自的交接文件里写明。
4. **契约从哪来**：Windows 仓库的契约 v0 已经合并，版本 0.1.0，位置是 `D:\Agent\Worktrees\OMNIX-Mycelium\main\contract`。同步时在 `contract/UPSTREAM.md` 里记下当时那边的提交号。
5. **记录**：
   - 交付报告写在 `docs/reviews/<日期>-<编号>/DELIVERY.md`；
   - 进度和交接写在 `docs/tracks/<线名>.md`。
6. **审核**：用 Codex 命令行，按 Windows 仓库的 `REVIEW-GUIDE.md` 和省额度的写法。
   - Codex 的额度是所有线共用的。调用前先看最近一次会话日志里的 5 小时用量，超过 70% 就不要发起，请用户转给统筹（Claude）审。
   - 截图类证据一律交统筹看。
7. **要用户明确同意的事**：推送、合并 PR、打 tag、发布、改 GitHub 仓库名、下载模型。
8. **不许提交**：模型文件、keystore、token、本机 SDK 和代理路径、APK、构建缓存。
9. **汇报**：只写「已通过 / 失败 / 未验证」三栏。真机结果和电脑上的测试结果分开写，模拟器结果不能当真机结果。
