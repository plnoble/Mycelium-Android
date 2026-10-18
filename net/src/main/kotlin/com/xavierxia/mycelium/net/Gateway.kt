package com.xavierxia.mycelium.net

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why a request leaves the phone. The audit log stores this, never the credentials.
 *
 * [HearthChatTest] is the hearth test mode: the body is still sent (the service needs it) but the
 * audit keeps only time, host, purpose, size and status, so no conversation text is written down.
 */
enum class GatewayPurpose {
    Update,
    FetchModels,
    RoundtableChat,
    HearthChat,
    HearthChatTest,
    ;

    /** Only the hearth test mode keeps the body out of the audit log. */
    val recordsBody: Boolean get() = this != HearthChatTest
}

data class GatewayRequest(
    val url: String,
    val purpose: GatewayPurpose,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000,
    /**
     * The caller asks the audit log to keep only the size of this request, never its text. It only
     * changes what is written down: the service still receives the real body.
     */
    val omitAuditBody: Boolean = false,
    /**
     * This request's cancel switch. It exists before the socket does, which is the whole point:
     * `disconnect()` on a connection that never connected returns without doing anything, so it
     * cannot be the cancel marker. Whoever may need to stop the request holds this and calls
     * [GatewayCall.cancel] whenever the stop arrives — before the body is written, while it is being
     * written, or while the answer is being read.
     */
    val call: GatewayCall = GatewayCall(),
    /**
     * Hands the same [call] to the caller once the connection exists, for callers that only learn
     * about the request at that point. A stop that arrived before this call is not lost: the gateway
     * asks [GatewayCall.isCancelled] at every point where it could still send something.
     */
    val onOpen: ((GatewayCall) -> Unit)? = null,
)

data class GatewayResponse(
    val status: Int,
    val body: String,
    val sentCharacters: Int,
)

class GatewayRejected(message: String) : Exception(message)

/**
 * Thrown by a cancel checkpoint instead of sending anything else, and reported for a request whose
 * bytes were already on the wire when the stop arrived — there the closed socket's own failure is
 * translated into this one, so a stopped request always ends with the same clear exception. The
 * audit log records it as a failed request, exactly as it records a stopped request in flight.
 */
class GatewayCancelled(message: String) : Exception(message)

/**
 * One request's cancel switch, and the only thing here that remembers a stop.
 *
 * `HttpURLConnection.disconnect()` is not a cancellation marker: on a connection that has not been
 * established it can return immediately and the writing would go on. This object pairs that
 * disconnect with a flag that never goes back, so:
 *
 *  * a stop that lands before the socket is opened means no connection is opened at all;
 *  * a stop that lands while the socket is being opened is seen by [attach], by the gateway's own
 *    checkpoints and by [bodyStream], which reads the switch again the moment the output stream
 *    exists — the point where a real connection has actually been started — so not one body byte
 *    goes out;
 *  * a stop that lands after that is seen before every write and every flush of the body;
 *  * a stop that lands while the blocking read is waiting closes the connection at once, which is
 *    the only thing that ends that read.
 *
 * [cancel] and [attach] each write their own field and then read the other's, and both fields are
 * volatile, so whichever of the two runs second observes the other one.
 */
class GatewayCall {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var connection: HttpURLConnection? = null

    /** True from the first [cancel] on: a stop is never forgotten, whatever the connection does. */
    val isCancelled: Boolean get() = cancelled.get()

    /** Marks this request cancelled and closes the connection when one is already in use. */
    fun cancel() {
        cancelled.set(true)
        connection?.let { open -> runCatching { open.disconnect() } }
    }

    /** Remembers the connection for a later [cancel]; a stop that already happened closes it now. */
    internal fun attach(open: HttpURLConnection) {
        connection = open
        if (cancelled.get()) runCatching { open.disconnect() }
    }

