# P0-6 审核任务（第 0 阶段收尾）

你是独立审核者。审核对象：仓库 D:/Agent/Project/Nivra，分支 phase-0-bootstrap，提交 3ca8596（对照基点 90e551a，其前一个基点是远端 ba55dbc）。

## 背景

- 任务依据：docs/PHASE_0_HANDOFF.md（上一 agent 2026-09-28 留下的未提交改动，由本线保留并完成验证）。
- 本次提交内容 = 前任全部未提交改动（未 reset/clean）+ 本线新增的验证文档（PHASE_0_VERIFICATION.md、DELIVERY.md、TRACKS.md、任务书、tracks 交接）。
- 交付报告：docs/reviews/2026-10-05-P0-6/DELIVERY.md；验收报告：docs/PHASE_0_VERIFICATION.md。

## 只看这些（省额度）

1. `git diff 90e551a..3ca8596 --stat` 与逐文件 diff（共 26 个文件；重点：app/ 源码与测试、gradle wrapper、.github workflows、.gitattributes）。
2. 交接书 §3 约束逐条：未 reset/clean（对照 git log 应只有追加提交）；package/版本保持 com.nivra.app/0.0.1/1；无功能越界（未实现下载/安装）；规划文档未删；无秘密/模型/APK/构建缓存入库。
3. 交付报告「已通过」项抽查复现（可只跑一条最快命令）：
   - `./gradlew.bat :app:testDebugUnitTest --console=plain`（JBR 在 D:/Development/android-studio-quail4/Install/jbr，ANDROID_HOME=%LOCALAPPDATA%/Android/Sdk）
   - 或直接读 app/build/test-results/testDebugUnitTest/*.xml 计数（应 19/19）。
4. gradlew 行尾与 .gitattributes（gradlew=LF）、wrapper properties 的 distributionSha256Sum 是否为官方 9.5.0 值（553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746）。

## 结论格式

按 Windows 仓库 REVIEW-GUIDE.md：通过 / 需修改 / 不通过 + 逐条证据。最后一条消息就是完整审核报告。
