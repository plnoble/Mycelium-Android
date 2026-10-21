package com.xavierxia.mycelium.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * One file download. The caller picks [target] (the app passes `cacheDir/updates/...`); the download
 * writes that file in place, never through a temporary file, because a resumed download continues
 * exactly where the previous one stopped.
 */
data class DownloadRequest(
    val url: String,
    val purpose: GatewayPurpose,
    val target: File,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000,
    /** When true the existing [target] length decides the `Range` header of the first request. */
    val resume: Boolean = true,
    val call: GatewayCall = GatewayCall(),
)

/**
 * One [OutboundGateway.download] result. [status] is the status of the last hop: 200 for a full
 * entity, 206 for an accepted resume, 416 for "the server says the bytes already on disk are all of
 * it" (the caller then decides by hash whether the file is really complete).
 */
data class DownloadResult(
    val status: Int,
    val finalUrl: String,
    /** The domains actually contacted, in order. A hop that was refused was never contacted. */
    val hosts: List<String>,
    /** Bytes written by this call (a resumed call writes only the missing part). */
    val bytesWritten: Long,
    /** `target.length()` when the call returned. */
    val fileSize: Long,
    /** Redirects followed in the final walk; 0 means the start address answered directly. */
    val redirects: Int,
    /** True when the file was written from byte zero although bytes were already there. */
    val restartedFromZero: Boolean,
)

/**
 * `written` and `total` both count the whole file, so a resumed download keeps counting from the
 * bytes already on disk and `total` is still the full length. `total` is null when the server did
 * not say how long the entity is.
 */
fun interface DownloadProgress {
    fun onProgress(written: Long, total: Long?)
}

/** The server answered with a status that is not a 2xx and not a redirect, or a restart bound ran out. */
class GatewayHttpError(val status: Int, message: String) : Exception(message)

/** At most five redirects are followed: the start address plus five hops, so six addresses at most. */
internal const val MAX_REDIRECTS = 5

/** One "start over from the original address" restart each for an unsatisfied 416 and a bad 206. */
internal const val MAX_RESTARTS = 1

/** Streaming chunk. Only the progress callback's granularity depends on it. */
private const val BUFFER_BYTES = 8 * 1024

/**
 * Receives a file into [DownloadRequest.target] while writing, following redirects with every hop
 * checked against the same whitelist, resumable and cancellable, and never reading the entity into
 * memory. The caller decides afterwards whether the bytes are the right bytes (see `FileDownloader`).
 *
 * The bounds, each with a test in `DownloadGatewayTest`:
 *  * at most [MAX_REDIRECTS] redirects, i.e. at most six addresses contacted; the sixth redirect
 *    response never turns into a seventh request;
 *  * a resumed download walks the redirects again from the original address, and every hop is
 *    checked again — a redirect target that is no longer allowed is refused;
 *  * a 416 whose `Content-Range` says the server considers the entity complete and whose total
 *    equals the bytes on disk writes nothing and returns 416, leaving the file to the hash check;
 *  * any other 416 (missing, malformed or out-of-range `Content-Range`) re-requests the original
 *    address without a `Range` header, at most [MAX_RESTARTS] times;
 *  * an unusable 206 (missing `Content-Range`, or a start that is not the requested offset) never
 *    has its body written — the response is closed and the entity re-requested, same bound;
 *  * cancellation is checked before every write and before every request; cancelled or failed
 *    downloads keep the bytes already on disk (a resume needs them), and a failure before the first
 *    byte leaves no file at all.
 *
 * Every request carries `Accept-Encoding: identity`: without it `HttpURLConnection` may add gzip and
 * transparently decompress, which would make `bytesWritten` disagree with `Content-Length`.
 */
fun OutboundGateway.download(
    request: DownloadRequest,
    onProgress: DownloadProgress? = null,
): DownloadResult = DownloadRun(this, request, onProgress).run()

/**
 * Follows redirects but reads the body into memory, with a hard [limitBytes] ceiling. Only for small
 * metadata such as the `.sha256` file next to a release asset (the caller passes 4 KiB there); a file
 * download must never take this path. Oversized answers are refused, not truncated.
 */
fun OutboundGateway.readFollowingRedirects(
    request: GatewayRequest,
    limitBytes: Int = 64 * 1024,
): GatewayResponse = MetadataRun(this, request, limitBytes).run()

