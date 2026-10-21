# P2-2b 开工前方案审：审核要求（交独立审核者）

- 审核对象：安卓仓库主工作区（只读），分支 `wp/P2-2b-release-ready`，当前 HEAD `2d531fb`（第 1/3/4/5 步实施简报），工作区干净。
- 性质：**开工前方案审**。第 1/3/4/5 步的源码一行都还没写；第 2/6/7/8/9 步**已经提交**。所以这一轮既审方案，也审已经落地的五步。
- 任务书：`docs/work-packages/P2-2b-release-ready.md`（九步 + 验收标准 + 范围 + 风险 + 审核）。
- 审核日期：2026-10-19（Asia/Shanghai）。

## 0. 约束（只读沙箱）

- **不改任何文件**：不编辑、不新建、不删除、不 `git add` / `commit` / `rebase` / `stash` / `checkout` / `clean`。
- **不联网**：不下载、不 `git fetch` / `pull` / `push`、不访问 GitHub、不用真实 API key。
- **不跑 Gradle**（会写构建产物、会拉依赖）；**不用手机/设备**；**不打标签、不推送、不发版**。
- 报告直接作为最后一条消息给出，中文。
- 只读命令可以跑：`git log/show/diff/status/grep`、读文件、以及第 3 节里列出的脚本。

## 1. 要审什么

### 1.1 本轮的三份文档（主角）

| 文件 | 内容 | 行数 |
|---|---|---|
| `docs/reviews/2026-10-18-P2-2b/PLAN.md` | 九步方案 + 两项开工前实测（跳转链实测、v0.1.1 签名指纹实测）+ 全局验收表 + 7 条开放问题 + 「不确定/本轮做不到」 | 503 |
| `docs/reviews/2026-10-18-P2-2b/SURVEY.md` | 第 1/3/4/5 步的现状勘察（要改的文件、接口形状、测例清单、验收命令、先决问题）+ 11 条开放问题 | 556 |
| `docs/reviews/2026-10-18-P2-2b/IMPL-BRIEFS.md` | 第 1/3/4/5 步的实施简报（照做级施工单）+ 「先决决定」Q1–Q11 + 实施顺序与互踩文件分析 | 899 |

### 1.2 已经提交的部分（提交号**已按仓库核对**，以此为准）

| 步骤 | 真实提交 | 内容 |
|---|---|---|
| 第 9 步 | `3153f7a` | `scripts/check-public.ps1`（621 行）+ `.gitignore` 加 `.local/`（4 行） |
| 第 6 步 | `fd5ad3c` | `scripts/release-guard.sh`（270 行）+ `.github/workflows/release.yml`（+110/−14）+ 新建 `docs/releases/signing-cert-sha256.txt` |
| 第 7 步 | `6df413e` | `app/build.gradle.kts`：release 只留 `arm64-v8a`；随后 `f47c706` 关掉 `ChromeOsAbiSupport` lint |
| 第 2 步 | `e8ade02` | 按名找资产：`GitHubReleaseClient` / `ReleaseInfo` / 新增 `UpdateError.kt` / `UpdateManager` / `MyceliumApp` / `strings.xml` / 测试（+419/−82） |
| 第 8 步 | `caeab85` | `versionCode 5` + `versionName "0.4.0"`、新建 `docs/releases/v0.4.0.md`、新建 `docs/RELEASING.md`、改写 `docs/UPDATE_ARCHITECTURE.md` |

- 本轮之前的方案文档：`3a0371b`（PLAN.md）、`db09cf2`（SURVEY.md）、`2d531fb`（IMPL-BRIEFS.md）。
- **注意**：口口相传的两个提交号 `ba1488e`（第 6 步）与 `3782dad`（第 2 步）在本分支上**不是** HEAD 的祖先（`git merge-base --is-ancestor <sha> HEAD` 退出码为 1，是 rebase 前的旧对象）。引用时用上表的 `fd5ad3c` / `e8ade02`，不要用旧号。
- 已提交的第 2/6/7/8/9 步**没有交付报告**（`docs/reviews/2026-10-18-P2-2b/DELIVERY.md` 尚不存在），所以「方案说怎么测」与「实际测出什么」之间目前只有代码本身可查。

### 1.3 要对照读的既有代码与文档

