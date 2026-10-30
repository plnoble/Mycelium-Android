Codex 只读审核第 2 轮原文（默认模型，`-s read-only`）。本机路径已脱敏：仓库内文件写成相对路径，固定的 llama.cpp 源码写成 `<llama-5e03bdd>/`，日志目录写成 `<logs>/`。

结论：不通过

`5478d63..cc59fb4` 仅修改真机测例、发布说明和 `DEVICE.md`，产品代码未动。本轮未修改文件，未运行构建、测试或 adb。

**P0：未发现。**

**P1：第 1 轮两项主要阻断已修正，未发现新的 P1；仍有以下 P2。**

**P2**

1. **检查点断言仍有误判边界。**

   位置：[LargeModelHotfixDeviceTest.kt:140](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:140)，第 140–145 行。

   测例按文件大小选模型，却对所有模型强制要求检查点。纯注意力模型在生产中正常走旧缓存路径，`canCheckpoint()` 为假，仍会被这里判失败。依据：[LocalModelEngine.kt:710](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:710)、[engine.cpp:501](llm/src/main/cpp/engine.cpp:501)。

   此外，`stats.checkpointPos/checkpointBytes` 是原生捕获读数，并不保证轮末已入检查点仓库：统计先赋值，之后仍可能因内存通知、堆余量或仓库预算而丢弃快照，见引擎第 813–875 行。尤其第三轮没有下一轮恢复来验证，日志中的“检查点存在”可能并不成立。

   **必须修改：**按 `engine.canCheckpoint()` 区分适用性，同时要求验收样本包含支持检查点的目标模型；对“每轮保存成功”核验请求位置和轮末仓库／原生句柄状态，不能仅靠捕获统计。

2. **内存阈值已经加入，但不足以独立证明“重排副本不存在”。**

   位置：[LargeModelHotfixDeviceTest.kt:108](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:108)，第 108–115 行；对照选择在第 82–86 行。

   `RssAnon` 只统计**驻留**匿名页，已经换出的匿名副本不计入；加载后的一次低读数可能漏掉仍然存在的分配。小模型先通过 `>1/2`，也不能排除随后加载大模型时发生换出。该区别见 [Linux `/proc` 官方说明](http<local-path>)。

   还有两个缺口：缺失 `RssAnon` 时按零处理，会让大模型断言通过；默认运行没有小模型对照时，也可以直接报告“全部通过”。最小文件本身亦不保证支持重排。

   **必须修改：**缺失指标应失败；完整验收应明确要求已知会重排的对照。使用原生重排 buffer 观测，或补齐换出量、固定样本及测量条件后再使用内存阈值。这些读数可支持当前修复，但不能作为无条件的结构性证明。

3. **新增文件大小探针存在异常路径描述符泄漏。**

   位置：[LargeModelHotfixDeviceTest.kt:99](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:99)，第 99–102 行。

   `store.open(model)` 已经取得原描述符；如果随后 `ParcelFileDescriptor.fromFd()` 复制描述符失败，`also` 不会返回，原 `ModelFile` 无人关闭。引擎到 `open()` 返回后才执行 `descriptor = open()`，因此外层 `unload()` 也拿不到它，见 [LocalModelEngine.kt:348](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:348)。

   **必须修改：**探针执行期间由测例持有原文件的关闭责任；探针异常时关闭原文件，成功返回后再交给引擎。当前 `use` 只覆盖复制出来的描述符。

4. **发布说明的内存口径仍比证据宽。**

   位置：[v0.5.1.md:3](docs/releases/v0.5.1.md:3)，以及第 10–12 行。

   “内存占用不再翻倍”仍是对总占用的宽泛承诺；本次保证的是取消特定权重副本。“自己另外分配的内存只有约 0.5 GB”又把匿名 RSS 表述成了全部分配量。内存段没有明确限定两种量化、8 线程和 8192 上下文；证据中的 2048 上下文读数实际约为 0.29 GB。

   **必须修改：**首句限定为“不再因 CPU 权重重排额外保存一份权重”；内存段明确设备、量化、线程、上下文和短回复条件，并写成“匿名 RSS 约 0.47–0.56 GB，总 RSS 约 6–8 GB”。

