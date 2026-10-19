# P2-2b 交付报告（发版准备：自动检查更新、应用内下载校验安装、发版流程）

分支 `wp/P2-2b-release-ready`。本报告分节写入，每节一个提交；事实都能从本报告写到的提交号、仓库外 `D:\Agent\Worktrees\Mycelium-Android\p2-2-work\` 下的原始日志、以及本目录各方案文档复核。

本报告写入时分支尖端是 `0bb81f9`（本报告自身的提交在其后）。**本包不合并进 main、不推送、不打标签**；合并与推送由统筹做。

## 一、九步各自的完成情况和提交号

第 1～9 步**全部已完成**，没有「进行中」也没有「没动」的步。

| 步 | 内容 | 状态 | 提交 | 本步落地后的测例读数 |
|---|---|---|---|---|
| 1 | 网关能下载文件（按流写文件、断点续传、逐跳白名单、审计、通用化，供 P2-5 复用） | 已完成 | `849b885`、`9a500ad` | `:net:test` 43/43（新增 29 例） |
| 2 | 读发布信息（按名找资产、缺资产降级只给发布页、错误说人话） | 已完成 | `e8ade02`、方案审整改 `311fbde` | 随第 2 步提交时的门禁 |
| 3 | 自动检查（开 App 查、24 小时节流、设置开关、红点 + 卡片、忽略本版） | 已完成 | `593b8d9`、`2d6a66b` | `:app` 92 例（新增 48） |
| 4 | 下载和校验（空间公式、`updates/`、进度与取消、四项校验、清理） | 已完成 | `b495146` | `:app` 153 例（新增 61） |
| 5 | 安装（`PackageInstaller` 会话、结果回执、未知来源引导、调试包不自动装） | 已完成 | `6e46aa8`（另有一条施工 worktree 上的 `b982a97`，`git patch-id` 相同=同一次改动） | `:app` 170 例（新增 17） |
| 6 | 发版流程（`scripts/release-guard.sh` + `release.yml`，四项闸门 + 手动试跑） | 已完成 | `fd5ad3c`、方案审整改 `5f539cb` | 见第七节中止演示 |
| 7 | 正式包只带 `arm64-v8a` | 已完成 | `6df413e`、`f47c706` | 改前/改后大小见本节末 |
| 8 | 发布说明、版本号、发版与架构文档 | 已完成 | `caeab85`、方案审整改 `b751b40` | 版本 0.4.0 / versionCode 5 |
| 9 | 公开内容检查脚本 `scripts/check-public.ps1` | 已完成 | `3153f7a`、方案审整改 `9a52117` | 见第五节 |

九步之后在本分支上还落了五条收尾提交（都在本节表格的九步之后）：

| 提交 | 内容 |
|---|---|
| `c1cfe12` | 把 `main` 并进本分支（解冲突见第二节） |
| `f74e63e` | 文件下载复用网关同一套取消闸门与超时归一化（见第三节） |
| `0bb81f9` | 下载复用取消闸门的判别力测例（`DownloadCancelGateTest` 5 例，见第三节） |
| `b3bf3e0` | `check-all.ps1` 的 lint 提示只计数不判失败，并加自测 |
| `d351932` | `check-public.ps1` 支持「已接受的历史提交」清单（`scripts/accepted-history-commits.txt`，13 个已填） |
| `6ef1b2f` | `LocalHttp` 移出生产源码集并接住服务线程异常（修的是「设备测试没法一次跑完」的那条已知问题） |

**本分支 0bb81f9 上的门禁用例分布（348 例 0 失败）**：`:app` 181、`:contract` 22、`:hearth` 46、`:llm` 13、`:model-api` 3、`:net` 55、`:roundtable` 28。原文见第四节。

**已定稿的三处与简报的偏离**（沿用施工笔记与方案审记录，不是本轮新决定）：

- 空间检查放在下载器里，而不是 `UpdateCenter`（避免用第 4 步的实现去改第 3 步的测例结论）；
- 期望哈希通过本地边车 `.apk.sha256` 传给校验器，校验器不联网；
- 校验器本地那次哈希不给取消句柄（取消由 `:net` 的 `FileDownloader` 负责）。

**第 5 步的关键实现口径**（`6e46aa8`）：`PackageInstaller` 用 `MODE_FULL_INSTALL` + `setSize`，不调 `setAppPackageName`；写 → `fsync` → 关流 → `commit`；结果回执用**显式 component + 清单内 `exported=false` 接收器 + `FLAG_MUTABLE`**（依据：部分版本 `FLAG_IMMUTABLE` 拿不到系统填的 `EXTRA_STATUS`/`EXTRA_INTENT`）；会话阶段、sessionId、目标包名、基线 versionCode、tag 落盘，回调按 sessionId 幂等；`onResume` 用 `longVersionCode` 权威判定，超时或会话消失只标「待核实」，不冒充取消。判别力实证两处（挪 `fsync`、把幂等规则改成恒真）均变红并干净还原。

**第 6 步的中止演示共 5 类**：versionCode、发布说明、签名指纹、标签四类在 `fd5ad3c` 那一轮各跑过一次通过 + 一次中止；第五类「找不到前序标签 / 浅克隆」在 `5f539cb` 之后有实跑日志与旧脚本对照。

**第 7 步大小**：正式包只带 arm64 之前 / 之后的 release 未签名包大小差，见第四节门禁原文里的产物表（本轮门禁实测 **38.9 MiB**）。

各步更细的方案—实现—证据对应，见本目录 `PLAN.md`、`SURVEY.md`、`IMPL-BRIEFS.md`、`PLAN-REVIEW-MUSTFIX.md` 与仓库外施工笔记 `p2-2-work\step1-notes.md`、`step3-notes.md`、`step4-notes.md`、`step5-notes.md`。