- `net/src/main/kotlin/com/xavierxia/mycelium/net/`：`Gateway.kt`（`send()`、`AllowedHosts`、`GatewayCall`、`GatewayPurpose`）、`AuditLog.kt`、`LocalHttp.kt`（**在 main 源码集里**，`:roundtable` 测试与 app 的 androidTest 都在用）。
- `app/src/main/java/com/xavierxia/mycelium/update/`：`ReleaseInfo.kt`、`UpdateError.kt`、`GitHubReleaseClient.kt`、`UpdateManager.kt`。
- `app/src/main/java/com/xavierxia/mycelium/ui/MyceliumApp.kt`（915 行，`MineScreen` / `UpdateText` / 隐私页）、`MainActivity.kt`、`roundtable/ChatService.kt`、`net/AndroidGateway.kt`。
- `app/src/test/java/com/xavierxia/mycelium/` 里的 `GitHubReleaseClientTest.kt`、`UpdateManagerTest.kt`、`MyceliumAppTest.kt`、`MainActivityTest.kt`。
- `docs/UPDATE_ARCHITECTURE.md`（第 8 步已按「实际做法」改写）、`docs/RELEASING.md`、`docs/releases/v0.4.0.md`、`docs/releases/signing-cert-sha256.txt`、`app/build.gradle.kts`、`scripts/release-guard.sh`、`.github/workflows/release.yml`、`scripts/check-public.ps1`、`.gitignore`。

## 2. 重点核对

### 2.1 任务书九步的验收标准，方案是否逐条覆盖

逐条对照任务书的「步骤」与「验收标准」（第 44–121、141–177 行），给出覆盖结论。特别看这些容易被「方案写了、验收漏了」的项：

1. 步骤 1：跳转链实测（有，PLAN §1.1）；「跳转次数太多」「续传时重新走跳转」「审计有每一跳、没有正文」是否有测例（有，但请注意 SURVEY 补了 416、并发重入、`Accept-Encoding: identity` 等 PLAN 没写的测例）。
2. 步骤 2：按名找资产、缺 APK、缺 `.sha256`、限流（403/429）、404、格式不对，六类是否都有测例与中文文案。
3. 步骤 3：24 小时节流、忽略、开关、调试包不自动查、失败不打扰、离开页面下载继续、App 被杀下次续传。
4. 步骤 4：空间检查（说清差多少）、进度、取消、四项校验、失败删文件、清理（含「当天的 `.part` 不动」）。
5. 步骤 5：`PackageInstaller` 会话、系统确认界面、未知来源「只带路不代开」、成功/失败/取消都有结果、调试包无安装入口。
6. 步骤 6：`release.yml` + `release-guard.sh`（发布说明、versionCode、签名指纹、`workflow_dispatch` 试跑不发布不上传）。
7. 步骤 7：正式包只含 `arm64-v8a`，交付报告写改前/改后大小，调试包与测试包不变。
8. 步骤 8：`docs/releases/v0.4.0.md`（中文、给用户看）、`docs/RELEASING.md`、`UPDATE_ARCHITECTURE.md` 改成实际做法、版本号 0.4.0/5。
9. 步骤 9：扫描范围（HEAD 被跟踪文件 + `origin/main..HEAD` 每个提交）、一定失败的类别、本地词表、只列不失败、自测。

**已知两处可能没被覆盖，请重点判定**：

- **「升级保数据」的兜底**：任务书第 170 行要求「如果 `v0.1.1` 用现在的工具链编不出来，写明原因，改用能编出来的最早提交，并说明它和 0.1.1 的数据结构是否一样」。PLAN 第 483 行只写了怎么做这个测试，**没有写编不出来时的兜底**，交付物清单里也没有这一项。
- **设备测「校验失败时截图提示」**：任务书第 164 行要求单独截一张校验失败的提示；PLAN 第 326 行只写到「校验通过 → 拉起系统确认界面 → 截图到此为止」，没有校验失败那一张。
- 另外：任务书第 197 行说「额度够的话加一轮专项审（网关重定向白名单、下载校验和安装来源）」——方案没有交代这一轮专项审做不做、谁做。

### 2.2 方案里已知的自相矛盾，处理得够不够

