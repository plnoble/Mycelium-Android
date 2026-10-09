# P0-7 真机技术探针：本地模型、SQLite/SQLCipher、Keystore

- 仓库 / 基准：安卓仓库 `main`（第 0 阶段与 P0-9 改名合并后）→ 探针分支 `spike/P0-7-device`（推荐用 `git worktree add ..\mycelium-android-spike spike/P0-7-device`）
- 依赖：P0-6（第 0 阶段）和 P0-9（改名）合并；或者在单独 worktree 里进行，不碰其他人的工作区
- 预计规模：2–3 个工作日（含下载模型）
- 背景：
  - 伴侣只能用手机本地模型，目标是 Qwen3.5-4B，需要实测 9B 是否可行。
  - 圆桌要在手机上读角色包（SQLite + FTS5 trigram），但 minSdk 29 自带的 SQLite 是 3.22，不支持 trigram（需要 3.34+）。
  - 两个空间都要加密数据库。
  - 这些都要在真机（红魔 11 Pro+）上测出实际数据，再定技术选型。

## 目标

用实测数据回答四个问题：
1. 伴侣默认用 4B 还是 9B，用什么推理后端？
2. 数据库用 SQLCipher 还是自带 SQLite？
3. FTS5 trigram 能不能用？
4. Keystore 方案能不能跑通？

## 步骤

1. **设备信息**（adb）：型号、Android 版本、SoC、内存总量（`/proc/meminfo` 的 MemTotal）、可用存储；截图或保存日志。
2. **llama.cpp 构建**：用 NDK + CMake 编 arm64-v8a。至少测 CPU 后端；能编通的话，再测 Adreno 的 OpenCL 后端。记录 llama.cpp 的提交号和编译参数。探针代码可以是最简单的 JNI + 命令行式 Activity，不讲究界面。
3. **模型**：Qwen3.5-4B 和 Qwen3.5-9B 的 Q4_K_M GGUF，从官方或知名量化仓库下载，按页面上的 SHA256 校验。**不提交进仓库**，放在手机的应用私有目录。
4. **每个模型 × 后端，测这些指标**：
   - 加载时间；
   - 512 token 和 2048 token 提示的处理速度（token/s）；
   - 生成 256 token 的速度（token/s）；
   - 峰值内存（`/proc/<pid>/status` 的 VmHWM 或 `dumpsys meminfo`）；
   - 8K 上下文时的内存；
   - 连续生成 5 分钟后的电池温度（`dumpsys battery`）和降频情况；
   - 9B 是否被系统杀掉。
5. **主观质量样本**（不打分，只给用户看）：
   - 10 条中文陪伴聊天提示；
   - 5 条「给定引用材料、按规则回答」的圆桌式提示，规则用知衍契约 `answer/` 里的格式；契约还没出的话，用知衍 `core/persona_chat.rs` 的现有提示。

   4B、9B 各跑一遍，原样保存输出。
6. **SQLite**，每项都记录 `select sqlite_version()`、FTS5 和 trigram 是否可用、打开 50MB 库的耗时：
   - a) 系统自带；
   - b) `androidx.sqlite:sqlite-bundled`（BundledSQLiteDriver）；
   - c) SQLCipher for Android（`net.zetetic:sqlcipher-android`），并测加密后的读写速度。
7. **Keystore**：
   - 在 AndroidKeyStore 里生成 AES 密钥，有 StrongBox 就用 StrongBox；
   - 用它包装数据库口令；
   - 重启 App 后能解开；清除 App 数据后无法恢复（这是预期行为）。
   - 可选：绑定生物识别。
8. **写结论** `docs/spikes/P0-7-results.md`：
   - 结果表；
   - 推荐方案：伴侣默认模型、后端、数据库库、是否需要自带 SQLite；
   - 风险；
   - 原始日志路径。

## 范围

- **可以改**：探针分支上的任何代码；`docs/spikes/`。
- **不许碰**：
  - `main` 上的应用代码。探针代码不合并，只通过一个小的文档 PR 合并结论文件。
  - 第 0 阶段的工作区。
  - 仓库里的模型文件和密钥。

## 验收标准

- [ ] 所有数字都来自红魔真机，不能用模拟器；每个数字能对应到日志。
- [ ] 4B、9B 在 CPU 后端都有完整指标；OpenCL 能跑就给指标，不能跑就写明原因。
- [ ] 三种 SQLite 方案都有版本号和 FTS5/trigram 的结论。
- [ ] Keystore 流程跑通，或者写明失败原因。
- [ ] 结论文件给出明确推荐，并说明理由。

## 必交证据

- `docs/reviews/<日期>-P0-7/DELIVERY.md`
- 结论文件
- 原始日志
- 设备信息截图
- llama.cpp 提交号
- 模型文件的 SHA256

## 风险与注意

- 这是探针，速度优先、代码可以粗糙，但**数据必须真实**。
- 结果不理想，比如 9B 被杀、发热严重，也是有效结论，照实写。
