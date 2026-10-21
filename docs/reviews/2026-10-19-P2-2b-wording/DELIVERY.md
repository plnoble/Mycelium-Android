# 交付报告：界面文字统一为「人物包」（P2-2b 范围补充）

- 包：**P2-2b（发版准备）**，本次作为该包的**范围补充**，随 0.4.0 一起发，**不单独发版**
- 分支：`wp/P2-2b-persona-pack-wording`（从 `wp/P2-2b-release-ready` @ `bd375e3` 尖端拉）
- 工作树：`D:\Agent\Worktrees\Mycelium-Android\p2-2b-wording`
- 提交：`f1bb2d8`（相对 `wp/P2-2b-release-ready` 领先 1 个提交）
- 日期：2026-10-19

## 依据

- Windows 侧总计划 v1.8 第 2 条（2026-10-19 用户拍板）：导出的文件在界面上统一叫「人物包」（原「角色包」）；文件格式内部名 `mycelium-persona-pack` 和 `contract/` 都不改。
- 用户 2026-10-19：把安卓 App 里「导入角色包」等界面文字改成「人物包」，排进合适的包，不用单独发版。
- 用户裁定：排进 **P2-2b**（随 0.4.0 发）；范围＝**界面可见的文字全改**。

## 改了什么（只改用户可见文字，不动逻辑与契约）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/xavierxia/mycelium/ui/MyceliumApp.kt` | 圆桌导入按钮「导入角色包」→「导入人物包」；空态说明「在电脑版知衍的角色页导出角色包，传到手机后在这里导入。」→「在电脑版知衍的人物页导出人物包，传到手机后在这里导入。」；导入失败兜底「角色包没有通过校验」→「人物包没有通过校验」；「我的」页「已导入的角色包」→「已导入的人物包」；空态「还没有导入角色包。在圆桌页选择电脑版导出的文件。」→「还没有导入人物包。在圆桌页选择电脑版导出的文件。」 |
| `app/src/main/java/com/xavierxia/mycelium/roundtable/PackLibrary.kt` | 导入被拒兜底「角色包没有通过校验」→「人物包没有通过校验」 |
| `app/src/main/java/com/xavierxia/mycelium/roundtable/Roundtable.kt` | 「角色包太大」→「人物包太大」；「角色包超过 64 MB，没有导入」→「人物包超过 64 MB，没有导入」 |
| `app/src/test/java/com/xavierxia/mycelium/ui/MainActivityTest.kt` | 与上面两条界面文字对应的断言同步改 |
| `contract-lib/src/main/kotlin/com/xavierxia/mycelium/contract/PersonaPack.kt` | 8 处 `PackRejected` 报错文字「角色包…」→「人物包…」（统筹 2026-10-19 同意 A；只改文案，不动格式与契约） |
| `docs/releases/v0.4.0.md` | 三处用户可见的「导入的角色包」→「导入的人物包」（0.4.0 还没打标签，发布说明是草稿） |
| `docs/work-packages/P2-2b-release-ready.md` | 记一条「范围补充」，含改动清单与残留项 |

**没改**：`contract/`（`SPEC.md`、`pack-db.sql`、`manifest.schema.json`）、`.mpack` 扩展名与内部名 `mycelium-persona-pack`、包内表结构、`:contract` 的校验逻辑（只改了 8 句报错文案）、导入与检索逻辑、圆桌与炉边的对话逻辑、版本号、发布流程。

## 验证

