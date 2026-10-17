# P1-6 复审报告（第 1 轮，Claude 统筹代审）

- 复审对象：`wp/P1-6-roundtable-skeleton` @ `456156d`（记录提交 `0136a14`），对照 Codex 首审的 `2a2548b`。只查 `REVIEW.md` 的「必须修改」和这次的新改动。
- 审核者：统筹（Claude）代审。Codex 复审重跑完测试后停在写报告之前，没有出报告。额度：不适用。
- 结论：**需修改**。代码上的必须修改 1–4 都已通过；只剩必须修改 5 的真机部分没有证据，补齐后才能合并。

## 我重跑的验证

全新 worktree（`git worktree add --detach` 到 `456156d`，没有复制本机配置），审完已删除。

| 命令 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `gradlew.bat :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline` | 已通过，BUILD SUCCESSFUL | 一致 |
| `:contract:test` | 已通过：14 个、0 失败（PersonaPack 6、RetrievalVector 3、AnswerVector 3、Architecture 1、Checksum 1） | 一致 |
| `:app:testDebugUnitTest` | 已通过：27 个、0 失败（含 PackLibrary 5、SqlArgumentList 3） | 一致 |
| `:app:lintDebug` | 已通过：0 error、5 warning | 一致 |
| `:app:assembleDebugAndroidTest` | 已通过（只证明真机测试包能编出来，不等于真机测试通过） | — |
| 损坏包批量探针（临时测试，只放在审核 worktree，没有提交）：对契约样例包做 561 种截断（末尾 400 个长度逐一截，其余每隔 997 字节截一次）和 1558 处单字节改动 | 截断：561 种全部以 `PackRejected` 拒绝。改字节：1491 处以 `PackRejected` 拒绝；67 处被接受，但取出的数据库字节和原包完全相同（改动落在 zip 头里不影响内容的字段上）。没有任何其他异常漏出 | 交付只测了少 1、少 22 字节和中部一处 |

## Codex 首审「必须修改」逐条

| 首审必改 | 结果 | 证据 |
|---|---|---|
| 1. SQLCipher 参数绑定多一个位置 | 通过（JVM） | `SqlArgumentList.put` 改成按 1 起始放进 0 起始的数组，会补空位，同一位置再赋值时覆盖。3 个单测覆盖单参数、连续参数、重复赋值。安卓 SQLCipher 路径跑检索向量要等真机，见必须修改 1 |
| 2. 删一个版本连带删掉共用的数据库 | 通过 | 还有别的版本指向同一个文件时，文件不删。新测试：两个版本共用一份库，删掉第一个以后，第二个的文件还在，能读出 11 条观点。返回值有一处小问题，见建议 1 |
| 3. 尾部截断的 `.mpack` 被接受 | 通过 | 用 `ZipFile` 读中央目录，用 `ZipInputStream` 校验条目 CRC，两边的条目名和内容必须一致；`readPersonaPack` 把读包和开库时的所有异常都转成 `PackRejected`。批量探针结果见上 |
| 4. 搜索结果缺来源日期 | 通过 | 三条检索 SQL 都 `LEFT JOIN sources` 取 `published_at`。`sources.source_id` 是主键，不会多出行；排序语句没动，7 条检索向量仍然逐条一致。主题命中没有来源，日期为空，界面显示「无来源日期」；其余命中都有日期，测试断言了这两种情况 |
| 5. 补齐必做验收证据 | **部分**：全新 worktree 这一项，我已对 `456156d` 跑完全套，结果见上表；真机部分仍然缺 | 见必须修改 1 |

## 范围检查

- 只改了 `PackLibrary.kt`、`SqlCipherPackDatabase.kt`、`MyceliumApp.kt` 搜索卡片一行、`contract-lib` 的 `PersonaPack.kt` 和 `Retrieval.kt`、对应测试，以及 `DELIVERY.md`、`docs/tracks/A-圆桌.md`，并把 Codex 的 `REVIEW.md` 入档。没有越界。
- `contract/` 没动。`SearchHit` 新增的 `sourcePublishedAt` 只给界面用，不影响契约规定的命中对象和排序。

## 必须修改

1. **补齐真机证据**（Codex 首审第 5 条的剩余部分）。
   - 现象：交付报告写明，这次连上的唯一 adb 设备不是安卓手机（armv7l Linux，没有 `pm` 和 `getprop`），P0-7 用的红魔手机没有出现在 `adb devices` 里。
   - 期望：在红魔手机上完成以下各项，结果写进 `DELIVERY.md`，截图按任务书规则放到 Windows 仓库的评审目录，交统筹看：
     - 用 SQLCipher 跑完 `retrieval/vectors.json` 的真机 instrumented test。这一步同时验证了必须修改 1 的参数绑定，因为 JVM 测试走的是 sqlite-jdbc，不经过 SQLCipher 适配器；
     - 按任务书走查并截图；
     - debug 包和正式版同时安装，正式版的版本号前后不变。
   - 连接提示：一台电脑同时连了多个 adb 设备时，用 `adb -s <序列号>` 指定红魔，不要对那台非安卓设备做任何操作。

## 建议修改

1. `PackLibrary.delete` 的返回值：文件没有别的版本共用、但删除失败时，现在会返回 `true`。条件 `target.file.exists() || remaining.none { … }` 在这种情况下为真。建议分开写：共用时要求文件还在，不共用时要求文件已删除。
2. `readZipEntries` 现在把每个条目读了两遍（`ZipFile` 一遍，`ZipInputStream` 一遍），加上 URI 读取时的整包 `readBytes()`，单条目上限 64 MiB 时峰值内存可能到几百 MiB。建议在 URI 读取阶段先限制整包大小，这也是 Codex 首审的建议 2。
3. Codex 首审建议 1（文件读取异常转成 `ImportOutcome.Rejected`、导入协程用 `finally` 恢复 `busy`）这轮没有处理，仍然有效。

## 无法验证的项目及原因

- 真机 instrumented test、文件选择器流程、走查截图、滚动流畅度、debug 包和正式版同时安装：没有可用的安卓手机，原因见必须修改 1。
- CI：分支没推送。

## 下一轮

补完必须修改 1 以后交统筹。代码没有再改动的话，下一轮只看真机证据和截图；通过后由统筹合并进本地 `main`。