这几条是**方案自己写明或已被前序文档点名**的不一致，请逐条给结论（每条都要落到「按哪个口径实施 + 谁在哪个提交里把文档改齐」）：

1. **`.sha256` 的取法**：PLAN 第 266 行写「`api.github.com` 下载 `.sha256`（86 B 文本，同一个网关、`Update` 用途）」——这是错的：资产的 `browser_download_url` 在 `github.com/.../releases/download/...`，会 302 到 `release-assets.githubusercontent.com`；而网关现在的 `send()` 不跟跳转（PLAN §1 也承认），只会拿到 302 空体。SURVEY §1.4.7 与 §4.2 纠正了它，并为此新增了 `readFollowingRedirects(..., limitBytes)`。**PLAN §1 的接口清单里根本没有这个函数**——也就是说按 PLAN §1 实施，第 4 步必然撞墙。请判：PLAN 是否必须回改、IMPL-BRIEFS 的取法（`limitBytes = 4 * 1024`、审计独立一条）是否够，以及已提交的 `GitHubReleaseClient`（`sha256Url` 取 `browser_download_url`）是否与之一致。
2. **416 的两种说法**：PLAN §1 步骤 5 同一条列表里既写「收到 **416** → 截断重下」，又写「`Content-Range: bytes */N` 且 `offset == N`（文件已完整）→ 直接返回，不再写」。IMPL-BRIEFS Q2 给的顺序是「先判 `*/N` + `offset == N` → 以 416 返回、交给 sha256 决定留删；其余 416 → 截断重下」。请判这个顺序与判据是否唯一、可测（PLAN 第 1 步测例里**没有** 416 用例，SURVEY 测例 8 才补上）。
3. **四项校验的顺序**：任务书第 74–77 行编号是 sha256 → 包名 → **签名** → versionCode；PLAN §4 与已提交的 `docs/UPDATE_ARCHITECTURE.md` §4 也是这个顺序；SURVEY §4.2 与 IMPL-BRIEFS 改成 sha256 → 包名 → **versionCode** → 签名，理由是「读签名最贵、最容易在个别 ROM 上失败」。**IMPL-BRIEFS 没有说明这是有意偏离任务书的编号顺序**。请判：改顺序可接受吗？要不要在交付报告里写明偏离？（顺序不同，同一个坏包给出的文案不同。）
4. **重试放在哪一层**：PLAN §4（第 273 行）把「网络类失败重试 3 次、退避 2s/5s/10s」放在 app 的第 4 步编排里，而 `FileDownloader` 只做「校验不符 → 删 → 从 0 重下」；SURVEY §1.1 把网络重试放进 `:net` 的 `FileDownloader`（构造参数 `pause: (Long) -> Unit`、`attempts: Int`）；IMPL-BRIEFS Q6 选 SURVEY。这直接改第 1 步的 API 形状与测例归属（SURVEY 测例 17/18、IMPL-BRIEFS 测例 11/12）。请判：会不会出现「一处退避、一处不退避、取消按钮按不动」的裂缝；`FileDownloader` 若同时管重试和校验重下，接口是否还足够简单让 P2-5 复用。
5. **PendingIntent 三处不一致**：`docs/UPDATE_ARCHITECTURE.md` §5 第 69 行（**已提交**，第 8 步写的，且这一页自称「实际怎么做」）＝PLAN §5 第 304 行：「显式 `PendingIntent` + 动态注册的非导出接收器」（PLAN 还指定 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`）；SURVEY §5.2 建议：「显式 Intent + 清单里 `exported="false"` 的接收器 + `FLAG_MUTABLE`」，理由是部分版本 `FLAG_IMMUTABLE` 拿不到系统填的 `EXTRA_STATUS` / `EXTRA_INTENT`，并明说「现有文档与这个选择直接冲突，方案审要一次定死」；IMPL-BRIEFS Q8 选 SURVEY 并要求同步改 `UPDATE_ARCHITECTURE.md` 第 5 节。请判：定哪条、为什么，以及「第 8 步已经把这个未实现的选择写成定论」这件事本身算不算缺陷（第 5 步做的时候必须回头改文档，交付报告要能对上）。
6. **其余小口径**（各自至少给一句结论）：`DownloadResult` 的 `startedAt`（PLAN §1 有、SURVEY §1.1 没有，IMPL-BRIEFS 建议不加）；`OutboundGateway` 构造参数由 `AllowedHosts` 放宽成 `HostPolicy` 接口（改一处类型、调用点零改动，IMPL-BRIEFS 说已核对全部构造点用命名参数）；`AuditEntry` 新增 `hostPath`/`receivedBytes` 后隐私页要不要显示（IMPL-BRIEFS Q11 建议显示）；`LocalHttp` 的 reason phrase 只覆盖测例用到的状态码。

### 2.3 SURVEY 的 11 条开放问题与 IMPL-BRIEFS 的「先决决定」

逐条给「可接受 / 不可接受 / 需要改」。SURVEY §7 的 11 条是：①被白名单拒掉的那一跳要不要进审计；②416；③停滞检测（120 s 无新字节）；④续传要不要发 `If-Range: <ETag>`；⑤`MainActivityTest` 会被真的自动检查打到，注入工厂怎么设计；⑥sha256 边下边算还是下完再算 + 重试放哪层；⑦空间余量固定 32 MiB 还是 `max(32 MiB, apkSize/4)`；⑧PendingIntent 两条路线；⑨`commit` 后会话悬挂要不要 `abandonSession` 兜底；⑩失败关闭时要不要给「打开发布页」的次要入口、`update_open_apk` 怎么办；⑪隐私页要不要显示 `hostPath`/`receivedBytes`。IMPL-BRIEFS 的 Q1–Q11 一一对应，另有 3 条补充（`startedAt`、`LocalHttp` 状态行、`.sha256` 取法/四项顺序/签名只比当前 App）。

请特别判：

- Q7 与已提交文档冲突：`docs/UPDATE_ARCHITECTURE.md` 第 56 行已经把「安装包大小 + 32 MiB 余量」写成定论，而 SURVEY Q7 / IMPL-BRIEFS Q7 建议改成 `max(32 MiB, apkSize/4)` 且 `apkSizeBytes == null` 时用 64 MiB 下限。文档要不要跟着改、谁改。
- Q10 与 `strings.xml`：`update_open_apk` 现在还活着（`MyceliumApp.kt` 第 462 行在用，`strings.xml` 第 7 行）；删它或保留兜底入口两条路都要过 `lintRelease`（`UnusedResources` = 警告 = check-all 失败），方案给的取舍是否够。
- Q4 / Q3：不做 `If-Range`、不做停滞检测，把正确性全押在 sha256 上，是否可接受（同 tag 资产被重新上传的场景）。
- Q9：`abandonSession` 的阈值（10 分钟）是不是拍脑袋，有没有更可验证的判据。

### 2.4 第 1/3/4/5 步的实施顺序与文件冲突分析是否成立

IMPL-BRIEFS §6 主张：`1 → 3 → 4 → 5` 串行，每步一个提交；只有第 1 步与第 3 步理论上可并行（要先把三样签名冻结），第 4 步与第 5 步**不要**并行；§6.3 给了互踩文件表。请核：

1. 「第 1 步独占 `:net`、第 4/5 步都在 `app/`」是否真的文件级不重叠；`LocalHttp.kt` 在 **main** 源码集、被 `:roundtable` 测试共用这一点，是否足以支撑「第 1 步必须单独提交 + 单独跑 `:roundtable:test`」。
2. 「先 3 再 4」的理由（第 3 步定义 `UpdateCenter`/`UpdateUiState`/`DownloadUiState`/新注入签名与全部文案键）。**但** `UpdateCenter` 的构造形参里有第 4 步的 `ApkDownloader` 与第 5 步的 `InstallSessionGateway`，IMPL-BRIEFS 让第 3 步「先收成可空/占位并留 TODO」——请判这是不是让第 3 步的测例（≥7 条涉及卡片与状态）在残缺状态机上做假断言；更好的做法是不是第 3 步就先把这三个**接口**定死（接口是纯 Kotlin，不依赖实现）。
3. 顺序分析**漏掉的事实**：第 6/7/8/9 步已经提交；第 8 步已经把 `UPDATE_ARCHITECTURE.md` 写成「实际做法」的定论（与第 5 步的选择冲突）；第 8 步已经把版本号改成 0.4.0 而第 3/4/5 步还没做。请判这些要不要进「先决决定」，以及第 5 步是否必须与文档改动**同一提交**。
4. 「第 1 步与第 3 步并行收益有限、建议串行」这个自我否定是否成立（共享一个 Gradle 构建、单 worktree）。
5. 第 1–5 步的真机走查合成一次（同一台机、同一个调试包、同一次 `adb reverse`）是否可行；「四步的 JVM 全部绿了再上手机」的顺序有没有漏洞（第 7 步的 arm64-only 会让正式包装不进模拟器，走查用的是调试包，要确认不冲突）。

### 2.5 已提交的五步有没有明显缺陷

下面每条都请**独立复核**（含我给的事实；我核对过的，也要自己再验一遍，不要只抄结论）：

**（a）第 9 步 `scripts/check-public.ps1` 的判别力**

- **已核事实**：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-public.ps1` 在本分支上**退出码 1**（约 11 秒），报 **14 条 `[user-path]` 阻断**，全部来自历史 diff：`6ad3899`、`0f3dcff`、`01a5ff0`、`e0d3f7f`、`e94599b`、`0e18b45`、`6daf725`；而**当前被跟踪文件**在该类别下是 0 条。其中 `6ad3899` 就是那个「清掉文档里的本机用户名」的提交——它的**被删行**里带着那个用户名，所以「先提交清理提交、再跑脚本」永远过不了。**PLAN §9「怎么测」第 2 条预测「应落在『只列出』类别，不失败」，与实测不符**；PLAN §9 的风险只说了「已经公开的历史收不回来」，没说**未推送**的历史怎么修。请判：这是设计内（任务书要求「只在历史里出现过也算」）还是缺陷；补救是不是必须重写这些未推送的提交；交付报告要不要把这条 14 条与退出码写进去。
- 判别力其它要点：`-SelfTest` 只覆盖 `-ScanDirectory` 模式，**历史扫描这条路径（base 解析 `origin/main`→`main`、`git show --unified=0` 的解析、`@@` 行号推进、按 diff 头判敏感文件名）一条测例都没有**；`localpath` 只列不失败（任务书要求）；含 NUL 的文件按二进制跳过、只靠文件名兜底（用户名藏在 `.sqlite`/图片元数据里不会被发现）；`\\server\share` 这类 UNC 路径不命中；把用户名那段写成「盘符 + 正斜杠」（`D:/…` 那种写法）或 Git Bash 的 `/c/…` 形式，仍会被「正斜杠 + macOS 用户目录前缀」那一条命中（作者用 `[regex]::Matches` 在内存里验过，可复验）；**当 HEAD 已经等于 `origin/main`（例如推送之后又跑一次）时，`origin/main..HEAD` 为空，历史扫描静默变成 0 条**，只有打印的条数能看出来。
- 脚本自身的抗自匹配（用字符类 + 长度门槛，样例在临时目录造）与「命中不打原文」是否达到任务书要求。

