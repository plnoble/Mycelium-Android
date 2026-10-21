# 说明（2026-10-20 统筹裁定）

> 本文是开工前的方案稿，部分内容已被否决或改动，**以 docs/UPDATE_ARCHITECTURE.md 与 docs/reviews/2026-10-18-P2-2b/DELIVERY.md 为准**。
> 文中已否决的接口与退路用删除线标出，读到 ~~…~~ 请不要照它施工。

---

# P2-2b 发版准备：实施方案（开工前方案，交 Codex 方案审）

- 任务书：`docs/work-packages/P2-2b-release-ready.md`
- 分支：`wp/P2-2b-release-ready`（从 P2-2 提交 `a196aed` 拉出，本机 `main` = `0f3dcff`，`origin/main` = `3c7074c`）
- 方案日期：2026-10-18（Asia/Shanghai）
- 本轮范围：**只写方案 + 两项开工前实测**。不改代码、不跑 Gradle、不推送、不打标签、不发版。
- 目标版本：`0.4.0` / `versionCode 5`（**本轮不改**，在步骤 8 实施时改）

## 0. 一句话方案

把「出网下载」做成 `:net` 里的通用按流下载（逐跳过白名单、可续传、可取消、有审计），
把「检查—提示—下载—校验—安装」做成 `app/update/` 里的一条链路，
把「发版」的签名/版本号/发版说明检查收进 `scripts/release-guard.sh` 由 `release.yml` 调用，
再补 `scripts/check-public.ps1` 挡住公开前的敏感内容，最后发 `0.4.0`。

## 1. 开工前实测

两项都已完成，环境：Windows，curl 8.21.0（Schannel），Android build-tools 36.0.0，实测时间 2026-10-18 22:40–22:50（Asia/Shanghai）。

### 1.1 实测 A：GitHub 下载跳转链（真实 v0.1.1 发布资产）

**资产命名规则的来源（两个都核过）**：

- `.github/workflows/release.yml`（v0.1.1 当时的版本，用 `git show v0.1.1:.github/workflows/release.yml` 核过）：
  `cp app/build/outputs/apk/release/app-release.apk "mycelium-${GITHUB_REF_NAME}.apk"`，
  然后 `sha256sum "mycelium-${GITHUB_REF_NAME}.apk" > "mycelium-${GITHUB_REF_NAME}.apk.sha256"`。
- `api.github.com/repos/plnoble/Mycelium-Android/releases` 的真实返回：
  - `v0.1.1`：`mycelium-v0.1.1.apk`（23004794 B）、`mycelium-v0.1.1.apk.sha256`（86 B），发布时间 2026-10-09T21:17:40Z；
  - `v0.1.0`：同名规则。
  - 即 `mycelium-v<版本>.apk` / `mycelium-v<版本>.apk.sha256`，与任务书步骤 2 要求的按名查找一致。

**请求的起始地址**（真实地址）：
`https://github.com/plnoble/Mycelium-Android/releases/download/v0.1.1/mycelium-v0.1.1.apk`

**逐跳结果**（`curl.exe -sSIL`，HEAD 与 GET 结果一致；`-w "%{http_code} %{url_effective} %{remote_ip}"`）：

| 跳 | 域名 | 状态码 | remote_ip | 说明 |
|---|---|---|---|---|
| 1 | `github.com` | **302** | `2001:2::7c` | `Location:` 指向 `release-assets.githubusercontent.com`（无中间跳） |
| 2 | `release-assets.githubusercontent.com` | **200** | `2001:2::7d` | 带签名的临时地址，`Content-Length: 23004794` |

- **只有两跳**。没有出现 `objects.githubusercontent.com`（白名单里已有的那个），也没有 `github-releases.githubusercontent.com`。
- 第 2 跳的地址形如
  `https://release-assets.githubusercontent.com/github-production-release-asset/1391539911/1f606abf-975e-42da-82a9-f42658a602e2?sp=r&sv=2018-11-09&sr=b&spr=https&se=2026-10-18T15%3A28%3A17Z&rscd=attachment%3B+filename%3Dmycelium-v0.1.1.apk&rsct=application%2Fvnd.android.package-archive&skoid=…&sktid=…&skt=2026-10-18T14%3A27%3A51Z&ske=2026-10-18T15%3A28%3A17Z&sks=b&skv=2018-11-09&sig=…&jwt=…&response-content-disposition=…&response-content-type=…`
  → **是带签名的临时地址**：`sig=` + `jwt=` + `se`/`ske`（过期时间）。实测 `skt=14:27:51Z`、`ske=se=15:28:17Z`，**有效期约 60 分钟**。
- 第 2 跳响应头（对续传很关键）：
  - `Accept-Ranges: bytes`
  - 实测 `Range: bytes=0-1023` → **206 Partial Content**，`Content-Range: bytes 0-1023/23004794`
  - `Content-Type: application/vnd.android.package-archive`，`Last-Modified: Fri, 09 Oct 2026 21:17:40 GMT`，`ETag: "0x8DF264ABFFF5F7F"`
- 本机 `remote_ip` 是 IPv6（`2001:2::/32` 段，不像是家宽直连），像是经过了代理/中转；**手机上的解析路径可能不同，上线前要用手机侧日志再确认一次域名**（见「不确定」一节）。
- 实测中的坑（写进实施注意）：
  - curl 直接跑会报 `schannel: CRYPT_E_REVOCATION_OFFLINE`，本机连不上 CA 的吊销检查端点；本次用 `--ssl-revoke-best-effort`（等价 `--ssl-no-revoke`）绕过才拿到链。**这不影响 App（Android 用自己的 TLS 栈）**，但说明本机网络对 TLS 辅助请求不稳定。
  - 23 MB 的下载实测里，3 次尝试中有 2 次出现 `(28) Connection timed out` / `(35) SSL/TLS handshake failed`，必须 `--retry 5 --retry-all-errors` 才成功。→ 印证任务书风险栏「国内可能慢或连不上」，**下载器必须自带重试与续传**。

**对白名单的直接结论**：`Update` 用途必须补 `release-assets.githubusercontent.com`；
`objects.githubusercontent.com`（已有）本次未出现，先保留；`github-releases.githubusercontent.com` 未实测到，**不加**（见步骤 1 取舍）。

### 1.2 实测 B：v0.1.1 签名证书 SHA-256

