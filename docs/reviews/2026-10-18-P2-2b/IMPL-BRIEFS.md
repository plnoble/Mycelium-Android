# P2-2b 第 1 / 3 / 4 / 5 步实施简报（IMPL-BRIEFS）

- **用途**：把既定方案细化成「照着做」的施工单。Codex 方案审通过后，实施者按本文件逐节施工，
  不必再回到 `PLAN.md` 推断细节。
- **依据**（本文件只读整理，未改源码、未跑 Gradle、未联网、未用手机）：
  - `docs/work-packages/P2-2b-release-ready.md`（第 1/3/4/5 步的验收标准）
  - `docs/reviews/2026-10-18-P2-2b/PLAN.md`（既定方案）
  - `docs/reviews/2026-10-18-P2-2b/SURVEY.md`（现状勘察，含 11 条开放问题）
  - `docs/UPDATE_ARCHITECTURE.md`（既定做法，第 1/4/5 节的落地细节）
  - 本轮对现有代码的复核：`net/.../{Gateway,AuditLog,LocalHttp}.kt`、`net/src/test/.../*`、
    `app/.../update/{ReleaseInfo,UpdateError,GitHubReleaseClient,UpdateManager}.kt`、
    `app/.../ui/MyceliumApp.kt`、`app/.../MainActivity.kt`、`app/.../roundtable/ChatService.kt`、
    `app/.../net/AndroidGateway.kt`、`app/build.gradle.kts`、`net/build.gradle.kts`、
    `AndroidManifest.xml`、`res/values/strings.xml`、`scripts/check-all.ps1`、现有全部 app 单测。
- **名字来源**：函数/类名一律照 SURVEY 的核对结果，不凭空造。少数本文件「建议新增」的测例会显式标注。
- **每节的「先决决定」都是待方案审确认的建议**（格式「建议：…，理由：…」），不是定论；方案审拍板后按拍板结果施工。

## 0. 施工前必须知道的五条共用约定

1. **测试框架不能混**：`:net` 是纯 JVM，JUnit 5（`kotlin.test` + jupiter 6.0.3、`useJUnitPlatform()`、
   `@TempDir`、`LocalHttp`）；`:app` 是 JUnit 4 + Robolectric 4.17，Compose 用
   `androidx.compose.ui.test.junit4.v2.createComposeRule` / `createAndroidComposeRule<MainActivity>()`，
   `@Config(sdk = [35])`。别把 JUnit 5 的断言写进 app 测试。
2. **门禁口径**：`scripts/check-all.ps1` 把「lint 警告 / 构建输出 warning / Kotlin warning / Gradle 废弃提示」
   全算失败；`app` 与 `net` 的 `kotlin.compilerOptions.allWarningsAsErrors = true`，`app` 还
   `lint { warningsAsErrors = true }`。跑门禁前必须显式设 `JAVA_HOME`（Android Studio JBR）与
   `ANDROID_HOME`，否则脚本第 133–147 行直接退出。默认离线（脚本自带 `--offline`）。
3. **不新增依赖**（本机 Maven Central 连不上）：退避等待用注入的 `pause: (Long) -> Unit` 或已有的协程
   `delay`；**不要**引 `kotlinx-coroutines-test`（SURVEY 核对：Gradle 缓存里只有 1.9.0，与
   `coroutines-core` 的解析版本不一定一致）。
4. **真机**：离线时 `connectedDebugAndroidTest` 跑不了（缺 UTP 插件）。用
   `adb install -r` 装 `app-debug.apk` 与 `app-debug-androidTest.apk`，再
   `adb shell am instrument -w -r -e class <测试类全名> com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner`；
   假服务用 `adb reverse tcp:<port> tcp:<port>`。**不碰手机上装着的正式版 0.1.1。**
5. **本简报发现的、方案审需要拍板的方案内不一致**（细节见各节先决决定）：
   - `PLAN.md` §1 的 `DownloadResult` 有 `startedAt: Long`，SURVEY §1.1 没有 → 步骤 1。
   - 416 处理在 `PLAN.md` §1 同一节里自相矛盾（「416 → 截断重下」与「`bytes */N` 且 `offset == N` → 直接返回」）→ 步骤 1。
   - 「网络重试」`PLAN.md` 放在步骤 4、SURVEY 放在 `FileDownloader` → 步骤 4（影响步骤 1 的 API 形状）。
   - `.sha256` 取法 `PLAN.md` §4 写「从 `api.github.com` 下载」（错）→ 步骤 4。
   - 四项校验顺序：`PLAN.md` §4 是 sha256/包名/签名/versionCode，SURVEY §4.2 是 sha256/包名/versionCode/签名 → 步骤 4。
   - 安装结果接收：`PLAN.md` §5 与 `docs/UPDATE_ARCHITECTURE.md` 第 69 行写「显式 PendingIntent + 动态注册的非导出接收器」，
     SURVEY §5.2 建议「清单接收器 + `FLAG_MUTABLE`」→ 步骤 5，且**无论选哪条都要同步改 `UPDATE_ARCHITECTURE.md`**。

---

## 1. 步骤 1：网关通用文件下载（`:net`）

### 1.1 目标与可观察到的行为

**一句话**：在 `:net` 里加一条「边收边写文件」的下载路径，能跟跳转（**逐跳**过白名单）、能续传、
能取消、能审计，并且没有任何一条代码路径会把响应体读进内存。

做完后能观察到：

- 用真实的 `https://github.com/<owner>/<repo>/releases/download/v0.4.0/mycelium-v0.4.0.apk`
  调 `OutboundGateway.download(...)`：目标文件出现、长度等于 `Content-Length`；过程里进度回调持续被调用；
  `files/privacy/audit.json` 多一条记录，`hostPath` 是 `["github.com", "release-assets.githubusercontent.com"]`
  两个域名，而审计文本里**搜不到**包内容的任何特征串。
- 中断后用同一个请求再调一次：服务器回 206 就从已有字节接着下（请求里能看到 `Range: bytes=<offset>-`）；
  服务器回 200 就从 0 重写，`DownloadResult.restartedFromZero == true`。
- `GatewayCall.cancel()` 之后：抛 `GatewayCancelled`，**半截文件留在磁盘上**（续传的前提），
  且取消发生在开连接之前时假服务器计数为 0。
- 跳转跳到白名单外、超过 5 跳、或 `Update` 用途下出现 `http://`：抛 `GatewayRejected`，
  **目标文件没有被创建**，已经联系过的跳的域名进了审计。
- 现有行为逐字不变：`send()`、聊天/炉边/检查更新的出网与审计、`AllowedHosts` 对模型用途的判定。

### 1.2 改动清单（逐文件）

**新增 `net/src/main/kotlin/com/xavierxia/mycelium/net/Download.kt`**

```kotlin
data class DownloadRequest(
    val url: String,
    val purpose: GatewayPurpose,
    val target: File,                       // 调用方决定（app 传 cacheDir/updates/...）
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000,
    val resume: Boolean = true,
    val call: GatewayCall = GatewayCall(),
)

data class DownloadResult(
    val status: Int,                        // 最后一跳的状态码
    val finalUrl: String,
    val hosts: List<String>,                // 实际联系过的每一跳域名，按顺序
    val bytesWritten: Long,                 // 本次写的字节
    val fileSize: Long,                     // 结束后 target.length()
    val redirects: Int,
    val restartedFromZero: Boolean,
)

/** written/total 都是「整个文件」的口径（续传时 total 仍是完整长度）；total 未知为 null。 */
fun interface DownloadProgress { fun onProgress(written: Long, total: Long?) }
```

**新增 `net/src/main/kotlin/com/xavierxia/mycelium/net/FileDownloader.kt`**（传输之上的策略层，P2-5 直接复用）

```kotlin
sealed interface DownloadOutcome {
    data class Completed(val result: DownloadResult, val sha256: String) : DownloadOutcome
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadOutcome  // 文件已删
    data class Rejected(val message: String) : DownloadOutcome                              // 白名单拒绝
    data object Cancelled : DownloadOutcome                                                 // 保留半截文件
    data class Failed(val message: String) : DownloadOutcome                                // 网络/IO
}

class FileDownloader(
    private val gateway: OutboundGateway,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },   // 可注入，测试不真睡
    private val attempts: Int = 3,                              // 2s/5s/10s
) {
    fun download(request: DownloadRequest, expectedSha256: String? = null,
                 onProgress: DownloadProgress? = null, call: GatewayCall = request.call): DownloadOutcome
}
```

分工：网络类失败 → 退避重试（续传接着下）；`ChecksumMismatch` → 删文件 → 从 0 再下一次
（只重下一次，再不对就返回 `ChecksumMismatch`）；`Cancelled` → 立刻返回，保留已写字节。

**改 `net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt`**

