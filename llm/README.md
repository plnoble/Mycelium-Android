# 本地模型引擎（P2-1）

## 可重现离线构建

固定 llama.cpp：5e03bdd8700948b9c41c54dd1b00f28a2aebc03f。
准备一个含该提交的本地 Git 克隆，执行：

~~~powershell
.\scripts\prepare-llama.ps1 -SourceRepository '<已有的本地 llama.cpp 克隆>'
.\gradlew.bat --offline :model-api:test :llm:testDebugUnitTest :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
~~~

JAVA_HOME 指向 Android Studio 自带 JBR，ANDROID_HOME 指向已安装的 Android SDK；不需要提交 local.properties。
使用仓库现有 Gradle 9.5.0，NDK 30.0.16248370，CMake 3.22.1，arm64-v8a / API 29。
准备脚本不联网；克隆不使用 hardlink/alternates；忽略目录 llm/vendor/ 保存独立源码副本。
脚本和 CMake 都校验固定 HEAD 及源码干净状态。没有自动联网补取或构建产物入库。

CPU 构建：GGML_NATIVE=OFF、GGML_OPENMP=OFF、GGML_LLAMAFILE=OFF、LLAMA_OPENSSL=OFF；
关闭 common、server、tools、examples、app、UI；JNI 使用 -O2 -fexceptions 和 c++_shared。
只编译推理核心与同一固定提交的 Jinja 解释器、JSON/Unicode 支持，不链接包含 HTTP 的 llama-common。
verifyOfflineDependencies 对 :llm / :model-api 的传递运行时依赖采用允许列表；JVM 架构测试同时检查源码边界。

## 接口和生命周期

:model-api 是 JVM Kotlin 模块。ChatModel.generate 接收完整消息列表及 GenerationParameters，
返回冷 Flow<String>，每项为正文增量。取消收集或调用 stop() 都停止当前操作。异常经 Flow 传播。
本地实现为 LocalModelEngine.shared，同一进程仅一个模型，互斥覆盖加载、生成、取消后的收尾和卸载。
取消通过原子标志接入 llama CPU abort callback；停止后等待 JNI 返回才允许卸载，防止使用已释放的内存。
加载和推理在 Dispatchers.IO；JNI 的模型和 FILE*、Kotlin 的描述符均在卸载时释放。
默认 6 线程、8192 上下文、n_gpu_layers=0。每次生成先清空 KV/循环状态。

聊天模板来自 GGUF tokenizer.chat_template，用同版本 Jinja 渲染 system/user/assistant。
模板缺失或不支持时明确失败，不使用硬编码厂商格式。传 enable_thinking=false；
模板仍打开思考段时从隐藏状态开始；流式过滤 <think>…</think>，跨片段标签也不会泄露。
未闭合思考段或标签尾部不会作为正文输出。输出字节用增量 UTF-8 解码，保留跨 token 中文/emoji。

## 模型登记

系统 ACTION_OPEN_DOCUMENT + EXTRA_LOCAL_ONLY，不申请存储权限、不下载。
SHA256 按 1 MiB 块校验，显示进度并检查 GGUF 魔数、已知文件大小；按 URI 和 SHA256 去重。
优先持久只读授权，使用 dup + fdopen + llama_model_load_from_file_ptr 直接读取，
不通过 /proc/self/fd 重开路径（SAF 授权不保证这种路径访问）。
不可持久授权或不可 seek 时，先提示确定的空间大小，经用户确认后复制到私有目录；
提供器不报告大小时拒绝复制。复制取消或失败清理临时文件，登记原子替换。
移除仅修改登记、释放授权和删除应用私有复制件；接口没有删除源文档的方法。

## 真机复验

先确认共享手机已由其他开发线释放。离线状态下 Gradle 的 connectedDebugAndroidTest 缺 UTP 插件，不要为此联网。
安装本分支 debug 包用 adb install -r；仅当其他线 debug 版本更高时加 -d。保留应用数据，不卸载、不清数据；不安装 release。
在「我的 → 本地模型」用系统选择器导入既有 Qwen3.5-4B-Q4_K_M.gguf 后运行：

~~~powershell
adb -s <序列号> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <序列号> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s <序列号> shell am instrument -w com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner
~~~

测试读取该登记及持久授权，验证 64 token、确定性重复的流式顺序、生成中停止 <1 秒、
长提示处理中取消 <1 秒、卸载 RSS 回落；文件日志在 debug 应用 files/p2-1-device.txt。
测试不复制、写入或删除用户原模型。截图和设备原始日志保存在 Windows 侧评审目录，不入公开安卓仓库。

