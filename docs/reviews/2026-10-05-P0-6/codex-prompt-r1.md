# P0-6 复审（第二轮）

只复核上一轮（docs/reviews/2026-10-05-P0-6/REVIEW.md）指出的三项「必须修改」在提交 cce0736（分支 phase-0-bootstrap，最新提交 cce073e，对照 3ca8596）里的落实情况，不重审其他内容。

1. gradlew 可执行位：`git ls-tree HEAD gradlew` 应为 100755；DELIVERY.md / PHASE_0_VERIFICATION.md 里「合并后设置」的说法已删改。
2. Wrapper：`gradle/wrapper/gradle-wrapper.jar` 的 SHA-256 应为 497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7（官方 9.5.0）；properties 仍指向 9.5.0 + 官方 distributionSha256Sum。
3. 报告事实修正：lint 5 条警告分类（3 版本提示 + MissingApplicationIcon + UseKtx）；Release workflow 行为如实描述（tag 触发会构建并发布 Release，本轮未触发）；§1 标题改为分步执行。

可选快速复现：`git diff 3ca8596..cce073e --stat` 应只有 gradlew(.bat)/wrapper/两个报告/REVIEW/prompt 几个文件。

结论：通过 / 需修改（+理由）。最后一条消息 = 完整复审报告。
