# P0-8 复审报告（第 1 轮，Claude 统筹代审）

- 复审对象：`wp/P0-8-contract-consumer` @ `9cf8097`，对照上一轮 `58efa16`。只查上一轮 5 条「必须修改」和这次的新改动。
- 审核者：统筹（Claude）代审。额度：不适用。
- 结论：**需修改**。5 条里有 3 条已改好，另外 2 条各剩一处没改完，都是小改。

## 我重跑的验证

全新 worktree（`git worktree add --detach` 到 `9cf8097`，没有复制本机配置），审完已删除。

| 命令 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `gradlew.bat :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | 已通过，BUILD SUCCESSFUL | 一致 |
| `:contract:test` | 已通过：12 个、0 失败（AnswerVector 3、Architecture 1、Checksum 1、PersonaPack 4、RetrievalVector 3） | 一致 |
| `:app:testDebugUnitTest` | 已通过：19 个、0 失败 | 一致 |
| `:app:lintDebug` | 已通过：0 error | 一致 |
| 跨端对照：本机 `rustc 1.94` 编译一个对照程序。`search_terms` 原样摘自桌面端 `core/persona_sqlite_archive.rs`，长查询切词用 `split_whitespace()`，和 `persona_pack.rs` 一样。和安卓的真实代码跑同一批 16 组输入，再逐码点（U+0000–U+10FFFF）比较空白判定和字母数字判定 | 二字词全部一致；空白判定有 7 个码点不一致 | 交付说「与 Rust 一致」，不成立，见必须修改 1 |
| 解析和容器边角探针（临时测试，只放在审核 worktree，没有提交） | 发现 1 处解析缺口 | 交付未涉及 |

## 上一轮 5 条逐条

| 上一轮必改 | 结果 | 证据 |
|---|---|---|
| 1. 长查询按 Unicode 空白切词 | **部分通过** | 全角空格 U+3000 和桌面端一致，回归测试也有。但不换行空格没对齐，见必须修改 1 |
| 2. 二字词按码点切 | 通过 | 16 组输入两端的二字词逐字一致：扩展区汉字「𠮷」、数学粗体字母、天城文、带点的 İ、希腊字母 Σ、全角拉丁字母、停用词、表情、行分隔符 |
| 3. 回答解析收紧 | **部分通过** | 单引号、不加引号的中文值、值后面的文字、四个字段类型不对、数组空位、重复键，都判 `invalid_json`。尾随逗号接受，和桌面端的修复行为一致，属于非契约行为。剩一个缺口，见必须修改 2 |
| 4. manifest 类型错误统一抛 `PackRejected` | 通过 | 计数写成字符串或 null、`pack_version` 写成数字 `0.1`、后面跟着多余内容的 manifest，都以 `PackRejected` 拒绝；`const` 同时比类型和值 |
| 5. 补反例测试 | 通过 | manifest 7 类、mpack 3 类反例，加上全角空格查询和扩展区汉字，都断言了 `PackRejected` 或 `invalid_json`。「契约版本不兼容」那条改成 `0.2.0`，是被 schema 的 pattern 拦下的，没有走到 `contractVersionCompatible`；两处都会拒绝，可以接受 |

## 范围检查

- 只改了 `:contract` 的源码和测试、`DELIVERY.md`、`docs/tracks/A-圆桌.md`，并把统筹的 `REVIEW.md` 入档。没有越界。

## 必须修改

1. **长查询的空白判定还没和桌面端对齐。**
   - 位置：`Retrieval.kt:204-206`，`isUnicodeWhitespace` 是 `Character.isWhitespace(cp) || cp == 0x85`。
   - 现象（逐码点对照实测）：
     - U+00A0、U+2007、U+202F 三个不换行空格：桌面端切开，安卓不切；
     - U+001C–U+001F 四个控制字符：安卓切开，桌面端不切。
     - 例：「记账改」和「变的是」中间夹一个 U+00A0，桌面端的检索表达式是 `"记账改" AND "变的是"`，安卓是一整个短语 `"记账改 变的是"`。
   - 依据：Rust 的 `split_whitespace()` 按 `char::is_whitespace`，也就是 Unicode 的 White_Space 属性切词，这个属性包含上面三个不换行空格，不包含 U+001C–U+001F。Java 的 `Character.isWhitespace` 正好相反：排除不换行空格，却算上这四个控制字符。代码注释和 `DELIVERY.md` 里「U+00A0 仍不切，与 Rust 一致」的说法不成立。从网页复制文字时，常会带进 U+00A0。
   - 期望：改成 `cp in 0x09..0x0D || cp == 0x85 || Character.isSpaceChar(cp)`。`isSpaceChar` 覆盖 Zs、Zl、Zp 三类，加上前两项正好等于 White_Space。同时改注释和 `DELIVERY.md` 里的说法。补回归测试：U+00A0 查询和半角空格查询结果相同，U+001F 不切开。入口的 `query.trim()` 也可以换成同一个判定（只影响首尾的 U+001C–U+001F），不强求。

2. **两个字面量或数字紧挨着时，会被拼成一个不加引号的字符串。**
   - 位置：`Json.kt:84-144`，`requireStrictJson` 里 `consumeLiteral`、`consumeJsonNumber` 返回后，没有检查下一个字符。
   - 现象（实测，全部被**接受**）：
     - `{"statements":[],"refusal_reason":truefalse}` 被当成拒答，原因是「truefalse」；
     - `"refusal_reason":nullnull` 同上；
     - `"text":1true` 渲染成 `1true [C1]`。
     - 原因：逐词检查把 `true`、`false` 当成两个合法 token 放过去，org.json 再把整段读成一个不加引号的字符串。
   - 依据：上一轮必须修改 3「不加引号的值判为不是合法 JSON」；桌面端 `serde_json` 对这些输入都报错，判 `invalid_json`。
   - 期望：字面量和数字后面只能接 JSON 空白、`,`、`]`、`}` 或输入结束，否则判不是合法 JSON。把上面三个例子加进 `wrongTypesAndTrailingTextAreInvalidJson`。

## 建议修改

1. `readPersonaPack` 遇到坏容器或坏数据库时，抛出的不是 `PackRejected`：
   - 截掉一半的 `.mpack` 抛 `java.util.zip.ZipException`；
   - `persona.sqlite` 不是数据库时抛 `org.sqlite.SQLiteException`。
   这不在上一轮必改范围内。P1-6 导入用户文件之前，要么在这里统一转成 `PackRejected`，要么在导入处兜住，建议 P1-6 开工时一起做。
2. 字母数字判定逐码点对照，有 4672 个码点不一样，全部是 Unicode 17 新收录的字符（例如 U+088F、U+0C5C、U+A7CE，以及汉字扩展 J 区 U+323B0 起）：本机 Rust 1.94 用 Unicode 17，JBR 25 用 Unicode 16，安卓侧没有多判的。手机上 `Character` 用系统自带的 Unicode 数据，Android 10 更旧，差得会更多。这些字基本不会出现在查询里，记录即可，不用改。
3. 类型检查是在逐条校验时才做的，所以拒绝类别有时和桌面端不同。例如第 1 条陈述自己写了引用标记、第 2 条类型不对时，安卓报 `self_written_citation`，桌面端报 `invalid_json`。两边都是拒绝，接受和拒绝的结论一致，向量不受影响。要完全对齐，可以先整体查完类型，再做语义校验。
4. 上一轮建议 1–7 里还没处理的，仍然有效：zip 目录条目和条目大小上限、schema 不认识的关键字、架构测试覆盖面、`UPSTREAM.md` 的日期和本机路径、`sync-contract.sh` 调用的 `python`、推送后第一次 CI。

## 无法验证的项目及原因

- CI：分支没推送。
- `scripts/sync-contract.sh`：本机是 Windows，没有跑。
- 真机：任务书里是可选项，留给 P1-6。

## 下一轮

改完上面 2 条后交统筹复审。下一轮只查这 2 条和新改动；通过后由统筹合并进本地 `main`，P1-6 从合并后的 `main` 拉分支。
