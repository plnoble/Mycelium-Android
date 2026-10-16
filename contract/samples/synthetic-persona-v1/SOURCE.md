# 合成人物样例「陆川」生成说明（SOURCE.md）

- 用途：跨端契约 v0 的可执行样例包（`manifest.json` / `persona.sqlite` / `.mpack`）。
- 作者「陆川」及其三篇正篇、一篇已停用复盘文**全部为虚构内容**，由开发 agent 在本仓库为契约样例原创撰写；与任何真人、任何真实文章无关，不含任何真实语料片段。
- 条目：3 篇活动来源（记账 / 定投 / 家庭预算）+ 1 篇已停用来源（年末复盘，用于检索 inactive 过滤向量）；11 条观点、11 条引文、3 个主题、2 条观点关系。
- 生成方式：知衍参考实现 `core/persona_pack.rs` 的 `#[ignore]` 测试 `regenerate_synthetic_sample`（`cargo test --lib --offline regenerate_synthetic_sample -- --ignored`）；生成后重跑 `python scripts/gen_contract_checksums.py`。
- 确定性：注册分配的 UUID 在种子阶段固定为 `luchuan-synthetic-v1`，时间戳全部写死；同一导出连续两次结果逐字节一致（测试 `pack_build_is_byte_deterministic_across_two_builds` 校验），逻辑哈希以 `manifest.json` 的 `logical_sha256` 为准。
- 此目录不含任何真实语料、密钥或个人信息；`includes_full_text=true` 收录的是上述虚构全文。