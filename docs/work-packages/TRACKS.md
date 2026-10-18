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

## 2026-10-15 起：A-圆桌线

- 用户要求开「人物搬到手机」这条线，和 Windows 侧的工作并行。
- 由一个开发 agent 在 `D:\Agent\Project\Nivra` 做，按顺序：P0-8 契约消费方 → P1-6 圆桌骨架（发 0.2.0）→ P1-7 圆桌单聊（发 0.3.0）。本仓库 `main` 的合并由统筹决定。
- A-探针线已结束，它的两个 worktree 只读保留。
- 进度和交接写在 `docs/tracks/A-圆桌.md`。
- 2026-10-17 补充：P1-6a（「伴侣」改名「炉边」）也归 A-圆桌线，在单独的 worktree 做，见任务书。

## 2026-10-17 起：A-炉边线

用户要求另开一条线做「炉边」，和 A-圆桌线的 P1-7 并行。

### 分工

| | A-圆桌线 | A-炉边线 |
|---|---|---|
| 工作包（按顺序） | P1-7 圆桌单聊、P1-6a 改名（都已合并）→ P1-8 圆桌本地私密模式 | P2-1 本地模型引擎（已合并）→ P2-1b 全套检查脚本和零警告 → P2-2 炉边人物和对话（之后的 P2-3 记忆等由统筹再排） |
| 工作目录 | `D:\Agent\Project\Nivra`；P1-6a 在 `D:\Agent\Worktrees\Mycelium-Android\P1-6a` | `D:\Agent\Worktrees\Mycelium-Android\hearth`（`git worktree add` 从 `main` 建） |
| 负责的模块 | `:net`、`:roundtable`；「我的」里的云端模型和隐私记录 | `:model-api`、`:llm`；「我的」里的本地模型；之后的 `:hearth` 和炉边页面 |
| 进度和交接 | `docs/tracks/A-圆桌.md` | `docs/tracks/A-炉边.md` |

- A-炉边线**不进** `D:\Agent\Project\Nivra`；A-圆桌线也不进 `hearth` 目录。
- 一个工作包合并后，再从新的 `main` 拉下一个分支。`hearth` 这个 worktree 可以一直用，换分支前先确认工作区干净。

### 共用的文件

- `settings.gradle.kts`、`app/build.gradle.kts`、`MyceliumApp.kt`、架构测试这几处两条线都会改。规则是：只加自己那部分，不改对方的代码。
- 交审前，先把最新的 `main` 并进自己的分支。冲突自己解决不了的，报统筹。
- 模型接口以 A-炉边线的 `:model-api` 为准。P1-7 的「回答模型」接口要统一过去：哪个包后合并，就由哪个包负责对齐。

### 共用这台电脑和这台手机

1. Gradle 全局缓存是共用的：不清缓存，不升级 Gradle Wrapper（9.5.0）、NDK（30.0.16248370）和 CMake（3.22.1）。要装或升级 SDK 组件，先在自己的交接文件里写明，再报统筹。
2. 两条线同时构建会抢 CPU 和内存，变慢是正常的，不要为此改构建配置。
3. 红魔只有一台。要做真机测试或走查，先请用户确认另一条线现在没在用手机。两条线装的都是 `com.xavierxia.mycelium.debug`，后装的会覆盖先装的，走查前先重装自己的包。
4. 手机上 `/sdcard/Models/` 里的模型是用户的原文件：只读，不改、不删、不移动。
5. llama.cpp 源码：A-探针线的 worktree `D:\Agent\Worktrees\Mycelium-Android\spike-P0-7` 里，`spikes/p0-7/app/src/main/cpp/llama-build` 是固定提交 `5e03bdd…` 的本地克隆，可以从这里克隆，不用联网。不要在探针目录里改任何东西。

### 其他

- 审核、Codex 额度、禁止提交的内容、汇报格式、要用户明确同意的事，都和上面「规则」一节相同。Codex 额度是所有线共用的，5 小时窗口超过 70% 就不发起，交统筹代审。
- 两条线都不合并 `main`、不推送、不打标签、不发布。合并由统筹做；推送和发布由用户决定。

## 2026-10-18 起：单一开发线（取代上面的 A-圆桌、A-炉边两条线）

用户决定：交给**一个**新的开发 agent 负责全部开发，并且先专注炉边。上面两条线的分工、目录和模块归属从这天起作废；手机、Gradle 缓存、llama.cpp 源码的规则仍然有效。

- **工作目录**：`D:\Agent\Project\Nivra`，按工作包拉分支（`wp/<编号>-<短名>`）。
  - 探针类的工作包（例如 P2-6）另建 worktree，放在 `D:\Agent\Worktrees\Mycelium-Android\` 下。
  - 交付前的「全新 worktree 全套检查」也在这个目录下建、用完删掉。
- **队列**：以 `docs/work-packages/README.md` 索引里的「队列 1、2、3……」为准，统筹会随时更新。2026-10-18 的顺序是：P2-1b → P2-2（修订）→ P2-3 → P2-5 → P2-4 → P2-6，之后才回到圆桌的 P1-8。
- **进度和交接**：写在 `docs/tracks/开发线.md`。旧的 `A-圆桌.md`、`A-炉边.md` 保留作历史，不再追加。
- **要清理的旧目录**（确认工作区干净后再删）：
  - `D:\Agent\Worktrees\Mycelium-Android\hearth`：`wp/P2-2-hearth-chat` 分支还在，删掉 worktree 后可以在 `Nivra` 里切过去；
  - `D:\Agent\Worktrees\Mycelium-Android\p1-7-final`。
  - `spike-P0-7`、`p0-7-docs` 只读保留，**不要删**：`spike-P0-7` 里有 llama.cpp 固定提交的本地克隆。
- **手机**：只剩一条线，不用再和别的线错开时间；但每次用红魔前**仍然先问用户**，因为手机是用户自己在用。

### Codex：多用（2026-10-18 用户决定，取代上面「规则」第 6 条的 70% 停审线）

用户说 Codex 额度经常重置，却一直没用完，要求多花。每周额度最近读数是 29%。

- **停审线**：5 小时窗口用到 **90%** 才停（原来是 70%）。停了就**等它重置**，日志里的 `resets_at` 写着重置时间；等超过 1 小时才交统筹代审。每周额度到 90% 时，停下来报统筹。
- **每个工作包至少四轮**：
  1. **开工前审方案**：让 Codex 读任务书和要改的代码，列出实现风险和容易漏的地方，存成 `docs/reviews/<日期>-<编号>/PLAN-REVIEW.md`。
  2. **每完成一个大步骤就审一次增量**：例如一个模块、一个页面、一次数据库升级。存成 `STEP-<n>.md`。
  3. **交付前全量审**，存成 `REVIEW.md`；结论是「需修改」，就改完复审（`REVIEW-r1.md`、`REVIEW-r2.md`……），直到「通过」。
  4. **专项审**，凡是涉及都要单独审一次：隐私和出网（网关、审计、密钥、云端发出的内容）；并发和资源释放（本地引擎、后台任务）；数据库升级；测试质量（有没有恒真断言、夹具像不像真实数据）。存成 `SPECIAL-<主题>.md`。
- 所有报告都写进仓库，随分支交审。统筹仍然会自己复核，不因为 Codex 通过就跳过。
