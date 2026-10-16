# P0-8 复审报告（第 2 轮，Claude 统筹代审）

- 复审对象：`wp/P0-8-contract-consumer` @ `e1ca624`，对照上一轮 `9cf8097`。只查 `REVIEW-r1.md` 的 2 条「必须修改」和这次的新改动。
- 审核者：统筹（Claude）代审。额度：不适用。
- 结论：**通过**。已由统筹合并进本地 `main`（`a36a5c2`），没有推送。

## 我重跑的验证

全新 worktree（`git worktree add --detach` 到 `e1ca624`，没有复制本机配置），审完已删除。

| 命令 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `gradlew.bat :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | 已通过，BUILD SUCCESSFUL | 一致 |
| `:contract:test` | 已通过：12 个、0 失败 | 一致 |
| `:app:testDebugUnitTest` | 已通过：19 个、0 失败 | 一致 |
| `:app:lintDebug` | 已通过：0 error | 一致 |
| 跨端对照：沿用上一轮的 Rust 对照程序（`rustc 1.94`，`search_terms` 原样摘自桌面端，切词用 `split_whitespace()`），和 `e1ca624` 的真实代码跑同一批 16 组输入，逐码点比较空白判定 | 空白判定在 U+0000–U+10FFFF 上两端完全一致；16 组输入的二字词和检索表达式逐字一致 | 一致 |
| 解析边角探针（临时测试，只放在审核 worktree，没有提交） | 见下 | 一致 |

## 上一轮 2 条逐条

| 上一轮必改 | 结果 | 证据 |
|---|---|---|
| 1. 长查询的空白判定对齐 Unicode White_Space | 通过 | `Retrieval.kt` 改成 `cp in 0x09..0x0D \|\| cp == 0x85 \|\| Character.isSpaceChar(cp)`。逐码点对照和 Rust `char::is_whitespace` 完全相同：切 U+00A0、U+2007、U+202F，不切 U+001C–U+001F。新测试覆盖 U+00A0 和 U+001F。注释和 `DELIVERY.md` 的说法已改正 |
| 2. 字面量和数字后面必须有分隔 | 通过 | `truefalse`、`nullnull`、`1true`、`null"x"`、`false[]` 都判 `invalid_json`；顺带前导零 `01` 也判不合格，和 `serde_json` 一致。合法输入不受影响：`null` 后面接 `}`、`\r\n`，Markdown 代码围栏，尾随逗号，各种合法数字写法，以及 CRLF 换行的 manifest，都照常通过 |

## 范围检查

- 只改了 `Retrieval.kt`、`Json.kt`、对应测试、`DELIVERY.md`、`docs/tracks/A-圆桌.md`，并把 `REVIEW-r1.md` 入档。没有越界。

## 必须修改

- 无。

## 建议修改

- 沿用 `REVIEW-r1.md` 的建议 1–4。其中第 1 条（坏 `.mpack`、坏数据库抛的不是 `PackRejected`）和上一轮的 zip 目录条目、条目大小上限，在 P1-6 做导入时一起处理。

## 无法验证的项目及原因

- CI：分支没推送。
- `scripts/sync-contract.sh`：本机是 Windows，没有跑。
- 真机：任务书里是可选项，留给 P1-6。
