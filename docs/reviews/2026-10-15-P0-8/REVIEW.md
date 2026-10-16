# P0-8 审核报告（Claude 统筹代审）

- 审核对象：`wp/P0-8-contract-consumer` @ `58efa16`（功能提交 `4caca80`），对照 `main` @ `f1d0182`
- 审核者：统筹（Claude）代审。两次 Codex 审核因服务端点超时没有出报告，按 `REVIEW-GUIDE.md`「Codex 不可用时由 Claude 补位」处理，报告格式相同。
- 额度：不适用（没有用 Codex 额度）。
- 结论：**需修改**

## 我重跑的验证

全部在全新 worktree 上跑（`git worktree add --detach` 到 `58efa16`，没有复制任何本机配置），审完已删除。

| 命令 | 结果 | 与交付报告是否一致 |
|---|---|---|
| `gradlew.bat :contract:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | 已通过，BUILD SUCCESSFUL | 一致 |
| `:contract:test --rerun` | 已通过：7 个测试、0 失败（AnswerVector 2、Architecture 1、Checksum 1、PersonaPack 2、RetrievalVector 1） | 一致 |
| `:app:testDebugUnitTest` | 已通过：19 个、0 失败 | 一致 |
| `:app:lintDebug` | 已通过：0 error、3 warning | error 一致；warning 数交付写 6 条，不影响结论 |
| `scripts/sync-contract.ps1 -Source <知衍 main @ 4ff64a4>` | 已通过：校验 28 个文件；重跑后 `git status` 无差异 | 一致 |
| 临时探针测试（只放在审核 worktree，没有提交） | 发现 4 处跨端不一致或异常处理问题，见「必须修改」1–4 | 交付未涉及 |

## 范围检查

- `:app` 源码、更新逻辑、版本号：没动。
- `contract/`：重跑同步脚本后零差异，只多出安卓侧的 `UPSTREAM.md`，和任务书一致。
- 越界改动（轻微，已在交付报告申报，理由成立，不要求回退）：
  - `.gitattributes`：把 `*.sqlite`、`*.mpack` 标成二进制，防止行尾规则改字节。
  - `scripts/record-contract-upstream.py`：同步脚本的辅助脚本，归入 `scripts/sync-contract.*` 的范围。

## 验收逐条

| 验收项 | 结果 | 证据 |
|---|---|---|
| `:contract:test` 全部通过并纳入 CI | 本地已通过；CI 未验证 | 本地见上表；`android-ci.yml` 已加 `:contract:test` 和报告上传。分支没推送，没有 CI 运行链接 |
| 四类样例测试 + 架构测试通过 | 已通过 | 7 条检索向量、13 条回答向量按完整序列和 `error` 类别比对；逻辑哈希重算等于 `9e5e3e83…3c69`。测试本身的缺口见「必须修改」5 |
| `UPSTREAM.md` 的提交号与内容一致，重跑同步无差异 | 已通过（同一天） | 来源 `4ff64a4`、契约 `0.1.0`；换一天重跑会出差异，见「建议修改」4 |
| `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 仍通过 | 已通过 | 见上表 |

## 专项检查

- 花钱：不涉及，没有模型调用。
- 真实语料：样例是知衍侧原创的虚构人物「陆川」（见 `contract/samples/synthetic-persona-v1/SOURCE.md`），不含真实文章。
- 隐私：`:contract` 只依赖 `org.json` 和 `sqlite-jdbc`，不依赖 Android 和网络库；架构测试存在并通过（覆盖面见「建议修改」3）。
- 秘密与大文件：没有 key、token、keystore、`*.gguf`、APK。`persona.sqlite`（约 156 KB）和 `.mpack`（约 157 KB）是任务书要求原样复制的契约样例，可以提交。`UPSTREAM.md` 里有本机仓库路径，见「建议修改」4。
- 测试质量：样例比对写得严，没有恒真断言。但角色包和 manifest 只有正例，没有反例，见「必须修改」5。
- 契约：和知衍 `4ff64a4` 逐字节一致，安卓侧没有手改。
- 提示词：系统提示直接用契约模板渲染，没有改动。
- 记录与提交：`4caca80` 只做本包功能，`58efa16` 只记审核未完成，说明属实。
- 已对照知衍参考实现核过、和桌面端一致的部分：短查询 SQL、二字词回退的打分 SQL、主检索的 bm25 排序和平手规则、合并规则、逻辑哈希的表顺序、列顺序、可空列和整数列编码、回答协议的判定顺序和渲染格式。