```kotlin
fun interface HostPolicy { fun allows(target: URI, purpose: GatewayPurpose): Boolean }
class AllowedHosts(...) : HostPolicy { override fun allows(...) = ... }   // 方法体原样
class OutboundGateway(private val hosts: HostPolicy, ...)                 // 只换第一个参数的类型

/** 边收边写文件，逐跳过白名单，可续传、可取消；返回后调用方自己决定内容对不对。 */
fun download(request: DownloadRequest, onProgress: DownloadProgress? = null): DownloadResult

/** 跟随跳转但把响应读进内存（有上限）。只给 ≤64 KiB 的元数据用，例如 .sha256。 */
fun readFollowingRedirects(request: GatewayRequest, limitBytes: Int = 64 * 1024): GatewayResponse

private fun follow(purpose: GatewayPurpose, start: URI, call: GatewayCall): HttpURLConnection
```

- `send()` 一行不改；`instanceFollowRedirects = false` 保持不动，由 `follow()` 自己跟 301/302/303/307/308。
- 每一跳都 `hosts.allows(uri, purpose)`；3xx **不读 body**、`disconnect()`、`redirects++`；
  `redirects > 5` 抛「跳转次数太多」；`call.check()` 在每个能发字节的点之前；失败时保留已写字节。
- `AllowedHosts`：`UPDATE_HOSTS += "release-assets.githubusercontent.com"`；
  `objects.githubusercontent.com` 保留；`github-releases.githubusercontent.com` **不加**（失败关闭）。
- 已核对改动面：全仓 `AllowedHosts(...)` / `OutboundGateway(...)` 的构造点只有
  `app/.../net/AndroidGateway.kt`、`net/src/test/.../GatewayTest.kt`、
  `roundtable/src/test/.../{CloudChatTest,HearthCloudModelTest}.kt`，**全部用命名参数 `hosts = AllowedHosts(...)`**
  → 放宽成 `HostPolicy` 后**零调用点改动**。

**改 `net/src/main/kotlin/com/xavierxia/mycelium/net/AuditLog.kt`**

```kotlin
data class AuditEntry(
    ...现有九个字段...,
    val hostPath: List<String> = listOf(host),   // 顺序 = 实际联系过的跳；缺省就是单跳
    val receivedBytes: Long? = null,
)
```

`toJson()` 写这两个字段；`toEntry()` 在字段缺失时回落（`optJSONArray("hostPath")` 为空/不存在 →
`listOf(host)`，`receivedBytes` 缺失 → null）。老 `privacy/audit.json` 必须还能读。
已核对：`AuditEntry` 的构造点只有 `Gateway.kt`（2 处，命名参数）与 `AuditLogTest.entry()`（命名参数），
androidTest 只读 → **加带默认值的字段是安全的**。

**改 `net/src/main/kotlin/com/xavierxia/mycelium/net/LocalHttp.kt`**（测试假服务器；它在 **main** 源码集里，
`:roundtable` 的测试也在用它 → 改它等于改生产代码，同样受 `allWarningsAsErrors` 约束）

```kotlin
data class HttpReply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())
// 保留 HttpReply(status, body: String) 重载；serve() 不再硬编码 "200 OK"，按状态写状态行，
// 并真的写出 headers（Content-Range / Content-Length / ETag / Location）
```

`HttpCall.headers` 已经是小写 key 的 map → Range 测例在 handler 里自己实现 206/416。
已核对：现有测例只用到 `HttpReply(status, body)` 与 `HttpCall`，加带默认值的新参数 + 保留 String 重载即可不动它们。

### 1.3 测例清单

全部 `net/src/test/kotlin/com/xavierxia/mycelium/net/DownloadGatewayTest.kt`（JUnit 5 + `@TempDir` + `LocalHttp`，
**不联网**）。用 `HostPolicy { uri, _ -> uri.toString().startsWith(allowedPrefix) }` 精确构造「第 N 跳才出白名单」；
用真实 `AllowedHosts` 测生产策略。

| # | 测例名 | 断言要点 | 类型 |
|---|---|---|---|
| 1 | `downloadWritesBodyThroughOneRedirect` | 302 → 1 MiB 假二进制；文件逐字节相等、`hosts.size == 2`、`redirects == 1`、`bytesWritten == 文件长` | JVM |
| 2 | `downloadRejectsRedirectOutOfWhitelist` | 第 2 跳不在策略 → `GatewayRejected`，**目标文件没有被创建** | JVM |
| 3 | `downloadRejectsWhenTheThirdHopLeavesWhitelist` | 前两跳放行、第三跳不放行 → 拒绝，`hostPath` 只含已联系的两跳 | JVM |
| 4 | `downloadRejectsCleartextRedirectUnderTheUpdatePolicy` | 真实 `AllowedHosts`：起始 `http://` 直接拒；另一例第 2 跳是 `http://` 时拒 | JVM |
| 5 | `downloadRejectsMoreThanFiveRedirects` | 连续 302 → 拒绝；假服务器收到几次请求写进断言（含起始那次），按实现锁定后在注释里写清 | JVM |
| 6 | `downloadResumesFromExistingBytes` | 预置 300 KiB、服务器 206 + `Content-Range: bytes 307200-1048575/1048576` → 文件 1 MiB、内容正确、收到的 `Range` 恰是 `bytes=307200-` | JVM |
| 7 | `downloadRestartsFromZeroWhenServerIgnoresRange` | 服务器无视 Range 回 200 全量 → 从 0 重写、`restartedFromZero`、长度等于全量 | JVM |
| 8 | `downloadTreatsASatisfiedRangeHintAsAlreadyComplete` | 416 + `Content-Range: bytes */N` 且 `offset == N` → 不写、状态 416 | JVM |
| 9 | `downloadRewalksRedirectsOnResume` | 预置半截后服务器**换了新的跳转目标** → 跳 1 的计数 +1（证明没有复用旧地址） | JVM |
| 10 | `cancelKeepsPartialFile` | 写 200 KiB 后 `call.cancel()` → `GatewayCancelled`、文件长度在 200 KiB 附近且 ≠ 0 | JVM |
| 11 | `cancelBeforeOpenNeverConnects` | `cancel()` 后再 download → 假服务器计数 0（照现有 `aCancelledRequestNeverOpensAConnection` 的手法） | JVM |
| 12 | `downloadAuditRecordsHostPathAndBytesWithoutContent` | 恰好 1 条审计：`hostPath` 两个域名、`receivedBytes == 文件长`、`status == 200`、`sentBody == ""`，且 `audit.json` 文本里**不含**下载内容特征串 | JVM |
| 13 | `auditReadsEntriesFromBeforeTheNewFields` | 手写旧格式 JSON → `read()` 成功、`hostPath == listOf(host)`、`receivedBytes == null` | JVM |
| 14 | `allowedHostsCoversTheMeasuredChainAndRefusesTheRest` | `https://github.com/...`、`api.github.com`、`release-assets.githubusercontent.com`、`objects.githubusercontent.com` 通过；`http://github.com/...`、`https://evil.example/...`、`https://github.com.evil.example/...` 拒绝 | JVM |
| 15 | `fileDownloaderDeletesAndRedownloadsOnChecksumMismatch` | 预置被污染的续传文件 + 期望 sha256 → 删 → 从 0 下一次 → `Completed`，磁盘上只剩一个正确文件 | JVM |
| 16 | `fileDownloaderGivesUpAfterASecondMismatch` | 两次都对不上 → `ChecksumMismatch`、文件已删 | JVM |
| 17 | `fileDownloaderRetriesNetworkFailureThenResumes` | 第一次中途断 → `pause` 记录 2000 ms；第二次带 `Range` 成功；`pause` 不真睡 | JVM |
| 18 | `fileDownloaderRefusesASecondConcurrentDownloadOfTheSameTarget` | 并发重入被拒，第二次没有写文件 | JVM |
| R1 | 回归：`GatewayTest` / `AuditLogTest` 现有全部测例 | 一个都不许红（`send()` 语义与审计字段不变的证据） | JVM |
| R2 | 回归：`:roundtable` 全部测例（`LocalHttp` 改了） | 假服务器行为对旧测例不变 | JVM |
| D1 | 真实跳转链（真机侧） | 真机上下载一次，从审计里读出**实际**每一跳域名，记进交付报告；出现未实测域名 → 失败关闭 | **真机** |
| D2 | 弱网/超时下的续传与重试 | 中途断网再连，字节接着长；重试退避可观察（logcat） | **真机** |
| D3 | 真实 CDN 的 Range/ETag 行为 | 真机发出 `Range` 得到 206（或明确 200 全量），`If-Range` 是否要做以实测为准 | **真机** |

### 1.4 验收命令

先设环境（一次，每个 shell 都要）：

