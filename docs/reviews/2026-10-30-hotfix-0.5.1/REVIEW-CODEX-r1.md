Codex 只读审核第 1 轮原文（默认模型，`-s read-only`）。本机路径已脱敏：仓库内文件写成相对路径，固定的 llama.cpp 源码写成 `<llama-5e03bdd>/`，日志目录写成 `<logs>/`。

结论：不通过

核心 C++ 修法成立：在当前两套 CPU 原生库配置下，超过 4 GiB 的模型会使用 mmap，并避开 CPU 重排权重副本。阻断点在验收覆盖和发布结论：测例没有启用检查点，现有真机记录也不足以宣称闪退已经解决。

审查对象为 `5478d63` 相对 `v0.5.0` 的指定差异；未修改文件，未运行构建、测试或 adb。

**P0：未发现。**

**P1**

1. **新增测例没有执行它声称覆盖的检查点保存与恢复。（问题 4、5）**

   位置：[LargeModelHotfixDeviceTest.kt:27](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:27)、同文件第 83–86 行。

   测例只传了 `cacheKey`。`checkpointPos` 默认是 `-1`，引擎的 `checkpointEnabled` 默认也为 `false`；保存和恢复必须同时满足开关开启、位置为正、有缓存键及模型支持。即使其他代码打开了共享引擎的开关，这个测例仍因位置为 `-1` 而不走检查点。证据：[ChatModel.kt:27](model-api/src/main/kotlin/com/xavierxia/mycelium/model/ChatModel.kt:27)、[LocalModelEngine.kt:274](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:274)、同文件第 710–717、750–760 行。

   炉边实际会打开开关，并调用 `checkpointPositionFor(messages)`：[HearthViewModel.kt:1069](app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:1069)、第 1127–1134 行。因此，测例漏掉了检查点分配及恢复时的内存负担。

   **必须修改：**按生产路径设置检查点开关和位置，保存真实生成回答作为后续历史；对支持检查点的模型断言实际保存了状态、后续轮次实际恢复了状态，并在结束时恢复共享设置。[DEVICE.md:60](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:60) 将“没用上检查点”归因于替换回答，遗漏了这里更直接的原因，必须更正。

2. **发布说明把“消除权重副本”升级成“闪退已修复”，并把未查明的 SIGKILL 写成已知因果。（问题 6）**

   位置：[v0.5.1.md:7](docs/releases/v0.5.1.md:7)、第 18 行。

   “大模型不再一开聊就闪退”与记录直接冲突：9B Q6_K 仍有一次加载后首轮被杀、一次加载中被杀；最后三次前台运行也有两次被杀。参见 [DEVICE.md:25](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:25) 至第 39 行、第 74 行。

   证据中的以下推论也不成立：

   - 同时有其他 App 收到 SIGKILL，只能说明时间相关，不能证明“系统先清后台腾内存，再误杀知衍”。`reason=SIGNALED/status=9` 没有指出发送者和原因。
   - 修复包的小模型路径与旧版等价，支持“问题不只发生于关闭重排的大模型”；不能替代“正式版 0.5.0 使用同一小模型”的实测，证明历史上已经发生相同故障。
   - 四次单独加载成功，不能证明“与线程数、上下文无关”；每秒采样的一次 `MemAvailable` 也不能排除采样间的压力或其他内存限制。参见 [DEVICE.md:64](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:64) 至第 75 行。

   **必须修改：**将修复结论限定为“取消超过 4 GiB 文件的 CPU 权重重排，降低内存占用”；明确仍观察到原因未明的 SIGKILL。删除“手机内存再大也会被关掉”“确定是系统清后台”“确定不是本版带来”等断言。若保留“闪退已解决”，必须补同模型、同设置、同前台状态的旧版/新版对照，并解释新版仍失败的案例。

**P2**