    /** The gateway's checkpoint: a cancelled request throws instead of sending the next byte. */
    internal fun check() {
        if (cancelled.get()) throw GatewayCancelled("请求已被取消")
    }

    /**
     * Hands back the stream the body is written into, with the switch read on both sides of the call
     * that creates it.
     *
     * `connection.outputStream` is where a real connection is established, so a checkpoint in front
     * of it cannot be the last word: this thread can be paused between that checkpoint and this call
     * — and `disconnect()` on a connection that has not connected does nothing — so the paused
     * thread would come back and start the connection anyway. Reading the switch again the moment
     * the stream exists closes exactly that gap: whatever happened during the pause, the request
     * never hands a body byte over. The stream that goes back asks the same switch before every
     * write and every flush, so no later action can send a byte without verifying it again, and the
     * switch only ever goes from false to true, so a stop that has been seen once is seen by every
     * checkpoint after it.
     */
    internal fun bodyStream(open: () -> OutputStream): OutputStream {
        // A stop that landed before this point opens nothing at all.
        check()
        val stream = open()
        // The stream exists now: a stop that landed while it was being opened still wins, before the
        // first body byte.
        check()
        return StoppedBodyStream(stream, this)
    }
}

/**
 * The only place a request body is written. Every write and every flush asks the request's switch
 * first, so a stop that lands between two of the gateway's own checkpoints — a thread pause, for
 * instance — cannot push the body out anyway.
 */
private class StoppedBodyStream(
    private val out: OutputStream,
    private val call: GatewayCall,
) : OutputStream() {
    override fun write(b: Int) {
        call.check()
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        call.check()
        out.write(b, off, len)
    }

    override fun flush() {
        call.check()
        out.flush()
    }

    /**
     * A stopped request deliberately leaves the stream alone: closing it would flush whatever was
     * already buffered out of the door. The socket is torn down by the gateway's own `disconnect()`
     * instead, and that is what ends the request.
     */
    override fun close() {
        if (call.isCancelled) return
        out.close()
    }
}

/** Source-compatible name for the old abort handle; the gateway now hands out the whole call. */
typealias GatewayAbort = GatewayCall

/**
 * The only place HTTP leaves the app. Callers hand over the body they are willing to have stored;
 * the gateway writes that body into the audit log and never writes request headers.
 */
