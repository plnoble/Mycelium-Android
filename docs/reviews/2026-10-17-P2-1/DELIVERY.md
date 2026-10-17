# P2-1 交付报告

- 分支：`wp/P2-1-local-llm`，从 `main @ 41bfd10` 拉出。没有推送，没有打标签，没有发布，没有合并。
- 交审前 `main` 仍停在 `41bfd10`，没有新提交需要并进来。
- 调试包版本号沿用分支上已有的 0.2.0（versionCode 3）。本包没有再改版本号。正式发布仍等统筹。
- 出具日期：2026-10-17。
- 全新 worktree 的全套检查：见文末。电脑上 11:46 那一次 JVM 41 个测试和 lint 是在本目录跑的，不是全新目录。

## 做了什么

- 新增 `:model-api`。它是纯 JVM Kotlin，没有网络代码，也没有原生代码。`ChatModel.generate` 接收完整消息列表和 `GenerationParameters`，返回冷 `Flow<String>`，每项是可见正文的增量。取消收集或调用 `stop()` 都停止当前操作。本地和云端都实现这个接口。A-圆桌线的「回答模型」之后要对齐到这里；后合并的那条线做对齐。
- 新增 `:llm`。它是 Android 库，带 llama.cpp 的 JNI。不依赖 `:net`，也不依赖任何 HTTP 库。架构测试禁止 `java.net`、`javax.net`、`okhttp`、`retrofit`、`ktor`、`HttpURLConnection`、`https?://`、`sys/socket.h`、`netdb.h`、`cpp-httplib`。CMake 只编译推理核心，加上同一提交里的 Jinja 解释器、JSON 和 Unicode。不链接 `llama-common`，那部分含有 HTTP。
- `app/.../localmodels/`：「我的 → 本地模型」。导入、线程数、上下文长度、列表（名称、大小、读取原文件还是应用内复制件、SHA256）、加载、移除、试运行。试运行有提示和最多 token 数，以及生成、卸载、停止。占位文字是「生成的正文会逐步显示在这里，思考段默认隐藏。」离开页面会卸载引擎。忙碌时屏幕保持常亮。
- 模型登记用系统的 `ACTION_OPEN_DOCUMENT` 加 `EXTRA_LOCAL_ONLY`。不申请存储权限，应用内不下载。SHA256 按 1 MiB 分块计算并显示进度，检查 GGUF 魔数和已知大小，按 URI 和 SHA256 去重。优先持久只读授权：`dup` + `fdopen` + `llama_model_load_from_file_ptr` 直接读，不通过 `/proc/self/fd` 重开路径。提供器不能持久授权或不能 seek 时，先把确定的占用大小告诉用户，确认后才复制到应用私有目录；大小未知就拒绝复制。移除只改登记、释放授权、删除私有复制件。没有删除源文件的接口。
- 进程内只有一个引擎。互斥覆盖加载、生成、取消后的收尾和卸载。加载和推理在 `Dispatchers.IO`。取消用原子标志接到 llama 的 CPU abort 回调。卸载等到 JNI 返回。JNI 的模型、`FILE*` 和 Kotlin 的文件描述符都在卸载时释放。默认 6 线程、8192 上下文、`n_gpu_layers=0`。每次生成前清空 KV 和循环状态。
- 聊天模板来自 GGUF 的 `tokenizer.chat_template`，用同一版本的 Jinja 渲染。模板缺失或不支持就失败，不写死厂商格式。传入 `enable_thinking=false`。模板仍然打开思考段时，流式输出从隐藏状态开始。`<think>…</think>` 跨片段也会滤掉。未闭合的思考段和标签尾部不当作正文。UTF-8 按增量解码，跨 token 的中文和 emoji 保留。

## 编译