1. **测例通过条件不足以证明用户可正常聊天，也不能独立证明内存回归已修复。（问题 5）**

   位置：[LargeModelHotfixDeviceTest.kt:65](app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt:65) 至第 93 行。

   `toList()` 收集的正文被丢弃，唯一内容相关断言是 `generatedTokens > 0`。原生 token 计数发生在正文过滤之前，因此只有隐藏思考或空白、没有可见回答，也可能通过：[engine.cpp:389](llm/src/main/cpp/engine.cpp:389) 至第 396 行、[LocalModelEngine.kt:794](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:794) 至第 801 行。

   此外，“超过 4 GiB／不重排”标签来自登记表大小，不是本次打开文件的实际大小或原生观测；内存只有记录，没有验收条件。即使重排误恢复，只要该次没有被杀，仍能通过。报告直到第 73 行才覆盖；此前参数解析、读取登记表或断言失败，会留下上一次的“全部通过”。

   **必须修改：**断言拼接后的可见回答非空；核对实际打开文件的大小；为内存修复提供可核验的原生 buffer 观测或同条件内存对照；报告加入本次运行标识并在前置检查前写入开始状态，以本次 instrumentation 结果判定成功。

   需要区分：异常、超时、SIGKILL 本身不会被这个测例吞掉变成 JUnit 成功；空模型列表也已有断言。上述风险是覆盖不足或读取旧报告造成的误判。

2. **内存、速度的发布口径超出了测量范围。（问题 6）**

   位置：[v0.5.1.md:7](docs/releases/v0.5.1.md:7)、第 19 行；证据为 [DEVICE.md:41](docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md:41) 至第 60 行。

   - **“额外内存降到半 GB”**有当前短回复测试的匿名 RSS 支持，但这不是总内存，也不是单独测出的重排副本大小。总 RSS 仍约为 6.21 GB、7.80–7.87 GB；这些测量还没有启用检查点。旧记录的 13.3 GiB 是不同构建、不同提示下的进程峰值，不能与当前匿名 RSS 直接比较，得出严格的“从几 GB 降到半 GB”实测结论。
   - **“9B 速度约为 4B 一半”**作为这台设备上这些样本的粗略描述，并非完全不符：4B 为 10–13.7 token/s，9B Q4 为 5.5–9.4，9B Q6_K 为 3.9–6.0。但不能泛化到所有 9B，也不能据此量化本次关闭重排造成的速度损失。
   - **“短话要先读 2–5 秒才开始出字”**主要对应约 36 token 的预填充测量。96 token 的记录达到 6.5–13 秒；`promptMillis` 也不包含加载、模板/tokenize、首个可见正文等全部等待。

   **必须修改：**写明设备、量化、线程、上下文及短回复条件；明确“匿名 RSS 约半 GB，总 RSS 仍为数 GB”；历史峰值只作非严格参考。速度最好给出两种量化的实测范围，将“2–5 秒”限定为指定短提示的读取耗时。源码足以支持“去掉重排副本”，不必用不等口径的数据夸大它。

3. **“被杀后重新打开即可，聊天记录不会丢”遗漏了正在生成的这一轮。（问题 6）**

   位置：[v0.5.1.md:18](docs/releases/v0.5.1.md:18)。

   生成中的回答先放在内存缓冲，结束后才经 `finishTurn()` 调用 `chat.finish()` 落库。SIGKILL 不会执行协程清理，不能保证屏幕上已经显示的本轮内容保存下来。证据：[HearthViewModel.kt:870](app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt:870)、第 896–902、1303–1306 行。

   **必须修改：**限定为“已保存的历史记录保留，正在生成、尚未保存的一轮可能丢失”；把“重新打开再试一次就行”改成建议操作，不承诺重试一定成功。

**已核对通过的点**

1）**超过 4 GiB：确实避开重排副本，`FILE*` 路径确实 mmap。（问题 1）**

