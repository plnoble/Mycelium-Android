# 说明（2026-10-20 统筹裁定）

> 本文是开工前的方案稿，部分内容已被否决或改动，**以 docs/UPDATE_ARCHITECTURE.md 与 docs/reviews/2026-10-18-P2-2b/DELIVERY.md 为准**。
> 文中已否决的接口与退路用删除线标出，读到 ~~…~~ 请不要照它施工。

---

# P2-2b 第 1/3/4/5 步实施勘察（SURVEY）

- 任务书：`docs/work-packages/P2-2b-release-ready.md`（第 1、3、4、5 步）
- 既定方案：`docs/reviews/2026-10-18-P2-2b/PLAN.md`（对应四步）
- 分支 / 基准：`wp/P2-2b-release-ready`，HEAD `e8ade02`（第 2 步），工作区干净
- 勘察日期：2026-10-18（Asia/Shanghai）
- 本轮**只读**：不改源码/测试/构建文件，不跑 Gradle，不联网，不用设备

## 0. 现状基线（四步共用的事实，实施前请对照）

**已经做掉的步骤**（都已提交，本勘察不重复它们）：

- 第 2 步 `e8ade02`：`ReleaseInfo` 已扩成 `apkUrl/apkSizeBytes/sha256Url/releaseNotes/publishedAt`（都缺就都置空），新增 `UpdateError.kt`（`Network/RateLimited/Server/Malformed` + `UpdateCheckException` + `toUpdateError()`）。
- 第 6/7/8/9 步：`scripts/release-guard.sh`、`scripts/check-public.ps1`、`docs/RELEASING.md`、`docs/releases/v0.4.0.md`、`docs/releases/signing-cert-sha256.txt` 已在；`app/build.gradle.kts` 已是 `versionCode 5` / `versionName "0.4.0"`，release 已是 `abiFilters += "arm64-v8a"`。
- `docs/UPDATE_ARCHITECTURE.md` **已经**按最终做法写成中文（含逐跳白名单、续传、四项校验、安装、必须用户确认）。它是步骤 1/3/4/5 的既定设计，本勘察在它下面补「怎么落地、怎么测、哪里有坑」，有冲突的地方在风险里点名。

**模块与依赖**（`settings.gradle.kts`）：`:app`、`:contract`（目录 `contract-lib`）、`:net`、`:roundtable`、`:model-api`、`:llm`、`:hearth`。

- `:net` 是纯 Kotlin/JVM（`kotlin("jvm")` + `org.json`，**没有 coroutines、没有 Android**）；`roundtable` 依赖 `:net`；`:hearth` 不依赖 `:net`，由 `hearth`/`contract-lib` 的 `ModuleBoundaryCheck` 守住（hearth 不许直接或间接依赖 `:net`，也不许引入 HTTP 库）。
- 假服务器 `LocalHttp.kt` 在 `:net` 的 **main** 源码集里（不在 test 里），`:roundtable` 的测试也在用它 → 改它等于改生产代码，要照旧过 `allWarningsAsErrors`。

**网关现状**（`net/src/main/kotlin/com/xavierxia/mycelium/net/Gateway.kt`，281 行）：

- `send()` 把响应整段读成 `String`；`instanceFollowRedirects = false`；`AllowedHosts.UPDATE_HOSTS = {api.github.com, github.com, objects.githubusercontent.com}`（**缺** `release-assets.githubusercontent.com`）。
- 取消机制已经就绪且经过测例：`GatewayCall`（`cancel()` 先于 socket、`check()` 检查点、`attach()` 记住连接），`GatewayCancelled` 是统一的取消异常，且在 `catch` 里靠 `call.isCancelled` 翻译。
- `open: (URI) -> HttpURLConnection` 是构造参数 → 测试可以注入；`openHttp` 是全项目唯一开连接的地方。
- 审计 `AuditEntry(at, host, purpose, sentCharacters, sentBody, status, succeeded, promptTokens, completionTokens)`；`AuditLog` 按文件路径共享锁 + `.partial` 原子写；全仓只有 `Gateway.kt`（2 处，命名参数）和 `AuditLogTest.entry()` 构造它，androidTest 只读 → **加带默认值的字段是安全的**。
- `GatewayPurpose.recordsBody` 只对 `HearthChatTest` 返回 false；下载路径不会用到它（见 §1.2）。

**app 侧现状**：

- `update/` 只有 `ReleaseInfo.kt`、`UpdateError.kt`、`GitHubReleaseClient.kt`、`UpdateManager.kt`。`UpdateManager.checkForUpdate()` 是 `suspend` + `Dispatchers.IO`，把异常收敛成 `UpdateResult`。
- `ui/MyceliumApp.kt`（915 行）里：`MyceliumApp(updateManager: UpdateManager? = null)` 是唯一的测试注入口；`MineScreen`（362–447 行）用本地 `remember` 状态做手动检查，`UpdateText`（449–475 行）在有 `apkUrl` 时给「打开下载页面」按钮（`context.startActivity(ACTION_VIEW)`）。**没有**自动检查、没有红点、没有卡片、没有下载/校验/安装。
- 出网只有一个来源：`ChatService` 持有的 `AndroidGateway`（`chat.releaseSource()` 把 `Update` 用途的 `HttpGet` 交出去；`chat.audit()` 给隐私页读同一个 `privacy/audit.json`）。每次「云端模型」变更会重建 `AndroidGateway`，但 audit 指向同一文件。
- `AndroidManifest.xml`：`INTERNET`、`REQUEST_INSTALL_PACKAGES` **都已有**；`file_paths.xml` 的 `cache-path name="updates" path="updates/"` **已有**；没有 `<receiver>`。
- `MainActivity` 只有 15 行（`setContent { MyceliumApp() }`），没有 ViewModel。

**测试与门禁（会决定怎么写）**：

- `net/src/test`：JUnit 5（`kotlin("test")` + jupiter 6.0.3，`useJUnitPlatform()`）+ `@TempDir` + `LocalHttp`。
- `app/src/test`：JUnit 4 + Robolectric 4.17；Compose 测试用 `androidx.compose.ui.test.junit4.v2.createComposeRule`（`MyceliumAppTest`）/`createAndroidComposeRule<MainActivity>()`（`MainActivityTest`），`@Config(sdk = [35])`。**没有** `kotlinx-coroutines-test`（Gradle 缓存里只有 1.9.0，而 `coroutines-core` 缓存里有 1.7.3/1.8.1/1.9.0，解析版本不确定）→ 尽量不加依赖，可注入 `pause: (Long) -> Unit` 代替。
- Robolectric 4.17 里**确实有**（从 `shadows-framework-4.17.jar` 的符号表核到）：`ShadowPackageInstaller`（`createSession`/`openWrite`/`commit`/`getAllSessions`/`setSessionSucceeds|Fails|ActiveState|Progress`/`registerSessionCallback`，类里还有 `sendIntent`/`IntentSender`）、`ShadowPackageManager.getPackageArchiveInfo`/`setPackageArchiveInfo`/`setCanRequestPackageInstalls`、`ShadowStatFs.registerStats`。
- `scripts/check-all.ps1`：跑所有模块单测 + `:app:lintDebug`/`lintRelease` + `assembleDebug/Release/AndroidTest`；**lint 警告、构建输出里的 warning、Kotlin 警告全算失败**（app 的 `allWarningsAsErrors = true`，仓库里没有 `lint.xml`/baseline 关掉任何检查）。本机是 `--offline` 构建，Maven Central 连不上 → **新增依赖要慎之又慎**。