- 下载：真实 `mycelium-v0.1.1.apk` 下到 **仓库之外** 的 `%TEMP%\p2-2b-cert\`（已确认该目录不在仓库内）。
- 一致性：下载文件 `size = 23004794`，本地 `sha256 = 1a21350d267321a9255c1d252a4eac50fa183e043f67434a7d68a337ce490ead`，与发布的 `.sha256` 内容（86 B：`1a21350d…ead  mycelium-v0.1.1.apk`）**逐字符一致**。
- 取证命令：`"%ANDROID_HOME%\build-tools\36.0.0\apksigner.bat" verify --print-certs --verbose <apk>`（先补 `JAVA_HOME`，本机 `apksigner.bat` 缺 `JAVA_HOME` 时会直接报错退出）。

| 项 | 值 |
|---|---|
| **build-tools 版本** | **36.0.0** |
| 签名方案 | v1 = false，**v2 = true**，v3/v3.1/v4 = false |
| 签名者数 | 1 |
| 证书 DN | `CN=Mycelium Android Release, OU=Release, O=XavierXia, C=CN` |
| **证书 SHA-256 指纹** | **`bb72a9c7e71137dd62558a9a644c1b5a44eae8501d31ee27138887835ba7fe68`** |
| 证书 SHA-1 | `f64c6a2e4e4af9d956eb858238bfe66ff7c619bc` |
| 公钥算法 / 长度 | RSA / 4096 bit |
| 公钥 SHA-256 | `333493573189a0e3f15244465e46d2f4b9f639b46e3ecb05ab217abe90dd5b76` |

- 干净性：取完指纹后 `%TEMP%\p2-2b-cert` **整个目录已删除**（`Test-Path` 为 false）；`git ls-files` 中没有任何 `.apk` / `.gguf` / `.jks` / `.keystore`；本轮**没有提交任何 APK**。
- 待办：这个指纹要在步骤 6 写进 `docs/releases/signing-cert-sha256.txt`（本轮不写，实施时写）。

### 1.3 实测中顺带确认的本机事实（会影响实施与验收）

1. **`JAVA_HOME`、`ANDROID_HOME` 在当前 shell 里都是空的**。`scripts/check-all.ps1` 第 133–147 行会在开头直接 `Stop-WithError`。→ 最终门禁跑之前必须显式设置这两个环境变量（SDK 在 `%LOCALAPPDATA%\Android\Sdk`，Java 用 Android Studio 自带 JBR）。
2. **WSL 的 `bash`（`C:\Windows\system32\bash.exe`）本机起不来**（`E_ACCESSDENIED`，WSL2 HCS 报错）。可用的 bash 是 **Git for Windows 的 `bash.exe`**（`Get-Command bash` 找不到它，需要走 `git.exe` 同级的 `bin\bash.exe`）。→ 步骤 6 的「本地演示 `release-guard.sh`」用 Git Bash，已验证可跑 `apksigner.bat`、`sha256sum`、`git`。
3. **本机 build-tools 36.0.0 只有 `apksigner.bat`，没有 POSIX 的 `apksigner` 包装脚本**。→ `release-guard.sh` 必须同时支持 `$ANDROID_HOME/build-tools/*/apksigner`（CI/Linux）和 `apksigner.bat`（本机 Git Bash），否则本地演示做不了。
4. **Git Bash 的 `sha256sum` 输出与 Linux 不同**：本机实测为 `79b4256c…85736 */d/Agent/.../app-debug.apk`（**带 `*` 且是绝对路径**），而 CI（Linux）和已发布的 v0.1.1 文件是 `1a21350d…ead  mycelium-v0.1.1.apk`（两个空格）。→ App 解析 `.sha256` 时必须「取第一个 64 位十六进制串」，忽略后面的一切，否则本地生成的校验文件会让 App 校验失败。
5. **正式包改前的实测基线**（读现有产物，未跑 Gradle）：
   - `app-release-unsigned.apk` = **52.34 MiB**；`app-debug.apk` = 59.59 MiB；`app-debug-androidTest.apk` = 3.13 MiB。
   - release 包里的非 arm64 原生库：`armeabi-v7a/libsqlcipher.so` 3472 KiB、`x86/libsqlcipher.so` 4803 KiB、`x86_64/libsqlcipher.so` 5607 KiB，另加三个 `libandroidx.graphics.path.so`（7/9/11 KiB）→ 合计约 **13.58 MiB**。
   - 预计步骤 7 改后 release 包 ≈ **38–39 MiB**（AGP 8 默认 `useLegacyPackaging=false`，`.so` 在 APK 里不压缩存储，所以接近 1:1 减少）；交付报告以实际构建为准。
6. 占比注意：v0.1.1 的 APK 只有 21.9 MiB（没有 llama.cpp），0.4.0 会因为本地模型引擎大一倍多。→ 步骤 4 的「空间检查」和步骤 3 的「不用流量自动下载」两条设计是对的，不要省。

---

## 步骤 1：网关能下载文件（`:net`）

### 怎么做

**新增文件**

- `net/src/main/kotlin/com/xavierxia/mycelium/net/Download.kt`
  - `data class DownloadRequest(url, purpose, target: File, headers: Map<String,String> = emptyMap(), connectTimeoutMs = 15_000, readTimeoutMs = 60_000, resume: Boolean = true, call: GatewayCall = GatewayCall())`
  - `data class DownloadResult(status: Int, finalUrl: String, hosts: List<String>, bytesWritten: Long, fileSize: Long, startedAt: Long, restartedFromZero: Boolean, redirects: Int)`
  - `fun interface DownloadProgress { fun onProgress(written: Long, total: Long?) }`（`total` 未知时为 `null`）
- `net/src/main/kotlin/com/xavierxia/mycelium/net/FileDownloader.kt`
  - `class FileDownloader(private val gateway: OutboundGateway)`，`download(request, expectedSha256: String? = null, onProgress: DownloadProgress? = null): DownloadOutcome`
  - 负责「续传 → 校验 → 不符则删掉重下」的**策略**（任务书把它写在步骤 1 里），传输细节交给网关。
  - `sealed interface DownloadOutcome { data class Completed(result); data class ChecksumMismatch(...); data class Rejected(message); data class Cancelled }`（具体形状实现时定，但要能区分「被白名单拒绝 / 取消 / 校验不符 / 网络失败」）。

**改 `Gateway.kt`**

- 新增 `OutboundGateway.download(request, onProgress): DownloadResult`，与现有 `send()` 并列：
  1. `parse(url)` 复用；**每一跳都用 `hosts.allows(uri, request.purpose)` 重新检查**，用途不变；
  2. `instanceFollowRedirects = false` 保持不动，自己跟：状态码 301/302/303/307/308 → 取 `Location`，**不读响应体**，`disconnect()`，`redirects++`；`redirects > 5` 抛「跳转次数太多」；
  3. 白名单外 → 抛 `GatewayRejected("地址不在出网白名单里：<host>")`，并在抛之前把**已经联系过的跳的域名**写进审计；
  4. 到 2xx 才开始写文件：`connection.inputStream` → `FileOutputStream(target, append = resume && offset > 0)`，8–32 KiB 缓冲；每块写完后 `call.check()`；每块回调一次进度；
  5. 续传：`offset = if (resume) target.length() else 0`；`offset > 0` 时带 `Range: bytes=<offset>-`：
     - 收到 **206** → 校验 `Content-Range` 的起始等于 `offset`（不等就当成 200 处理：截断重下）；
     - 收到 **200**（服务器不支持 Range）→ `target` 截断为 0，从 0 写，`restartedFromZero = true`；
     - 收到 **416** → 截断重下；
     - 服务器给 `Content-Range: bytes */N` 且 `offset == N`（文件已完整）→ 直接返回，不再写。
  6. 取消与失败：`catch` 里若 `call.isCancelled` → 抛 `GatewayCancelled("请求已被取消")`（与 `send()` 一致）；失败时**保留已写下的字节**（这是续传的前提），调用方决定删不删。
  7. 审计：**每次下载记一条**，`hostPath` = 实际联系过的每一跳域名（含被拒的那一跳不算，因为没发出去），`status`、`receivedBytes`、`succeeded`；`sentBody = ""`（GET 无正文）。
- `AllowedHosts`：
  - `UPDATE_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")`（新增项来自 1.1 实测）。
  - 为了能测「逐跳白名单」，把 `OutboundGateway` 的构造参数类型从 `AllowedHosts` 放宽为 `interface HostPolicy { fun allows(target: URI, purpose: GatewayPurpose): Boolean }`，`AllowedHosts` 实现它。**（取舍见下）**