- [engine.cpp:173](llm/src/main/cpp/engine.cpp:173) 至第 184 行设置 `MMAP`，并仅在文件大小 `<= 4 GiB` 时启用 extra buffers；恰好 4 GiB 仍走原路径。
- 固定源码 [llama.cpp:489](<llama-5e03bdd>/src/llama.cpp:489) 将 `FILE*` 和参数原样交给实现；[llama-model-loader.cpp:675](<llama-5e03bdd>/src/llama-model-loader.cpp:675) 至第 710 行使用 `no_alloc=true` 读取元数据并包装原 FILE，没有强制关闭 mmap。
- [llama-mmap.cpp:425](<llama-5e03bdd>/src/llama-mmap.cpp:425) 取 `fileno()`，第 482–497 行调用 `mmap(PROT_READ, MAP_SHARED, …)`；映射失败会报错，不会悄悄整份读入匿名内存。
- [llama-model.cpp:1112](<llama-5e03bdd>/src/llama-model.cpp:1112) 的 extra-buffer 分支被跳过，CPU 默认 buffer 在第 1856–1875 行直接包装映射地址；loader 第 1655–1669 行直接绑定 tensor。原重排 buffer 则会另行分配并重排，见 [repack.cpp:5150](<llama-5e03bdd>/ggml/src/ggml-cpu/repack.cpp:5150) 至第 5177 行。

这消除的是重排权重副本；KV、计算缓冲、检查点和驻留的文件页仍然占内存。加载还会预取映射页，不能理解成“文件不进物理内存”。

2）**不超过 4 GiB：当前构建中 AUTO → MMAP 没有实质加载路径变化。（问题 2）**

默认参数原本就是 `AUTO + use_extra_bufts=true`：[llama-model.cpp:2925](<llama-5e03bdd>/src/llama-model.cpp:2925)。AUTO 和 MMAP 都先启用映射；AUTO 仅在所选设备不支持 mmap 时关闭它，第 1514–1523 行。当前 CPU 后端声明支持 mmap 和 host-pointer buffer：[ggml-cpu.cpp:390](<llama-5e03bdd>/ggml/src/ggml-cpu/ggml-cpu.cpp:390)。

因此，小模型仍可使用原有重排及加速内核，预期速度和权重内存行为不变。“仍可重排”取决于张量类型和硬件支持，并非所有小文件都一定重排。新增 `fstat` 拒绝条件是额外的输入检查。

3）**描述符顺序和所有权正确，无法确定大小时拒绝加载合理。（问题 3）**

[AndroidModelStore.kt:49](llm/src/main/java/com/xavierxia/mycelium/llm/AndroidModelStore.kt:49) 返回持有 `ParcelFileDescriptor` 的包装；[LocalModelEngine.kt:343](llm/src/main/java/com/xavierxia/mycelium/llm/LocalModelEngine.kt:343) 至第 376 行保证加载期间持有它，卸载原生资源后才关闭原描述符。

原生 `fstat → dup → fdopen → fseek(0)` 顺序正确。`fdopen` 失败关闭 duplicate；后续失败由 `engine.clear()` 关闭 FILE。llama 的 FILE 包装设置 `owns_fp=false`，不会重复关闭：[llama-mmap.cpp:229](<llama-5e03bdd>/src/llama-mmap.cpp:229)、第 390–395 行。

`dup` 共享文件偏移，但这里没有并发读取原 fd。项目仅构建 arm64，不存在该比较的 32 位大小截断问题。对有效、可映射的模型文件，大小必须为正；失败时不能把未知大小当小模型继续重排。该行为不代表支持所有未知大小或流式 SAF provider——原路径本来也要求可 seek。

4）**两套库、状态接口、取消回调未发现静态冲突。（问题 4）**

两套库使用同一个 CMake 源目录和 `engine.cpp`，固定同一 llama 提交：[CMakeLists.txt:8](llm/src/main/cpp/CMakeLists.txt:8)、第 49 行；[llm/build.gradle.kts:95](llm/build.gradle.kts:95) 至第 148 行。

`llama_state_seq_*` 序列化的是 context memory，而非权重 buffer：[llama-context.cpp:3446](<llama-5e03bdd>/src/llama-context.cpp:3446)。换模型时原有检查点会清除。此次没有修改状态格式、JNI 接口或取消协议。

加载回调仍逐张量及结束时检查取消，解码仍使用 `abort_callback`；失败会释放资源。不过映射预取期间不保证即时响应取消，这是原有边界。**静态兼容成立，新增真机测例未证明检查点路径已经通过。**

5）**测例目前能证明的范围有限。（问题 5）**