## 1. 第 1 步：网关通用文件下载（`:net`）

### 1.1 要新增/要改的文件与函数

**新增 `net/src/main/kotlin/com/xavierxia/mycelium/net/Download.kt`**

```kotlin
data class DownloadRequest(
    val url: String,
    val purpose: GatewayPurpose,
    val target: File,                       // 由调用方决定（app 传 cacheDir/updates/...）
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

不新增异常类型：白名单外仍然抛 `GatewayRejected("地址不在出网白名单里：<host>")`，取消仍然抛 `GatewayCancelled("请求已被取消")`（与 `send()` 一致，界面与审计都按现有规则处理）。

**新增 `net/src/main/kotlin/com/xavierxia/mycelium/net/FileDownloader.kt`**（传输之上的**策略**层，P2-5 直接复用）

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

`download` 的分工：网络类失败 → 退避重试（续传接着下）；`ChecksumMismatch` → **删文件 → 从 0 再下一次**（只重下一次，再不对就返回 `ChecksumMismatch`）；`Cancelled` → 立刻返回，**保留已写字节**。
（方案把网络重试放在第 4 步、把「校验不符重下」放在本类，两处不一致；见开放问题 6。）

**改 `net/.../Gateway.kt`**

- 新增接口并让现有类实现（**改一处类型、不动任何调用点**）：

```kotlin
fun interface HostPolicy { fun allows(target: URI, purpose: GatewayPurpose): Boolean }
class AllowedHosts(...) : HostPolicy { override fun allows(...) = ... }   // 方法体原样
class OutboundGateway(private val hosts: HostPolicy, ...)
```

- 新增两个函数（`send()` 一行不改，聊天/检查更新的出网语义与审计保持逐字不变）：

```kotlin
/** 边收边写文件，逐跳过白名单，可续传、可取消；返回后调用方自己决定内容对不对。 */
fun download(request: DownloadRequest, onProgress: DownloadProgress? = null): DownloadResult

/** 跟随跳转但把响应读进内存（有上限）。只给 ≤64 KiB 的元数据用，例如 .sha256（见 §4.2）。 */
fun readFollowingRedirects(request: GatewayRequest, limitBytes: Int = 64 * 1024): GatewayResponse
```

  两个函数共用一个私有 `follow(request.purpose, start, call): HttpURLConnection`：`instanceFollowRedirects = false` 保持不动，自己跟 301/302/303/307/308；**每一跳都 `hosts.allows(uri, purpose)`**；3xx **不读 body**、`disconnect()`、`redirects++`；`redirects > 5` 抛「跳转次数太多」；`call.check()` 在每个能发字节的点之前。
- `AllowedHosts`：`UPDATE_HOSTS += "release-assets.githubusercontent.com"`；`objects.githubusercontent.com` 保留（本次没测到，但不能证明不会用）；`github-releases.githubusercontent.com` **不加**（失败关闭）。

**改 `net/.../AuditLog.kt`**

```kotlin
data class AuditEntry(
    ...现有九个字段...,
    val hostPath: List<String> = listOf(host),   // 顺序 = 实际联系过的跳；缺省就是单跳
    val receivedBytes: Long? = null,
)
```

`toJson()` 写这两个字段；`toEntry()` 在字段缺失时回落（`optJSONArray("hostPath")` 为空/不存在 → `listOf(host)`，`receivedBytes` 缺失 → null）。老的 `privacy/audit.json` 必须还能读（照 `AuditLogTest` 的现有风格加一例）。

**改 `net/.../LocalHttp.kt`**（测试假服务器，唯一的假服务实现）

- `data class HttpReply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())`，保留 `HttpReply(status, body: String)` 重载；`serve()` 不再硬编码 `200 OK`，按状态给出 reason phrase 并真的写出 `headers`（`Content-Range`/`Content-Length`/`ETag`/`Location`）。
- `HttpCall.headers` 已经是小写 key 的 map → Range 测例在 handler 里自己实现 206/416 即可。

### 1.2 接口与约束

**「`:net` 独占 HTTP」怎么满足**：下载实现在 `:net`，走的就是 `OutboundGateway` + `openHttp`；`:net` 没有 Android、没有协程，所以 API 必须是**阻塞 + 回调 + `GatewayCall` 取消**（app 在 `Dispatchers.IO` 上调用）。app 侧怎么拿到它：新增 `app/.../update/UpdateTransport.kt`（接口，见 §4.1），实现类放在 **`:app`**（`app/.../update/GatewayUpdateTransport.kt`），底层复用 `ChatService` 已经持有的 `AndroidGateway.gateway`，由 `ChatService` 加一个 `fun updateTransport(): UpdateTransport` 暴露。
理由：**审计必须写进同一个 `privacy/audit.json`**（隐私页读 `chat.audit()`），不该为更新另建一套网关实例。代价是 `ChatService`（圆桌的类）多一个职责；更好的长期做法是把 `AndroidGateway` 的持有者提到 app 级（`AppGateway`），但那要改 `ChatService` 的网关重建逻辑，超出本步必要范围。P2-5 会再要一次同样的口子，届时可以一起收。

**审计记哪些字段**：一次下载 = 一条记录。

| 字段 | 值 |
|---|---|
| `at` | 下载开始（或结束）的时间，`clock()` |
| `host` | 起始域名（兼容隐私页现有显示） |
| `hostPath` | **实际联系过的每一跳域名**，按顺序（2 跳就是 2 项） |
| `purpose` | `Update`（P2-5 会是 `FetchModels`，API 里是参数，不写死） |
| `receivedBytes` | 本次写入的字节数（续传时是「本次」而非文件总长，总长在 `fileSize` 里但**不进审计**也可接受） |
| `status` | 最后一跳的 HTTP 状态；被拒/连不上为 `null` |
| `succeeded` | `status in 200..299` 且没有中途抛异常 |
| `sentCharacters` / `sentBody` | `0` / `""`（GET 无正文） |
| token 字段 | `null`（下载路径**不能**调 `tokenUsage()`——响应是二进制） |

**哪些情况不记正文**：下载路径在结构上就不读响应体（3xx 不读；2xx 边收边写文件），所以「内容不进审计」不是靠开关，而是没有代码路径能把内容写进去。这也是为什么 **不要复用 `send()` 来下载**：`send()` 会把 38 MiB 读成 `String`。

**白名单与实测链**：`github.com` → `release-assets.githubusercontent.com` 两跳，第二跳是带签名的临时地址（`sig`/`jwt`/`se`/`ske`，约 60 分钟有效期），实测见 `PLAN.md` §1.1 与 `docs/UPDATE_ARCHITECTURE.md` 第 1 节；本勘察不重复实测。要点：**续传时必须从原始 url 重新走一遍跳转**（临时地址会过期），所以 `DownloadRequest.url` 永远是 `github.com` 的资产地址，绝不复用上一轮 `finalUrl`。

**每跳只许 https 是谁的责任**：这是**策略**（`AllowedHosts`：`Update` 用途要求 `https`），不是下载器的硬编码。下载器的不变式只有一条：**每一跳都问一遍 `hosts.allows(uri, purpose)`**。这样 P2-5 的模型下载仍能在调试包里允许 `http://127.0.0.1`，而更新用途永远只许 https。测「http 跳转被拒」时要用真实 `AllowedHosts`（假服务器是 `http://127.0.0.1`，生产策略永远不会放行它）。