- `AuditLog.kt`：`AuditEntry` 增加两个字段，均带向后兼容默认值：
  - `hostPath: List<String> = listOf(host)`（旧记录读出来就是 `[host]`）
  - `receivedBytes: Long? = null`
  - JSON 读写：字段缺失时回落默认值，**保证老的 `privacy/audit.json` 不会让隐私记录页崩**。

### 怎么测

`net/src/test/kotlin/com/xavierxia/mycelium/net/DownloadGatewayTest.kt`（`:net` 是 JVM 模块，JUnit 5 + `kotlin.test`，与 `GatewayTest.kt` 同风格）。先把测试用的假服务器扩出来：

- `LocalHttp` 现状只能回「一个 UTF-8 字符串 + 固定 `OK` + 无自定义头」，二进制和 Range 都做不了。改造（保持旧构造可用，避免动到现有测例和零警告门禁）：
  - `HttpReply(status: Int, body: ByteArray, headers: Map<String,String> = emptyMap())` + 保留 `String` 重载；
  - 允许按路径路由（`LocalHttp(routes: Map<String, (HttpCall) -> HttpReply>)` 或用现有 `handler` 自己判断 path）；
  - handler 能看到请求头 → Range/续传测例自己实现。
- 测试用的策略：`HostPolicy { uri, _ -> uri.toString().startsWith(allowedPrefix) }`，这样「第二跳出白名单」「http 跳转被拒」都能精确构造，而不用真的换域名。

测例（名字即断言点）：

1. `downloadsFileThroughRedirectAndWritesBytes`：跳 1 回 302 到本地服务器，跳 2 回 1 MiB 假二进制 → 文件字节逐字节相等，`hosts` 有 2 项，`redirects == 1`。
2. `rejectsRedirectOutOfWhitelist`：跳 2 的地址不在策略里 → `GatewayRejected`，且**目标文件没有被创建**。
3. `rejectsWhenSecondHopLeavesWhitelist`：跳 1 允许、跳 2 允许、跳 3 不允许 → 拒绝，`hostPath` 只含已联系的两跳。
4. `rejectsCleartextRedirect`：策略只放行 https 前缀（模拟生产 `AllowedHosts`）→ 起始 `http://` 直接拒；另加一条：跳 2 是 `http://` 时拒。
5. `rejectsTooManyRedirects`：6 次连续 302 → 「跳转次数太多」，第 6 跳没有发出请求（假服务器计数为 5 或 6，按实现锁定并写清）。
6. `resumesFromExistingBytes`：先写 1 MiB 文件的前 300 KiB，服务器返回 206 + `Content-Range: bytes 307200-1048575/1048576` → 结果文件长度 1 MiB、内容正确、服务器收到的 `Range` 头是 `bytes=307200-`。
7. `restartsFromZeroWhenServerIgnoresRange`：同样预置 300 KiB，服务器无视 Range 回 200 全量 → 文件从 0 重写，`restartedFromZero == true`。
8. `resumesByRewalkingRedirects`：预置部分文件后，服务器**换了新的跳转地址**（模拟签名过期后 `Location` 变化）→ 断言客户端又走了跳 1（假服务器上跳 1 的计数 +1），而不是复用旧地址。
9. `deletesAndRedownloadsWhenChecksumMismatches`（`FileDownloader`）：预置被污染的续传文件 + 期望 sha256 → 断言「删除 → 从头再下一次 → 结果 sha256 正确」，且失败的那次不会把结果留在磁盘上。
10. `checksumMismatchAfterFullRedownloadFailsCleanly`：两次都对不上 → 抛/返回 `ChecksumMismatch`，文件被删除。
11. `cancelStopsDownloadAndKeepsPartialFile`：写 200 KiB 后 `call.cancel()` → `GatewayCancelled`，文件长度在 200 KiB 附近不为 0（可续传）。
12. `cancelBeforeOpenNeverConnects`：`cancel()` 后再 download → 假服务器计数为 0。
13. `auditRecordsHostPathSizeAndStatusWithoutBody`：跑一次带跳转的下载 → `AuditLog.read()` 恰好 1 条，`hostPath` 含两个域名，`receivedBytes == 文件大小`，`status == 200`，`sentBody` 为空；且**文件内容的十六进制/ASCII 不在审计 JSON 里**。
14. `auditParsesOldEntriesWithoutNewFields`：手写一份旧格式 `audit.json`（无 `hostPath`/`receivedBytes`）→ `read()` 成功且 `hostPath == [host]`。
15. `allowedHostsCoversMeasuredChainAndRefusesOthers`（纯策略测例）：`Update` 用途下 `https://github.com/...`、`https://api.github.com/...`、`https://release-assets.githubusercontent.com/...`、`https://objects.githubusercontent.com/...` 通过；`http://github.com/...`、`https://evil.example/...`、`https://github.com.evil.example/...` 拒绝。

### 风险与取舍

- **`AllowedHosts` → `HostPolicy` 接口**：好处是重定向白名单能被真正测到（否则本地 http 假服务器永远进不了 `Update` 白名单）。代价是 `OutboundGateway` 的构造签名变了（调用点只有 `AndroidGateway` 和测试，改动小）。替代方案是给 `AllowedHosts` 加「测试用构造参数」，那样生产类里会留下只为测试存在的开关，更差。
- **`objects.githubusercontent.com` 保留、`github-releases.githubusercontent.com` 不加**：实测只看到 `release-assets`。加未实测的域名等于放宽出网面，不加则是**失败关闭**——策略从「尽量能下」改成「只下已确认的地址」。代价：GitHub 若再换域名，应用内下载会被拒；兜底是错误提示里给「用浏览器打开发布页」（见步骤 3）。
- **续传保留半截文件**：好处是不浪费已下载流量；代价是磁盘上会留下垃圾，靠步骤 4 的清理规则和「文件名带 tag」兜住（否则可能把 A 版本的半截文件续成 B 版本 → 见步骤 4）。
- **sha256 重下策略放在 `FileDownloader` 而不是网关**：网关只做传输，策略可被 P2-5 直接复用（模型下载同样是「按流写文件 + 校验 + 续传」）。代价是多一层调用。
- **审计新增字段**：`AuditEntry` 是 `data class`，加字段会让 `copy`/解构的调用点需要跟着改；已确认仓库内只有 `AuditLog.kt` 和隐私记录页读它（实施时再全仓 grep 一次）。
- **不读重定向响应体**：GitHub 的 302 正文很短，但按「不把内容读进内存」的原则就该不读；已写进设计。

---

## 步骤 2：读发布信息（`GitHubReleaseClient`）

### 怎么做

- `ReleaseInfo` 扩成：
  `data class ReleaseInfo(versionName, tagName, releasePageUrl, apkUrl: String?, apkSizeBytes: Long?, sha256Url: String?, releaseNotes: String, publishedAt: String?)`
  —— `apkUrl` 可为空（缺资产时）。
- `GitHubReleaseClient.parseRelease()`：
  - 期望的文件名由 tag 推出来：`apk` = `mycelium-v<tag去v>.apk`，`sha256` = 同上 + `.sha256`；但**先按 tag 拼、找不到再退化为「名字以 `mycelium-v<版本>.apk` 开头」**（防 GitHub 对附件的重命名/去空格策略变化），**不再取「第一个 `.apk`」**；
  - 同时读 `assets[].size`、`body`；
  - `apkUrl == null || sha256Url == null` → 返回 `ReleaseInfo` 但两个 URL 都置空，UI 只显示「有新版本 + 发布页链接」。
