# P0-8 契约 v0 消费方：复制契约 + 一致性测试

- 仓库 / 基准：安卓仓库 `main`（第 0 阶段与 P0-9 改名合并后）→ 分支 `wp/P0-8-contract-consumer`
- 依赖：
  - Windows 侧 P0-8 已产出 `contract/`（含样例包与 `checksums.json`），并通过 Codex 审核；
  - 第 0 阶段和 P0-9 已合并。本包要改 `settings.gradle.kts`。
- 预计规模：3–4 个工作日
- 背景：
  - 手机上的角色要和电脑端说话方式一致、搜索结果一致。
  - 契约以知衍为真源，定义角色包格式、检索规则、回答协议，并附带样例和期望结果。
  - 安卓版要实现同样的逻辑，并用同一批样例证明结果一致。

## 目标

建立纯 Kotlin 的 `:contract` 模块。它能读取角色包、按规则检索、校验和渲染模型回答，并在 CI 里用契约样例自动验证。阶段 1 的圆桌空间直接复用这个模块。

## 步骤

1. **复制契约**：
   - 新建 `contract/`，把知衍仓库 `contract/` 的内容原样复制过来；
   - 新建 `contract/UPSTREAM.md`，写明知衍提交号、契约版本和复制时间。
2. **同步脚本** `scripts/sync-contract.ps1`，参数是本地知衍仓库路径。脚本做三件事：复制；按 `checksums.json` 校验；更新 `UPSTREAM.md`。如果 CI 需要，也提供等价的 `.sh`。
3. **`:contract` 模块**：纯 Kotlin/JVM，不依赖 Android、不联网。内容包括：
   - **角色包读取**：解 `.mpack` 文件；用 JSON Schema 校验 manifest（推荐 `com.networknt:json-schema-validator`）；按 SPEC 重新计算 `logical_sha256` 并与 manifest 比对。
   - **检索**：按 `retrieval/SPEC.md` 实现，包括少于 3 个字的回退和相同分数时的排序规则。数据库访问做成接口：JVM 测试用 `org.xerial:sqlite-jdbc`；App 里用 P0-7 选定的 SQLite 方案。
   - **回答协议**：按 `answer/` 的模板渲染系统提示；按 `output.schema.json` 和 `VALIDATION.md` 校验模型输出并渲染（引用标记、「【推演】」、拒答）。
4. **测试**（JVM，进 CI）：
   - 复制过来的文件和 `checksums.json` 一致；
   - 样例 manifest 通过 Schema 校验，重算的 `logical_sha256` 与 manifest 相同；
   - `retrieval/vectors.json` 每个查询的结果和期望完全一致（包括顺序）；
   - `answer/vectors/*.json` 全部符合期望；
   - **架构测试**：`:contract` 不依赖 Android SDK，也不依赖任何网络库。
5. **真机测试**（可选）：P0-7 选定数据库方案之后，加一个 instrumented test，在真机上用选定的 SQLite 跑检索样例。

## 范围

- **可以改**：
  - `contract/`（只能来自同步脚本）
  - `scripts/sync-contract.*`
  - `:contract` 模块
  - `settings.gradle.kts` 里新增模块
  - CI 工作流里加入 `:contract` 测试
- **不许碰**：
  - 手工修改 `contract/` 里的任何文件（发现契约有问题，报给知衍侧改）；
  - `:app` 的界面；
  - 更新逻辑；
  - 第 0 阶段已完成的内容。

## 验收标准

- [ ] 用 Gradle Wrapper 运行 `:contract:test` 全部通过，并纳入 CI（附 CI 运行链接或本地日志）。
- [ ] 四类样例测试（校验和、manifest/哈希、检索、回答）全部通过；架构测试通过。
- [ ] `contract/UPSTREAM.md` 记录的提交号与复制内容一致：重新运行同步脚本后没有差异。
- [ ] 原有的 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 仍然通过。

## 必交证据

- `docs/reviews/<日期>-P0-8/DELIVERY.md`
- 测试报告路径
- 同步脚本运行输出
- CI 链接

## 风险与注意

- 两端对不上时，先确认是安卓实现的问题还是契约描述不清。契约的问题报给统筹（Claude），由 Windows 侧修改契约并升版本号，**不要在安卓这边改样例**。
- JVM 上用的 sqlite-jdbc 自带较新的 SQLite，能跑通 trigram，不代表手机上也能。真机结论以 P0-7 为准。