一次完整通过只能证明：所选模型在当次环境下完成加载和三轮短生成，返回了非零 token，且没有报告停止。不能据此证明所有目标模型、生产检查点续聊、长期稳定性或 SIGKILL 根因已解决。用户另外两个约 4.4/4.5 GB 的模型尚未测试，记录也明确承认这一点。

6）**真机记录有价值，但发布结论必须收窄。（问题 6）**

记录支持“当前测试中大模型匿名 RSS 明显低于文件大小”，也如实列出了失败。成功次数统计本身能与表格对上；问题在从这些观察进一步推导原因、历史归属及普遍稳定性。具体修正见上述 P1、P2。

7）**更新测例改为 0.7.0 合理，没有掩盖已发现的产品问题。（问题 7）**

[UpdateManager.kt:16](app/src/main/java/com/xavierxia/mycelium/update/UpdateManager.kt:16) 比较真实 `BuildConfig.VERSION_NAME`。本包升到 0.5.1 后，远端 0.5.1 应被判断为已是最新版；继续期待更新卡片才是错误。

0.7.0 同时高于当前版本和已忽略的 0.6.0，更符合该测例名称。相同/更旧版本仍有独立断言：[UpdateManagerTest.kt:43](app/src/test/java/com/xavierxia/mycelium/update/UpdateManagerTest.kt:43)。测试依赖构建版本的脆弱性仍存在，但不是这次改动引入的产品缺陷。

8）**0.5.1 / versionCode 7 与 release-guard 的静态规则一致。（问题 8）**

[app/build.gradle.kts:19](app/build.gradle.kts:19) 的赋值唯一；发布标签应为 `v0.5.1`，对应说明文件存在且非空。已读取的正式标签 versionCode 为 1、2、5、6，最大为 `v0.5.0` 的 6，故 7 满足增长要求。对应规则见 [release-guard.sh:178](scripts/release-guard.sh:178)、第 191–204、266–289 行。

这是静态一致性结论；未运行 guard，也未核验签名 APK，不能声称完整发布闸门已通过。

**实际读取的文件**

下列包括完整读取和为追踪调用链读取的相关片段。

- 本次全部差异：`app/build.gradle.kts`；`app/src/androidTest/java/com/xavierxia/mycelium/localmodels/LargeModelHotfixDeviceTest.kt`；`app/src/test/java/com/xavierxia/mycelium/update/UpdateCenterTest.kt`；`llm/src/main/cpp/engine.cpp`；`docs/releases/v0.5.1.md`。
- 加载与检查点：`llm/build.gradle.kts`、`llm/src/main/cpp/CMakeLists.txt`；`llm/src/main/java/com/xavierxia/mycelium/llm/` 下的 `AndroidModelStore.kt`、`ModelRegistry.kt`、`LocalModelEngine.kt`、`NativeLibraryVariant.kt`、`CheckpointPlan.kt`；`model-api/src/main/kotlin/com/xavierxia/mycelium/model/ChatModel.kt`；`app/src/main/java/com/xavierxia/mycelium/hearth/HearthViewModel.kt`。
- 更新与发布：`app/src/main/java/com/xavierxia/mycelium/update/` 下的 `UpdateManager.kt`、`UpdateCenter.kt`；对应测试目录的 `UpdateFakes.kt`、`UpdateManagerTest.kt`；`scripts/release-guard.sh`、`.github/workflows/release.yml`、`docs/releases/v0.5.0.md`。
- 真机证据：`docs/reviews/2026-10-30-hotfix-0.5.1/DEVICE.md`；通过 `git show` 读取整合分支的 `docs/reviews/2026-10-25-9b-memory-fix/DEVICE.md`。
- 固定 llama 源码 `5e03bdd`：`AGENTS.md`、`CMakeLists.txt`、`include/llama.h`；`src/llama.cpp`、`llama-model.cpp`、`llama-model-loader.cpp`、`llama-mmap.cpp`、`llama-context.cpp`；`ggml/CMakeLists.txt`、`ggml/src/ggml-cpu/CMakeLists.txt`、`ggml-cpu.cpp`、`repack.cpp`。
- 历史对照：`v0.5.0` 的差异基线，以及 `v0.1.0`、`v0.1.1`、`v0.4.0`、`v0.5.0` 中的 `app/build.gradle.kts` 版本赋值。