- 错误改成人话（新增 `sealed interface UpdateError`，UI 只认类型不认英文原文）：
  - 连不上 / 超时（`IOException`）→ `Network`「连不上 GitHub，检查网络后再试」；
  - **403 / 429** → `RateLimited`「检查太频繁，稍后再试」；
  - 其他非 200 → `Server(status)`「GitHub 暂时不可用（HTTP xxx）」；
  - JSON 解析失败 / 缺字段 → `Malformed`「GitHub 返回的内容看不懂，稍后再试」；
  - `latestRelease()` 的 404 仍然返回 `null` → `NoPublishedRelease`。
- `UpdateManager.checkForUpdate()` 的 `UpdateResult.Error(message: String)` 换成 `Error(error: UpdateError)`。

### 怎么测

`app/src/test/java/com/xavierxia/mycelium/update/GitHubReleaseClientTest.kt`（JUnit4 + Robolectric 无需，纯 JVM 即可；`org.json` 已是 testImplementation）：

1. `selectsAssetByExactTagName`：真实形状的 `releases/latest` JSON（`tag_name: v0.4.0`、`assets` 里同时塞 `.sha256`、`.apk`、`checksums.txt`）→ 选中的是 `mycelium-v0.4.0.apk`，`sha256Url` 指向 `mycelium-v0.4.0.apk.sha256`，`apkSizeBytes` 等于 JSON 里的 `size`，`releaseNotes` 等于 `body`。
2. `assetOrderDoesNotMatter`：把 `.apk` 排在 `.sha256` 之后、再放一个 `mycelium-android-0.4.0.apk` 干扰项 → 仍然选对。
3. `missingChecksumAssetDisablesInAppInstall`：只有 `.apk` → `sha256Url == null`。
4. `missingApkAssetDisablesInAppInstall`：只有 `.sha256` → `apkUrl == null`。
5. `rateLimitIsReportedAsRateLimited`：403 与 429 都映射到 `RateLimited`（且 `UpdateResult.Error` 里不再出现 `GitHub returned HTTP`）。
6. `notFoundIsNoPublishedRelease`：404 → `null`。
7. `serverErrorIsReportedWithStatus`：500/503 → `Server(500)`。
8. `malformedJsonIsReportedAsMalformed`：`200` + `not json` / 缺 `tag_name` → `Malformed`。
9. `networkFailureIsReportedAsNetwork`：`throw IOException("Connection timed out")` → `Network`（`assertThrows` 的行为被 `UpdateResult.Error` 取代，旧测例要跟着改）。
10. `userAgentAndApiVersionHeadersUnchanged`：保留现有断言，防止顺手改坏。

### 风险与取舍

- **按名查找 vs 兼容旧命名**（方案审必须修改项 6 已改口径）：只认忽略大小写的**精确**文件名，前缀/包含匹配**全部删掉**——`mycelium-v<版本>.apk.asc`、`.sig`、`.apk.sha256.asc`、`.apk.backup.apk` 都只比目标名字长一点，前缀匹配正好会把它们选成 APK/校验文件；「后面解析一定失败」也不是理由（别的文件同样可能带 64 位十六进制文本）。代价是 GitHub 真对附件改名时会「找不到资产」，按缺资产处理（只给发布页、不给应用内安装）——宁可失败关闭。测例锁定 `.asc`/`.sig`/备份包/别的后缀/别的版本/大小写都不越界。
- 文案与错误类型分开，是为了以后改文案不用碰解析逻辑；代价是多一层类型。

---

## 步骤 3：自动检查

### 怎么做

- 存储 `app/src/main/java/com/xavierxia/mycelium/update/UpdatePreferences.kt`（SharedPreferences，文件名 `updates`，**不是**加密库/SecretStore）：
  - `autoCheck: Boolean = true`、`lastCheckAtMs: Long = 0`、`ignoredTag: String? = null`；
  - 存的是「上次检查时间」和「忽略的版本」，都不是个人数据。
- 节奏 `UpdateCheckPolicy`：`fun isDue(nowMs: Long, lastCheckAtMs: Long) = nowMs - lastCheckAtMs >= 24h`；`nowMs < lastCheckAtMs`（用户改过系统时间）视为**到期**（宁可多查一次，也不会卡死）。手动检查不看到期，直接查。
- 检查入口：`MyceliumApp` 根部 `LaunchedEffect(Unit)`（或在 `UpdateViewModel.init` 里）触发一次自动检查，跑在 `Dispatchers.IO`，**不阻塞首帧**；失败只写内部状态，不在「我的」以外显示（手动检查才显示错误）。
- 调试包：`BuildConfig.APPLICATION_ID.endsWith(".debug")` → 不自动检查、不显示红点、不给安装按钮；**手动检查仍然可用**（只显示有没有新版 + 发布页链接）。
- 忽略：`ignoredTag == release.tagName` → 不提示；`VersionComparator.isNewer(新 tag, 忽略 tag)` 为真 → 重新提示（即「出了更新的版本再提示」；简单做法：只存一个 tag，遇到更新的版本时 tag 不相等自然重新提示）。
- UI（`MyceliumApp.kt`）：
  - 底栏「我」→ `BadgedBox` + 小圆点（有新版且未被忽略时才显示；不显示数字）；
  - `MineScreen` 顶部卡片：版本号、安装包大小（MiB 一位小数）、更新说明（纯文本，超过约 6 行折叠 + 「展开」）；按钮「下载并安装」「忽略这个版本」；**不弹窗**；
  - 「我的」加开关「自动检查更新」（默认开）+ 一行「上次检查：yyyy-MM-dd HH:mm」（未检查过就显示「还没检查过」）+ 保留现有「检查更新」按钮。
- 状态持有 `app/src/main/java/com/xavierxia/mycelium/update/UpdateViewModel.kt`：`AndroidViewModel`（和 `HearthViewModel`/`LocalModelsViewModel` 同一模式），暴露 `StateFlow<UpdateState>`，下载任务跑在 `viewModelScope`：
  - ViewModel 在 Activity 存活期内不随切换底栏销毁 → 满足「离开这个页面，下载继续」；
  - Activity 结束时 `onCleared` → 任务随进程结束 → 满足「App 被关掉就停，下次接着下」；
  - `MyceliumApp(updateManager: UpdateManager? = null)` 这个测试注入口要保留，改成同时接受一个可注入的 `UpdateCenter`/factory（现有 `MyceliumAppTest` 依赖它）。
  - 取舍：如果 Robolectric + `viewModel()` 在 `createComposeRule()` 下不好用，退路是根部 `remember { UpdateCenter(...) }` + `DisposableEffect`（代价：旋屏会中断下载，靠步骤 4 的续传兜底）。实施时先试 ViewModel。

### 怎么测

`app/src/test/java/com/xavierxia/mycelium/update/` 下新增 `UpdateCheckPolicyTest.kt`、`UpdatePreferencesTest.kt`；`app/src/test/java/com/xavierxia/mycelium/ui/UpdateFlowTest.kt`（Robolectric + Compose，照 `MyceliumAppTest` 的写法）：