**（b）第 6 步的发版闸门能不能被绕过**

1. 覆盖参数（`--release-notes` / `--expected-cert-file` / `--previous-tag` / `--version-code` / `--apksigner`）在 `CI` 或 `GITHUB_ACTIONS` 下被直接拒绝——机制成立吗（工作流本身不传覆盖参数）？
2. **静默跳过**：`--previous-tag` 取不到时只 `warn` 不 `abort`（「首个发布？」），于是第 3 项 versionCode 检查**整条消失**。浅克隆、标签不在 `HEAD^` 可达集合里（例如从另一个分支发版）都会走进这条路。`fetch-depth: 0` + `git fetch --tags --force` 只是降低概率。这条默认值够不够？
3. **试跑模式**：`workflow_dispatch` 的 `RELEASE_TAG` 是从 `versionName` 推出来的，所以「闸门 1：标签与 versionName 一致」在试跑里**恒真**，没有独立证据；试跑也不能验证「Release 附件齐全」（PLAN 已承认）。
4. 发布 job 从 artifact 下载 APK 后**不重新校验指纹**（只在 build job 里比过一次）；工作流没有 `concurrency` 分组（两个标签同时推会并发发布）。
5. 指纹文件用「第一个 64 位十六进制串」取值（`grep -oE` + `head -1`）：文件里多出第二个 64 位串不会报错；`Signer #N certificate SHA-256 digest` 计数必须为 1 的判据，在带 v3 轮换 lineage 的包上是否还成立（当前正式包是 v2-only）。
6. `.github/workflows/release.yml` 的细节：`build` job `contents: read` + `publish` job `contents: write`；`publish` 只吃 artifact，不重新构建；`body_path: docs/releases/<tag>.md`；干跑只在日志/`$GITHUB_STEP_SUMMARY` 里打版本号、大小、sha256。**核对一遍顺序与权限有没有洞**（例如 `upload-artifact` 的路径用的是 `github.ref_name`，而闸门用的是 `RELEASE_TAG`——两者在试跑模式下不同名，会不会漏）。
7. `scripts/release-guard.sh` 本体：`set -uo pipefail`（没有 `-e`）、`read_version_code -` 依赖 GNU grep 的 stdin 约定、CRLF 风险、`grep -oE` 对 `versionName`/`versionCode` 的解析在注释里也含同名文本时是否选对（`app/build.gradle.kts` 第 18–19 行前后有中文注释，请核 `head -1` 取到的是不是真值）。

