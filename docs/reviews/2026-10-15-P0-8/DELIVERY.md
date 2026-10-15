# P0-8 契约 v0 消费方 · 交付报告

- 分支：`wp/P0-8-contract-consumer`，基准 main `f1d0182`（0.1.1）。
- 契约来源：知衍 Windows 仓库 `4ff64a4e2f51320a389b888a76da32a3e10acbff`，契约版本 `0.1.0`，记录在 `contract/UPSTREAM.md`。

## 做了什么

1. `scripts/sync-contract.ps1`（以及等价的 `scripts/sync-contract.sh`）按字节复制知衍 `contract/`，按 `checksums.json` 校验 28 个文件，再写 `contract/UPSTREAM.md`。`UPSTREAM.md` 是安卓侧记录，不在校验清单里，脚本不覆盖它以外的任何内容。
2. 新建纯 Kotlin/JVM 模块 `:contract`（源码在 `contract-lib/`，`contract/` 目录留给契约原件）：
   - 读 `.mpack`：条目必须且只能是 `manifest.json`、`persona.sqlite`，顺序固定；按 schema 校验 manifest；按 SPEC §3 重算 `logical_sha256`。
   - 检索：按 `retrieval/SPEC.md` 实现短查询、trigram 主检索和二字词回退。数据库访问是 `PackDatabase` 接口，JVM 测试用 sqlite-jdbc。
   - 回答协议：渲染系统提示，按 `VALIDATION.md` 校验并渲染。不做修复式解析。
3. 测试进 CI：`.github/workflows/android-ci.yml` 增加 `:contract:test`，并上传该模块的测试报告。

## 验证（电脑上）

| 项 | 结果 |
|---|---|
| `scripts/sync-contract.ps1` | 已通过：校验 28 个文件，来源提交 `4ff64a4`，契约 `0.1.0`。连跑两次，第二次无差异 |
| `:contract:test` | 已通过：7 个测试、0 失败。覆盖校验和、manifest 与逻辑哈希、7 条检索向量、13 条回答向量、架构约束 |
| `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | 已通过：19 个单测、0 失败；lint 0 error。6 条 warning 都是改动前就有的（Gradle/依赖版本、备份属性、缺图标、`UseKtx`），本包未改这些文件 |
| 真机 | 未验证。任务书里真机检索是可选项，留到 P1-6 作为必做项 |

报告：`contract-lib/build/reports/tests/test/index.html`、`app/build/reports/tests/testDebugUnitTest/`、`app/build/reports/lint-results-debug.txt`。

## 范围

- 改了：`contract/`（只由同步脚本写入）、`scripts/sync-contract.*`、`scripts/record-contract-upstream.py`、`contract-lib/`、`settings.gradle.kts`、CI 工作流、`.gitattributes`（把 `*.sqlite`、`*.mpack` 标为二进制，避免行尾规则改字节）。
- 没改：`:app` 源码、更新逻辑、版本号。

## 备注

- manifest 校验按契约 `manifest.schema.json` 的字段、常量、类型和格式执行，没有引入 Jackson。任务书推荐的 `json-schema-validator` 会带进整套 Jackson，本机 Maven Central 握手失败，所以没有用它。
- 构建用 Android Studio 自带 JBR 25，字节码目标 17，与 `:app` 一致。本机没有单独的 JDK 17。
- Codex 审核未完成。2026-10-15 用 `codex-cli 0.162.0-alpha.17.2` 试了两次（15:16、15:47），都停在服务重连，没有写出 `REVIEW.md`。`codex doctor` 的结论是 ChatGPT 推理地址请求超时。开审前 5 小时窗口额度 26%，周额度 4%，未到 70% 停审线。
- 统筹代审结论是需修改，见同目录 `REVIEW.md`。五条已返工：长查询按 Unicode 空白切词（与 Rust `split_whitespace` 一致，U+00A0 仍不切）；二字词按码点切，切段用 Alphabetic 或 Nd/Nl/No；回答解析要求吃完输入、拒绝单引号和不加引号的值，字段类型不对记 `invalid_json`；manifest 类型错误只记校验错误并以 `PackRejected` 抛出，`const` 同时比类型和值；反例测试覆盖缺字段、多余字段、数字 `0.1`、坏时间、坏计数、不兼容版本、条目顺序、多条目、改过的哈希，以及全角空格查询和扩展区汉字。尾随逗号仍接受，与桌面端修复行为一致，不是契约。
- 返工后电脑上重跑：`:contract:test` 已通过；`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 已通过。真机仍是未验证。