1. `checksOnStartWhenNeverChecked`：假源计数 = 1。
2. `doesNotCheckTwiceWithin24Hours`：写入「上次检查 = 现在 - 23h」→ 假源计数 = 0。
3. `checksAgainAfter24Hours`：`-25h` → 计数 = 1。
4. `checksWhenClockMovesBackwards`：`lastCheckAtMs` 在未来 → 仍然查。
5. `manualCheckIgnoresSchedule`：23h 内点按钮 → 计数 = 1。
6. `autoCheckOffSkipsAutomaticCheckButManualStillWorks`：开关关掉 → 启动不查、点按钮查。
7. `ignoredVersionIsNotShownAgain`：命中忽略 tag → 无红点、无卡片。
8. `newerVersionAfterIgnoreIsShownAgain`：新 tag 更大 → 提示回来。
9. `failedAutomaticCheckShowsNoError`：假源抛 `Network` → 界面没有错误文案（只有手动点过才出现）。
10. `manualCheckShowsHumanReadableErrors`：403 → 出现「检查太频繁，稍后再试」，且**不出现** `HTTP 403`、`GitHub returned` 这类原文。
11. `debugPackageDoesNotAutoCheck`（用 Robolectric 的 `applicationId` 覆盖或把判断抽成可注入的函数）→ 计数 = 0，且没有「下载并安装」。
12. `releaseWithoutChecksumShowsReleasePageOnly`：卡片只给发布页链接，没有安装按钮。
13. `lastCheckTimeIsDisplayed`：写入固定时间戳 → 界面出现对应文本。

### 风险与取舍

- **不做后台定时检查、不自动下载**（任务书明确）：好处是不耗流量、不打扰；代价是「打开 App 才知道有新版」。用户点开就查（24h 节流），够用。
- **检查放在根部 `LaunchedEffect`**：代价是每次 Activity 重建都会调一次 `isDue`（有节流，不是每次真查）；好处是不引入 WorkManager 依赖。
- **忽略只存一个 tag**：简单、可解释（「忽略的就是这一个版本」）；代价是忽略多个历史版本不可能（也不需要）。
- 红点用「不打断」的方式而不是 Snackbar/弹窗，符合任务书「不弹窗」。

---

## 步骤 4：下载和校验

### 怎么做

- 目录：`cacheDir/updates/`（`file_paths.xml` 已经有 `cache-path name="updates" path="updates/"`，不用改）。
  **文件名带 tag**：`mycelium-<tag>.apk.part` → `mycelium-<tag>.apk`（下完改名）。这样续传只可能续同一个版本，不会把 A 的半截文件续成 B。
- 空间检查（下载前）：`StatFs(cacheDir).availableBytes` 与 `apkSize + 32 MiB` 余量比较；不够就中止并显示「需要 X MiB，可用只有 Y MiB」。余量要给安装阶段留位置（`PackageInstaller` 会再复制一份，见步骤 5）。
- 下载：`FileDownloader.download(...)`，进度回调驱动 Compose 的 `LinearProgressIndicator`（未知总长时用不确定进度）；按钮在下载中变「取消」。
- 校验 `ApkVerifier`（下完依次四项，任何一项不过 → **删除文件** + 中文说明）：
  1. sha256：`api.github.com` 下载 `.sha256`（86 B 文本，同一个网关、`Update` 用途）→ **取第一个 64 位十六进制串**（兼容 Git Bash 的 `hash *path` 形式）；与本地按流计算的 `sha256(file)` 比较（55 MB 文件一次流式哈希，实测成本可接受）；
  2. 包名：`packageManager.getPackageArchiveInfo(path, 0)?.packageName == context.packageName`；
  3. 签名证书：`getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)?.signingInfo.apkContentsSigners` 的第一个签名者字节的 SHA-256，与**当前安装的 App**（`getPackageInfo(context.packageName, GET_SIGNING_CERTIFICATES)`）比对；
  4. `versionCode`：APK 的 `longVersionCode >` 当前安装版本的 `longVersionCode`。
- 清理：
  - 安装成功、用户取消、放弃 → 删 `updates/` 里该 tag 的文件；
  - 启动时清理：**按年龄**（默认 > 7 天）删 `updates/*`，**不动当天的 `.part`**（否则续传永远没用）——这一条是实现中最容易写错的地方，要有专门测例。
- 失败重试：网络类失败自动重试（最多 3 次，退避 2s/5s/10s），续传接着下；非网络类失败（校验不过）不重试，直接报错。

### 怎么测

`app/src/test/java/com/xavierxia/mycelium/update/ApkVerifierTest.kt`（Robolectric，`PackageManager` 的 `getPackageArchiveInfo` 需要 shadow；若 Robolectric 的 shadow 覆盖不到 `signingInfo`，就把「取包名/签名/版本号」抽成一个 `fun interface ApkFactsReader`，真机实现走 PackageManager，测试注入假事实）：
1. `acceptsMatchingApk`：四项全对 → 通过。
2. `rejectsChecksumMismatch`：只错 sha256 → 拒绝 + 文件被删。
3. `rejectsForeignPackageName`。
4. `rejectsDifferentSigner`：证书 SHA-256 不同 → 拒绝 + 删文件。
5. `rejectsSameOrLowerVersionCode`：相等与更小都拒绝。
6. `parsesChecksumFileWithTwoSpaces`、`parsesChecksumFileFromGitBash`（`hash *path` 形式）、`rejectsChecksumFileWithoutHash`。
7. `deletesFilesOlderThanSevenDaysOnStart`、`keepsFreshPartialFileOnStart`（续传前提）。
8. `newTagDropsPartialFileOfAnotherTag`。
9. `freeSpaceShortageBlocksDownloadWithNumbers`：`StatFs` 假值 → 出现差值文案，且没有发起下载。

### 风险与取舍

- **先哈希整文件再比对**（而不是边下边算）：多读一遍 38 MiB，换来「续传拼接错了也能查出来」；且实现简单。取舍理由写进注释。
- **签名只和「当前装着的 App」比对**，不读仓库里的固定指纹：仓库里那份是给 CI 用的；App 里再硬编码一份，会在「以后合法换签名」时把用户挡死，而「和当前 App 一致」已经等价于「能覆盖安装」。（这是有意识不做的一件事，请审。）
- **缓存目录会短暂占两份空间**（一份缓存 APK + 一份安装会话），空间检查的余量按 32 MiB 给；如果设备确实吃紧，`STATUS_INSUFFICIENT_STORAGE` 会作为独立错误文案出现。
- 校验失败一律删文件：安全，但用户要重新下 38 MiB（网络差时体验差）。取舍是「正确优先」，并用 `RateLimited`/`Network` 文案告诉用户「稍后再试」。

---

## 步骤 5：安装

### 怎么做