**续传细节**：

- `offset = if (resume) target.length() else 0`；`offset > 0` 时带 `Range: bytes=<offset>-`。
- **206**：核对 `Content-Range` 的起点 == `offset`（不等 → 按 200 处理，截断重写）。
- **200**：`target` 截断为 0、从 0 写、`restartedFromZero = true`。
- **416**：见风险 1（方案自相矛盾）。
- 写入用 `FileOutputStream(target, append = offset > 0)`，8–32 KiB 缓冲；每块 `call.check()` + 一回调；结束 `flush()` + `fd.sync()`。
- `Accept-Encoding: identity`（否则 `HttpURLConnection` 会自己加 gzip 并透明解压，`receivedBytes` 与 `Content-Length` 对不上，进度会跳）。
- 进度 `total`：200 时取 `Content-Length`；206 时取 `Content-Range` 的总长；拿不到就 `null`（不确定进度条）。

**并发/重入**：`OutboundGateway`/`FileDownloader` 都是阻塞且非线程安全的。加一个**按目标文件绝对路径的进程内锁**（照 `AuditLog` 里 `ConcurrentHashMap<String, Any>` + `computeIfAbsent` 的现成写法），同一 target 的第二个并发 `download()` 直接拒绝（返回 `Failed`/抛 `IllegalStateException`，实施时定一个并写进测例）。app 侧再保证同一时刻只有一个下载任务（`UpdateCenter` 持有单个 Job）。

### 1.3 可测性

全部 `net/src/test/kotlin/com/xavierxia/mycelium/net/DownloadGatewayTest.kt`（JUnit 5 + `@TempDir` + `LocalHttp`，**不联网**）。用 `HostPolicy { uri, _ -> uri.toString().startsWith(allowedPrefix) }` 精确构造「第 N 跳才出白名单」；用真实 `AllowedHosts` 测生产策略。

| # | 测例名 | 断言 |
|---|---|---|
| 1 | `downloadWritesBodyThroughOneRedirect` | 跳 1 = 302 到本地、跳 2 = 1 MiB 二进制 → 文件逐字节相等、`hosts.size == 2`、`redirects == 1`、`bytesWritten == 文件长` |
| 2 | `downloadRejectsRedirectOutOfWhitelist` | 跳 2 不在策略 → `GatewayRejected`、**目标文件没有被创建** |
| 3 | `downloadRejectsWhenTheThirdHopLeavesWhitelist` | 前两跳放行、第三跳不放行 → 拒绝，`hostPath` 只含已联系的两跳 |
| 4 | `downloadRejectsCleartextRedirectUnderTheUpdatePolicy` | 真实 `AllowedHosts`：起始 `http://` 直接拒；再一例：跳 2 是 `http://` 时拒 |
| 5 | `downloadRejectsMoreThanFiveRedirects` | 连续 302 → 拒绝；把「假服务器收到几次请求」写进断言（含起始那次 = 5，按实现锁定后在注释里写清） |
| 6 | `downloadResumesFromExistingBytes` | 预置 300 KiB、服务器回 206 + `Content-Range: bytes 307200-1048575/1048576` → 文件 1 MiB、内容正确、服务器收到的 `Range` 恰是 `bytes=307200-` |
| 7 | `downloadRestartsFromZeroWhenServerIgnoresRange` | 服务器无视 Range 回 200 全量 → 文件从 0 重写、`restartedFromZero`、长度等于全量 |
| 8 | `downloadTreatsASatisfiedRangeHintAsAlreadyComplete` | 416 + `Content-Range: bytes */N` 且 `offset == N` → 不写、状态 416（`FileDownloader` 之后按 sha256 决定留还是删） |
| 9 | `downloadRewalksRedirectsOnResume` | 预置半截后服务器**换了新的跳转目标** → 跳 1 的计数 +1（证明没有复用旧地址） |
| 10 | `cancelKeepsPartialFile` | 写 200 KiB 后 `call.cancel()` → `GatewayCancelled`、文件长度在 200 KiB 附近且 ≠ 0 |
| 11 | `cancelBeforeOpenNeverConnects` | `cancel()` 后再 download → 假服务器计数 0（照现有 `aCancelledRequestNeverOpensAConnection` 的手法） |
| 12 | `downloadAuditRecordsHostPathAndBytesWithoutContent` | 恰好 1 条审计：`hostPath` 两个域名、`receivedBytes == 文件长`、`status == 200`、`sentBody == ""`，且 `audit.json` 文本里**不含**下载内容的特征串 |
| 13 | `auditReadsEntriesFromBeforeTheNewFields` | 手写旧格式 JSON → `read()` 成功、`hostPath == listOf(host)`、`receivedBytes == null` |
| 14 | `allowedHostsCoversTheMeasuredChainAndRefusesTheRest` | `https://github.com/...`、`api.github.com`、`release-assets.githubusercontent.com`、`objects.githubusercontent.com` 通过；`http://github.com/...`、`https://evil.example/...`、`https://github.com.evil.example/...` 拒绝 |
| 15 | `fileDownloaderDeletesAndRedownloadsOnChecksumMismatch` | 预置被污染的续传文件 + 期望 sha256 → 删 → 从 0 下一次 → `Completed`，磁盘上只剩一个正确文件 |
| 16 | `fileDownloaderGivesUpAfterASecondMismatch` | 两次都对不上 → `ChecksumMismatch`、文件已删 |
| 17 | `fileDownloaderRetriesNetworkFailureThenResumes` | 第一次中途断 → `pause` 记录 2000 ms；第二次带 `Range` 成功；`pause` 不真睡 |
| 18 | `fileDownloaderRefusesASecondConcurrentDownloadOfTheSameTarget` | 并发重入被拒，第二次没有写文件 |

