# 工作包任务书（知衍安卓版）

- 总计划在 Windows 仓库：`D:\Agent\Project\OMNIX-Mycelium（知衍）\MYCELIUM-双端总计划-2026-09-29.md`
- 对接说明：[../MYCELIUM_INTEGRATION.md](../MYCELIUM_INTEGRATION.md)
- 通用规则、交付报告格式、任务书模板、审核指南（`REVIEW-GUIDE.md`）都与 Windows 侧相同，见 Windows 仓库 `docs/work-packages/`。

## 索引与顺序

| 编号 | 标题 | 依赖 | 状态 |
|---|---|---|---|
| P0-6 | 完成原第 0 阶段 | — | 已合并 |
| [P0-9](P0-9-rename-to-mycelium.md) | 改名为知衍（App 名、包名、仓库名、文档） | P0-6 合并 | 已合并，已发 0.1.0 / 0.1.1 |
| [P0-7](P0-7-device-spike.md) | 真机技术探针（本地模型、SQLite/SQLCipher、Keystore） | P0-9 合并 | 已完成，结论见 `docs/spikes/P0-7-results.md` |
| [P0-8](P0-8-contract-consumer.md) | 契约 v0 消费方（复制 + 一致性测试） | Windows 侧 P0-8 产出样例；P0-9 合并 | 已合并（2026-10-16，统筹代审三轮后通过，见 `docs/reviews/2026-10-15-P0-8/`） |
| [P1-6](P1-6-roundtable-skeleton.md) | 圆桌空间骨架：页面、导入角色包、浏览和搜索观点（发 0.2.0） | P0-8 合并 | 已合并（2026-10-17，Codex 首审 + 统筹代审两轮后通过，见 `docs/reviews/2026-10-16-P1-6/`）；0.2.0 发布等用户决定 |
| [P1-6a](P1-6a-rename-hearth.md) | 改名：「伴侣」空间改为「炉边」（随 0.2.0 一起发） | P1-6 合并 | 已合并（2026-10-17，统筹审核通过，见 `docs/reviews/2026-10-17-P1-6a/`）；随 0.2.0 发 |
| [P1-7](P1-7-roundtable-chat-cloud.md) | 圆桌单聊（云端模型）：出网网关、审计记录、密钥、拉取模型列表（发 0.3.0）；2026-10-17 改为不设单价和上限 | P1-6 合并 | 已合并（2026-10-18，统筹代审通过，含统筹补改 `7df0b8b`，见 `docs/reviews/2026-10-17-P1-7/`）；真机补跑 `DeviceConversationTest` 待手机 |
| [P1-8](P1-8-roundtable-local-private.md) | 圆桌本地私密模式：用手机上的模型和角色单聊 | P1-7、P2-1 合并 | 排后：2026-10-18 用户定先专注炉边 |
| [P2-1](P2-1-local-llm-engine.md) | 本地模型引擎：导入手机上的 GGUF、流式生成、可停止 | P1-6 合并 | 已合并（2026-10-17，统筹代审通过，见 `docs/reviews/2026-10-17-P2-1/`） |
| [P2-1b](P2-1b-check-all-zero-warnings.md) | 全套检查脚本、零警告、CI 能构建本地模型引擎 | P2-1 合并 | 已合并（2026-10-18，统筹代审通过，含统筹补改 `d4c5526`，见 `docs/reviews/2026-10-18-P2-1b/`） |
| [P2-2](P2-2-hearth-characters-chat.md) | 炉边：人物和对话；10-18 修订：每个人物选本地或云端模型、测试模式和沉浸模式、预设人物 | P2-1b 合并 | 已合并（2026-10-19，`0b89bd4`；方案审、隐私出网专项审、全量审加六轮复审，最后一轮统筹代审；第 1 条建连取消残留裁定为已知残留；见 `docs/reviews/2026-10-18-P2-2/`） |
| [P2-2b](P2-2b-release-ready.md) | 发版准备：自动检查更新、应用内下载校验安装、补全发版流程；做完发 0.4.0（第一个正式版） | P2-2 合并 | **队列 1**：进行中，九步代码已写完；接下来并入 main、真机环节（含 P2-2 留下的本地模型设备测例）、全量审 |
| [P2-1c](P2-1c-local-prompt-cache.md) | 本地对话提速：记住已读过的上下文，下一轮只读新增部分 | P2-2 合并 | **队列 2** |
| [P2-3](P2-3-hearth-memory.md) | 炉边记忆：沉浸模式下，每个人物独立记得聊过的事 | P2-2 合并 | **队列 3** |
| [P2-5](P2-5-model-manager.md) | 「我的 → 模型」：推荐、Hugging Face 下载、保存目录、默认模型、自动调速、本地引擎跨页协调 | P2-2b 合并（复用它的网关下载） | **队列 4** |
| P2-4 | 炉边联网搜索开关 | P2-2 合并；用户选定搜索服务 | **队列 5**：等用户选定搜索服务后再写任务书 |
| [P2-6](P2-6-gpu-npu-spike.md) | GPU / NPU 加速探针 | P2-5 合并 | **队列 6** |

状态由统筹（Claude）更新。

## 安卓侧补充规则

1. **先收尾第 0 阶段**：第 0 阶段在 `phase-0-bootstrap` 分支上，还有之前的 agent 留下的未提交改动。2026-09-30 起由同一个开发 agent 接手：先读 `docs/PHASE_0_HANDOFF.md`，在现有改动的基础上按其中 A→B→C 的顺序完成，**不得丢弃现有改动**。原来的「主审核 agent」由 Codex 命令行替代。第 0 阶段合并进 `main` 之后，再按索引顺序做 P0-9、P0-7、P0-8。
2. **分支**：`wp/<编号>-<短名>`，从 `main` 拉；允许在自己的分支上提交；Codex 审核「通过」后合并进本地 `main`；push、打 tag、发布、在 GitHub 上改仓库名都由用户决定。
3. **禁止提交**：模型文件（`*.gguf` 等）、keystore、token、本机 SDK/代理路径、APK、构建缓存。
4. **汇报**：只写「已通过 / 失败 / 未验证」。在 JVM 或 Robolectric 上的验证和真机验证要分开写；模拟器结果不能冒充真机结果。
5. **审核**：和 Windows 侧一样，由同一个开发 agent 自己调用 Codex 命令行审核（程序位置和命令模板见 Windows 仓库总计划 §10），审核依据是 Windows 仓库的 `docs/work-packages/REVIEW-GUIDE.md`。审核报告用 `-o` 写到 `docs/reviews/<日期>-<编号>/REVIEW.md`，交付报告写在同目录的 `DELIVERY.md`。结论是「通过」才可以合并进本地 `main`，不推送。
6. **交付和验收一律用 `scripts/check-all.ps1`**（P2-1b 起）：在**全新 worktree** 里对**已提交的版本**跑，覆盖所有模块的单元测试、`:app` 的 debug 和 release lint、调试包 / 正式包 / 测试包，最后给一张汇总表。任何失败或警告都以非零退出码结束；缺报告、缺产物、该跑的任务没跑也算失败。llama.cpp 自己的警告是约定的例外，单独计数。CI 用等价的 `scripts/check-all.sh`（`--online`）。以前那种每个包各写一串 Gradle 命令的做法作废。