```powershell
$env:JAVA_HOME = "<Android Studio 自带 JBR>"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

定向（离线；单测任务名已核对：`:net:test`、`:app:testDebugUnitTest`、`:roundtable:test`）：

```powershell
.\gradlew.bat --offline --console=plain :net:test --tests "com.xavierxia.mycelium.net.DownloadGatewayTest"
.\gradlew.bat --offline --console=plain :net:test
.\gradlew.bat --offline --console=plain :roundtable:test
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest :app:lintDebug   # AuditEntry 改动跨到 app
```

门禁（本步做完，可选一次子集；四步全做完再跑全量）：

```powershell
# llm/vendor/llama.cpp 已准备好的话
.\scripts\check-all.ps1 -SkipLlamaPrepare -Tasks ":net:test",":roundtable:test",":app:testDebugUnitTest",":app:lintDebug"
# 没准备好的话（需要本地 llama.cpp 克隆）
.\scripts\check-all.ps1 -LlamaSourceRepository "<本地克隆>" -Tasks ":net:test",":roundtable:test",":app:testDebugUnitTest",":app:lintDebug"
```

必须真机的部分：D1–D3 不单独跑任务，**并入第 3/4 步的真机走查一次做完**。
走查后读审计链（调试包 debuggable，可直接 `run-as`）：

```powershell
adb shell run-as com.xavierxia.mycelium.debug cat files/privacy/audit.json
```

### 1.5 先决决定（待方案审确认）

- **Q1 被白名单拒掉的那一跳要不要写进审计？**
  建议：**不写进 `hostPath`**，但要把「已经发出去过的那几跳」在抛 `GatewayRejected` 之前落一条审计
  （`status = null`、`succeeded = false`）；被拒的那一跳因为没发出请求，不出现。理由：隐私页「出网记录」的语义是
  「实际联系过哪些域名」，把没发出去的一跳记成发生过是误导；而失败仍要留痕，现有 `send()` 的失败路径就是这么做的。
  用户想知道「有一次想跳到 X 被拒了」时，用 `GatewayRejected` 的 message（已含被拒域名）在界面提示。
- **Q2 416 怎么处理？**
  建议：解析出 `Content-Range: bytes */N` 且 `offset == N` → 视为「服务器认为已完整」，**不再写、以 416 返回**，
  交给 `FileDownloader` 的 sha256 决定留还是删；其余 416 → 截断从 0 重写（`restartedFromZero = true`）。
  理由：`*/N` + `offset == N` 是「你要的都已经拿到了」，当错误处理会白下一整包；其它 416（offset 越界之类）
  确实只能从头来；判据只看响应头，不靠本地猜测。
- **Q3 要不要加「停滞检测」（如 120 s 没有新字节判失败）？**
  建议：**本步不做**，只保留 `readTimeoutMs`；把阈值留到真机弱网实测之后再定（第 4 步的重试 + 续传已经兜住常见情形）。
  理由：新增时间判据会误杀「慢但有效」的下载，而续传让「失败」的代价很小；先拿真机数据再收紧比先拍一个阈值安全。
- **Q4 续传要不要发 `If-Range: <ETag>`？**
  建议：**不做**（至少不在本步）。理由：正确性兜底已经是 sha256（不符就删了重下），`If-Range` 只省一次全量流量，
  却要引入 `.part` 旁边的旁路元数据文件 + 另一条测例；列为后续小包的可选优化。
- **补充（本简报发现的两处不一致）**：
  - `DownloadResult` 的 `startedAt`：`PLAN.md` §1 有、SURVEY §1.1 没有。建议：**不加**（时间由 `OutboundGateway.clock`
    进审计，结果对象里没人读）；若方案审要保留，字段名用 `startedAtMs: Long` 把单位写进名字。
    理由：同一个信息有两个来源容易不一致。
  - `LocalHttp` 的 reason phrase：建议只覆盖测例用到的状态码（200/206/302/303/307/308/416），其余走
    `HTTP/1.1 <status> Unknown`，不引入完整状态码表。理由：假服务器只需要让状态行合法。

### 1.6 风险与回退

| 风险 | 表现 | 回退/兜底 |
|---|---|---|
| 漏了 `Accept-Encoding: identity` | `HttpURLConnection` 自己加 gzip 并透明解压：`bytesWritten` 与 `Content-Length` 对不上、进度跳变 | 下载请求里显式设 identity；测例里断言进度单调且终值等于长度 |
| 416 判据写反 | 明明已下完却从 0 重下（白费 38 MiB），或半截文件被当成完整 | 按 Q2 的拍板实现，并在测例 8 里锁死两种情形；实现里只按响应头判断 |
| `readTimeoutMs` 不是总时长 | 服务器慢慢滴字节可无限拖 | 本步接受（Q3）；调用方可传更小的 `readTimeoutMs`，真正的兜底是第 4 步的重试与用户的取消按钮 |
| 改 `LocalHttp` 波及生产源码集 | `:roundtable` 的 5 个测例全在用它；`main` 源码集的 warning 也算门禁失败 | 改完立刻跑 `:roundtable:test`；只加字段/重载，不动 `HttpCall` 与 `count` 语义 |
| `HostPolicy` 放宽 | 理论上是构造签名变更 | 已核对全部构造点用命名参数且仍传 `AllowedHosts` → 零改动；若某测试用位置参数，编译期就会红，不会静默 |
| 续传正确性只靠 sha256 | 同一 tag 资产被重新上传 → 拼接出的包 sha256 必错 | 这是设计内的：`FileDownloader` 删了从 0 重下；额外流量换正确性（Q4 若要 `If-Range` 另开） |
| 失败保留半截文件 | `cacheDir/updates` 会积垃圾 | 第 4 步「文件名带 tag」+ 启动 `purge` 兜住；本步只保证「取消/失败不删字节」 |
| 审计字段改动 | 老 `audit.json` 读崩（隐私页白屏） | `AuditEntry` 新字段带默认值 + `toEntry()` 回落，并由测例 13 锁住 |
| 把响应读进内存 | 有人图省事用 `send()` 下载 `.sha256`/APK | `.sha256` 走 `readFollowingRedirects`（有 `limitBytes`）；APK 一律 `download()` |

---

## 3. 步骤 3：自动检查

### 3.1 目标与可观察到的行为

**一句话**：打开 App 时按 24 小时节流在后台查一次新版本，有新版就在「我的」上给不打断的提示
（底栏红点 + 顶部卡片），失败不打扰，忽略的版本不再提示，并给一个默认打开的「自动检查更新」开关。

做完后能观察到：

- 全新安装（从未检查过）打开 App → 后台发一次 `api.github.com/.../releases/latest`，首帧不卡；
  假源计数为 1。
- 23 小时内再打开 → 不发请求；超过 24 小时或系统时间被往回调 → 又发一次。
- 有新版（且未被忽略、非调试包）：底栏「我的」出现小圆点，进入「我的」顶部有卡片，显示版本号、
  安装包大小（MiB 一位小数；`apkSizeBytes == null` 显示「大小未知」）、更新说明（纯文本，超长折叠 +「展开」），
  以及「下载并安装」「忽略这个版本」两个按钮。**不弹窗。**
- 忽略某版本后红点与卡片都消失；出了更新的版本（`VersionComparator.isNewer` 为真）又回来。
- 自动检查失败：界面上**没有**任何错误文案；手动点「检查更新」失败：出现中文原因
  （403 →「检查太频繁，稍后再试」），**不出现** `HTTP 403` / `GitHub returned`。
- 调试包（`isDebugPackage = true`）：不自动检查、无红点、无「下载并安装」；手动检查仍显示
  「有新版本 + 打开发布页」。
- 「上次检查：yyyy-MM-dd HH:mm」显示上次成功跑完的时间；从没查过显示「还没检查过」；
  失败**不会**把下一次自动检查推迟 24 小时。

### 3.2 改动清单（逐文件）

**新增 `app/src/main/java/com/xavierxia/mycelium/update/UpdatePreferences.kt`**

```kotlin
class UpdatePreferences(context: Context) {          // SharedPreferences("updates", MODE_PRIVATE)
    var autoCheck: Boolean      // 默认 true
    var lastCheckAtMs: Long     // 默认 0
    var ignoredTag: String?     // 默认 null
}
```

照 `LocalModelsViewModel`（`app/.../localmodels/LocalModelsViewModel.kt` 第 26–27、66 行）的
`getSharedPreferences(name, 0)` + `androidx.core.content.edit { }` 写法；**不是** SecretStore（不是个人数据）。
`lastCheckAtMs` 只在**一次检查真正跑完**之后写。

**新增 `app/.../update/UpdateCheckPolicy.kt`**

```kotlin
object UpdateCheckPolicy {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
    /** 时钟回拨（now < last）视为到期：宁可多查一次，也不卡死。 */
    fun isDue(nowMs: Long, lastCheckAtMs: Long): Boolean =
        nowMs - lastCheckAtMs >= INTERVAL_MS || nowMs < lastCheckAtMs
}
```

**新增 `app/.../update/UpdateCenter.kt`**（一条链路的状态持有者）

```kotlin
data class UpdateUiState(
    val checking: Boolean = false,
    val release: ReleaseInfo? = null,        // 已被忽略的版本不进这里
    val error: UpdateError? = null,          // 只有手动检查才填
    val autoCheck: Boolean = true,
    val lastCheckAtMs: Long = 0L,
    val ignoredTag: String? = null,
    val download: DownloadUiState = DownloadUiState.Idle,
    val notice: String? = null,              // 安装/校验结果的中文提示
)

sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Running(val written: Long, val total: Long?) : DownloadUiState
    data object Verifying : DownloadUiState
    data class Ready(val file: File, val size: Long) : DownloadUiState
    data object Installing : DownloadUiState
    data class Failed(val message: String) : DownloadUiState
}

class UpdateCenter(
    private val updates: UpdateManager,
    private val prefs: UpdatePreferences,
    private val clock: () -> Long,
    private val isDebugPackage: Boolean,
    private val scope: CoroutineScope,               // Activity 存活期
    private val downloader: ApkDownloader,           // 第 4 步
    private val installer: InstallSessionGateway,    // 第 5 步
) {
    val state: StateFlow<UpdateUiState>
    fun checkIfDue()
    fun checkNow()
    fun ignore(tag: String)
    fun setAutoCheck(on: Boolean)
    fun startDownload(); fun cancelDownload(); fun install(); fun onResume()
}
```

**改 `app/.../ui/MyceliumApp.kt`**（915 行；只动下列位置）

- 第 86 行：`fun MyceliumApp(updateManager: UpdateManager? = null)` **保留**（`MyceliumAppTest` 第 31/44 行在用），
  新增 `fun MyceliumApp(updateCenter: UpdateCenter? = null)`；旧重载内部包一个最小 center。
- 第 100–107 行的 `NavigationBarItem`：`Space.Mine` 的 icon 包 `BadgedBox { Badge { } }` + 现有 `Text("我")`，
  仅当「有新版且未被忽略且非调试包」时显示小圆点（不带数字）。
- `MineScreen`（362–447 行）：顶部插入卡片（版本号 / 大小 / 更新说明折叠 + 展开 / 「下载并安装」/「忽略这个版本」 /
  「打开发布页」）。缺 `apkUrl` 或 `sha256Url` 时只给「打开发布页」。卡片下加 `Switch`「自动检查更新」+
  「上次检查：…」/「还没检查过」。保留现有「检查更新」按钮与错误显示。
- `UpdateText`（449–475 行）：`Available` 不再走 `ACTION_VIEW` 打开 APK。
- 触发：`LaunchedEffect(Unit) { center.checkIfDue() }`（`UpdateManager.checkForUpdate()` 已在 `Dispatchers.IO`，
  不挡首帧）。
- 隐私页 `AuditCard`（852–862 行）：按第 1 步 Q11 的拍板显示 `hostPath` / `receivedBytes`。

**改 `app/.../MainActivity.kt`**（现 15 行）

```kotlin
private val center by viewModels { updateCenterFactory(application) }   // Activity 存活期
setContent { MyceliumApp(center) }
```

`isDebugPackage` 在这里算一次：`BuildConfig.APPLICATION_ID.endsWith(".debug")`（**不要**在逻辑里直接读 `BuildConfig.
APPLICATION_ID`，Robolectric 下 applicationId 与真机不同、测不了）。

**改 `app/src/main/res/values/strings.xml`**：按 SURVEY §6 的名单一次加齐（`update_auto_check`、
`update_last_check_never`、`update_last_check`、`update_size_unknown`、`update_notes_expand`/`_collapse`、
`update_download_install`、`update_ignore_version`、`update_cancel`、`update_downloading`、`update_space_short`
以及第 4/5 步用到的校验/安装文案）；同时**删掉第 7 行的 `update_open_apk`**（见 Q10）。

### 3.3 测例清单

`app/src/test/java/com/xavierxia/mycelium/update/`（JUnit 4 + Robolectric）与 `.../ui/`（Compose，照
`MyceliumAppTest` 的 import 与 `createComposeRule` 写法）：

| # | 测例名 | 断言要点 | 类型 |
|---|---|---|---|
| 1 | `checksOnStartWhenNeverChecked` | 假 `ReleaseSource` 计数 == 1 | JVM |
| 2 | `doesNotCheckTwiceWithin24Hours` | `lastCheckAtMs = now - 23h` → 计数 0 | JVM |
| 3 | `checksAgainAfter24Hours` | `now - 25h` → 计数 1 | JVM |
| 4 | `checksWhenTheClockMovesBackwards` | `lastCheckAtMs` 在未来 → 仍然查（计数 1） | JVM |
| 5 | `manualCheckIgnoresTheSchedule` | 23 h 内点按钮 → 计数 1 | JVM |
| 6 | `autoCheckOffSkipsTheAutomaticCheckButManualStillWorks` | 开关 false → 启动不查、点按钮查 | JVM |
| 7 | `ignoredVersionIsNotShownAgain` | 无红点、无卡片 | JVM |
| 8 | `newerVersionAfterIgnoreIsShownAgain` | 更大的版本 → 红点与卡片回来 | JVM |
| 9 | `failedAutomaticCheckShowsNoError` | 假源抛 `IOException` → 界面没有错误文案 | JVM |
| 10 | `manualCheckShowsHumanReadableErrors` | 403 → 出现「检查太频繁，稍后再试」，**不出现** `HTTP 403` / `GitHub returned` | JVM |
| 11 | `debugPackageNeverAutoChecksAndHasNoInstallEntry` | 注入 `isDebugPackage = true` → 计数 0、无「下载并安装」 | JVM |
| 12 | `releaseWithoutChecksumShowsTheReleasePageOnly` | 只给发布页按钮 | JVM |
| 13 | `lastCheckTimeIsDisplayed` | 固定时间戳 → 出现对应文本；未检查过 → 「还没检查过」 | JVM |
| 14 | `failedCheckDoesNotPushTheNextCheckTwentyFourHoursAway` | 失败后 `lastCheckAtMs` 未变 | JVM |
| 15 | `UpdatePreferencesTest`：`defaultsAreAutoCheckOnAndNeverCheckedAndNothingIgnored` / `valuesSurviveANewInstance` | 默认值与跨实例持久化 | JVM |
| R1 | 回归：`MyceliumAppTest`（2 例）与 `MainActivityTest`（1 例） | 一个都不许红；`MainActivityTest` 不许真发网络请求 | JVM |
| D1 | 红点/卡片在深色主题、大字体下的观感 | 真机截图交统筹 | **真机** |
| D2 | 自动检查对冷启动首帧的影响 | logcat / `am start -W` 记录启动耗时 | **真机** |

### 3.4 验收命令

```powershell
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.update.UpdateCheckPolicyTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.update.UpdatePreferencesTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.ui.UpdateFlowTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.ui.MainActivityTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest :app:lintDebug
```

`strings.xml` 一改就必须跑 `:app:lintDebug`（`UnusedResources` 是 lint 警告 = 失败）：

```powershell
.\gradlew.bat --offline --console=plain :app:lintDebug
.\gradlew.bat --offline --console=plain :app:lintRelease
```

门禁子集：

```powershell
.\scripts\check-all.ps1 -SkipLlamaPrepare -Tasks ":app:testDebugUnitTest",":app:lintDebug",":app:lintRelease"
```

必须真机的部分：D1、D2（截图 + 冷启动耗时），并入四步一次的走查。

### 3.5 先决决定（待方案审确认）

- **Q5 `MainActivity` 的自动检查会打到 `MainActivityTest`，注入工厂怎么设计？**
  建议：在 `MainActivity` 暴露一个可替换的工厂（例如 `internal var updateCenterFactory: (Context) -> UpdateCenter
  = ::defaultUpdateCenter`，或等价的可注入对象），`MainActivityTest` 在 `@Before` 里换成「假源 + 假
  transport + 指向测试 SharedPreferences」的 center，`@After` 还原；`MyceliumApp` 同时保留旧
  `updateManager` 重载，`MyceliumAppTest` 不动。理由：`MainActivityTest` 是既有测例、不许红；把
  「谁能造 center」收成一个可替换点，比在 UI 里加 debug 分支更小、更可测；也避免逻辑里读
  `BuildConfig.APPLICATION_ID`。另外：失败不打扰、`lastCheckAtMs` 只在成功跑完后写，这两条要写进
  测例 9/14 锁死。
- **Q10 失败关闭时要不要给「打开发布页」的兜底入口？`update_open_apk` 怎么办？**
  建议：**要**给次要入口「打开发布页」（用已有的 `update_open_release_page`），在「缺资产」、
  「白名单拒绝/网络失败」、「签名读不出」三种情况下都能点到；并**删掉 `update_open_apk`**。
  理由：删掉「浏览器直开 APK」路径是任务书的意图，留着不用就是 `UnusedResources`（仓库无 lint
  baseline、`warningsAsErrors` + check-all 把 lint 警告算失败）；而兜底指向**发布页**而不是 APK 直链，
  失败关闭的原则不破（用户自己去浏览器下载是用户的选择，App 不替他绕过四项校验）。
- **Q11 隐私页要不要显示 `hostPath` / `receivedBytes`？**
  建议：**要**。`AuditCard` 的域名行改成 `hostPath.joinToString(" → ")`（单跳效果与现在一样），
  `receivedBytes` 有值才在字数旁加一行（如「本次下载 37.2 MiB」）。理由：第 1 步专门加了这两个字段，
  不显示等于白记；改动只在显示层，旧记录已由 `AuditEntry` 的默认值兜住（显示成单跳、无字节数）。

### 3.6 风险与回退

| 风险 | 表现 | 回退/兜底 |
|---|---|---|
| 注入签名变更把两个 UI 测试打红 | `MyceliumAppTest`（位置参数传 manager）编译失败或行为变化；`MainActivityTest` 真发网络请求 | 保留旧重载 + 工厂可替换（Q5）；实施时把这段改动与两个测试的同步改动放**同一个提交** |
| 界面状态放在 Compose `remember` | 切底栏/重建把卡片打回原形，下载任务被丢 | 状态必须挂在 `UpdateCenter`（Activity ViewModel）；`onCleared` 里 `scope.cancel()` |
| 在 Compose 里用 `viewModel()` | `createComposeRule()` 不提供 `ViewModelStoreOwner`，测试直接抛 | center 由 `MainActivity` 造好传进来，Compose 只收参数（SURVEY §3.2 的结论，照做） |
| 调试包判定写进业务逻辑 | Robolectric 下 applicationId 与真机不同，测不了 | `isDebugPackage` 作为构造参数注入，生产在 `MainActivity` 算一次 |
| 自动检查抢首帧 | 启动时网络慢会让「我的」页卡 | 只在这里触发、跑在 `Dispatchers.IO`，界面只读 `StateFlow`；失败不填 `error` |
| 24 h 节流写坏 | 一次网络抖动把下次检查推到 24 h 后 | `lastCheckAtMs` 只在检查真正跑完后写；测例 14 锁住 |
| `strings.xml` 大量新增 | 拼错/漏 `%1$s` 占位 → lint 或运行时崩 | 一次加齐，随后 `:app:lintDebug` + `:app:lintRelease`；文案只在这里，逻辑不拼中文 |

---

## 4. 步骤 4：下载与校验

### 4.1 目标与可观察到的行为

**一句话**：把「空间检查 → 按流下载（可续传/可取消/有进度）→ 四项校验 → 改名成待安装文件」编排成
app 侧的一条链路，任何一项校验不过都删文件并用中文说明是哪一项。

做完后能观察到：

- 磁盘不够（`freeBytes() <= apkSize + 余量`）时：**根本不发起下载**，提示「需要 X MiB，可用只有 Y MiB」。
- 点「下载并安装」：`LinearProgressIndicator` 前进（总长未知时不确定态），按钮变「取消」；
  中途离开页面（切底栏）下载继续；点「取消」立刻停，`.part` 留下。
- 下载完成后：`UpdateFiles.promote(tag)` 把 `mycelium-<tag>.apk.part` 改名为 `mycelium-<tag>.apk`；
  校验通过进入 `Ready`。
- 校验失败：该 tag 的文件被删掉，界面出现与失败项对应的中文文案（校验和不符 / 包名不对 /
  版本号没涨 / 签名不对 / 读不出签名）。
- 网络类失败：最多重试 3 次、退避 2s/5s/10s，续传接着下；点「取消」能立刻中断退避。
- 启动时 `purge(now, keepTag)`：>7 天的 `.apk`/`.part`/`.sha256` 全清，当前 tag 的 `.part` **不动**；
  换 tag 时删掉别的 tag 的 `.part`。

### 4.2 改动清单（逐文件）

**新增 `app/.../update/UpdateTransport.kt`**（app 侧的出网口子）

```kotlin
interface UpdateTransport {
    fun downloadApk(url: String, target: File, call: GatewayCall,
                    progress: (written: Long, total: Long?) -> Unit): DownloadOutcome
    fun fetchChecksumText(url: String, limitBytes: Int = 4 * 1024): String   // 走 readFollowingRedirects
    fun sha256(file: File): String
}
```

**新增 `app/.../update/GatewayUpdateTransport.kt`**

```kotlin
class GatewayUpdateTransport(
    private val gateway: OutboundGateway,
    private val fileDownloader: FileDownloader = FileDownloader(gateway),
) : UpdateTransport
```

底层复用 `ChatService` 已经持有的 `AndroidGateway.gateway`（**审计必须写进同一个 `privacy/audit.json`**，
隐私页读的是 `chat.audit()`）；`ChatService`（`app/.../roundtable/ChatService.kt`，第 61/128/141 行附近）
加一个 `fun updateTransport(): UpdateTransport`。

**新增 `app/.../update/UpdateFiles.kt`**

```kotlin
class UpdateFiles(private val root: File /* cacheDir/updates */) {
    fun partFile(tag: String): File        // mycelium-<tag>.apk.part
    fun finalFile(tag: String): File       // mycelium-<tag>.apk
    fun promote(tag: String): File         // .part -> 最终名（Files.move + REPLACE_EXISTING）
    fun deleteForTag(tag: String)
    fun purge(nowMs: Long, keepTag: String?): List<File>   // >7 天；keepTag 的 .part 不动
    fun freeBytes(): Long                  // StatFs(root).availableBytes（Robolectric 可假）
    fun requireSpace(apkSizeBytes: Long?, margin: Long = MARGIN_BYTES)   // 不够抛 SpaceShortage(needed, actual)
}
```

`freeBytes()` 用 `StatFs(root).availableBytes` 而不是 `File.usableSpace`：Robolectric 有 `ShadowStatFs`，
`File.usableSpace` 在 JVM 测试里拿不到可控值（对照现成形状：`llm/.../ModelRegistry.kt` 第 64 行
`directory.usableSpace > it + 16 MiB`）。

**新增 `app/.../update/ApkVerifier.kt`**

```kotlin
data class ApkFacts(val packageName: String?, val versionCode: Long, val signerSha256: String?)
fun interface ApkFactsReader { fun read(path: String): ApkFacts? }     // 真机: getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)
fun interface InstalledAppFacts { fun installed(): ApkFacts }          // 真机: getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)

