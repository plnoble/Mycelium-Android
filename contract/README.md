# 知衍 × 安卓 跨端契约（v0.1.0）

- 真源：**知衍（本仓库）**。安卓仓库（Mycelium-Android）只消费本目录，不修改。
- 本目录现行版本：见 `VERSION`（`0.1.0`）。
- 适用范围：角色包格式、检索规则、回答协议三件事。

## 目录

- `VERSION` —— 契约版本号；任何语义变化必须先升版本。
- `checksums.json` —— `contract/` 下所有文件的 SHA256（由 `scripts/gen_contract_checksums.py` 生成，改完文件必须重跑脚本并提交）。
- `persona-pack/` —— 角色包格式：`SPEC.md`（权威说明）、`manifest.schema.json`（draft 2020-12）、`pack-db.sql`（包内 SQLite 建表语句）。
- `retrieval/` —— 检索规则：`SPEC.md`（语言无关步骤）、`vectors.json`（样例包上的查询 → 期望命中）。
- `answer/` —— 回答协议：`system-prompt.zh.txt`（系统提示模板，变量 `{mode_rule}`、`{max_statements}`）、`mode-rules.zh.json`（三种模式的规则文字）、`output.schema.json`（模型输出 JSON Schema）、`VALIDATION.md`（校验与渲染规则）、`vectors/*.json`（模型输出样例 → 期望结果）。
- `samples/synthetic-persona-v1/` —— 合成人物样例包（虚构人物「陆川」，零真实语料；生成说明见 `SOURCE.md`），含 `manifest.json`、`persona.sqlite`；同目录 `.mpack` 是打包后的容器。

## 真源与双向测试

- 一方为真源：以上定义的变更只能发生在知衍仓库，通过 Codex 审核后合并进 main。
- 双向测试：知衍把样例（`samples/`、`retrieval/vectors.json`、`answer/vectors/`）作为横跨两端的可执行验收——两端各自运行同一向量集，结果必须一致。

## 安卓仓库同步方式

1. 把知衍 `contract/` **整个目录原样复制**到安卓仓库对应位置（例如 `contract/`）。不要挑选文件、不要改行尾或编码（UTF-8，无 BOM）。
2. 复制后执行校验：对 `contract/` 下每个文件计算 SHA256，与知衍提交的 `checksums.json` 逐项比对，全部一致才可用。
3. 记录同步来源：写明对应的知衍提交号（例如 `contract/ 来自 omnix-mycelium@4b55ea4`）。安卓仓库已有 `.gitignore` 等服务文件时以安卓侧约定为准，但上述第 1、2 条不变。
4. 契约升级：知衍升 `VERSION` 并更新 `checksums.json` 后，安卓按 1–3 重新同步；旧版本契约继续只读可用，直到安卓宣布不再支持。

## 生成与校验命令（知衍侧）

- 重生成校验和：`python scripts/gen_contract_checksums.py`
- 契约测试：`python -m unittest test_contract`（在 `scripts/` 下；校验 manifest schema、checksums 一致性）
- 样例包重新生成：`cargo test --lib --offline persona_pack::regenerate_synthetic_sample -- --ignored`（`#[ignore]` 测试，见 `core/persona_pack.rs`）
- 回答协议快照测试：`cargo test --lib --offline persona_chat`（系统提示渲染逐字节不变的快照测试在其中）