**（c）第 2 步的资产匹配会不会选错**

`GitHubReleaseClient.kt` 第 82–96 行的 `findAsset`：先精确比对期望名（`mycelium-v<版本>.apk` / 同名 + `.sha256`，忽略大小写），找不到再退化为「以期望名开头」，且仍要求 `isRightKind`（APK：`endsWith(".apk")`；校验文件：`contains(".sha256")`）。请核：

- 退化分支对校验文件用 `contains` 会不会选中 `*.sha256.asc` / `*.sha256.sig` 之类（选中后 `ChecksumText.parseFirstSha256` 会失败 → 打不开应用内安装，属于失败关闭，但文案是「校验文件异常」，与「缺校验文件」不同）；
- 会不会误选别的版本/别的命名（例如 tag 是 `v0.4.0` 而仓库里同时有 `mycelium-v0.4.0.apk` 与别的 `.apk`）；
- `apkSizeBytes` 在**不可安装**时仍然取到了值（第 68 行 `apk?.let { sizeOf(it) }`），而两个 URL 被置空——界面会不会出现「有大小、没有安装按钮」；PLAN §2 只要求 URL 置空，这算不算偏离；
- `UpdateResult.Error(message: String)` 是否已按 PLAN §2 换成 `Error(error: UpdateError)`，并且界面显示的是中文（读 `UpdateManager.kt`、`UpdateError.kt`、`MyceliumApp.kt` 的 `updateErrorText`/`UpdateText`）；
- 已提交的测试（`GitHubReleaseClientTest.kt`）是不是真的能证明「不再取第一个 `.apk`」，有没有恒真断言。