sealed interface VerifyOutcome {
    data object Verified
    data object ChecksumMismatch
    data class PackageNameMismatch(val found: String?)
    data class SignerMismatch(val expected: String, val found: String)
    data object SignerUnreadable              // 读不出 ≠ 不一致（文案分开，仍然失败关闭）
    data class VersionCodeNotNewer(val current: Long, val candidate: Long)
}

class ApkVerifier(private val transport: UpdateTransport, private val archive: ApkFactsReader,
                  private val installed: InstalledAppFacts) {
    /** 任一不过都先删文件再返回；expectedSha256Url 是资产的 .sha256 地址。 */
    fun verify(file: File, expectedSha256Url: String, currentVersionCode: Long): VerifyOutcome
}

object ChecksumText { fun parseFirstSha256(text: String): String? }   // 取第一个 64 位十六进制串
```

**新增 `app/.../update/ApkDownloader.kt`**（空间检查 + 下载 + 校验的编排）

```kotlin
class ApkDownloader(private val files: UpdateFiles, private val transport: UpdateTransport,
                    private val verifier: ApkVerifier) {
    fun downloadAndVerify(release: ReleaseInfo, currentVersionCode: Long, call: GatewayCall,
                          progress: (Long, Long?) -> Unit): DownloadOutcome
}
```

**改 `app/.../ui/MyceliumApp.kt`**：进度（`LinearProgressIndicator`）、下载中按钮变「取消」（`call.cancel()`）、
失败/空间不足文案。

### 4.3 测例清单

| # | 测例名 | 断言要点 | 类型 |
|---|---|---|---|
| 1 | `checksumTextAcceptsTheTwoSpaceForm` | `hash  mycelium-v0.4.0.apk` → hash | JVM |
| 2 | `checksumTextAcceptsTheGitBashAsteriskForm` | `hash */d/.../app-release.apk` → hash（取第一个 64 位 hex，忽略后面一切） | JVM |
| 3 | `checksumTextRejectsTextWithoutAHash` | 空/短串/含非 hex → null | JVM |
| 4 | `verifierAcceptsAMatchingApk` | 四项全对 → `Verified` | JVM |
| 5 | `verifierRejectsChecksumMismatchAndDeletesTheFile` | 文件被删、返回 `ChecksumMismatch` | JVM |
| 6 | `verifierRejectsForeignPackageName` | `PackageNameMismatch` + 删 | JVM |
| 7 | `verifierRejectsDifferentSigner` | `SignerMismatch` + 删 | JVM |
| 8 | `verifierSeparatesUnreadableSignerFromMismatch` | `SignerUnreadable`（同样失败关闭，不同文案） | JVM |
| 9 | `verifierRejectsSameOrLowerVersionCode` | `==` 与 `<` 都拒 | JVM |
| 10 | `spaceShortageBlocksTheDownloadAndSaysHowMuchIsMissing` | `ShadowStatFs` 假值 → 出现差值文案，且**没有发起下载** | JVM |
| 11 | `downloadResumesAfterAFailureAndKeepsTheBytes` | 第一次失败留 `.part`，第二次带 `Range` 续 | JVM |
| 12 | `networkFailureRetriesAtMostThreeTimesWithBackoff` | 注入 `pause` 收到 2000/5000/10000 ms，且不真睡 | JVM |
| 13 | `checksumFailureIsNotRetried` | 只下两次（原始 + 从 0 重下），没有第 3 次全量 | JVM |
| 14 | `partialFileOfAnotherTagIsDeletedWhenANewTagStarts` | 旧的 `.part` 被删 | JVM |
| 15 | `purgeDeletesFilesOlderThanSevenDays` | 老的 `.apk`/`.part`/`.sha256` 都没了 | JVM |
| 16 | `purgeKeepsTheCurrentPartialFile` | 当天的 `.part` 还在（续传前提） | JVM |
| 17 | `installedVersionCodeComesFromTheCurrentApp` | `InstalledAppFacts` 假实现被调用一次 | JVM |
| D1 | 真机 `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` | 各 ROM 上的实际表现（v0.1.1 是 v2-only 签名） | **真机** |
| D2 | 38 MiB 的 sha256 耗时 | 决定「边下边算」还是「下完再算」的实测依据 | **真机** |
| D3 | `cacheDir` 被系统回收 | `.part`/已下好的 APK 在安装前被清掉时的行为 | **真机** |

Robolectric 4.17 已核到有 `ShadowPackageInstaller` / `ShadowPackageManager.getPackageArchiveInfo` /
`setPackageArchiveInfo` / `setCanRequestPackageInstalls` / `ShadowStatFs.registerStats`（来自 SURVEY 的
`shadows-framework-4.17.jar` 符号核对；本简报未复跑）。**但「取包名/签名/版本号」仍抽在 `ApkFactsReader`
后面**：把状态机绑死在影子行为上会盖住真机差异。

### 4.4 验收命令

```powershell
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.update.ApkVerifierTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.update.UpdateFilesTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.update.ApkDownloaderTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest :app:lintDebug
```

门禁子集：

```powershell
.\scripts\check-all.ps1 -SkipLlamaPrepare -Tasks ":app:testDebugUnitTest",":app:lintDebug",":app:lintRelease"
```

必须真机的部分：D1–D3 并入走查（真机走查以调试包 + `adb reverse` 指向本机假服务，假服务提供
APK 与 `.sha256` 两条响应；下载进度、校验通过、`.part` 续传各截一张）。走查前先 `:app:assembleDebug`
与 `:app:assembleDebugAndroidTest` 并 `adb install -r` 两个包：

```powershell
.\gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb reverse tcp:8765 tcp:8765
adb shell am instrument -w -r -e class com.xavierxia.mycelium.update.UpdateInstallDeviceTest com.xavierxia.mycelium.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### 4.5 先决决定（待方案审确认）

