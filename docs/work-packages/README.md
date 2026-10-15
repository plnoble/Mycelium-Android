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
| [P0-8](P0-8-contract-consumer.md) | 契约 v0 消费方（复制 + 一致性测试） | Windows 侧 P0-8 产出样例；P0-9 合并 | 下一个（2026-10-15 开工） |
| [P1-6](P1-6-roundtable-skeleton.md) | 圆桌空间骨架：页面、导入角色包、浏览和搜索观点（发 0.2.0） | P0-8 合并 | 排队 |
| [P1-7](P1-7-roundtable-chat-cloud.md) | 圆桌单聊（云端模型）：出网网关、审计记录、密钥、预算（发 0.3.0） | P1-6 合并；用户确认云端服务商 | 排队 |

状态由统筹（Claude）更新。

## 安卓侧补充规则

1. **先收尾第 0 阶段**：第 0 阶段在 `phase-0-bootstrap` 分支上，还有之前的 agent 留下的未提交改动。2026-09-30 起由同一个开发 agent 接手：先读 `docs/PHASE_0_HANDOFF.md`，在现有改动的基础上按其中 A→B→C 的顺序完成，**不得丢弃现有改动**。原来的「主审核 agent」由 Codex 命令行替代。第 0 阶段合并进 `main` 之后，再按索引顺序做 P0-9、P0-7、P0-8。
2. **分支**：`wp/<编号>-<短名>`，从 `main` 拉；允许在自己的分支上提交；Codex 审核「通过」后合并进本地 `main`；push、打 tag、发布、在 GitHub 上改仓库名都由用户决定。
3. **禁止提交**：模型文件（`*.gguf` 等）、keystore、token、本机 SDK/代理路径、APK、构建缓存。
4. **汇报**：只写「已通过 / 失败 / 未验证」。在 JVM 或 Robolectric 上的验证和真机验证要分开写；模拟器结果不能冒充真机结果。
5. **审核**：和 Windows 侧一样，由同一个开发 agent 自己调用 Codex 命令行审核（程序位置和命令模板见 Windows 仓库总计划 §10），审核依据是 Windows 仓库的 `docs/work-packages/REVIEW-GUIDE.md`。审核报告用 `-o` 写到 `docs/reviews/<日期>-<编号>/REVIEW.md`，交付报告写在同目录的 `DELIVERY.md`。结论是「通过」才可以合并进本地 `main`，不推送。