**（d）第 7 步「只带 arm64」的后果**

- 正式包在 x86/x86_64 模拟器上装不了（PLAN 明说，调试包保留全 ABI）。
- **32 位 ARM（armeabi-v7a-only）设备从此装不了正式包**；而 App 自己的四项校验都不查 ABI，所以这类设备会**先把 38 MiB 下完、四项全过**，最后在 `PackageInstaller` 拿到 `STATUS_FAILURE_INCOMPATIBLE`。任务书与 PLAN 都没把「ABI 不兼容」当成升级路径的一种结果来交代（`SURVEY` 只把它列进文案表）。已提交的 `docs/releases/v0.4.0.md` 第 35 行写着「正式包只提供 64 位 ARM…2020 年以后的手机基本都是这个架构」——请判这句与「0.1.1 用户升级」的实际覆盖面是否一致、要不要在卡片/说明里补一句。
- `lint { disable += "ChromeOsAbiSupport" }`（`f47c706`）加在**全局** lint 块，debug 构建也一起关掉了——范围是否合适。
- 「改前 52.34 MiB / 改后预计 38–39 MiB」只有读现有产物的估算，**没有构建证据**（本轮不跑 Gradle）；交付报告必须写实际值。

**（e）第 8 步及其它已提交内容**

- `versionCode 5` / `versionName "0.4.0"` 已提交：**已核**：`git grep "0\.3\.0" -- app/src` 为空，`MyceliumAppTest`/`MainActivityTest`/`UpdateManagerTest` 用的是 `BuildConfig.VERSION_NAME`，所以 PLAN §8 担心的「写死 0.3.0 会红」不成立——请复验，并检查有没有别的写死版本号的断言（含 `0.4.0` 字面量在 `GitHubReleaseClientTest` 里）。
- `docs/UPDATE_ARCHITECTURE.md` 第 4 节把「32 MiB 余量」「签名只和当前 App 比」写成定论，第 5 节把接收器选择写成定论——与 SURVEY/IMPL-BRIEFS 的建议逐条对照，列出「文档已定论但实施可能改」的差异清单。
- `docs/releases/v0.4.0.md` 是给用户看的预告：它描述的「自动检查 / 红点 / 卡片 / 下载并安装 / 四项校验」**目前一行都没实现**。请判：把这些写成已完成是否可接受，交付时要同步到什么程度。
- `docs/RELEASING.md` 与实际 `release.yml` 是否逐条对得上（干跑说明、闸门四项、覆盖参数在 CI 被拒、`check-public` 是推前门禁）。