- **Q6 sha256 算在哪里 + 网络重试放在哪一层？**
  建议：(a) sha256 **边下边算**（`MessageDigest` 跟着写文件走），**续传时先流式哈希 `.part` 已有前缀**再继续；
  (b) **网络类重试放 `:net` 的 `FileDownloader`**，app 的第 4 步只做「空间 → 下载 → 四项校验 → promote」，
  **不**再自己写一层重试。理由：(a) 省一次 38 MiB 读盘，且「续传前缀先哈希」正是 sha256 的语义，
  整文件哈希留作退化实现即可；(b) 重试与续传是同一件事（退了再连就是从已有字节继续），拆在两处
  （`PLAN.md` 现在就是这么拆的）必然出现「一处退避、一处不退避」的裂缝，P2-5 也没法直接复用。
- **Q7 空间余量取固定 32 MiB 还是更大？**
  建议：`max(32 MiB, apkSize / 4)`；`apkSizeBytes == null` 时用固定下限 64 MiB 并在界面显示「大小未知」。
  理由：安装会话要再复制一份（`PackageInstaller` 的接口决定，无法避免），38 MiB 的包用固定 32 MiB 偏薄；
  `/4` 让更大的包自动放宽，两个数都写进 `SpaceShortage(needed, actual)` 的文案里。
- **`.sha256` 怎么取（`PLAN.md` §4 写错的地方）？**
  建议：`gateway.readFollowingRedirects(GatewayRequest(url = sha256Url, purpose = Update, call = call),
  limitBytes = 4 * 1024)`；超过上限当「校验文件异常」拒绝，**不要**整段读。审计是**独立一条**
  （一次安装共两条：APK + 校验文件）。理由：资产的 `browser_download_url` 在 `github.com`，
  会 302 到 `release-assets.githubusercontent.com`；用现在不跟跳转的 `send()` 只会拿到 302 空体。
- **四项校验的顺序（`PLAN.md` 与 SURVEY 不一致）？**
  建议：sha256 → 包名 → **versionCode** → 签名（SURVEY §4.2 的顺序）。理由：读签名证书
  （`GET_SIGNING_CERTIFICATES`）最贵、也最容易在个别 ROM 上失败；放在最后能让「包名/版本号就不对」的坏包
  更快被拒，给出的失败原因也更接近用户能理解的东西。
- **签名只和「当前装着的 App」比**（有意识不做的事，请方案审确认）：建议照 SURVEY/PLAN 一致结论执行——
  不硬编码 `docs/releases/signing-cert-sha256.txt` 的指纹。理由：那份指纹是 CI 闸门用的；App 里再写一份，
  以后合法换签名会把用户挡死，而「与当前 App 一致」已经等价于「能覆盖安装」。

### 4.6 风险与回退

| 风险 | 表现 | 回退/兜底 |
|---|---|---|
| `apkSizeBytes` 为 null | 空间检查与「大小」显示都没数 | 64 MiB 下限 + 「大小未知」文案（Q7）；测例里覆盖 null 分支 |
| `cacheDir` 被系统回收 | 交给 `PackageInstaller` 前文件没了，会话拿到空/半截包 | 开会话**之前**重新 stat（存在且长度对），不在就重下或报错，**不能拿路径直接开会话** |
| sha256 成本 | 「下完再整文件哈希」多读 38 MiB | 边下边算 + 续传哈希前缀（Q6）；保留整文件哈希作退化实现 |
| 校验失败一律删文件 | 用户要重下 38 MiB | 安全优先；文案要说清「不是网络问题，是包不对」；网络类失败（可重试）与校验失败（不重试）分开 |
| 退避不能取消 | 点「取消」要等 10 s 才停 | 退避用可取消的等待（协程 `delay` 或切成小片检查 `call.isCancelled`），**不要裸 `Thread.sleep` 挡在 UI 路径上**；测例 12 的 `pause` 注入是给单测用的 |
| 32 MiB 余量偏薄 | 安装会话 `STATUS_FAILURE_STORAGE` | Q7 的 `max(32 MiB, size/4)`；`STATUS_FAILURE_STORAGE` 有独立文案 |
| `purge` 删掉当天的 `.part` | 续传永远无效（每次都从 0） | `purge(now, keepTag)` 明确「keepTag 的 `.part` 不动」；测例 16 专门锁这一条 |
| 调试包不给安装入口 | 安装路径在调试包里不可达，容易把「调试包不安装」和「校验」混着测 | 走查时分清：校验用调试包可测，安装入口必须构造非 debug 的 `isDebugPackage` 或在设备上装正式包路径的验证放到发版日 |

---

## 5. 步骤 5：安装

### 5.1 目标与可观察到的行为

**一句话**：用 `PackageInstaller` 会话安装校验过的 APK，把系统确认界面拉起来，成功/失败/取消都有中文结果；
没开「安装未知应用」时先说明再带路，**只带路，不代开**。

做完后能观察到：