**必须真机**：真实 DNS/TLS/IPv6 下的跳转域名链（`PLAN.md` §1.1 的本机实测经过代理，手机侧路径可能不同）、弱网/超时下的续传与重试、真实 CDN 的 Range/ETag 行为。真机走查时把实际域名记进交付报告。

### 1.4 风险与取舍

1. **416 的处理方案自相矛盾**：方案第 5 条一处写「416 → 截断重下」，另一处写「`Content-Range: bytes */N` 且 `offset == N`（文件已完整）→ 直接返回」。建议：能解析出 `bytes */N` 且 `offset == N` → 视为「服务器认为已完整」，交给 `FileDownloader` 的 sha256 判定（对不上就删了从 0 下）；其它 416 → 截断重下。（开放问题 2）
2. **gzip 与 `Content-Length`**：不加 `Accept-Encoding: identity` 时 `bytesWritten` 与 `Content-Length` 会不一致。APK 的 content-type 本来不会被压缩，但别赌。
3. **`readTimeout` 不是总时长**：它是两次读之间的上限，服务器慢慢滴字节可以让下载无限拖。要不要加「停滞检测」（例如 120 s 没有新字节 → 失败留给续传）→ 开放问题 3。
4. **续传的正确性兜底只有 sha256**：方案完全没提 `ETag`/`If-Range`。文件名带 tag 已经排除「把 A 的半截续成 B」；但同一个 tag 下附件被重新上传（GitHub 允许覆盖资产）时，续传拼出来的包 sha256 必错 → 多花一次全量流量。实测响应里有强 `ETag`，可以选择性地在 `.part` 旁边记一份 ETag/Last-Modified 下标、续传时发 `If-Range`（实体变了服务器会回 200，天然不拼接）。建议做，但把它当优化而不是正确性依赖。（开放问题 4）
5. **失败保留半截文件**：这是续传的前提，代价是 `cacheDir/updates` 会有垃圾；靠第 4 步的「文件名带 tag」+ 启动清理兜住。
6. **`HostPolicy` 放宽构造参数**：收益是「逐跳白名单」能被真测（假服务器 `http://127.0.0.1` 永远进不了生产 `AllowedHosts` 的 `Update`）。代价是 `OutboundGateway` 的构造参数类型变了 —— 实际很小：`AllowedHosts` 原地 `: HostPolicy` + `override`，`AndroidGateway` 与 `roundtable`/`hearth` 的测试都传 `AllowedHosts`，不用改。替代方案（给 `AllowedHosts` 加测试专用开关）会在生产类里留一个只为测试存在的口子，更差。**同意方案的这一取舍**。
7. **`send()` 保持不动**：跳转与流式只在新函数里。副作用是 `.sha256` 必须换新函数取——**方案 4.1 写的「从 `api.github.com` 下载 `.sha256`」是错的**：资产的 `browser_download_url` 在 `github.com/.../releases/download/...`，会 302 到 `release-assets`；用现在的 `send()`（不跟跳转）只会拿到 302 空体。见 §4.2。
8. **不加依赖**：`:net` 保持无协程 → 阻塞 + 回调。P2-5 复用时自己起线程/前台服务。

## 3. 第 3 步：自动检查

### 3.1 要改/要新增的文件与函数

**新增 `app/src/main/java/com/xavierxia/mycelium/update/UpdatePreferences.kt`**

```kotlin
class UpdatePreferences(context: Context) {          // SharedPreferences("updates", MODE_PRIVATE)
    var autoCheck: Boolean      // 默认 true
    var lastCheckAtMs: Long     // 默认 0
    var ignoredTag: String?     // 默认 null
}
```

照 `CloudSettings.kt` / `LocalModelsViewModel` 的 `androidx.core.content.edit { }` 写法；**不是** SecretStore（这些不是个人数据）。写 `lastCheckAtMs` 只在**一次检查真正跑完**之后（失败不写，否则一次网络抖动会让下一次自动检查推迟 24 小时）。

**新增 `app/.../update/UpdateCheckPolicy.kt`**

```kotlin
object UpdateCheckPolicy {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000
    /** 时钟回拨（now < last）视为到期：宁可多查一次，也不卡死。 */
    fun isDue(nowMs: Long, lastCheckAtMs: Long): Boolean = nowMs - lastCheckAtMs >= INTERVAL_MS || nowMs < lastCheckAtMs
}
```

**新增 `app/.../update/UpdateCenter.kt`**（一条链路的状态持有者；名字可用 `UpdateViewModel`，但见 3.2 的 ViewModel 结论）

```kotlin
data class UpdateUiState(
    val checking: Boolean = false,
    val release: ReleaseInfo? = null,        // 已被忽略的版本不进这里（或另存 ignored 标记）
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
    fun checkIfDue()                      // 启动时调；内部先看 autoCheck / isDebugPackage / isDue
    fun checkNow()                        // 手动按钮，不看节流，错误要显示
    fun ignore(tag: String)
    fun setAutoCheck(on: Boolean)
    fun startDownload(); fun cancelDownload(); fun install(); fun onResume()   // onResume 用于未知来源返回与安装结果复查
}
```

**改 `app/.../ui/MyceliumApp.kt`**

- `fun MyceliumApp(updateCenter: UpdateCenter? = null)`：**保留**旧的 `MyceliumApp(updateManager: UpdateManager?)` 重载（`MyceliumAppTest` 在用），内部包一层 fake/最小 center。
- 第 100–106 行的 `NavigationBarItem`：`Space.Mine` 的 icon 包 `BadgedBox { Badge { } ; Text("我") }`，仅当「有新版且未被忽略」时显示小圆点（不带数字）。
- `MineScreen`（362–447 行）：顶部插入卡片 —— 版本号、安装包大小（`apkSizeBytes` → MiB 一位小数；null 显示「大小未知」）、`releaseNotes`（纯文本，超过约 6 行折叠 +「展开」）、按钮「下载并安装」「忽略这个版本」；缺 `apkUrl`/`sha256Url` 时只给「打开发布页」。卡片下面加 `Switch`「自动检查更新」+ 一行「上次检查：yyyy-MM-dd HH:mm」/「还没检查过」，保留现有「检查更新」按钮与错误显示。
- `UpdateText`（449–475 行）：`Available` 不再走浏览器打开 APK；`updateErrorText()` 保留。
- **不弹窗**（任务书明确）：红点 + 卡片，不用 Snackbar/AlertDialog。

### 3.2 接口与约束