class OutboundGateway(
    private val hosts: AllowedHosts,
    private val audit: AuditLog,
    private val clock: () -> String,
    private val open: (URI) -> HttpURLConnection,
) {
    fun send(request: GatewayRequest): GatewayResponse {
        val target = parse(request.url)
        if (!hosts.allows(target, request.purpose)) {
            throw GatewayRejected("地址不在出网白名单里：${target.host}")
        }
        val sent = request.body?.codePointCount() ?: 0
        // Two independent guards, both applied to the success and the failure entry: the request can
        // ask for it, and the test-mode purpose can never record a body no matter who calls it.
        val recorded = if (request.omitAuditBody || !request.purpose.recordsBody) "" else request.body.orEmpty()
        val response = try {
            exchange(target, request)
        } catch (failure: GatewayRejected) {
            throw failure
        } catch (failure: Exception) {
            audit.record(
                AuditEntry(
                    at = clock(),
                    host = target.host,
                    purpose = request.purpose,
                    sentCharacters = sent,
                    sentBody = recorded,
                    status = null,
                    succeeded = false,
                    promptTokens = null,
                    completionTokens = null,
                ),
            )
            throw failure
        }
        val usage = tokenUsage(response.body)
        audit.record(
            AuditEntry(
                at = clock(),
                host = target.host,
                purpose = request.purpose,
                sentCharacters = sent,
                sentBody = recorded,
                status = response.status,
                succeeded = response.status in 200..299,
                promptTokens = usage?.first,
                completionTokens = usage?.second,
            ),
        )
        return response
    }

    private fun exchange(target: URI, request: GatewayRequest): GatewayResponse {
        val call = request.call
        // A stop that landed before this point, even before the request started: nothing is opened.
        call.check()
        val connection = open(target)
        call.attach(connection)
        request.onOpen?.invoke(call)
        return try {
            connection.requestMethod = request.method
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            // A stop that landed while the socket was being opened, or while the switch was being
            // handed over: no request byte has gone out yet, and none may.
            call.check()
            if (request.body != null) {
                connection.doOutput = true
                // The stream acquisition is itself bracketed by the switch: the first read stops a
                // request that was already cancelled before the connection could start, the second —
                // taken once the stream exists — stops one whose stop landed while this thread was
                // paused between the checkpoint above and the moment the connection really started.
                // From there the stream keeps asking, so not one body byte leaves after a stop.
                call.bodyStream { connection.outputStream }
                    .use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                // The body is on the wire; a stop must still catch whatever comes back.
                call.check()
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            GatewayResponse(status, body, request.body?.codePointCount() ?: 0)
        } catch (failure: Exception) {
            // The disconnect that cut off a blocking read shows up as the socket's own failure; the
            // caller still gets the one clear cancellation exception for a request it stopped.
            if (call.isCancelled) throw GatewayCancelled("请求已被取消")
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(url: String): URI {
        val target = try {
            URI(url)
        } catch (failure: Exception) {
            throw GatewayRejected("地址无法解析")
        }
        if (target.scheme != "https" && target.scheme != "http") throw GatewayRejected("只允许 http 和 https")
        if (target.host.isNullOrBlank()) throw GatewayRejected("地址没有域名")
        if (target.userInfo != null) throw GatewayRejected("地址里不能带账号")
        return target
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)
}

/** Hosts the gateway may call. Localhost is allowed only when the debug build asks for it. */
class AllowedHosts(
    private val modelBaseUrls: List<String>,
    private val allowCleartextLocalhost: Boolean,
) {
    constructor(modelBaseUrl: String, allowCleartextLocalhost: Boolean) :
        this(listOf(modelBaseUrl), allowCleartextLocalhost)

    fun allows(target: URI, purpose: GatewayPurpose): Boolean {
        val host = target.host.orEmpty().lowercase()
        val https = target.scheme == "https"
        return when (purpose) {
            GatewayPurpose.Update -> https && host in UPDATE_HOSTS
            GatewayPurpose.FetchModels, GatewayPurpose.RoundtableChat,
            GatewayPurpose.HearthChat, GatewayPurpose.HearthChatTest,
            -> modelHost(host, target.scheme)
        }
    }

    private fun modelHost(host: String, scheme: String?): Boolean =
        modelBaseUrls.any { base -> matches(base, host, scheme) }

    private fun matches(base: String, host: String, scheme: String?): Boolean {
        val configured = try {
            URI(base)
        } catch (_: Exception) {
            return false
        }
        if (!host.equals(configured.host, ignoreCase = true)) return false
        if (scheme == "https" && configured.scheme == "https") return true
        return allowCleartextLocalhost &&
            scheme == "http" &&
            configured.scheme == "http" &&
            host == "127.0.0.1"
    }

    private companion object {
        val UPDATE_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com")
    }
}

/** Reads the `usage` object a service reports. Absent or malformed usage is simply not recorded. */
private fun tokenUsage(body: String): Pair<Int, Int>? {
    val usage = try {
        org.json.JSONObject(body).optJSONObject("usage")
    } catch (_: Exception) {
        null
    } ?: return null
    if (!usage.has("prompt_tokens") && !usage.has("completion_tokens")) return null
    return usage.optInt("prompt_tokens") to usage.optInt("completion_tokens")
}

/** The only HTTP open in the project. Callers outside :net use the gateway, not this function. */
fun openHttp(uri: URI): HttpURLConnection = uri.toURL().openConnection() as HttpURLConnection

data class HttpText(val status: Int, val body: String)