- llama.cpp：`5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`。由 `scripts/prepare-llama.ps1` 从本地克隆检出，脚本不联网。源码在 `llm/vendor/llama.cpp`，被 Git 忽略，不入库。CMake 会核对这个提交，并且源码必须干净。
- 工具链没有升级：Gradle 9.5.0，NDK 30.0.16248370，CMake 3.22.1。arm64-v8a，`ANDROID_PLATFORM=android-29`，Release，`c++_shared`。JNI 用 `-O2 -fexceptions`。
- 四个开关：`GGML_NATIVE=OFF`、`GGML_OPENMP=OFF`、`GGML_LLAMAFILE=OFF`、`LLAMA_OPENSSL=OFF`。common、server、tools、examples、app、UI 都关。CPU 后端，`n_gpu_layers=0`。
- 真机上那次调试包里，去掉符号后的 arm64-v8a `.so` SHA256（打进 APK 的就是这一份；文件是构建产物，不入库）：

| 文件 | SHA256 |
|---|---|
| libggml-base.so | `2c7988fb344ced5684f5f03810477065c539abe2c17fffca532b39555491fab2` |
| libggml-cpu.so | `9066fe6f45219dd9f0bb342d87d72b6f9e0083a111818900710f9a406d956803` |
| libggml.so | `f4f25a3fcb23c26827d39267d4a73722bf5100a0cab55b47548a609971f042a0` |
| libllama.so | `06d86453deb48b2efbe423687bba5d745f95c0aa6d2ba8670de47c093825be20` |
| libmycelium_llm.so | `95578be4037a06d55e99af3ac6f03e109f6d9601ed2ce3d35902a403f8a817ab` |
| libc++_shared.so | `b5d97c38a728afe28d341cda3369f512d35eaf9c680c1b0563cd6a63e395230f` |

这是本包自己的构建。P0-7 探针的 `libspike_jni.so` 等哈希是另一次构建，不能拿来对照本包。`libc++_shared.so` 来自 NDK，两次构建相同。源码里的 `__FILE__` 会把编译目录写进二进制，所以换一个目录重编，除了 `libc++_shared.so`，其余五个的 SHA256 会变。这不是源码变了。

## 电脑上的结果

11:46 在开发目录离线构建，不是全新 worktree：

| 命令 | 结果 |
|---|---|
| `:model-api:test` | 已通过，3 个 |
| `:llm:testDebugUnitTest` | 已通过，11 个 |
| `:app:testDebugUnitTest` | 已通过，27 个 |
| `:app:lintDebug` | 已通过，0 error |
| `:app:assembleDebug` | 已通过 |
| `:app:assembleDebugAndroidTest` | 已通过 |

JVM 合计 41 个，0 失败。`:contract:test` 在这个目录当时没有跑，记为未验证，等全新 worktree 补上。

`connectedDebugAndroidTest`：失败。离线缓存缺 `com.android.tools.utp:android-test-plugin-host-additional-test-output:32.3.0`。没有为它联网，也没有清缓存。真机改用 adb 的 `am instrument`。

## 真机

设备是 P0-7 用的红魔（Android 16）。电脑上同时连着一台非安卓设备，操作都用 `adb -s` 指定红魔，没有对那台设备做任何事。序列号不写入仓库。

只安装调试包 `com.xavierxia.mycelium.debug`（界面名「知衍（调试）」）和对应的测试包。用 `adb install -r`。没有卸载，没有清数据，没有安装正式包。