- `app/src/main/java/com/xavierxia/mycelium/update/PackageInstallerLauncher.kt`：
  - `PackageInstaller.createSession(SessionParams(MODE_FULL_INSTALL).apply { setAppPackageName(packageName) })`；
  - `session.openWrite("base.apk", 0, size)` 把校验过的文件按流拷进会话 → `fsync` → `commit(pendingIntent)`；
  - 结果用**显式** `PendingIntent.getBroadcast(..., FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)` + 动态注册的 `BroadcastReceiver`（`ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，targetSdk 37 必须）；
  - 状态处理：
    - `STATUS_PENDING_USER_ACTION` → 取出 `Intent.EXTRA_INTENT` 并 `startActivity`（这就是任务书说的「把系统确认界面拉起来」）；
    - `STATUS_SUCCESS` → 提示成功，清理 `updates/`；
    - `STATUS_FAILURE_BLOCKED / ABORTED / CONFLICT / INCOMPATIBLE / INVALID / STORAGE` → 各自给中文说明（尤其 `INSUFFICIENT_STORAGE` → 「空间不够」）；
    - 用户取消（`STATUS_FAILURE_ABORTED` / 回到前台但没装）→ 提示「已取消，安装包已清理」。
- 「安装未知应用」：安装前 `packageManager.canRequestPackageInstalls()`：
  - `false` → 先弹一次**说明**（为什么需要：要把下载好的安装包交给系统安装器），确认后 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 带 `package:` URI 跳到本应用的设置页；`onResume` 回来后重新检查，允许了才继续安装，不允许就停在说明上（**只带路，不替用户开**）。
  - 用 `ActivityResultContracts.StartActivityForResult` 在 Compose 里拿返回。
- 调试包：`applicationId` 以 `.debug` 结尾 → 根本不暴露安装入口（步骤 3 已说明），也不做「未知来源」引导。
- 安装成功后不自动重启 App：提示「更新完成，下次打开生效」，并把 `lastCheckAtMs` 重置、清掉忽略状态。

### 怎么测

- JVM/Robolectric（`app/src/test/.../update/PackageInstallFlowTest.kt`）：把 `PackageInstaller` 抽在 `fun interface InstallSessionGateway` 后面，测：
  1. `asksForUnknownSourcesPermissionBeforeInstalling`（`canRequestPackageInstalls=false` → 只跳设置、不建会话）；
  2. `retriesInstallAfterPermissionGranted`（回来后 `true` → 建会话）；
  3. `pendingUserActionLaunchesSystemConfirm`（假会话回 `STATUS_PENDING_USER_ACTION` → 断言 `EXTRA_INTENT` 被交给 launcher）；
  4. `successClearsUpdatesDirectory`；
  5. `insufficientStorageShowsSpaceMessage`；
  6. `cancelShowsCancelledAndCleansUp`；
  7. `debugPackageHasNoInstallEntry`.
- 设备测（`app/src/androidTest/.../update/UpdateInstallDeviceTest.kt`，用调试包 + `adb reverse` 指向本机假服务）：自动检查 → 红点 + 卡片 → 下载进度 → 校验通过 → 拉起系统确认界面 → **截图到此为止**；「安装」按钮由用户点或经同意后由 agent 点；点完确认版本号变了、数据还在。**不碰手机上装着的正式版 0.1.1。**

### 风险与取舍

- **会话要再占一份磁盘**：无法避免（`PackageInstaller` 的接口就是把包写进会话）。取舍见步骤 4。
- **必须有用户确认**：Android 不允许静默升级；任务书的目标是「点一下就升级好了」，实际上仍是「点两下（下载并安装 → 系统安装）」，这一点要在 `docs/RELEASING.md`/更新架构文档里对用户说清楚，不要让用户以为能全自动。
- 若 `getPackageArchiveInfo` 的签名读取在厂商 ROM 上不可靠（个别 ROM 会对归档解析做限制），签名校验可能误拒。兜底：把「签名校验读取失败」与「签名不一致」分成两种文案，前者提示「这台机器读不出安装包签名，请用浏览器下载安装」——**失败关闭，不放过**。

---

## 步骤 6：发版流程（`release.yml` + `scripts/release-guard.sh`）

### 怎么做

**`scripts/release-guard.sh`**（POSIX sh/bash，纯 ASCII 注释，供 CI 和本地 Git Bash 用）

- 接口（全部有默认值，CI 走默认；显式参数是为了本地演示，见下）：
  ```
  bash scripts/release-guard.sh \
    --tag "$GITHUB_REF_NAME" \                 # 默认: git describe --tags --exact-match
    --apk "mycelium-v0.4.0.apk" \              # 必填（签名后的 release APK）
    --release-notes "docs/releases/v0.4.0.md" \ # 默认: docs/releases/<tag>.md
    --expected-cert-file "docs/releases/signing-cert-sha256.txt"
  ```
  本地演示用的额外开关：`--previous-tag <tag>`、`--version-code <n>`、`--expected-cert-file <临时文件>`。
- 四项检查，任一不过就 `exit 1` 并打印中文原因：
  1. **tag 与 versionName 一致**（把现在 YAML 里那段搬进来）；
  2. **发布说明存在**：`docs/releases/<tag>.md` 必须是普通文件且非空，否则「发布说明缺失，发布中止」；
  3. **versionCode 严格大于已发布的最高值**：`git tag --list 'v[0-9]*'` 排除本次发布的标签后逐个 `git show "<tag>:app/build.gradle.kts"` 读 `versionCode`，取其中最大值——**不看 `HEAD^` 祖先集合**（浅克隆、前序标签只在别的分支、标签被删都会让它为空，而发布确实发生过）；找不到任何前序标签、或前序标签的版本号都读不出来 → **中止，不跳过**（本仓已有正式标签，不适用「首个发布」例外）；只有已知基线标签 `v0.1.1` 在仓里但读不出文件时用内置 `versionCode=2` 兜底并打警告；
  4. **签名指纹**：`apksigner verify --print-certs "$apk"` 取 `Signer #1 certificate SHA-256 digest`，与期望值比对，不一致 →「签名指纹不一致，发布中止」。
     - 找 apksigner：`$ANDROID_HOME/build-tools/*/apksigner`（Linux/CI）→ 找不到再用 `apksigner.bat`（本机 Git Bash 实测只有这个）→ 都没有就报「找不到 apksigner」，**不要静默跳过**。
- 期望指纹文件 `docs/releases/signing-cert-sha256.txt`（新建，内容为公开信息）：
  ```
  # Mycelium Android 正式发布签名证书 SHA-256 指纹
  # 来源: GitHub 发布 v0.1.1 的 mycelium-v0.1.1.apk，apksigner (build-tools 36.0.0) --print-certs
  # 实测日期: 2026-10-18
  bb72a9c7e71137dd62558a9a644c1b5a44eae8501d31ee27138887835ba7fe68
  ```
- CI 与本地演示的隔离：如果设置了 `CI=true` 而调用方又用了 `--expected-cert-file` / `--previous-tag` / `--version-code` 覆盖，脚本**直接失败**（防止哪天有人用覆盖绕过指纹）。

**`.github/workflows/release.yml`**

- `on:` 增 `workflow_dispatch`；
- `permissions: contents: write`（试跑时收窄：`workflow_dispatch` 的那次只读 → 用 `github.event_name` 判断，或用两个 job）；
- `checkout@v4` 加 `fetch-depth: 0`（版本号检查要历史）；
- 删掉现在的 `Verify tag matches versionName` 步骤，改为构建后统一调用：
  `bash scripts/release-guard.sh --tag "$GITHUB_REF_NAME" --apk "mycelium-${GITHUB_REF_NAME}.apk"`；
- 发布说明：`softprops/action-gh-release@v2` 加 `body_path: docs/releases/${{ github.ref_name }}.md`，删掉 `generate_release_notes: true`；
- **试跑模式**（`workflow_dispatch`）：照常装 NDK、编 llama.cpp、签名、比对指纹、算 sha256，**不建 Release、不上传 APK**，只在 `$GITHUB_STEP_SUMMARY`/日志里打出版本号、sha256、指纹。目的就是任务书说的「第一次推送后验证 GitHub 上能编出带 llama.cpp 的签名包」。

### 怎么测