## 必须修改

1. **长查询按空白切词只认 ASCII 空白，和桌面端不一致。**
   - 位置：`contract-lib/src/main/kotlin/com/xavierxia/mycelium/contract/Retrieval.kt:106`，`query.split(Regex("\\s+"))`。
   - 现象：Java 正则的 `\s` 只匹配 ASCII 空白。中文输入法常打出的全角空格（U+3000）和不换行空格（U+00A0）不会被切开，整串成了一个 FTS 短语。在样例包上查「记账改」和「变的是」两个词，中间隔一个空格：用半角空格时 `claim:lc-claim-record-1` 由主检索命中，relevance 约 1005.35；换成 U+3000 或 U+00A0 后，主检索 0 命中，只剩回退分 8.0。source 文档只能由主检索命中，这类查询会把它整条丢掉，排序也会跟着变。
   - 依据：`retrieval/SPEC.md` §4.1「按空白切词」；参考实现 `persona_pack.rs` 用 Rust 的 `split_whitespace()`，按 Unicode 空白切。
   - 期望：按 Unicode 空白切词，与 `split_whitespace()` 一致；补一条用 U+3000 的回归测试。

2. **二字词按 UTF-16 代码单元切，不是按 Unicode 标量。**
   - 位置：`Retrieval.kt:58-66`，`segment.toList()`、`windowed(2)`、`segment.length >= 2`。
   - 现象：`searchTerms("𠮷野家")` 在安卓得到 `野家`、`𠮷`（只有一个字）、`\uDFB7野`（半个代理对加「野」）；桌面端得到 `野家`、`𠮷野`。一个扩展区字母（如 U+1D400）在安卓会被算成 2 个字、单独成词，桌面端不会。
   - 依据：`retrieval/SPEC.md` 开头写明「字符数一律指 Unicode 标量数……UTF-16 代码单元计数不算数」；参考实现 `search_terms` 用 `chars()`。
   - 期望：按码点遍历和计数。切段的判定也顺手对齐 Rust 的 `is_alphanumeric()`，即 Alphabetic 属性，或数字类 Nd/Nl/No（Java 用 `Character.isAlphabetic(cp)` 加 `Character.getType(cp)` 判断）。现在的 `[^\p{L}\p{N}]` 在带组合记号的文字上会和桌面端切得不一样。补扩展区汉字的回归测试。

3. **回答解析对类型和非 JSON 内容太宽松，会接受桌面端拒绝的输出。**
   - 位置：`Json.kt:7-8`（`JSONTokener.nextValue()`），`Json.kt:22-26`（`asText()` 把任何值转成字符串），`AnswerProtocol.kt:98`、`:105`、`:112`。
   - 现象（探针实测，全部被安卓**接受**）：
     - `"text": 123` 渲染成 `123 [C1]`；`"text": {"a":1}` 渲染成 `{"a":1} [C1]`；
     - `"refusal_reason": 123` 或 `true` 被当成拒答原因；
     - JSON 后面跟着别的文字（如 `{…} 以上是回答`）、单引号、不加引号的值，都能解析通过。
   - 依据：`output.schema.json` 规定了各字段类型；`VALIDATION.md` §1.1「解析失败 → 拒绝（不是合法 JSON）」。参考实现按带类型的结构解析（`text: String`、`citation_ids: Vec<String>`、`refusal_reason: Option<String>`），上面这些都会判成 `invalid_json`。`Json.kt` 注释写「Strict JSON……rejected on purpose」，和实际行为不符。
   - 期望：解析要吃完整个输入（值后面只能有空白）；单引号、不加引号的值判为不是合法 JSON；`statements` 必须是对象数组，`text`、`kind` 必须是字符串，`citation_ids` 必须是字符串数组，`refusal_reason` 只能是字符串或 null，类型不对一律 `invalid_json`。尾随逗号在参考实现里会被修复，属于 §1.3 说的非契约行为，接受或拒绝都可以，但注释要写实。同一个 `parseJson` 也用来读 manifest，后面跟着多余内容的 manifest 现在也会通过，改完一起生效。