- **JVM 测试（本包门禁）**：在全新 worktree 里跑 `scripts/check-all.ps1 -SkipLlamaPrepare` 两轮，原始输出见同目录 `check-all.txt`（最新一轮）、`check-all-exit.txt`。结果：**两轮都通过**。

  | 轮次 | 被验提交 | 内容 | 结果 |
  |---|---|---|---|
  | 第 1 轮 | `f1bb2d8` | 界面 5 处 + 运行时提示 3 处 + 断言 + 发版说明 | PASS（253 任务执行、20 up-to-date） |
  | 第 2 轮 | `4da756c` | 加改 `:contract` 8 句报错文字（统筹同意 A） | PASS（32 任务执行、241 up-to-date） |

  两轮的数字一致：

  | 项 | 结果 |
  |---|---|
  | gradle 退出码 | 0 |
  | 单元测试 | **316 个用例，0 失败、0 错误** —— `:app` 170、`:contract` 22、`:hearth` 39、`:llm` 11、`:model-api` 3、`:net` 43、`:roundtable` 28 |
  | lint | `:app` debug 0 错 0 警；release 0 错 0 警 |
  | 产物 | `app-debug.apk` 59.7–59.8 MiB（已签名）、`app-debug-androidTest.apk` 2.7 MiB（已签名）、`app-release-unsigned.apk` 38.9 MiB（未签名） |
  | 警告 | 待修警告 0；llama.cpp 约定豁免 0；Kotlin 编译错误 0 |
  | 结论 | **PASS：0 失败、0 警告、exit 0** |

  取证口径：门禁报告分别自记「commit f1bb2d8」「commit 4da756c」；两轮跑完当时 `git status --porcelain` 都只有本证据目录是未跟踪的（`?? docs/reviews/2026-10-19-P2-2b-wording/`），也就是**代码树正好是被验提交**，没有夹带未提交的代码改动。`docs/tracks/开发线.md` 的记录提交（`13dc3c6`）只动文档，不影响门禁结论；P2-2b 全量审时还会在合并后的分支上再跑一次。
- **界面断言**：`MainActivityTest` 里断言「导入人物包」与「在电脑版知衍的人物页导出人物包，传到手机后在这里导入。」，随 `:app` 的 170 个用例一起跑过（0 失败）。
- **真机测试**：**未跑**。本次只改界面文字，不涉及权限、网络、存储、安装路径或布局，不需要真机；按安卓侧规则，真机与 JVM 结果分开写，这里不拿环境结果冒充真机。
- **环境说明**：全新 worktree 里 `llm/vendor/` 不随仓库（`.gitignore` 第 43 行 `llm/vendor/`），所以：① 从主检出已准备好的那棵树复制了 169 MB 的 llama.cpp 源码进本 worktree，用 `-SkipLlamaPrepare` 跑；这份复制**不提交**、不进版本库。② `JAVA_HOME=D:\Development\Android-studio\jbr`（OpenJDK 25.0.3，见 `docs/tracks/A-圆桌.md` 记的路径）、`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`；这两个环境变量在本机 shell 里默认是空的，门禁开头会直接退出，必须在跑之前显式设置。

## 统筹裁定与收口（2026-10-19）

1. **A 同意 → 已改完**：`:contract` 里 8 处会显示给用户的报错文字也统一成「人物包」，位置是 `contract-lib/src/main/kotlin/com/xavierxia/mycelium/contract/PersonaPack.kt` 第 53、57、61、134、150、167、172、185 行的 `PackRejected`（「…不是完整的 zip…」「…读不出来…」「…必须且只能按顺序包含…」「…的目录和条目内容不一致」「…的 <条目> 超过 … 字节」）。只改文案，不动格式、契约与包内 SQL；`PersonaPackTest` 只断言消息非空、不锁文案，测试不用改。这些文字确实会显示给用户，链路已核实：`PackRejected` → `PackLibrary.importBytes` 装进 `ImportOutcome.Rejected(message)` → `MyceliumApp.kt` 第 728–736 行 `notice()` 对 `Rejected` 直接返回 `message` → 界面文本。
2. **B 同意 → 保留**：空态里的「电脑版知衍的**人物页**」保留（P0-23 之后电脑版导航就叫「人物」）。
3. **C 不用改 → 保持原样**：`app/build.gradle.kts` 第 168 行的注释、`app/src/test/java/com/xavierxia/mycelium/roundtable/PackLibraryTest.kt` 第 66 行的测试用例标签「截断的角色包」。
4. 其他文档里的「角色包」按契约/历史说明保留：`docs/MYCELIUM_INTEGRATION.md`（集成说明）、`docs/work-packages/P1-6-roundtable-skeleton.md` 与 `P1-7-roundtable-chat-cloud.md`（已合并包的历史任务书）、`contract/` 全部。发版说明 `docs/releases/v0.4.0.md` 因为是这一版要发出去的对外文字，已随本次改。

## 合并

按统筹 2026-10-19 的分工（功能分支之间的合并由开发 agent 负责，统筹只合 `main`）：由我把 `wp/P2-2b-persona-pack-wording` 合进 `wp/P2-2b-release-ready`，P2-2b 全量审时再跑一次门禁。真机截图不需要（无界面布局变化，只是文案）。