- **触发**：`LaunchedEffect(Unit) { center.checkIfDue() }`（或 `MainActivity.onCreate`）；`UpdateManager.checkForUpdate()` 已经在 `Dispatchers.IO`，不会挡首帧。
- **调试包**：不要在逻辑里读 `BuildConfig.APPLICATION_ID`（Robolectric 的 applicationId 与真机不同，测不了）→ `isDebugPackage` 作为构造参数注入，生产在 `MainActivity` 里算一次 `BuildConfig.APPLICATION_ID.endsWith(".debug")`。调试包：不自动检查、无红点、无「下载并安装」；手动检查保留（显示版本 + 发布页链接）。
- **失败不打扰**：`error` 只在 `manual == true` 时填。
- **忽略**：只存一个 tag。判定用现成的 `VersionComparator.isNewer(release.versionName, ignoredVersion)`（比版本号，不是比 tag 字符串），`release.tagName == ignoredTag` 或 `isNewer(...) == false` → 不提示。
- **ViewModel 与「离开页面继续」**：结论是 **不要在 Compose 里用 `viewModel()`** —— `MyceliumAppTest` 用的是 `createComposeRule()`（`...junit4.v2`），它不提供 `ViewModelStoreOwner`，`viewModel()` 会抛。正确做法：`MainActivity` 里 `private val center by viewModels { UpdateCenter.factory(application) }`，把 center 传进 `MyceliumApp`；配置变更/切底栏时 ViewModel 存活（下载继续），`onCleared` 时 `scope.cancel()`（进程被杀自然停，下次续传）。测试直接构造一个用 `TestScope`/`CoroutineScope(Dispatchers.Unconfined)` 的 center 传进去。
- 存储只用普通偏好；`versionCode 5`/`0.4.0` 已提交，实施中不要动。

### 3.3 可测性

`app/src/test/java/com/xavierxia/mycelium/update/`（JUnit 4 + Robolectric）与 `.../ui/`（Compose，照 `MyceliumAppTest` 的 import）：

| # | 测例名 | 断言 |
|---|---|---|
| 1 | `checksOnStartWhenNeverChecked` | 假 `ReleaseSource` 计数 == 1 |
| 2 | `doesNotCheckTwiceWithin24Hours` | `lastCheckAtMs = now - 23h` → 计数 0 |
| 3 | `checksAgainAfter24Hours` | `now - 25h` → 计数 1 |
| 4 | `checksWhenTheClockMovesBackwards` | `lastCheckAtMs` 在未来 → 仍然查（计数 1） |
| 5 | `manualCheckIgnoresTheSchedule` | 23 h 内点按钮 → 计数 1 |
| 6 | `autoCheckOffSkipsTheAutomaticCheckButManualStillWorks` | 开关 false → 启动不查、点按钮查 |
| 7 | `ignoredVersionIsNotShownAgain` | 无红点、无卡片 |
| 8 | `newerVersionAfterIgnoreIsShownAgain` | 更大的版本 → 红点与卡片回来 |
| 9 | `failedAutomaticCheckShowsNoError` | 假源抛 `IOException` → 界面没有错误文案 |
| 10 | `manualCheckShowsHumanReadableErrors` | 403 → 出现「检查太频繁，稍后再试」，**不出现** `HTTP 403` / `GitHub returned` |
| 11 | `debugPackageNeverAutoChecksAndHasNoInstallEntry` | 注入 `isDebugPackage = true` → 计数 0、无「下载并安装」 |
| 12 | `releaseWithoutChecksumShowsTheReleasePageOnly` | 只给发布页按钮 |
| 13 | `lastCheckTimeIsDisplayed` | 固定时间戳 → 出现对应文本（未检查过 → 「还没检查过」） |
| 14 | `failedCheckDoesNotPushTheNextCheckTwentyFourHoursAway` | 失败后 `lastCheckAtMs` 未变 |
| 15 | `UpdatePreferencesTest`：`defaultsAreAutoCheckOnAndNeverCheckedAndNothingIgnored` / `valuesSurviveANewInstance` | 默认值与跨实例持久化 |

**必须真机**：红点与卡片在真机深色主题/大字体下的观感；自动检查对冷启动首帧的实际影响（logcat/`am start -W`）。

### 3.4 风险与取舍

1. **`MainActivityTest` 会被真的自动检查打到**：`MainActivityTest` 用 `createAndroidComposeRule<MainActivity>()`，`MainActivity` 一建 center 就会 `checkIfDue()` → Robolectric 下真发网络请求（会失败/变慢）。**`MainActivity` 的 center factory 必须可替换**（例如 `internal var centerFactory` 或把 transport 作为 factory 参数），否则这个已有测例会红。这一条要早定。（开放问题 5）
2. **`LaunchedEffect(Unit)` 每次重建都调一次 `checkIfDue()`**：有 24 h 节流，只多一次内存判断；但界面状态必须在 ViewModel 里（不能在 Compose `remember`），否则重建会把卡片打回原形。
3. **注入签名变更**是本步最大的破坏面（`MyceliumAppTest`、`MainActivityTest`）→ 保留旧重载或同步改这两个文件，实施时一起提交。
4. 不做后台定时检查、不自动下载（任务书明确）：代价是「打开才知道有新版」，用户手册里别写成「自动更新」。

## 4. 第 4 步：下载与校验

### 4.1 要改/要新增的文件与函数

**新增 `app/.../update/UpdateTransport.kt`**（app 侧的出网口子；`ChatService` 提供实现）

```kotlin
interface UpdateTransport {
    fun downloadApk(url: String, target: File, call: GatewayCall,
                    progress: (written: Long, total: Long?) -> Unit): DownloadOutcome
    fun fetchChecksumText(url: String, limitBytes: Int = 4 * 1024): String   // 走 readFollowingRedirects
    fun sha256(file: File): String
}
```

实现 `app/.../update/GatewayUpdateTransport.kt`（`constructor(gateway: OutboundGateway, fileDownloader: FileDownloader = FileDownloader(gateway))`）；`ChatService` 加 `fun updateTransport(): UpdateTransport`。

**新增 `app/.../update/UpdateFiles.kt`**

```kotlin
class UpdateFiles(private val root: File /* cacheDir/updates */) {
    fun partFile(tag: String): File        // mycelium-<tag>.apk.part
    fun finalFile(tag: String): File       // mycelium-<tag>.apk
    fun promote(tag: String): File         // .part -> 最终名（Files.move + REPLACE_EXISTING）
    fun deleteForTag(tag: String)
    fun purge(nowMs: Long, keepTag: String?): List<File>   // >7 天；keepTag 的 .part 不动
    fun freeBytes(): Long                  // StatFs(root).availableBytes（Robolectric 可假）
    fun requireSpace(apkSizeBytes: Long, margin: Long = MARGIN_BYTES)   // 不够抛 SpaceShortage(needed, actual)
}
```

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

`MyceliumApp.kt`：进度（`LinearProgressIndicator`，total 未知用不确定态）、下载中按钮变「取消」（`call.cancel()`）、失败文案。