- 未授权安装未知应用：先出现说明对话框，确认后跳到本应用的系统设置页；**没有创建会话**；
  回来后若已允许 → 立刻继续安装。
- 已授权：创建会话 → 按流写入 → 系统回 `STATUS_PENDING_USER_ACTION` → 系统的安装确认界面被拉起
  （截图到此为止）。
- 用户在系统界面点「安装」→ 回到 App 出现「更新完成，下次打开生效」，`updates/` 里该 tag 的文件被清，
  `lastCheckAtMs` 重置、`ignoredTag` 清空；重启 App 后 versionCode 变了、数据还在（不自动重启）。
- 用户点「取消」：提示「已取消，安装包已清理」并删文件。
- 空间不够 / 版本冲突 / 被设备策略阻止 / 包无效：各有独立中文文案。
- 广播没到也没关系：回到前台读 `longVersionCode` 是**权威判定**（装上了就是装上了）。
- 调试包：没有安装入口，也不做未知来源引导。

### 5.2 改动清单（逐文件）

**新增 `app/.../update/PackageInstallerLauncher.kt`**

```kotlin
sealed interface InstallStatus {
    data class PendingUserAction(val intent: Intent) : InstallStatus   // STATUS_PENDING_USER_ACTION
    data object Succeeded : InstallStatus
    data object Cancelled : InstallStatus
    data object InsufficientStorage : InstallStatus
    data object Incompatible : InstallStatus
    data object Invalid : InstallStatus
    data object Blocked : InstallStatus
    data object Conflict : InstallStatus
    data class Failed(val code: Int) : InstallStatus
}

/** 唯一碰到 PackageInstaller 的地方；编排（UpdateCenter）只认这个接口，测试用假实现。 */
interface InstallSessionGateway {
    fun canRequestPackageInstalls(): Boolean
    fun unknownSourcesIntent(): Intent                  // ACTION_MANAGE_UNKNOWN_APP_SOURCES + package: URI
    fun install(file: File, packageName: String, onStatus: (InstallStatus) -> Unit)
}

class AndroidInstallSessionGateway(private val context: Context) : InstallSessionGateway
```

会话写法：`createSession(SessionParams(MODE_FULL_INSTALL))` → `openWrite("base.apk", 0, size)` →
只读输入流 + 8 KiB 缓冲拷贝（**不要 `File.readBytes()`**，38 MiB 不进内存）→ `fd.sync()` →
`commit(pendingIntent)`。

**新增 `app/.../update/InstallResultReceiver.kt`** + `AndroidManifest.xml`（第 34 行 `</application>` 之前）加：

```xml
<receiver android:name=".update.InstallResultReceiver" android:exported="false" />
```

**改 `app/.../update/UpdateCenter.kt`**：`install()` 编排「未知来源 → 会话 → 状态 → 收尾」；
`onResume()` 复查 `longVersionCode`（权威判定）；成功 → 清该 tag、重置 `lastCheckAtMs`、清 `ignoredTag`、
提示「更新完成，下次打开生效」（不自动重启）。

**改 `app/.../ui/MyceliumApp.kt`**：未知来源说明对话框 +
`rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`
（文件里第 5–6 行已有这两个 import 的用法可照抄）+ 成功/失败/取消/空间不足的结果提示。

**新增 `app/src/androidTest/java/com/xavierxia/mycelium/update/UpdateInstallDeviceTest.kt`**（真机编排走查，
`adb reverse` 假服务；只走到「拉起系统确认界面并截图」）。

### 5.3 测例清单

`app/src/test/java/com/xavierxia/mycelium/update/PackageInstallFlowTest.kt`（只测状态机与编排，
`InstallSessionGateway` 用假实现）：

| # | 测例名 | 断言要点 | 类型 |
|---|---|---|---|
| 1 | `asksForUnknownSourcesPermissionBeforeCreatingASession` | `canRequestPackageInstalls() = false` → 只跳设置、没建会话 | JVM |
| 2 | `startsTheSessionAfterTheUserGrantsUnknownSources` | 回来后 true → 建会话 | JVM |
| 3 | `pendingUserActionLaunchesTheSystemConfirm` | 假会话回 `PendingUserAction(intent)` → 断言这个 Intent 被交给 launcher | JVM |
| 4 | `successClearsTheUpdatesDirectoryAndResetsTheIgnoredVersion` | 该 tag 文件没了、`ignoredTag == null`、`lastCheckAtMs` 被重置 | JVM |
| 5 | `insufficientStorageShowsItsOwnMessage` | 单独文案 | JVM |
| 6 | `userCancellationShowsCancelledAndCleansUp` | 「已取消，安装包已清理」+ 文件被删 | JVM |
| 7 | `failuresGetDistinctChineseMessages` | blocked/conflict/incompatible/invalid 各一条 | JVM |
| 8 | `versionCodeChangeOnResumeIsTheFinalConfirmation` | 广播没到但 `onResume` 读到新 versionCode → 判定成功 | JVM |
| 9 | `debugPackageHasNoInstallEntry` | 无入口、无未知来源引导 | JVM |
| D1 | 会话能建、系统确认界面能被拉起 | 真机截图（到此为止） | **真机** |
| D2 | `FLAG_MUTABLE` 与 `FLAG_IMMUTABLE` 哪个能收到 `EXTRA_STATUS` | 真机各验一次，结论写进交付报告 | **真机** |
| D3 | `EXTRA_INTENT` 能否取到；未知来源设置页跳转与返回 | 真机 | **真机** |
| D4 | 安装成功后 versionCode 变了、数据还在；取消后的提示与清理 | 真机（**不碰正式版 0.1.1**） | **真机** |

Robolectric 层面还可加（SURVEY 已核到影子）：`ShadowPackageInstaller.getAllSessions()`/`SessionInfo` 验证
会话参数与写入量、`setSessionSucceeds|Fails|ActiveState` 驱动状态分支、
`ShadowPackageManager.setCanRequestPackageInstalls`。**但编排仍留在 `InstallSessionGateway` 后面**：
`commit` 把 PendingIntent 送回来这件事与 ROM/版本相关，别把状态机绑死在影子行为上。

### 5.4 验收命令

```powershell
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest --tests "com.xavierxia.mycelium.update.PackageInstallFlowTest"
.\gradlew.bat --offline --console=plain :app:testDebugUnitTest :app:lintDebug :app:lintRelease
.\gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
```

`lintRelease` 必须跑：`PendingIntent` 可变性 / 隐式 Intent 这两条 lint 检查就是在这里拦（仓库
`warningsAsErrors = true`，check-all 把 lint 警告算失败）。

门禁子集：

```powershell
.\scripts\check-all.ps1 -SkipLlamaPrepare -Tasks ":app:testDebugUnitTest",":app:lintDebug",":app:lintRelease",":app:assembleDebug",":app:assembleDebugAndroidTest"
```

必须真机的部分（D1–D4）：`adb reverse` + `adb install -r` + `am instrument`（命令同 §4.4），
系统安装确认由用户点或经用户同意后由 agent 点。

### 5.5 先决决定（待方案审确认）

- **Q8 `PendingIntent` 用「清单接收器 + `FLAG_MUTABLE`」还是「动态接收器 + `FLAG_IMMUTABLE`」？**
  建议：**显式 Intent（指定 component + package）+ 清单里 `exported="false"` 的接收器 + `FLAG_MUTABLE`**；
  真机必须分别验 `EXTRA_STATUS` 与 `EXTRA_INTENT` 到不到。并且**同步改 `docs/UPDATE_ARCHITECTURE.md` 第 5 节
  （第 69 行）**——它现在写的是「动态注册的非导出接收器」，与这个选择直接冲突（`PLAN.md` §5 写的是
  「显式 PendingIntent + 动态注册接收器」，三处说法不一致，方案审要一次定死）。理由：显式 Intent 不会撞
  `MutableImplicitPendingIntent`；`FLAG_IMMUTABLE` 在部分版本上拿不到系统填的 extras；清单接收器在进程被回收后
  仍能把结果带回来（动态接收器不能），`exported=false` 不增加攻击面。若真机发现 `FLAG_MUTABLE` 收不到结果，
  退路是动态接收器 + `FLAG_IMMUTABLE`，而 `onResume` 读 versionCode 仍是权威判定，不会静默失败。
- **Q9 `commit` 之后用户一直不点安装（会话悬挂）要不要超时清理？**
  建议：**要兜底**。回到前台（`onResume`）读 `longVersionCode`；若还是旧版本且离 `commit` 超过阈值
  （建议 10 分钟）或用户明确放弃，则 `abandonSession()`（或下次进入时先 `getAllSessions()` 清掉自己的会话）
  并删该 tag 的文件、提示「已取消，安装包已清理」。理由：不清会让 `cacheDir` 和系统会话一直占着；
  `commit` 之后的会话只能 `abandon`，没有别的回收口；阈值只是「别在用户还停在系统界面时清」的保险，
  具体值实施时定并写进测例。
