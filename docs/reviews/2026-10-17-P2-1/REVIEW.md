# P2-1 审核报告（Claude 统筹代审）

- 审核对象：`wp/P2-1-local-llm` @ `53b31cd`（功能提交 `7623fa1`），对照 `main` @ `41bfd10`。
- 审核者：统筹（Claude）代审。Codex 共享额度 87%，超过 70% 停审线，开发线没有发起。额度：不适用。
- 结论：**通过**。和 P1-6a 一起合并进本地 `main`（`85736f0`），没有推送。

## 我重跑的验证

| 检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| 先合并 P1-6a、再合并 P2-1 的全新 worktree：`scripts/prepare-llama.ps1` 从 P0-7 留下的本地克隆准备源码 | 打印 `llama.cpp verified at 5e03bdd8700948b9c41c54dd1b00f28a2aebc03f`，没有联网 | 一致 |
| 同一目录离线跑 `:contract:test :model-api:test :llm:testDebugUnitTest :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` | 已通过：契约 14、`:model-api` 3、`:llm` 11、`:app` 27，一共 55 个，0 失败；lint 0 error；调试包和测试包都编出来了 | 一致 |
| 正式合并后的树和上面验证的树逐文件对比 | 完全一致 | — |
| 真机原始记录（Windows 仓库 `.omx/development/reviews/2026-10-17-android-P2-1/`） | `device-full-suite.txt`：3 个测试，逐条先开始后通过；`device-256.txt`：256 token、11.71 token/秒、`think_marker=false`；`baseline.txt`：正式版和两个原模型的校验值 | 一致 |
| 3 张截图 | 导入时的校验进度、模型列表（读取原文件、SHA256）、一次流式生成（正文逐步出现、「停止」可点）。状态栏已裁掉，没有其他应用的内容 | 一致 |

- 真机部分这次统筹没有重跑：开发线留下了完整的 instrumentation 原始输出和测量日志，我逐条核对过。

## 代码审查

- **接口**：`:model-api` 是纯 Kotlin，`ChatModel.generate` 返回冷 `Flow<String>`（只含正文增量），取消收集或调用 `stop()` 都会停。参数有范围校验。
- **并发与释放**：一把互斥锁管住加载、生成和卸载。协程取消时触发原生停止，并且要等 JNI 返回后才释放；单测用假原生层验证了「原生调用进行中不能卸载」。
- **原生层**：读模型时复制文件描述符、定位到开头，交给 `llama_model_load_from_file_ptr`，不靠 `/proc/self/fd` 重开路径。用模型自带的聊天模板（同一提交的 Jinja）、`enable_thinking=false`。加载进度、解码中止、循环条件三处都会响应取消。提示加生成长度超过上下文时明确报错。报错不带用户消息片段。
- **文本**：UTF-8 增量解码，中文、表情被切开也不会乱码；思考标签被切在两段之间也能拦下。
- **模型登记**：校验 GGUF 文件头和大小；按地址和 SHA256 去重；登记文件原子替换写入；需要复制时先经用户同意，取消时不留残余；移除只释放授权或删 App 里的复制件，代码里没有删除原文件的路径。
- **隐私**：`:model-api`、`:llm` 的源码和构建文件都没有联网代码，`:llm` 的清单没有网络权限；CMake 不链接含 HTTP 的 `llama-common`。架构测试覆盖了这几点。
- **测试质量**：单测覆盖流式顺序、加载中取消、显式停止、去重、复制同意、取消复制、无效 GGUF、Unicode 和思考标签边界，都是有效断言。

## 验收逐条

| 验收项 | 结果 | 证据 |
|---|---|---|
| 测试全部通过，真机测试在红魔上跑过 | 通过 | 见上表 |
| 真机截图：模型列表、校验进度、一次流式生成 | 通过 | 3 张都有 |
| 正式版不动 | 通过 | 0.1.1（versionCode 2），最后更新时间 2026-10-10 05:23:04 |
| 全新 worktree 全套检查 | 通过 | 开发线对 `7623fa1` 跑过；统筹对合并后的树又跑了一遍 |

## 必须修改

- 无。

## 建议修改（不阻塞，放进 P2-2 或之后）

1. `llm/README.md` 的「真机复验」写的是 `:app:connectedDebugAndroidTest`，但离线状态下缺 UTP 插件，跑不了。建议改成实际用的 `adb install -r` 加 `am instrument`。
2. 256 token 只测了一次，而且提示只有 44 个 token。炉边对话会带上人物设定和历史，提示会长得多。P2-2 做真机走查时，用接近真实长度的提示（比如 1500 token 左右）测 3 次，记下读提示和生成两段的速度。这直接决定用户要等多久。
3. 「我的」里的模型页现在只有「本地模型」。P1-7 合并后，要按任务书分「本地」「云端」两组显示，由后合并的那个包来做。
4. 开发线留在 `D:\Agent\Worktrees\Mycelium-Android\` 下的 `p1-6a-check`、`p2-1-check` 两个检查用 worktree，请删掉。
5. `app/build.gradle.kts` 末尾多了两个空行。

## 无法验证的项目及原因

- CI：没有推送。
- 9B 模型这次没有测，P0-7 的 9B 数据仍然有效。