- 完整 instrumented 套件：已通过。`LocalModelDeviceTest` 加上 `DeviceRetrievalTest` 的两个方法，一共 3 个测试，0 失败，用时 15.268 秒。日志在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P2-1/device-full-suite.txt`。
- 读取方式：已通过。登记的是 `Qwen3.5-4B-Q4_K_M.gguf`，2,740,937,888 字节，SHA256 `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4`。日志写的是 `read=persisted-SAF-fd`，没有复制。
- 64 token 这一次：已通过。加载 400 毫秒。`promptTokens=44`，`generatedTokens=64`，`promptMillis=1521`，`generationMillis=4006`，约 15.98 token/秒。生成中停止 1 毫秒。提示处理阶段取消 18 毫秒。卸载后 RSS 从 3,358,444 kB 回到 354,048 kB。更早一次同测试是加载 419 毫秒、约 15.83 token/秒、停止 2 毫秒、取消 33 毫秒。64 token 太短，这两次都不能拿来和 P0-7 的 256 token 比快慢。
- 256 token：已通过，只跑了 1 次。`LongGenerationDeviceTest`，6 线程、8192 上下文，温度 0，同一条植物学家提示，`promptTokens=44`，`generatedTokens=256`，`stopped=false`。这次加载 1478 毫秒（含测试进程刚起来的开销，不和界面上的加载时间平均）。`promptMillis=1847`，`generationMillis=21855`，11.71 token/秒。输出里没有 `<think>`。日志在同一评审目录的 `device-256.txt`。
- 和 P0-7 4B@6 的 256 token 生成对照：旧构建 8.73 / 8.74 / 8.73 token/秒；四参数重编后 8.17 / 8.16 / 7.77。本包这一次是 11.71。提示只有 44 token，而且只跑了一次，不是三次，提示长度也和 P0-7 的 512 / 2048 不同。不能写成已经确认更快。
- 界面加载：已通过。同一模型显示「已加载（450 毫秒）」，卡片写「读取原文件」。这是界面计时，和上面 400 / 419 / 1478 毫秒是不同次测量，不合并成一个数。
- 流式截图：已通过。`stream-generation.png`。画面是「知衍（调试）」的试运行：提示「请用中文简短地介绍你自己。」，最多 256 token，正文已出现「你好！我是Qwen3.5，阿里巴巴最新推出的」，按钮是「停止」。没有 `<think>`，没有状态栏，没有通知。同目录还有 `import-progress.png`、`model-list.png`。三张都不进本仓库。
- 原文件：已通过。走查和测试之后，`/sdcard/Models/` 里 4B 的 SHA256 仍是 `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4`，9B 仍是 `03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8`。9B 这次没有加载。没有修改、移动或删除原文件。移除对话框曾被误点开，随后点了取消，登记还在。
- 共存：已通过。正式版 `com.xavierxia.mycelium` 仍是 0.1.1（versionCode 2），最后更新时间仍是 2026-10-10 05:23:04。

## 没有做

- 没有推送，没有打标签，没有发布，没有合并进 `main`。
- 没有升级 Gradle、NDK、CMake，没有清共享 Gradle 缓存。
- 没有 GPU，没有炉边界面，没有应用内下载。
- 没有把 9B 再测一遍。P0-7 的 9B 数字仍然有效，本包没有新的 9B 结果。
- 没有把 256 token 再跑两次。一次不能代替 P0-7 的三次。
- Codex 没有发起。共享额度已经到 87%。交统筹代审。

## 全新 worktree

已通过。对 `7623fa1` 另开一个不挂分支的目录。该目录一开始没有 `llm/vendor`。`scripts/prepare-llama.ps1` 从 P0-7 留下的本地克隆检出 `5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，脚本打印 `llama.cpp verified`，没有联网。

然后离线跑：

| 命令 | 结果 |
|---|---|
| `:contract:test` | 已通过，14 个（向量 3、架构 1、校验和 1、角色包 6、检索向量 3） |
| `:model-api:test` | 已通过，3 个 |
| `:llm:testDebugUnitTest` | 已通过，11 个（引擎 4、登记 4、文本流 3） |
| `:app:assembleDebug` | 已通过 |
| `:app:testDebugUnitTest` | 已通过，27 个 |
| `:app:lintDebug` | 已通过，0 error |
| `:app:assembleDebugAndroidTest` | 已通过 |

0 失败。`:contract:test` 里 sqlite-jdbc 在 JBR 25 上有 `System::load` 的限制警告，测试仍通过。Gradle 9.5.0 有将在 Gradle 10 不兼容的弃用提示，没有升级。

这个目录编出的五个引擎 `.so`，SHA256 和上面真机那一包不同。去掉符号之后逐条比对字符串，差异只有编译目录（`__FILE__`）。`libc++_shared.so`、`libsqlcipher.so`、`libandroidx.graphics.path.so` 两边相同。真机速度是开发目录那一包测的，不是这个新目录的包。新目录的包没有再装到手机上。