### 4.2 接口与约束

- **目录与命名**（照 `docs/UPDATE_ARCHITECTURE.md` 第 4 节）：`cacheDir/updates/`，`mycelium-<tag>.apk.part` → `mycelium-<tag>.apk`。`file_paths.xml` 已有，不用改；`PackageInstaller` 会话不需要 FileProvider（但该 provider 声明保持不动，出网/存储范围之外）。
- **`.sha256` 必须走跳转**（§1.4.7）：资产地址在 `github.com`，302 到 `release-assets.githubusercontent.com`。用 `OutboundGateway.readFollowingRedirects(GatewayRequest(url = sha256Url, purpose = Update), limitBytes = 64 * 1024)`；超过上限 → 当「校验文件异常」拒绝（不要把它当文本整段读）。用途仍是 `Update`，审计是**独立一条**（一次安装共两条：APK + 校验文件）。
- **四项校验**依次做（便宜的在前面）：sha256 → 包名 → versionCode → 签名。签名读取可能失败，失败与不一致给两种文案，都**不放过**。
- **签名只和当前装着的 App 比**（`getPackageInfo(context.packageName, GET_SIGNING_CERTIFICATES)` 的 `signingInfo.apkContentsSigners[0].toByteArray()` 的 SHA-256），**不**硬编码 `docs/releases/signing-cert-sha256.txt` 里的指纹；理由与取舍已写在 `docs/UPDATE_ARCHITECTURE.md` 第 4 节（那份指纹是 CI 闸门用的，App 里再写一份会在以后合法换签名时把用户挡死）。
- **版本号**：`PackageInfo.longVersionCode`（minSdk 29）或 `PackageInfoCompat.getLongVersionCode`。
- **空间**：`freeBytes() > apkSizeBytes + 32 MiB`（`ModelRegistry.import` 的现成形状是 `usableSpace > size + 16 MiB`）。`apkSizeBytes == null` 时用下限（例如 64 MiB）并在界面显示「大小未知」；不够就拦住并说清差多少。**用 `StatFs(root).availableBytes` 而不是 `File.usableSpace`**：Robolectric 有 `ShadowStatFs`，而 `File.usableSpace` 在 JVM 测试里拿不到可控值。
- **清理**：安装成功/用户取消/放弃 → 删该 tag；启动时 `purge(now, keepTag = 当前目标 tag)` 删 >7 天的，**当前 `.part` 不动**；换 tag 时删别的 tag 的 `.part`。
- **重试**：网络类失败最多 3 次、退避 2s/5s/10s、续传接着下；`ChecksumMismatch` 不重试（只按 §1.1 从 0 再下一次）。退避等待必须**可取消**（点「取消」要立刻中断），用协程 `delay` 或切成小片检查 `call.isCancelled`，不要裸 `Thread.sleep`。
- **生命周期**：任务挂在 `UpdateCenter`（Activity ViewModel），不用前台服务；进程被杀 → `.part` 留着下次续。

### 4.3 可测性

| # | 测例名 | 断言 |
|---|---|---|
| 1 | `checksumTextAcceptsTheTwoSpaceForm` | `hash  mycelium-v0.4.0.apk` → hash |
| 2 | `checksumTextAcceptsTheGitBashAsteriskForm` | `hash */d/.../app-release.apk` → hash（取第一个 64 位 hex，忽略后面一切） |
| 3 | `checksumTextRejectsTextWithoutAHash` | 空/短串/含非 hex → null |
| 4 | `verifierAcceptsAMatchingApk` | 四项全对 → `Verified` |
| 5 | `verifierRejectsChecksumMismatchAndDeletesTheFile` | 文件被删、返回 `ChecksumMismatch` |
| 6 | `verifierRejectsForeignPackageName` | `PackageNameMismatch` + 删 |
| 7 | `verifierRejectsDifferentSigner` | `SignerMismatch` + 删 |
| 8 | `verifierSeparatesUnreadableSignerFromMismatch` | `SignerUnreadable`（同样的失败关闭，不同文案） |
| 9 | `verifierRejectsSameOrLowerVersionCode` | `==` 与 `<` 都拒 |
| 10 | `spaceShortageBlocksTheDownloadAndSaysHowMuchIsMissing` | `ShadowStatFs` 假值 → 出现差值文案，且**没有发起下载** |
| 11 | `downloadResumesAfterAFailureAndKeepsTheBytes` | 第一次失败留 `.part`，第二次带 `Range` 续 |
| 12 | `networkFailureRetriesAtMostThreeTimesWithBackoff` | 注入 `pause` 收到 2000/5000/10000 ms，且不真睡 |
| 13 | `checksumFailureIsNotRetried` | 只下两次（原始 + 从 0 重下），没有第 3 次全量 |
| 14 | `partialFileOfAnotherTagIsDeletedWhenANewTagStarts` | 旧的 `.part` 被删 |
| 15 | `purgeDeletesFilesOlderThanSevenDays` | 老的 `.apk`/`.part`/`.sha256` 都没了 |
| 16 | `purgeKeepsTheCurrentPartialFile` | 当天的 `.part` 还在（续传前提） |
| 17 | `installedVersionCodeComesFromTheCurrentApp` | `InstalledAppFacts` 假实现被调用一次 |

**必须真机**：真实 APK 上 `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` 在各 ROM 上的表现（v0.1.1 是 v2-only 签名）、38 MiB 的 sha256 在真机上的耗时（决定是「边下边算」还是「下完再算」）、`cacheDir` 被系统回收时的表现。

### 4.4 风险与取舍

1. **`apkSizeBytes` 可能为 null**：空间检查与「大小」显示都依赖它 → 定好回退（下限值 + 「大小未知」）。
2. **`cacheDir` 会被系统回收**：`.part` 或下好的 APK 可能在安装前被清掉 → 交给 `PackageInstaller` 之前必须重新 stat（存在且长度对），不在就重下或报错，**不能拿路径直接开会话**。
3. **sha256 的算法位置**：方案的「下完整文件再哈希」会多读 38 MiB。更好的做法：从 0 下载时边写边算；续传时先哈希已有前缀再继续（读盘量 = 已下载量）。建议按这个实现，把「整文件哈希」留作退化实现。（开放问题 6）
4. **校验失败一律删文件**：安全，但用户要重下 38 MiB；文案要说明「不是网络问题，是包不对」。
5. **重试与取消的交互**：见 4.2（退避必须可中断）。
6. **32 MiB 余量对 ~38 MiB 的包偏薄**（安装会话还要再复制一份）→ 建议 `max(32 MiB, apkSize/4)`。（开放问题 7）
7. **调试包不给安装入口**：第 4 步的安装路径在调试包里不可达；设备走查时注意别把「调试包不安装」和「校验」混着测。