### 2.6 范围与约束

- 已提交的五步有没有越界：任务书「不许碰」= 签名密钥本身、GitHub 仓库密钥、`:hearth`、圆桌/炉边对话逻辑、llama.cpp、`contract/`。逐提交看 `--stat`。
- 有没有把本机绝对路径、用户名、密钥、APK/GGUF、keystore 带进仓库（`git ls-files` 里查；`check-public.ps1` 的结果与这条互为证据）。
- `net/...` 的改动是否只限本包需要（`HostPolicy`、`download`、`AuditEntry` 两个字段）；`.gitignore` 的改动是否只加了 `.local/`。

## 3. 建议自己跑一遍的只读核对

不联网、不跑 Gradle、不碰设备：

```powershell
git log --oneline -14                          # 核对第 1.2 节的提交号
git show --stat 3153f7a fd5ad3c 6df413e e8ade02 caeab85 f47c706
git diff --check                               # 空白错误
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-public.ps1   # 只读，~11 秒，注意退出码与阻断条数
```

- 有 Git for Windows 的 bash 时，可以跑 `bash -n scripts/release-guard.sh`、`grep -c $'\r' scripts/release-guard.sh`；跑不了就写明没跑（不要为了跑它去装东西或联网）。
- **不要**跑 `-SelfTest`（它会在 `%TEMP%` 下建样例再删），也**不要**跑 Gradle / 设备测试 / 真机走查。
- 第 2.5(a) 节里那条「14 条阻断」请自己复跑一次确认，不要只引用本文的数字。

## 4. 产出格式（缺一项都算不合格）

1. **一句话结论**：只能是「通过」或「需修改」。
2. **逐项状态表**：九步（第 1–9 步）+ 第 2.2 节列出的自相矛盾点，逐行给「通过 / 需修改 / 不适用 + 依据（文件:行 或 提交号）」。
3. **必须修改**：可执行的具体改动，每条写清**什么输入会出什么错**、改哪个文件、改完拿什么证据算过（测例名或命令）。
4. **建议**：不阻塞开工的改进项，按「收益/代价」一句话排序。
5. **未能验证的项目**：如实列出（本轮不跑 Gradle、不联网、不用手机意味着哪些结论只是推断），并说明各自需要什么才能验证。
6. **额度读数**：报告的**最后一行**写 5 小时窗口与每周窗口的已用百分比。来源：本机 `%USERPROFILE%\.codex\sessions\` 下最新 rollout 文件里的 `rate_limits`（两个窗口各一个已用百分比；按文件实际字段名读）；取不到就写「取不到」。这一行之后不要再写任何内容。

## 5. 判定口径

- 「通过」= 九步验收都有可执行的落实路径、第 2.2 节的矛盾都已定死一个实施口径、已提交的五步没有需要回改的实质缺陷（文档口径冲突可以在实施时随提交一并改齐，但必须写清是谁在第几步改）。
- 「需修改」= 上面任一条不成立。每条必须修改都要能被实施者直接照做，不接受「建议再评估」这类空话。
- 结论只写一次（第 1 节），不要在中途改口。