/** The status line and headers of one hop, with the body still unread and the connection open. */
internal class Hop(
    val status: Int,
    val uri: URI,
    val connection: HttpURLConnection,
) {
    val contentLength: Long? get() = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
    val contentRange: String? get() = connection.getHeaderField("Content-Range")

    fun disconnect() {
        runCatching { connection.disconnect() }
    }
}

/** Opens one request at a time and follows redirects, checking the policy at every hop. */
internal class HopWalker(
    private val gateway: OutboundGateway,
    private val purpose: GatewayPurpose,
    private val call: GatewayCall,
    private val method: String,
    private val headers: Map<String, String>,
    private val body: ByteArray?,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {
    private val contacted = mutableListOf<String>()

    /**
     * 是否与最初那个地址**同源**（复审要求）：协议、主机名（大小写不敏感）与**生效端口**三者都相同。
     * 只比主机名不够：`https://a:443 -> https://a:8443` 会把凭据发给同一主机上的另一个服务。
     */
    private fun sameOriginAsStart(here: URI, start: URI): Boolean =
        here.scheme.equals(start.scheme, ignoreCase = true) &&
            here.host?.equals(start.host, ignoreCase = true) == true &&
            effectivePort(here) == effectivePort(start)

    /** 显式端口优先；没写端口时按协议取默认端口。 */
    private fun effectivePort(uri: URI): Int = if (uri.port != -1) {
        uri.port
    } else {
        when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private companion object {
        /** 只该发给最初那个域名的凭据类请求头。 */
        val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
    }

    /** Redirects followed in the walk that just ran. */
    var redirects: Int = 0
        private set

    /** Domains actually contacted, in order. */
    fun hosts(): List<String> = contacted.toList()

    fun walk(start: URI, range: String?): Hop {
        var uri = start
        var followed = 0
        redirects = 0
        while (true) {
            // Every hop, including the redirect targets of a resumed download, is checked here.
            if (!gateway.hosts.allows(uri, purpose)) {
                throw GatewayRejected("地址不在出网白名单里：${uri.host}")
            }
            call.check()
            val connection = gateway.open(uri)
            call.attach(connection)
            try {
                connection.requestMethod = method
                connection.connectTimeout = boundedConnectTimeout(connectTimeoutMs)
                connection.readTimeout = boundedReadTimeout(readTimeoutMs)
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) ->
                    // 跨域跳转不带凭据（工作包 1.2 第 3 条）：Authorization / Cookie /
                    // Proxy-Authorization 只发给最初那个域名；跳到别的域名（CDN）就摘掉。
                    if (sameOriginAsStart(uri, start) || name.lowercase() !in CREDENTIAL_HEADERS) {
                        connection.setRequestProperty(name, value)
                    }
                }
                if (range != null) connection.setRequestProperty("Range", range)
                call.check()
                if (body != null) {
                    connection.doOutput = true
                    call.check()
                    // The same gate the gateway's own exchange uses: a stop that wins the lock means
                    // the stream — and the connection behind it — is never created, and every write
                    // asks the switch again. A download must not open its own way around it.
                    call.bodyStream { connection.outputStream }.use { it.write(body) }
                    call.check()
                }
                // Reaching for the status is what starts the connection on a request without a body,
                // so it passes the same gate as [GatewayCall.bodyStream].
                val status = call.startingConnection { connection.responseCode }
                // 只有拿到状态码才算真的连上过（M11）：建连之前就被取消、或连接根本没建立起来的那些跳，
                // 不能记进「实际联系」的域名链里，否则审计会把一次没发生的联系写成事实。
                contacted += uri.host.orEmpty()
                if (status in 300..399) {
                    // The redirect response itself is never read; only its Location matters.
                    if (followed >= MAX_REDIRECTS) {
                        throw GatewayRejected("跳转次数太多：最多联系 ${MAX_REDIRECTS + 1} 个地址")
                    }
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        throw GatewayHttpError(status, "服务器要求跳转却没有给出地址")
                    }
                    followed++
                    redirects = followed
                    runCatching { connection.disconnect() }
                    // Validated like a first address, then whitelisted at the top of the next round.
                    uri = gateway.parse(uri.resolve(location).toString())
                    continue
                }
                return Hop(status, uri, connection)
            } catch (failure: Exception) {
                runCatching { connection.disconnect() }
                throw failure
            }
        }
    }
}

