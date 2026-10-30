Codex 只读审核第 3 轮原文（默认模型，`-s read-only`）。本机路径已脱敏：仓库内文件写成相对路径，固定的 llama.cpp 源码写成 `<llama-5e03bdd>/`，日志目录写成 `<logs>/`。

结论：通过

P0：无。  
P1：无。  
P2：无必须修改项。原 5 项主要整改已落实，以下两项作为后续建议。

已通过 `git diff cc59fb4 66b0438` 确认：仅修改真机测例、发布说明和 `DEVICE.md`，**产品代码未动**。本轮没有修改文件，没有运行构建、测试或 adb。

**建议（不阻断发布）**

1. **检查点断言以后可进一步核对“本轮的新快照”。**  
   [测例第 164 行](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:164) 已核对轮末仓库，但 `storedCount >= 1` 仍可能由上一轮留下的快照满足；[引擎第 869 行](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:869) 的失败处理只删除超过指定位置的快照。建议以后核对仓库最新位置或序号，以及下一轮恢复位置是否等于上一轮保存位置。当前断言能够证明“轮末仓库非空”，不能单独证明每轮都成功更新快照。

2. **`MemAvailable` 还有一处 GB／GiB 标注遗漏。**  
   [DEVICE.md 第 126 行](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:126) 写约 `15.8 GB`；[原日志第 27 行](<logs>/hotfix-0.5.1-device-run2-mem.txt:27) 是 `16525888 kB`，实际为 **16.9225 GB＝15.7603 GiB**。建议改为 `16.9 GB` 或 `15.8 GiB`。这不影响权重重排修复和发布说明中的进程内存范围。

**已核对通过的点**

1. **检查点适用性与仓库核验已修改。**  
   [测例第 120 行](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:120) 读取 `canCheckpoint()`；第 141、162–169 行只对支持的模型执行检查点断言；第 181 行要求完整验收至少包含一个支持检查点的模型。第 153、164 行实际调用并检查 `checkpointUsage()`，不再只依赖原生捕获统计。上述“新快照”边界列为建议。

2. **内存判据已补齐。**  
   [测例第 124–135 行](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:124) 使用 `(RssAnon + VmSwap) × 1024`；任一指标缺失都会失败。第 91 行要求完整验收存在对照，第 133 行要求其匿名内存确实超过文件一半。第 36–38 行明确说明内存读数是佐证，结构依据是关闭 `use_extra_bufts`。

3. **文件大小探针的异常路径已修复。**  
   [测例第 109–116 行](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:109)：复制描述符由 `use` 关闭；探针抛异常时关闭原文件；成功后才返回给引擎。结合引擎的接管及清理流程，未发现新增泄漏。第 175、179 行仍负责卸载模型和恢复检查点开关。

4. **发布说明的条件、单位及数字对应正确。**  
   [首句](docs/releases/v0.5.1.md:3) 已限定为超过 4 GiB、因权重重排额外保存权重。[第 10–13 行](docs/releases/v0.5.1.md:10) 写明设备、两种量化、8 线程、8192 上下文、短回复及十进制 GB。  
   DEVICE 两个 8192 上下文模型的匿名内存范围合并为 **0.48–0.59 GB**；RSS 合并为 **6.31–8.11 GB**，约写成 **6.3–8.1 GB** 正确。

5. **DEVICE 的主要统计已复算。**  
   [运行表与合计表](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:46) 排除整次冻结运行后：

   | 模型 | 轮到次数 | 完成三轮 | 被杀 |
   |---|---:|---:|---:|
   | 4B | 12 | 7 | 5 |
   | 9B Q4 | 7 | 7 | 0 |
   | 9B Q6_K | 11 | 7 | 4 |

   [第 32–34 行](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:32) 已区分三个版本的检查点、64／128 token 上限和内存读数口径；第 59 行正确列出 `6/8192` 两次、`8/2048` 一次、`8/8192` 一次，没有虚构 `6/2048`。

   [内存表及注释](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:90) 已区分“加载后及轮末范围”和“仅加载后百分比”。直接用表中四舍五入的范围除以文件大小，分别得到 **116.42–119.34%、8.45–10.39%、6.52–7.74%、4.08%**；这些不能直接替代不同阶段的加载后百分比，因此原先的表面矛盾已有说明。换算抽核：`6161320 kB × 1024 / 10⁹＝6.30919 GB`，对应表中 `6.31 GB`。

   [速度表](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:105) 与发布说明逐项一致：生成 **10–14.5、5.5–9.4、3.9–6.1 token/s**；短提示耗时 **0.5–1.1、1.5–3.7、2.7–4.2 秒**。6 线程的 **2.5–4.9 秒**已单列。

   本轮没有读到三版完整的逐轮原始报告，因此新增内存极值和速度极值核验到文档一致性，不能声称已从全部原始样本重新求出。

6. **未发现新增 Kotlin、警告即错误或 lint 阻断。**  
   新增调用、类型和断言静态检查无异常；警告即错误配置仍保留。既有 [gate-5 第 8 行](<logs>/hotfix-0.5.1-gate-5.txt:8) 对应精确提交 `66b0438` 和干净工作区，记录 521 个单测零失败、lint 零错误零警告、门禁通过；[Gradle 日志第 301 行](build/check-all/gradle.log:301) 的 AndroidTest Kotlin 编译为 `UP-TO-DATE`。这是读取既有结果。

**实际读取的文件**

包括全文、历史版本或相关片段：

- `logs/hotfix-0.5.1-codex-review-r1.md`、`hotfix-0.5.1-codex-review-r2.md`
- `app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt`
- `docs/releases/v0.5.1.md`
- `docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md`
- `llm/src/main/java/com/xavierxia/mycelium/llm/` 下的 `LocalModelEngine.kt`、`CheckpointStore.kt`、`AndroidModelStore.kt`
- `app/build.gradle.kts`
- `scripts/check-all.ps1`、`scripts/check-all.sh`
- `logs/hotfix-0.5.1-gate-5.txt`、`hotfix-0.5.1-apk-build-4.txt`、`hotfix-0.5.1-device-run2-mem.txt`
- `build/check-all/summary.md`、`build/check-all/gradle.log`