本地（Git Bash，**不跑 Gradle**）：
1. **签名指纹 通过**：临时 keystore（生成在 `%TEMP%`，不进仓库）签一份 `app-release-unsigned.apk` 的副本 → `--expected-cert-file` 指向含该指纹的临时文件 → 期望 exit 0，日志里打印比对的两串指纹。
2. **签名指纹 中止**：不覆盖期望文件（用仓库里那份真实指纹）跑同一个 APK → 期望 exit 1 +「签名指纹不一致」。
3. **versionCode 通过**：`--previous-tag v0.1.1 --version-code 4` → exit 0（4 > 2）。
4. **versionCode 中止**：`--previous-tag v0.1.1 --version-code 2` → exit 1 +「versionCode 没有增长」。
4b. **没有前序标签 中止**（方案审必须修改项 7）：在浅克隆里跑（`git clone --depth 1 --no-tags`，仓库里标签数为 0）→ exit 1 +「找不到任何前序版本标签」；同一场景跑修复前那份脚本作对照 → 只 warn「跳过 versionCode 增长检查」后 exit 0，这正是要修掉的静默放行。
5. **缺发布说明 中止**：`--release-notes docs/releases/v9.9.9.md` → exit 1 +「发布说明缺失」。
6. **缺发布说明 通过**：`--release-notes docs/releases/v0.4.0.md`（步骤 8 写完后再跑）→ exit 0。
7. `bash -n scripts/release-guard.sh` 语法检查；再加一条「`grep -c $'\r'` = 0」（不能有 CRLF，否则 Linux 上会炸）。
CI：本轮**不能**验证（不推送、不发版）；只保证 YAML 语法与步骤顺序可读。→ 交付报告必须写明「CI 上的试跑由统筹在发版日执行，本轮无证据」。

### 风险与取舍

- **指纹固定会拦住合法换签名**：这是目的（防「签名不对的包发出去」），代价是以后真要换签名必须同步改这个文件并让所有用户重装。写进 `docs/RELEASING.md`。
- **`--expected-cert-file` 覆盖开关**：为了本地能演示「通过」。用 `CI=true` 时禁用作为闸门；请在方案审时确认这个闸门够不够（还有别的绕过路径吗）。
- **前序版本取不到时不能静默放行**（方案审必须修改项 7）：`git tag --merged HEAD^` 在浅克隆、标签缺失、前序标签不在祖先集合里时都会给出空集，旧写法只 warn 一句就跳过检查，最后仍报「全部检查通过」。现在改成「与本仓所有正式标签里最高的 `versionCode` 比，取不到就中止」。`fetch-depth: 0` 与 `git fetch --tags --force` 照旧保留——它们是让闸门能拿到标签的手段，不是可以跳过检查的理由。
- 试跑模式不建 Release（任务书要求），意味着它**不能**验证「附件齐全」；附件齐全要等真正打标签那次。

---

## 步骤 7：正式包只带 arm64-v8a

### 怎么做

- `app/build.gradle.kts` 的 `buildTypes.release { ndk { abiFilters += "arm64-v8a" } }`（**只加在 release**；debug / androidTest 不动，模拟器还要用）。
- 理由写进注释：本地模型引擎（`:llm`）本来就只编 `arm64-v8a`，SQLCipher 的其他架构库在正式包里是纯浪费。

### 怎么测

1. （实施阶段）构建 release 包 → 用 `System.IO.Compression`/`unzip -l` 枚举 `lib/**`，断言**只有** `lib/arm64-v8a/*`。
2. debug 包与 androidTest 包仍然有 4 个 ABI（回归）。
3. 大小：改前（实测）52.34 MiB → 改后写实际值（预估 38–39 MiB），交付报告两个数都写。
4. 设备：arm64 手机上装正式的 release 包能起来、本地模型能加载（这条属于发版日的验证，本轮不做）。

### 风险与取舍

- 去掉 `x86/x86_64` 后**正式包不能在模拟器上装**。取舍：正式包只给真机；调试包保持全 ABI，开发不受影响。
- `abiFilters` 放 buildType 只影响打包的 `.so`，**不会**阻止 `:llm` 的 CMake 配置；`:llm` 本来就只编 arm64，无影响。

---

## 步骤 8：发布说明、版本号、发版文档

### 怎么做

- `app/build.gradle.kts`：`versionCode = 5`、`versionName = "0.4.0"`（**不打标签、不推送**）。
- 新建 `docs/releases/v0.4.0.md`（中文，给用户看）：这一版有什么（圆桌、炉边人物、本地与云端模型、应用内更新）、怎么升级、已知问题（本地模型第一轮慢；首次升级 0.1.1 → 0.4.0 只能走浏览器下载或数据线，因为 0.1.1 还没有应用内更新）。
- 新建 `docs/RELEASING.md`（一页纸）：改版本号 → 写 `docs/releases/v<x>.md` → 合并 → 推送 → 看 CI → 手动试跑发布流程 → 打标签 → 看发布与附件 → 手机上验证；并写明「签名指纹固定在 `docs/releases/signing-cert-sha256.txt`」和「换签名要重装」。
- `docs/UPDATE_ARCHITECTURE.md` 改成中文的**实际做法**（不再是计划）：网关下载/逐跳白名单/续传、检查节奏、下载目录与文件名、四项校验、安装流程与「必须用户确认」的限制、模型更新与应用更新分开。
- 同时把任务书里「我的 → 检查更新」的旧行为（浏览器打开 APK）从文档里去掉，改成实际行为。

### 怎么测

- JVM：无（文档）。但要跑一次 `git diff --check`（无空白错误）和 `scripts/check-public.ps1`（步骤 9）确认新文档没有带用户名/密钥。
- 版本号改动后必须有：`app/src/test` 里版本相关的现有测例仍通过（`MyceliumAppTest` 里对版本号文本的断言要同步）。

### 风险与取舍

- 版本号一改，`MyceliumAppTest`/`UpdateManagerTest` 里如果有写死 `0.3.0` 的断言会红；实施时先 grep `0.3.0` 一次性改干净。
- 文档写「能做什么」时必须与实际一致，尤其「应用内更新在这台手机上要用户点确认」——不夸大。

---

## 步骤 9：公开内容检查脚本 `scripts/check-public.ps1`

### 怎么做

- 纯 ASCII（和 `check-all.ps1` 同样的理由：PowerShell 5.1 读 BOM-less 文件按 ANSI），`[CmdletBinding()]`，`-SelfTest` 开关。
- 扫描范围：
  1. `git ls-files`（HEAD 的所有被跟踪文件）→ 读内容；同时查**文件名**本身；
  2. `git log --format=%H origin/main..HEAD` 的每个提交 → `git show --format= --unified=0 <sha>` 的文本与文件路径（**只在历史里出现过也算**）。
- **一定失败**（exit 1）：
  - 私钥样式：`-{5}BEGIN[A-Z ]*PRIVATE KEY-{5}`（写成字符类，避免脚本匹配到自己）；
  - 长令牌：`sk-[A-Za-z0-9_-]{20,}`、`hf_[A-Za-z0-9]{20,}`、`ghp_[A-Za-z0-9]{20,}`、`github_pat_[A-Za-z0-9_]{20,}`（**带长度门槛**，所以脚本自己那行正则不会被自匹配）；
  - 带用户名的路径：三条「用户目录前缀 + 名字」模式——① Windows 盘符式：盘符 + 冒号 + 反斜杠 + `Users` + 反斜杠，后接名字段；② 正斜杠的 Linux 家目录：正斜杠 + `home` + 正斜杠，后接名字段；③ 正斜杠的 macOS 用户目录：正斜杠 + `Users` + 正斜杠，后接名字段。名字段至少一个字符，标点、空白、引号都不算，脚本因此不会匹配到自己的模式文本；也**不**匹配没有名字的「盘符 + 用户目录前缀」；
  - 被跟踪的敏感文件：`git ls-files` 里出现 `*.keystore`、`*.jks`、`*.gguf`、`*.apk`（`.gitignore` 已有这些规则，这个检查是防 `git add -f`）。