## 5. 第 5 步：安装

### 5.1 要改/要新增的文件与函数

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

**新增 `app/.../update/InstallResultReceiver.kt`** + `AndroidManifest.xml` 加

```xml
<receiver android:name=".update.InstallResultReceiver" android:exported="false" />
```

**改 `UpdateCenter`**：`install()` 编排「未知来源 → 会话 → 状态 → 收尾」；`onResume()` 复查 `longVersionCode`（**权威判定**：用户取消了还是装上了，不依赖广播到不到）；成功 → 清 `updates/` 该 tag、重置 `lastCheckAtMs`、清 `ignoredTag`、提示「更新完成，下次打开生效」（不自动重启）。

**改 `MyceliumApp.kt`**：未知来源说明对话框 + `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`（文件里已有 `rememberLauncherForActivityResult` 与 `ActivityResultContracts` 的用法可照抄）+ 成功/失败/取消/空间不足的结果提示。

### 5.2 接口与约束

- **会话**：`PackageInstaller.createSession(SessionParams(MODE_FULL_INSTALL))` → `openWrite("base.apk", 0, size)` → 用只读输入流 + 8 KiB 缓冲拷贝（**不要 `File.readBytes()`**，38 MiB 不进内存）→ `fd.sync()` → `commit(pendingIntent)`。`SessionParams` 里已废弃的 setter 不要用（Kotlin 废弃警告 = 构建失败）；`MODE_FULL_INSTALL` 不需要 `setAppPackageName` 之类。
- **结果接收：方案这里有两个真坑，必须处理**：
  1. lint：`UnspecifiedImmutableFlag` 要求 `PendingIntent` 明确给 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`；`MutableImplicitPendingIntent` 又禁止「隐式 Intent + `FLAG_MUTABLE`」。仓库 `warningsAsErrors = true` + check-all 把 lint 警告算失败 → 写错就是红。
  2. 语义：现代 Android 上系统要把 `EXTRA_STATUS`（以及 `STATUS_PENDING_USER_ACTION` 的 `EXTRA_INTENT`）填进这个 Intent，`FLAG_IMMUTABLE` **有可能拿不到 extras**（社区反复出现的现象，必须真机验）。
  建议实现：**显式 Intent（指定 component + package）+ 清单里 `exported="false"` 的接收器 + `FLAG_MUTABLE`** —— 显式 Intent 不会撞 `MutableImplicitPendingIntent`，`exported=false` 也能被 `PendingIntent` 以本应用身份送达，且进程被回收后结果仍能带回来。备选：动态 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` + 隐式 action + `FLAG_IMMUTABLE`（不会撞 lint，但要真机确认 extras 到得了、且进程被杀就丢了结果）。（开放问题 8）
- **取 Intent extra 要用 `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)`**（targetSdk 37 上 `getParcelableExtra(String)` 已废弃）。状态码用 `PackageInstaller.EXTRA_STATUS`。
- **状态到文案**：`STATUS_SUCCESS`；`STATUS_FAILURE_ABORTED`（用户取消，且清理文件）；`STATUS_FAILURE_BLOCKED`（安装被阻止，常见于未知来源没开/设备策略）；`STATUS_FAILURE_CONFLICT`（版本号未增长，与第 4 步的 versionCode 校验是同因异径）；`STATUS_FAILURE_INCOMPATIBLE`；`STATUS_FAILURE_INVALID`；`STATUS_FAILURE_STORAGE`（单独「空间不够」文案）。未知码走 `Failed(code)`。
- **「安装未知应用」**：`packageManager.canRequestPackageInstalls()` 为 false → 先弹一次**说明**（为什么要：把下载好的安装包交给系统安装器）→ 用户确认后 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + `Uri.parse("package:$packageName")` → 回来（`StartActivityForResult` 回调 / `onResume`）复查 → 允许了继续装，没允许就停在说明上。**只带路，不代开**。
- **Manifest**：`REQUEST_INSTALL_PACKAGES` 已在（第 3 行），不用加；不需要 FileProvider/URI 授权（会话是我们自己写字节）。
- **调试包**：不暴露安装入口、不做未知来源引导（第 3 步已保证）。

### 5.3 可测性

`app/src/test/java/com/xavierxia/mycelium/update/PackageInstallFlowTest.kt`（只测状态机与编排，`InstallSessionGateway` 用假实现）：

| # | 测例名 | 断言 |
|---|---|---|
| 1 | `asksForUnknownSourcesPermissionBeforeCreatingASession` | `canRequestPackageInstalls() = false` → 只跳设置、没建会话 |
| 2 | `startsTheSessionAfterTheUserGrantsUnknownSources` | 回来后 true → 建会话 |
| 3 | `pendingUserActionLaunchesTheSystemConfirm` | 假会话回 `PendingUserAction(intent)` → 断言这个 Intent 被交给 launcher |
| 4 | `successClearsTheUpdatesDirectoryAndResetsTheIgnoredVersion` | 该 tag 文件没了、`ignoredTag == null`、`lastCheckAtMs` 被重置 |
| 5 | `insufficientStorageShowsItsOwnMessage` | 单独文案 |
| 6 | `userCancellationShowsCancelledAndCleansUp` | 「已取消，安装包已清理」+ 文件被删 |
| 7 | `failuresGetDistinctChineseMessages` | blocked/conflict/incompatible/invalid 各一条 |
| 8 | `versionCodeChangeOnResumeIsTheFinalConfirmation` | 广播没到但 `onResume` 读到新 versionCode → 判定成功 |
| 9 | `debugPackageHasNoInstallEntry` | 无入口、无未知来源引导 |

Robolectric 层面（**已核实 4.17 里有这些影子**）还能加：`ShadowPackageInstaller` 的 `getAllSessions()`/`SessionInfo` 验证会话参数与写入量、`setSessionSucceeds|Fails|ActiveState` 驱动状态分支、`ShadowPackageManager.setCanRequestPackageInstalls`/`setPackageArchiveInfo` 造假事实、`ShadowStatFs.registerStats` 造空间。**但编排仍要留在 `InstallSessionGateway` 后面**：`commit` 把 PendingIntent 送回来这件事与 ROM/版本相关，别把状态机绑死在影子行为上。

**必须真机**（红魔，调试包；**不碰手机上装着的正式版 0.1.1**）：会话能建、系统确认界面能被拉起（截图到此为止）、`FLAG_MUTABLE` 与 `FLAG_IMMUTABLE` 哪个能收到 `EXTRA_STATUS`、`EXTRA_INTENT` 能否取到、未知来源设置页的跳转与返回、安装成功后 versionCode 变了、取消后的提示与清理。假源用 `adb reverse` 指本机假服务（与 update 检查同一手法）。

### 5.4 风险与取舍

