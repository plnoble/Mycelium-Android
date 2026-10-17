# P1-6 复审报告（第 2 轮，Claude 统筹代审）

- 复审对象：`wp/P1-6-roundtable-skeleton` @ `cae82de`，对照上一轮 `456156d`（记录提交 `0136a14`、`8302760`）。查 `REVIEW-r1.md` 的必须修改 1（真机证据），以及这轮新增的一处代码改动。
- 审核者：统筹（Claude）代审。额度：不适用。
- 结论：**通过**。已由统筹合并进本地 `main`（`5496949`），没有推送，没有发布。

## 我重跑的验证

| 命令 / 检查 | 结果 | 与交付报告是否一致 |
|---|---|---|
| 全新 worktree（`git worktree add --detach` 到 `cae82de`）：`gradlew.bat :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline` | 已通过，BUILD SUCCESSFUL | 一致 |
| `:contract:test` / `:app:testDebugUnitTest` | 已通过：14 个 / 27 个，0 失败 | 一致 |
| `:app:lintDebug` | 已通过：0 error、5 warning | 一致 |
| 真机：把上面这次构建出的调试包和测试包用 `adb install -r` 装到红魔（Android 16，只连了这一台设备），再运行 `am instrument -w -r com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner` | 已通过：`DeviceRetrievalTest` 2 个测试，`OK (2 tests)` | 一致 |
| 正式版前后对照（`dumpsys package`，只读） | 测试前后都是 0.1.1（versionCode 2），最后更新时间都是 2026-10-10 05:23:04；手机上装的知衍相关包前后一样 | 一致 |
| 13 张走查截图逐张查看 | 7 步齐全，内容和交付记录一致，见下 | 一致 |

- 这台手机的 logcat 里查不到应用的测试日志（厂商限制了日志输出），所以测试结果以 `am instrument` 的逐条输出为准：2 个测试都先报开始，再报通过。
- 测试跑完，调试包和测试包留在手机上，和测试前一样，没有卸载。

## 这轮新增的代码改动

- `SqlCipherPackDatabase.kt`：`rawQuery(sql, *arguments.toTypedArray())`。SQLCipher 的 `rawQuery` 参数是 Java 可变参数；Kotlin 传数组时不加 `*`，整个数组会被当成第 1 个参数。`456156d` 就是这样，所以真机检索报数据类型不匹配。电脑上的 JVM 测试走 sqlite-jdbc，覆盖不到这条路径。改法正确。
- 我检查过 `app/src/main` 里的其他调用，没有别处再把数组传给可变参数。
- 新增的真机测试 `boundQueriesReachTheSamplePack`，覆盖主题列表、全部观点、按主题筛选、观点详情（依据和关系都非空）和搜索命中回查观点。界面上所有带参数的查询都在里面。

## 上一轮必须修改 1 逐项

| 项 | 结果 | 证据 |
|---|---|---|
| SQLCipher 跑完检索向量 | 通过 | 7 条向量的策略和命中顺序逐条一致（真机测试，我重跑过） |
| 走查和截图 | 通过 | 13 张截图存放在 Windows 仓库 `.omx/development/reviews/2026-10-17-android-P1-6/`。状态栏都裁掉了，没有通知，也没有本应用以外的内容。走查 7 步：空态 → 系统选择器 → 「已导入陆川」 → 人物页的主题、观点两个分页 → 观点详情（所属主题、原文依据、来源和日期、观点关系） → 搜「记账」（二字提示，主题命中显示「无来源日期」）和「不做清单」（长查询提示，带片段和 2024-05-18） → 「已经导入过」 → 伴侣页说明 → 「我的」0.2.0（3） → 删除确认 → 删除后列表为空 |
| 调试版和正式版同时安装，正式版版本号不变 | 通过 | 见上表 |

- 第 2 张截图是系统文件选择器，严格说当时在前台的不是知衍。但画面里只有专门放样例包的 `p16-walkthrough` 文件夹和那一个文件，没有其他文件和隐私内容，可以接受。以后的任务书里，截图规则可以写明这种例外。

## 范围检查

- 这轮只改了 `SqlCipherPackDatabase.kt` 一行、真机测试、`DELIVERY.md`、`docs/tracks/A-圆桌.md`，并把 `REVIEW-r1.md` 入档。没有越界，截图没有进安卓仓库。

## 必须修改

- 无。

## 建议修改（不阻塞，可以放进后续工作包）

1. `REVIEW-r1.md` 的三条建议仍然有效：删除失败时返回值误报成功；URI 读取阶段先限制整包大小；导入时的读取异常转成 `ImportOutcome.Rejected`，并在 `finally` 里恢复 `busy`。
2. 搜索卡片：
   - 标题命中时，片段和标题一样，同一行字会显示两遍（截图 07）；
   - 长查询片段里的 `[`、`]` 是 FTS 的高亮标记，现在原样显示成「[不做清单]」（截图 08），可以改成真正的高亮。
3. 「我的」页删除最后一个角色包后，已导入列表只剩一个标题（截图 13）。可以加一句空态说明，和圆桌首页一致。

## 无法验证的项目及原因

- CI：分支没推送。
- 滚动流畅度：截图看不出来，交付记录里也没有量化数据；样例包只有 11 条观点，暂时不影响。