- **本地补充词表**：从 `.local/public-denylist.txt` 读（每行一个词，`#` 开头是注释）；文件不存在就跳过并打印「未配置本地词表」；同时把 `.local/` 加进 `.gitignore`。脚本和仓库里都不出现这些词本身。
- **只列出、不失败**：其他本机盘符路径（`\b[A-Za-z]:\\` 且不在 `C:\Users\<名字>` 类别里的）→ 归入「需要统筹逐条看」的清单。
- 输出：默认只打「类别 + 文件:行号」，**不打命中的原文**（本地词表那类连词的长度都不打，只给行号）；末尾一个汇总表（各类别条数）。
- 退出码：有任何「一定失败」→ 1；只有「只列出」→ 0。
- 自测 `-SelfTest`：在 `%TEMP%` 下生成一批**造出来的违规样例**（假 PEM、假 `sk-…`、假用户名路径、假 keystore 文件名）和一批干净文件，逐条断言「该失败的失败、该通过的不报」，然后删掉临时目录。**样例不进仓库**，避免脚本自己扫自己。

### 怎么测

1. `powershell -File scripts/check-public.ps1 -SelfTest` → 全部通过，exit 0。
2. 对本分支跑一次：`powershell -File scripts/check-public.ps1` → 结果写进交付报告，**只写数量和类别**（结合实测：本仓库文档里就有 `D:\Agent\Project\Nivra` 这类绝对路径，应落在「只列出」类别，不失败）。
3. 反向验证一条：临时在 `%TEMP%` 里放一个假 `sk-…`（40 字符）文件并让脚本扫它（用参数指到临时目录做一个 `-Path` 覆盖开关，只给自测用）→ 必须失败。

### 风险与取舍

- **正则自匹配**是这类脚本最常见的坑（脚本自己的模式串命中了它自己）。用「字符类 + 长度门槛」绕开；自测样例只在临时目录生成。若审阅者认为「扫描器排除自己」更诚实，可以改成显式排除 + 一条注释说明，但那样会留一个盲区。
- **只列盘符路径不失败**是任务书要求；代价是真正的用户名泄漏如果不在 `C:\Users\<名字>` 形态（例如用户名恰好等于某个目录名）就只会被列出，不会拦。所以本地词表（用户名、设备序列号）是**必须**配的，脚本要在结尾提醒「本地词表未配置」。
- 扫描历史提交时，GitHub 上**已经**公开的历史无法撤回：这个脚本是「推之前」的门禁，不是「推之后」的补救。写进 `docs/RELEASING.md`。

---

## 全局验收与门禁计划

| 验收项 | 怎么做到 | 本轮状态 |
|---|---|---|
| JVM 测例全部用本机假服务，不连真实 GitHub | 假源 = `ReleaseSource`/`HttpGet` 的 lambda（已有先例）；下载测例用 `LocalHttp` | 待实施 |
| `release-guard.sh` 三项本地演示 | 见步骤 6「怎么测」1–6（Git Bash，已实测可用） | 待实施 |
| `check-public.ps1` 自测 + 对本分支跑一次 | 见步骤 9 | 待实施 |
| 正式包只含 arm64-v8a + 前后大小 | 见步骤 7（改前基线已实测 52.34 MiB / 非 arm64 库 13.58 MiB） | 部分有数 |
| 全新 worktree 里 `scripts/check-all.ps1` 零失败零警告 | **先设 `JAVA_HOME`（Android Studio JBR）与 `ANDROID_HOME`**，否则脚本第一行就退出；再按 P2-2 的做法在全新 worktree、已提交版本上跑 | 待实施 |
| 设备测（红魔，用前先问用户） | 调试包 + `adb reverse` 假服务；**不碰手机上的正式版 0.1.1** | 本轮不做 |
| 升级保数据 | 用调试签名分别构建 `v0.1.1` 标签与本分支，装旧的造数据再覆盖装新的；交付报告列出 0.1.1 实际有哪些数据 | 本轮不做 |

交付物（实施阶段）：代码与测例、`docs/releases/v0.4.0.md`、`docs/releases/signing-cert-sha256.txt`、`docs/RELEASING.md`、改写后的 `docs/UPDATE_ARCHITECTURE.md`、`scripts/release-guard.sh`、`scripts/check-public.ps1`、`.gitignore`（+`.local/`）、`docs/reviews/2026-10-18-P2-2b/DELIVERY.md`（含步骤 7 前后大小、check-public 的数量与类别、本机实测日期与结果）。

## 请方案审重点看的开放问题

1. `OutboundGateway` 构造参数由 `AllowedHosts` 改成 `HostPolicy` 接口——是否接受这个改动面？有没有更小的测法能让「逐跳白名单」被真实测到？
2. 续传的**策略**放 `FileDownloader`（`:net`）、`ApkVerifier` 只做四项校验——职责划分是否合理，P2-5 复用会不会有坑？
3. `AuditEntry` 加 `hostPath` / `receivedBytes` 两个字段（带默认值、兼容旧 JSON）——隐私页展示要不要跟着改？
4. App 侧**只和当前安装的 App 比对签名**、不硬编码仓库里的固定指纹——是否同意这个取舍？
5. `release-guard.sh` 的 `--expected-cert-file` / `--previous-tag` / `--version-code` 覆盖开关（`CI=true` 时禁用）——闸门够不够？
6. 自动检查的下载任务放 `UpdateViewModel`（`viewModelScope`）——在 Robolectric 的 Compose 测试里是否可行；不行就退回根部 `remember` + 续传兜底。
7. 下载失败时额外给一个「用浏览器打开发布页」的次要入口（任务书只写了两个按钮）——加不加？

## 不确定 / 本轮做不到

1. **跳转链是从这台电脑实测的，不是从手机实测的**。本机 `remote_ip` 是 `2001:2::/32` 段（像是代理/中转），手机上的解析路径可能不同，域名也可能多一跳。→ 步骤 1 的白名单以本次实测为准，**并在真机走查时把实际域名记进交付报告**。若真机出现新域名，按「失败关闭 + 浏览器兜底」处理，必要时再补白名单。
2. **CI（`release.yml`）本轮完全无法验证**：不推送、不打标签、不发版，所以「GitHub 上能编出带 llama.cpp 的签名包」「试跑模式」「附件齐全」都没有证据，只能由统筹在发版日执行。
3. **`check-all.ps1` 本轮没跑**（任务书明确说这一轮不跑 Gradle），且当前 shell 里 `JAVA_HOME`/`ANDROID_HOME` 为空、本机 WSL bash 不可用——最终门禁必须由实施阶段在设好环境变量的全新 worktree 里跑，并用 Git for Windows 的 bash 跑 `release-guard.sh` 的本地演示。
4. 设备测试、升级保数据、真机截图本轮一律不做（不许用手机）。
5. `objects.githubusercontent.com` / `github-releases.githubusercontent.com` 是否还会被用到，本次没测到；保留前者、不加后者，属于**有意的失败关闭**。