private class DownloadRun(
    private val gateway: OutboundGateway,
    private val request: DownloadRequest,
    private val onProgress: DownloadProgress?,
) {
    private val walker = HopWalker(
        gateway = gateway,
        purpose = request.purpose,
        call = request.call,
        method = "GET",
        headers = request.headers,
        body = null,
        connectTimeoutMs = request.connectTimeoutMs,
        readTimeoutMs = request.readTimeoutMs,
    )

    private var bytesWritten = 0L
    private var restartedFromZero = false

    fun run(): DownloadResult {
        val start = gateway.parse(request.url)
        var offset = if (request.resume) request.target.length() else 0L
        var restarts = 0
        var status: Int? = null
        try {
            while (true) {
                val hop = walker.walk(start, if (offset > 0) "bytes=$offset-" else null)
                status = hop.status
                if (hop.status == 416) {
                    val complete = unsatisfiedSize(hop.contentRange)
                    hop.disconnect()
                    if (complete != null && complete == offset) {
                        // The server says the bytes on disk are the whole entity. Write nothing and
                        // let the caller's hash decide whether to keep the file.
                        return finish(416, hop.uri, start)
                    }
                    if (restarts >= MAX_RESTARTS) {
                        throw GatewayHttpError(416, "服务器拒绝了续传请求：Range 不被接受")
                    }
                    restarts++
                    restartedFromZero = true
                    offset = 0L
                    continue
                }
                if (hop.status !in 200..299) {
                    hop.disconnect()
                    throw GatewayHttpError(hop.status, "服务器拒绝了下载：HTTP ${hop.status}")
                }
                if (hop.status == 206) {
                    val range = contentRange(hop.contentRange)
                    if (range == null || range.first != offset) {
                        // An unusable partial answer: close it, never write its body, ask for the
                        // whole entity instead.
                        hop.disconnect()
                        if (restarts >= MAX_RESTARTS) {
                            throw GatewayHttpError(206, "服务器返回了无法使用的 206 响应")
                        }
                        restarts++
                        restartedFromZero = true
                        offset = 0L
                        continue
                    }
                    stream(hop, append = offset > 0, offset = offset, total = range.second)
                } else {
                    if (offset > 0) restartedFromZero = true
                    stream(hop, append = false, offset = 0L, total = hop.contentLength)
                }
                return finish(hop.status, hop.uri, start)
            }
        } catch (failure: Exception) {
            record(status, succeeded = false, start = start)
            // A stop that arrived while bytes were on the wire shows up as the socket's own failure;
            // the caller still gets the one clear cancellation exception for a request it stopped.
            if (request.call.isCancelled) throw GatewayCancelled("请求已被取消")
            throw failure
        }
    }

    /** Reads the body into the file, streaming, with a cancel checkpoint before every write. */
    private fun stream(hop: Hop, append: Boolean, offset: Long, total: Long?) {
        val target = request.target
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("无法创建下载目录：$parent")
        }
        var written = offset
        onProgress?.onProgress(written, total)
        val input = hop.connection.inputStream
        try {
            val output = FileOutputStream(target, append)
            try {
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    request.call.check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    request.call.check()
                    output.write(buffer, 0, read)
                    bytesWritten += read
                    written += read
                    onProgress?.onProgress(written, total)
                }
                output.flush()
                // A body shorter than the announced length is a broken download, never a small file.
                if (total != null && written < total) {
                    throw IOException("响应提前结束：收到 $written / $total 字节")
                }
            } finally {
                runCatching { output.close() }
            }
        } finally {
            runCatching { input.close() }
            hop.disconnect()
        }
    }

    private fun finish(status: Int, uri: URI, start: URI): DownloadResult {
        val result = DownloadResult(
            status = status,
            finalUrl = uri.toString(),
            hosts = walker.hosts(),
            bytesWritten = bytesWritten,
            fileSize = request.target.length(),
            redirects = walker.redirects,
            restartedFromZero = restartedFromZero,
        )
        record(status, succeeded = status in 200..299, start = start)
        return result
    }

    private fun record(status: Int?, succeeded: Boolean, start: URI) {
        val contacted = walker.hosts()
        gateway.audit.record(
            AuditEntry(
                at = gateway.clock(),
                // Nothing was contacted yet on a first-hop refusal, so the start host is the only
                // thing the entry can name; hostPath stays empty and says "nothing left the phone".
                host = contacted.lastOrNull() ?: start.host.orEmpty(),
                purpose = request.purpose,
                sentCharacters = 0,
                sentBody = "",
                status = status,
                succeeded = succeeded,
                promptTokens = null,
                completionTokens = null,
                hostPath = contacted,
                receivedBytes = bytesWritten,
            ),
        )
    }
}

