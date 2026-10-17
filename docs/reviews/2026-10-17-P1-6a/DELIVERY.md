# P1-6a 交付报告

- 分支：`wp/P1-6a-rename-hearth`，从 `main @ 41bfd10` 拉出。没有推送，没有打标签，没有发布，没有合并。
- 版本号不变：0.2.0（versionCode 3）。改名随 0.2.0 一起发；发不发由用户决定。
- 出具日期：2026-10-17。
- 工作目录是单独的 worktree。`D:\Agent\Project\Nivra` 里 P1-7 没提交的改动没有动。
- 全新 worktree 对 `0d2d8a2` 的全套检查：已通过。见文末。下面「电脑上的结果」是在开发目录跑的，提交前。
- Codex 没有调。任务书规定改动小，直接交统筹看。

## 做了什么

- 底栏标签「伴侣」改成「炉边」，图标字「伴」改成「炉」。圆桌仍是「桌」，我的仍是「我」。
- 空间页标题改成「炉边」。说明改成「炉边在下一阶段开发：只用手机上的模型，不联网。」
- 颜色区分没变。圆桌仍是浅色 `#8D4A2B`，炉边仍是深色 `#7EB6A4`。
- 标识符：`Space.Companion` 改成 `Space.Hearth`，`companionColors` 改成 `hearthColors`，`CompanionScreen` 改成 `HearthScreen`。
- `MainActivityTest` 的文字断言跟着改。
- `app/src` 和 `contract-lib/src` 里搜「伴侣」：没有。Kotlin 的 `companion object` 是语言关键字，留在 `AnswerProtocol.kt`，没有改。

## 电脑上的结果

命令离线，使用仓库已有的镜像初始化脚本。

| 命令 | 结果 |
|---|---|
| `:contract:test` | 已通过，14 个 |
| `:app:testDebugUnitTest` | 已通过，27 个 |
| `:app:lintDebug` | 已通过，0 error |
| `:app:assembleDebug` | 已通过 |

`:contract:test` 里 sqlite-jdbc 在 JBR 25 上有 `System::load` 的限制警告，测试仍通过。`libandroidx.graphics.path.so` 和 `libsqlcipher.so` 无法 strip，按原样打进包，这是这条线原来就有的提示。

## 真机

设备是 P0-7 用的红魔（Android 16）。只安装调试包 `com.xavierxia.mycelium.debug`（界面名「知衍（调试）」），`adb install -r`。没有卸载，没有清数据，没有安装正式包。

- 界面：已通过。圆桌首页底栏是「桌 / 炉 / 我」，标签是「圆桌」「炉边」「我的」。点「炉边」后，标题是「炉边」，正文是「炉边在下一阶段开发：只用手机上的模型，不联网。」顶栏也是「炉边」。界面树上没有「伴侣」，图标字不是「伴」。
- 截图：2 张，`roundtable.png`、`hearth.png`。裁掉了顶部状态栏。放在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P1-6a/`，没有放进本仓库。没有通知，没有其他应用。
- 共存：已通过。调试包是 0.2.0（versionCode 3）。正式版 `com.xavierxia.mycelium` 安装前后都是 0.1.1（versionCode 2），最后更新时间仍是 2026-10-10 05:23:04。
- 输入法仍是原来的默认输入法。这次没有输入文字。

## 没有做

- 没有改版本号、更新逻辑、发布签名、`release.yml`、`contract/`。
- 没有改 `docs/reviews/`、`docs/tracks/` 里已有的内容和已完成的任务书。
- 没有推送，没有打标签，没有发布，没有合并。
- 没有调 Codex。
- 没有把 P1-7 并进来。P1-7 交审前，由那条线把含本包的 `main` 并进 `wp/P1-7-roundtable-chat`；`MyceliumApp.kt` 有冲突时以本包的命名为准。

## 全新 worktree

已通过。对 `0d2d8a2` 另开一个不挂分支的目录，离线重跑：

| 命令 | 结果 |
|---|---|
| `:contract:test` | 已通过 |
| `:app:assembleDebug` | 已通过 |
| `:app:testDebugUnitTest` | 已通过 |
| `:app:lintDebug` | 已通过，0 error |

0 失败。sqlite-jdbc 的 `System::load` 警告和无法 strip 的两个库，与开发目录相同。这个新目录的包没有再装到手机上。