4. **manifest 校验对类型错误的处理不对。**
   - 位置：`ManifestSchema.kt:37-38`（`const`），`ManifestSchema.kt:52-53`（`minimum`）。
   - 现象：
     - `counts.sources` 写成 `"4"` 或 `null` 时，抛出 `IllegalStateException`（「不是整数」），不是 `PackRejected`。P1-6 导入用户给的包时，调用方只捕获 `PackRejected` 就会崩。
     - `const` 只比文字：`"pack_version": 0.1`（数字）能通过，JSON Schema 要求类型也相同。
   - 依据：任务书「用 JSON Schema 校验 manifest」；`readPersonaPack` 自己约定拒绝用 `PackRejected`。
   - 期望：类型不符只记校验错误，不再往下取值；`const` 同时比类型和值；所有拒绝都以 `PackRejected` 抛出。

5. **角色包和 manifest 只有正例，测不出校验器是否在工作。**
   - 位置：`contract-lib/src/test/kotlin/com/xavierxia/mycelium/contract/PersonaPackTest.kt`。
   - 现象：manifest 校验是自己写的（没有用任务书推荐的库），但测试只喂合法样例。就算 `validateManifestNode` 什么都不检查、`readPersonaPack` 不比哈希，现有测试也全部通过。
   - 依据：`REVIEW-GUIDE.md`「测试质量：新测试真的测到了需求」。
   - 期望：至少补这些反例，并断言都抛 `PackRejected`：缺必填字段、多出未定义字段、`const` 不符（含数字 `0.1`）、时间格式不对、计数类型不对或为负、条目顺序反了、多出条目、`logical_sha256` 被改、契约版本不兼容。这次探针已确认顺序反了、哈希被改、多出字段、时间格式不对这几项现在行为正确，补测试把它们锁住。必须修改 1–3 也各补回归测试。

## 建议修改

1. `PersonaPack.kt:101-108` 读 zip 时跳过了目录条目：`manifest.json`、`extra/`、`persona.sqlite` 这样的包会被接受，而 SPEC §1 写的是固定两个条目。建议目录条目也算数、直接拒绝。P1-6 开始导入用户文件前，再给单个条目加大小上限，`readBytes()` 现在不设上限。
2. `ManifestSchema.kt` 遇到不认识的 schema 关键字时会静默跳过。建议列出已支持的关键字，遇到别的就失败。这样契约以后加了 `maxLength`、`enum` 之类，测试会直接报出来。
3. `ArchitectureTest` 只扫 `java.net.http`，`java.net.URL`、`HttpURLConnection`、`Socket` 都扫不到；也没有检查实际解析出的运行时依赖。建议禁止整个 `java.net.`，再加一条按 `runtimeClasspath` 制品名检查的断言。
4. `record-contract-upstream.py:16` 的「复制时间」取运行当天的日期，隔天重跑同步脚本就会在 `UPSTREAM.md` 里出差异，和验收标准「重跑无差异」冲突。建议来源提交号没变时保留原日期，或者改记来源提交的时间。「来源仓库」现在写的是本机绝对路径，建议改成仓库名。
5. `sync-contract.sh` 调用的是 `python`，Linux/CI 上可能只有 `python3`；这个脚本在本机也没跑过。
6. `contract-lib/build.gradle.kts` 为本机连不上插件门户加了 buildscript 绕法。推送后第一次 CI 要确认它在干净环境下也能解析。
7. 缺 `kind` 或 `text` 时，安卓报 `bad_kind` / `statement_too_long`，参考实现报 `invalid_json`。现有向量没覆盖这种情况；按必须修改 3 先查类型，会自然对齐。

## 无法验证的项目及原因

- CI：分支没推送，没有 CI 运行，`:contract:test` 在 GitHub Actions 上能否通过未验证。
- `scripts/sync-contract.sh`：本机是 Windows，没有跑。
- 真机检索：任务书里是可选项，本包没做，交付报告已写明留给 P1-6。