private class MetadataRun(
    private val gateway: OutboundGateway,
    private val request: GatewayRequest,
    private val limitBytes: Int,
) {
    fun run(): GatewayResponse {
        require(limitBytes > 0) { "上限必须为正数" }
        val start = gateway.parse(request.url)
        val walker = HopWalker(
            gateway = gateway,
            purpose = request.purpose,
            call = request.call,
            method = request.method,
            headers = request.headers,
            body = request.body?.toByteArray(StandardCharsets.UTF_8),
            connectTimeoutMs = request.connectTimeoutMs,
            readTimeoutMs = request.readTimeoutMs,
        )
        var status: Int? = null
        var received = 0L
        try {
            val hop = walker.walk(start, null)
            status = hop.status
            if (hop.status !in 200..299) {
                hop.disconnect()
                throw GatewayHttpError(hop.status, "元数据地址返回了 HTTP ${hop.status}")
            }
            val bytes = ByteArrayOutputStream()
            try {
                val input = hop.connection.inputStream
                try {
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        request.call.check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        // 先记账再判上限：超限/中断时已经读到的字节也要如实记下来（M11）。
                        received += read
                        if (bytes.size() + read > limitBytes) {
                            throw GatewayRejected("响应超过 $limitBytes 字节的上限")
                        }
                        bytes.write(buffer, 0, read)
                    }
                } finally {
                    runCatching { input.close() }
                }
            } finally {
                hop.disconnect()
            }
            val response = GatewayResponse(
                status = hop.status,
                body = bytes.toString(StandardCharsets.UTF_8),
                sentCharacters = request.body?.length ?: 0,
            )
            record(status, succeeded = true, start = start, walker = walker, received = received)
            return response
        } catch (failure: Exception) {
            record(status, succeeded = false, start = start, walker = walker, received = received)
            if (request.call.isCancelled) throw GatewayCancelled("请求已被取消")
            throw failure
        }
    }

    private fun record(status: Int?, succeeded: Boolean, start: URI, walker: HopWalker, received: Long) {
        val contacted = walker.hosts()
        gateway.audit.record(
            AuditEntry(
                at = gateway.clock(),
                host = contacted.lastOrNull() ?: start.host.orEmpty(),
                purpose = request.purpose,
                sentCharacters = request.body?.length ?: 0,
                // This path only ever fetches metadata; whatever the caller sent is still recorded
                // by the same rule the plain send() uses.
                sentBody = if (request.omitAuditBody || !request.purpose.recordsBody) "" else request.body.orEmpty(),
                status = status,
                succeeded = succeeded,
                promptTokens = null,
                completionTokens = null,
                hostPath = contacted,
                receivedBytes = received,
            ),
        )
    }
}

/** `bytes start-end/total`; the total is null when the server wrote `*` or left it out. */
private fun contentRange(header: String?): Pair<Long, Long?>? {
    val value = header?.trim()?.removePrefix("bytes ")?.trim() ?: return null
    val parts = value.split("/")
    if (parts.size != 2) return null
    val span = parts[0].trim().split("-")
    if (span.size != 2) return null
    val start = span[0].trim().toLongOrNull() ?: return null
    val end = span[1].trim().toLongOrNull() ?: return null
    if (start > end || start < 0) return null
    val total = parts[1].trim().takeIf { it != "*" }?.toLongOrNull()
    return start to total
}

/** The total length of a 416 answer's `Content-Range` (the `bytes *` slash N form); null if malformed. */
private fun unsatisfiedSize(header: String?): Long? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes *")) return null
    val total = value.removePrefix("bytes *").removePrefix("/").trim()
    if (value.removePrefix("bytes *").trim() != "/$total") return null
    return total.toLongOrNull()
}