5. **`DEVICE.md` 有数字与条件描述不一致。**

   位置：[DEVICE.md:67](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:67)、第 20、46–49、99 行。

   - 4B 文件为 2.74 GB，匿名 RSS 为 3.11–3.20 GB，算得 **113.5%–116.8%**，不是表中的 116%–117%。若百分比来自另一组“刚加载完”的样本，应单列测量阶段。
   - 第 99 行写成“6 和 8 线程 × 2048 和 8192 上下文”，但四条记录实际是 `6/8192` 两次、`8/2048` 一次、`8/8192` 一次，**没有 `6/2048`**。
   - 第 20 行统一写每轮最多 128 token，第一轮旧测例实际为 64 token；两轮条件需要分别注明。

   **必须修改：**统一比例与内存数据的样本口径，按表列出实际参数组合，并补上两轮生成上限的区别。

**已核对通过的点**

- **P1-1 主路径已补齐。** 测例第 92–93 行打开开关，第 121–127 行计算并传入落点，第 147–148 行保留真实回答，第 154–155 行恢复开关，与炉边调用方式一致。恢复字段每轮清零，只有原生恢复成功才赋正值；`restoredFrom > 0`、复用数相等、回退原因零，能排除旧读数和普通前缀复用冒充检查点恢复。[引擎赋值证据](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:680)
- **第一轮未启用检查点的更正正确。** [DEVICE.md:38](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:38) 已正确指出开关和 `checkpointPos=-1`，不再错误归因于替换回答。
- **P1-2 的主要越界结论已删除。** 不再声称闪退解决、确定系统清后台、确定旧版同样发生，或线程和上下文无关；保留了失败记录和未验证范围。
- **P2-1 多数措施已落实。** 第 138 行断言可见正文非空；第 101、111 行核对实际打开文件大小；第 65–70 行在参数和登记表检查前删除旧报告、写入运行编号及开始状态。异常、超时不会被吞成成功。
- **P2-2 速度口径基本修正。** 发布说明明确设备、量化、线程、上下文和短回复条件；整数范围与 DEVICE 的 10–14、5.5–9.4、3.9–6.0 token/s 大体对应。提示读取时间已与加载、首个可见正文等待区分；历史 13.3 GiB 也明确不是严格对照。
- **运行合计正确。** 排除整次冻结运行后，4B 为 **8／5／3**，9B Q4 为 **5／5／0**，9B Q6_K 为 **9／6／3**，分别对应次数／完成／被杀。
- **P2-3 已修正。** [发布说明第 25–26 行](docs/releases/v0.5.1.md:25) 区分已保存历史与正在生成的这一轮，重试也改为建议，没有保证必成功。
- **未发现新增 Kotlin 编译或 lint 阻断。** 既有 [gate-4 记录](<logs>/hotfix-0.5.1-gate-4.txt:8) 对应精确提交及干净工作区，记录零警告、lint 零错误；[Gradle 日志](build/check-all/gradle.log:301) 中 AndroidTest Kotlin 编译为 `UP-TO-DATE`，整体成功。这是读取既有结果，不是本轮重新验证。

**实际读取的文件**

以下包含全文或相关片段：

- 第 1 轮记录：`logs/hotfix-0.5.1-codex-review-r1.md`。
- 本次三个修改文件：`LargeModelHotfixDeviceTest.kt`、`docs/releases/v0.5.1.md`、`docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md`，包括相关历史版本及差异。
- 引擎调用与状态：`LocalModelEngine.kt`、`AndroidModelStore.kt`、`CheckpointPlan.kt`、`CheckpointStore.kt`、`ChatModel.kt`。
- 生产调用及保存流程：`HearthViewModel.kt`。
- 原生检查点适用性：`llm/src/main/cpp/engine.cpp` 的 `canCheckpoint` 片段；未重审已通过的加载修法。
- 编译配置：`app/build.gradle.kts`。
- 既有验证记录：`logs/hotfix-0.5.1-gate-4.txt`、`build/check-all/gradle.log`。