- **`SessionParams` 的 setter / 废弃 API**：建议 `SessionParams(MODE_FULL_INSTALL)` 只调必要的 setter，
  **不**调 `setAppPackageName`（SURVEY 核对结论：`MODE_FULL_INSTALL` 不需要它；`PLAN.md` §5 写了它，
  按 SURVEY 去掉）。理由：Kotlin 废弃警告 = 构建失败；目标本来就必须是本应用，`setAppPackageName`
  不增加任何保证。取 extra 用 `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)`
  （targetSdk 37 上 `getParcelableExtra(String)` 已废弃）；版本号用 `PackageInfo.longVersionCode`（minSdk 29 可用）。
- **「安装未知应用」的交互**：建议照 SURVEY §5.2：先弹一次**说明**（为什么需要：要把下载好的安装包交给系统安装器），
  用户确认后跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + `Uri.parse("package:$packageName")`，回来复查；
  允许了继续装，没允许就停在说明上。理由：任务书明确「只带路，不代开」；`rememberLauncherForActivityResult`
  已有先例可照抄。

### 5.6 风险与回退

| 风险 | 表现 | 回退/兜底 |
|---|---|---|
| `PendingIntent` 可变性选错 | 收不到 `EXTRA_STATUS`/`EXTRA_INTENT`，界面永远停在「安装中」 | 这是本步最可能翻车的一处，**必须真机验**；即使广播收不到，`onResume` 读 versionCode 也能给出「装上了/没装上」，不静默失败 |
| lint 直接红 | `UnspecifiedImmutableFlag` / `MutableImplicitPendingIntent` | 显式 Intent + 明确 flag（Q8）；提交前必跑 `:app:lintRelease` |
| 用了废弃 API | `getParcelableExtra(String)`、废弃的 `SessionParams` setter → 废弃警告 = 构建失败 | 用 `IntentCompat`、`ContextCompat.registerReceiver`、`SessionParams` 的非废弃 setter |
| 会话再占一份磁盘 | `STATUS_FAILURE_STORAGE` | 单独文案「空间不够」；下载前的余量已经预留（第 4 步 Q7） |
| 用户以为「一键更新」 | 实际是「点两下」（App 一下、系统界面一下） | 文案与 `docs/RELEASING.md`/`UPDATE_ARCHITECTURE.md` 如实写，不夸大 |
| 进程被回收 | 结果丢失 | 清单接收器能把结果带回来（Q8 的主要理由）；`onResume` 复查作第二道 |
| Manifest 多一个组件 | 攻击面/审核关注 | `exported="false"` + 不做任何隐式解析（显式 Intent）；只处理本应用会话的状态码 |
| 会话悬挂 | 系统会话与 `.apk` 长期占着 | Q9 的 `abandonSession` 兜底 |
| 校验过的文件在开会话前被清 | 会话写入失败或装上坏包 | 第 4 步风险表里那条：开会话前重新 stat，不在就重下 |

---

## 6. 四步的实施顺序建议

### 6.1 推荐顺序：`1 → 3 → 4 → 5`（串行，每步一个提交）

| 步 | 依赖 | 完成后必须先跑的定向验证 |
|---|---|---|
| 1 | 无（只动 `:net` + `LocalHttp`） | `:net:test` + `:roundtable:test` + `:app:testDebugUnitTest :app:lintDebug` |
| 3 | 第 1 步不是硬依赖，但 UI 的 `DownloadUiState` / `UpdateCenter` 构造形参要与第 4/5 步对齐 | `:app:testDebugUnitTest` + `:app:lintDebug :app:lintRelease` |
| 4 | 第 1 步的 `download` / `readFollowingRedirects` / `FileDownloader` 与第 3 步的 `UpdateCenter` | `:app:testDebugUnitTest --tests "...update.*"` + `:app:lintDebug` |
| 5 | 第 3 步的 `UpdateCenter`（`install()` / `onResume()`）与第 4 步的 `Ready` 状态 | `:app:testDebugUnitTest` + `:app:lintDebug :app:lintRelease` + 两个 assemble |

**先 1 的理由**：它是唯一动 `:net` 的一步，而 `LocalHttp.kt` 在 **main** 源码集里、被 `:roundtable` 的测试共用
→ 必须单独提交并单独跑 `:roundtable:test`，混进 app 的改动里就分不清是谁弄红的。第 4 步的所有下载
依赖它的签名先定下来。

**先 3 再 4 的理由**：第 3 步定义 `UpdateCenter`、`UpdateUiState`、`DownloadUiState`、`MyceliumApp` 的新注入
签名与全部文案键；第 4 步只往里填下载编排的细节，第 5 步只往里填安装编排。反过来（先 4）会导致
`UpdateCenter` 与 UI 传参改两遍，而这两个文件正好是三步的**互踩点**。

### 6.2 哪些可以并行

- **只有第 1 步与第 3 步**具备「文件级不重叠」的并行条件（`:net` 对 `app/update` + `ui`）。前提是**先冻结
  三样东西**，否则并行会变成互相改签名：
  1. `OutboundGateway.download(DownloadRequest, DownloadProgress?)` / `readFollowingRedirects(GatewayRequest, Int)`
     的签名与 `DownloadOutcome` 五个分支；
  2. `AuditEntry` 的 `hostPath` / `receivedBytes` 字段（第 3 步的隐私页显示要照着写）；
  3. `MyceliumApp(updateCenter: UpdateCenter?)` 与 `UpdateCenter` 的构造形参（第 4/5 步要往里加
     `downloader` / `installer`，第 3 步可以先收成可空/占位并留 TODO）。
- 即便如此，**收益有限**：单仓库单 worktree 共享一个 Gradle 构建，第 3 步一改 app 源码就会触发
  `:net` 重编；真正并行需要 `git worktree` + 两个目录。**建议实际按 1 → 3 → 4 → 5 串行**，
  只把第 1 步的「写代码」提前到其他步之前完成并提交。
- **第 4 步与第 5 步不要并行**：它们同时改 `UpdateCenter.kt`、`MyceliumApp.kt`、`strings.xml`。
- **第 1–5 步的真机走查合并成一次**：同一台红魔、同一个调试包、同一次 `adb reverse`，按
  「域名链（步 1）→ 自动检查/红点卡片（步 3）→ 下载进度/校验（步 4）→ 系统确认界面截图（步 5）」
  的顺序走，避免反复卸载重装。但**四步的 JVM 部分全部绿了再上手机**。

### 6.3 会互相踩文件的清单（并行前必看）

| 文件 | 第 1 步 | 第 3 步 | 第 4 步 | 第 5 步 | 处理 |
|---|---|---|---|---|---|
| `net/.../Gateway.kt` / `AuditLog.kt` / `Download.kt` / `FileDownloader.kt` / `LocalHttp.kt` | 改/新增 | 只读（隐私页读字段） | 通过 `UpdateTransport` 用 | — | 第 1 步独占；先把 `AuditEntry` 字段定死 |
| `app/.../ui/MyceliumApp.kt` | — | 卡片/红点/开关/`UpdateText` | 进度/取消/失败文案 | 未知来源对话框 + launcher + 结果提示 | **最大互踩点**：按 3→4→5 串行 |
| `app/.../update/UpdateCenter.kt` | — | 新建（状态机） | 加 `startDownload`/`cancelDownload`/`download` 状态 | 加 `install`/`onResume` 编排 | 同上，串行；构造形参在第 3 步一次留足 |
| `app/src/main/res/values/strings.xml` | — | 加大部分文案、删 `update_open_apk` | 加校验/空间文案 | 加未知来源/安装结果文案 | 第 3 步先把 SURVEY §6 的名单一次加齐，4/5 只做增补 |
| `AndroidManifest.xml` | — | — | — | 加 `<receiver>` | 只有第 5 步改 |
| `app/.../roundtable/ChatService.kt` | — | 传 transport 进 center | 加 `updateTransport()` | — | 第 3/4 步交界处，建议放第 4 步 |
| `docs/UPDATE_ARCHITECTURE.md` | 第 1 节（下载）核对一致 | — | 第 4 节核对一致 | **第 5 节必须按 Q8 的拍板改写**（现在写的是动态接收器） | 第 5 步同提交改文档 |

### 6.4 收尾

四步都做完后，在**全新 worktree、已提交版本**上跑一次全量门禁（先设 `JAVA_HOME` / `ANDROID_HOME`）：

```powershell
.\scripts\check-all.ps1 -LlamaSourceRepository "<本地 llama.cpp 克隆>"
```

零失败、零警告才算过；`build/check-all/summary.md` 里记下测试数、lint 数、APK 大小与签名状态，
作为交付报告的证据。