1. **PendingIntent 可变性**（5.2）：本步最可能翻车的一处，必须真机验；即使广播收不到，`onResume` 比对 versionCode 也能给出「装上了/没装上」的结论，不会静默失败。
2. **会话要再占一份磁盘**（`PackageInstaller` 的接口决定的，无法避免）；`STATUS_FAILURE_STORAGE` 要有单独文案。
3. **必须有用户确认**：实际是「点两下」（App 一下、系统界面一下），文案与文档不要写成「一键更新」；`docs/UPDATE_ARCHITECTURE.md` 第 5 节已写明，实施时保持。
4. **进程被杀**：清单接收器能在进程被回收后把结果带回来，动态接收器不行 —— 这是推荐清单接收器的主要理由；代价是 Manifest 多一个组件（`exported=false`，不增加攻击面）。
5. **`commit` 后用户一直不点安装就回家**：会话会挂在系统里。要有兜底：回到前台读 versionCode 判定并清理；必要时 `abandonSession`。任务书没写，需要有意识地决定。（开放问题 9）

## 6. 全局约束（最容易踩的门禁）

- **字符串与 lint**：删掉「浏览器打开 APK」这条路径后，`strings.xml` 的 `update_open_apk` 会变成 `UnusedResources`（仓库没有 lint baseline，`warningsAsErrors` + check-all 把 lint 警告算失败）→ 要么删掉字符串，要么保留「打开发布页」的兜底入口（开放问题 10）。实施时以 `lintRelease` 实跑为准。
- **新增文案建议名**（一次加齐，避免反复）：`update_auto_check`、`update_last_check_never`、`update_last_check`、`update_size_unknown`、`update_notes_expand`/`update_notes_collapse`、`update_download_install`、`update_ignore_version`、`update_cancel`、`update_downloading`、`update_space_short`（需要 %1$s，可用只有 %2$s）、`update_verify_checksum_failed`/`update_verify_package_failed`/`update_verify_signer_failed`/`update_verify_signer_unreadable`/`update_verify_version_failed`、`update_install_unknown_sources_title`/`_body`/`_go`、`update_install_success`/`update_install_cancelled`/`update_install_blocked`/`update_install_storage`/`update_install_failed`。
- **API 用法**：`ContextCompat.registerReceiver`、`IntentCompat.getParcelableExtra`、`PackageInfoCompat.getLongVersionCode`，别用已废弃的直调（废弃警告 = 构建失败）。
- **测试目录约定**：`net/src/test` 是 JUnit 5（`useJUnitPlatform()`）；`app/src/test` 是 JUnit 4 + Robolectric，Compose 用 `androidx.compose.ui.test.junit4.v2.*`。别把 JUnit 5 的断言写进 app 测试。
- **不新增依赖**（本机离线构建）：退避等待用注入的 `pause` 或协程 `delay`；确实要用 `kotlinx-coroutines-test` 的话，先 `./gradlew :app:dependencies --offline` 确认解析到的版本与缓存一致。

## 7. 给 Codex 方案审的开放问题

1. 被白名单拒掉的那一跳要不要也写进审计？现在只写「实际联系过的」域名（与隐私页「出网记录」的语义一致），但用户可能想知道「有一次下载想跳到 X 被拒了」。
2. 416 的处理（方案两处不一致）：`offset == N` 且 `Content-Range: bytes */N` 时算「已完整」交给 sha256，其余 416 一律截断重下 —— 同意吗？
3. 要不要加「停滞检测」（例如 120 s 没有新字节就判失败、留给续传）？不加则 `readTimeout` 只能防「彻底不动」。
4. 续传要不要发 `If-Range: <ETag>`（同 tag 下资产被重新上传时服务器回 200，避免拼接出错误的前缀）？方案完全没提 ETag，而实测响应里有强 ETag。
5. `UpdateCenter` 挂 `MainActivity` 的 ViewModel 后，`MainActivityTest` 会被真的自动检查打到 —— 注入工厂怎么设计才不动（或少动）现有两个 UI 测试？
6. sha256 算在哪里：**边下边算 + 续传只哈希已有前缀**（推荐，省一次 38 MiB 读盘），还是方案里的「下完再整文件哈希」？另外「网络重试」放 `:net` 的 `FileDownloader`（P2-5 可复用）还是放 app 的第 4 步编排（方案现在的分法把两种重试拆在两处）？
7. 空间余量取固定 32 MiB 还是 `max(32 MiB, apkSize/4)`？~38 MiB 的包 + 安装会话再一份，32 MiB 偏薄。
8. `PendingIntent` 用「清单接收器 + `FLAG_MUTABLE`」还是「动态接收器 + `FLAG_IMMUTABLE`」？两条都要在真机上验 `EXTRA_STATUS` 到不到。
9. `commit` 之后用户一直不点安装（会话悬挂）：要不要超时清理/`abandonSession`？任务书没写。
10. 「失败关闭」与兜底的边界：跳转出现未实测域名（第 1 步）、或签名读不出（第 4 步）时，卡片上要不要给一个「打开发布页」的次要入口（方案开放问题 7）？给了就顺带解决 `update_open_apk` 的 lint 问题。
11. `AuditEntry` 加了 `hostPath`/`receivedBytes` 之后，隐私页要不要显示跳转链与字节数（现在只显示一个 host + 字数）？不显示就等于白记。

## 8. 本勘察校对过的基线（实施时可直接对照）

- `docs/work-packages/P2-2b-release-ready.md` 的步骤 1/3/4/5；`docs/reviews/2026-10-18-P2-2b/PLAN.md` 的同名四节；`docs/UPDATE_ARCHITECTURE.md`（已按最终做法写好）。
- `net/.../Gateway.kt`、`AuditLog.kt`、`LocalHttp.kt`、`net/src/test/.../GatewayTest.kt`、`AuditLogTest.kt`。
- `app/.../update/{ReleaseInfo,UpdateError,GitHubReleaseClient,UpdateManager}.kt`、`ui/MyceliumApp.kt`、`MainActivity.kt`、`roundtable/ChatService.kt`、`net/AndroidGateway.kt`、`localmodels/LocalModelsViewModel.kt`、`llm/AndroidModelStore.kt`、`llm/ModelRegistry.kt`。
- `AndroidManifest.xml`、`res/xml/file_paths.xml`、`res/values/strings.xml`、`app/build.gradle.kts`、`net/build.gradle.kts`、`settings.gradle.kts`、`scripts/check-all.ps1`、`app/src/test/**`（三个 update 测试与两个 UI 测试）。
- Gradle 缓存（只读列目录/读 jar 符号）：Robolectric 4.17 的 `shadows-framework` 里有 `ShadowPackageInstaller`/`ShadowPackageManager`/`ShadowStatFs`；`kotlinx-coroutines-test` 只有 1.9.0。
- 未做：任何源码/测试/构建文件的修改，Gradle 运行，网络访问，设备操作。