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
    /**
     * How long the platform may spend on the TCP connect of one connection attempt, in
     * milliseconds.
     *
     * This is a connection policy, not a deadline for the whole call. Two different things are easy
     * to confuse here, so they are written apart:
     *
     *  * **What this value is.** The platform applies it to the TCP connect of one attempt; a
     *    non-positive value is replaced by [DEFAULT_CONNECT_TIMEOUT_MS] and one above
     *    [MAX_CONNECT_TIMEOUT_MS] is capped there. The platform's own default is 0, which means *no
     *    limit at all* — measured on this machine: `getConnectTimeout()` reads 0 on a freshly opened
     *    connection. Normalising it is what keeps a single connect attempt from waiting forever.
     *  * **What it is not.** It is **not** a hard upper bound on the whole call, and it says nothing
     *    about the time from a stop to the end of the task. The TLS handshake and the response read
     *    have their own timeouts ([readTimeoutMs]), and a connect that fails may be followed by
     *    further attempts at the same or another route, each with its own wait. A stopped request
     *    whose connect is already in flight therefore keeps the value as a *per-attempt* bound on
     *    that step, not as a bound on the request as a whole.
     *
     * [DEFAULT_CONNECT_TIMEOUT_MS] is picked from both sides: smaller values kill handshakes on slow
     * or congested mobile links, where DNS plus TCP plus TLS on a bad link regularly exceeds ten
     * seconds, and larger ones leave a stopped request sitting on a socket that is still being
     * established.
     */
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    /**
     * How long one read may block once the connection is up, in milliseconds. 60s covers a
     * non-streaming chat completion. A non-positive value means "no limit" on the platform as well
     * and is replaced by [DEFAULT_READ_TIMEOUT_MS]. This is a per-read bound, not a deadline for the
     * whole call or for a stop: a stop disconnects the connection, which normally ends a blocked
     * read, but whether that happens at once depends on the platform.
     */
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
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
) {
    companion object {
        /**
         * The connect timeout every request gets unless it asks for another one. See
         * [GatewayRequest.connectTimeoutMs] for why it is this value.
         */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000

        /**
         * The ceiling on a caller-supplied connect timeout. A stop that lands while the connection
         * is being started may have to wait out that attempt, so no single request may ask for a
         * longer per-attempt window than this. It is twice the default, which leaves room for a
         * deliberately patient caller without letting the window grow without bound. It bounds one
         * attempt, not the whole call: later attempts and the TLS/read phases have their own waits.
         */
        const val MAX_CONNECT_TIMEOUT_MS = 30_000

        /** The read timeout every request gets unless it asks for another one. */
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
    }
}

data class GatewayResponse(
    val status: Int,
    val body: String,
    val sentCharacters: Int,
)

class GatewayRejected(message: String) : Exception(message)

/**
 * Thrown by a cancel checkpoint instead of sending anything else, and reported for a request whose
 * bytes were already on the wire when the stop arrived — there the failure that ends the in-flight
 * I/O is translated into this one, so a stopped request that fails still ends with the same clear
 * exception. It is not thrown for a request that had already finished: a stop after the last byte
 * cannot turn a completed exchange into a failure. The audit log records the exception as a failed
 * request, exactly as it records a stopped request in flight.
 */
class GatewayCancelled(message: String) : Exception(message)

/**
 * One request's cancel switch, and the only thing here that remembers a stop.
 *
 * `HttpURLConnection.disconnect()` is not a cancellation marker: on a connection that has not been
 * established it can return immediately and the connection would still start afterwards. This object
 * therefore pairs that disconnect with a flag that never goes back, and puts the two decisions that
 * must not interleave — "this request is stopped" and "this request starts its connection" — under
 * one lock:
 *
 *  * a stop that wins the lock makes [startingConnection] refuse, so the platform call that starts
 *    the connection is never made at all;
 *  * a stop that loses it finds whatever connection is registered and immediately attempts to
 *    disconnect it; the stop is not queued behind the blocking connect, but it also cannot veto a
 *    platform call the gate has already been allowed to make.
 *
 * The first branch is a guarantee only for stops that reach the lock before [startingConnection]'s
 * check; a stop that arrives after that check, while the connection object exists but no engine does,
 * is the residual window this class cannot close (see [OutboundGateway]).
 *
 * From there the flag keeps applying: [attach] disconnects a connection that appeared after the stop
 * (a no-op while the platform has no engine, like any disconnect at that point), the gateway's own
 * checkpoints throw instead of sending, [bodyStream] reads the switch again the moment the output
 * stream exists, and `StoppedBodyStream` asks before every write and every flush. A stop that lands
 * while the blocking read is waiting attempts to close the connection, which is what can end it.
 *
 * The lock is deliberately never held across the blocking connect, so a stop never waits for the
 * network: its only wait is the few instructions of the other side's critical section. Serialising
 * the stop *behind* the connect — what holding the lock across it would do — would leave the socket
 * alive after the stop for as long as that connect takes to finish or time out, so the short wait is
 * the right price: correctness first, cancel latency second.
 */
class GatewayCall {
    private val cancelled = AtomicBoolean(false)

    /**
     * The lock that orders the stop against the connection start. Every critical section on it is a
     * few instructions long on purpose; the class comment explains why the blocking connect stays
     * outside it.
     */
    private val decision = Any()

    @Volatile
    private var connection: HttpURLConnection? = null

    /** True from the first [cancel] on: a stop is never forgotten, whatever the connection does. */
    val isCancelled: Boolean get() = cancelled.get()

    /**
     * Marks this request cancelled and closes the connection when one is already in use. The mark and
     * the disconnect happen under [decision], the same lock [startingConnection] takes to decide
     * whether the connection may start, so a stop that reaches the gate first means no connection is
     * ever started. A stop that never reaches the gate before the gate's check still disconnects a
     * connection that has already been registered; what it cannot do is stop the platform call that
     * the gate has already been allowed to make.
     */
    fun cancel() {
        synchronized(decision) {
            cancelled.set(true)
            connection?.let { open -> runCatching { open.disconnect() } }
        }
    }

    /** Remembers the connection for a later [cancel]; a stop that already happened closes it now. */
    internal fun attach(open: HttpURLConnection) {
        synchronized(decision) {
            connection = open
            if (cancelled.get()) runCatching { open.disconnect() }
        }
    }

    /**
     * The connection-start gate: runs [connect] — the platform call that establishes the connection
     * (`getOutputStream`, `getResponseCode`, `getInputStream`) — unless a stop has already won.
     *
     * The guarantee is limited to that: a stop that arrives *before this check* is never overtaken,
     * but a stop that arrives after it returns and before the platform call is actually made is not
     * covered. `disconnect()` is a no-op on a connection with no engine yet, so a stop in that gap
     * cannot veto the call, and the request is started after all. Whether the platform can then end
     * that in-flight call immediately depends on the implementation: once an engine exists,
     * `disconnect()` reaches it and can cancel the connection and close the underlying socket, but
     * this implementation cannot promise that every start window is covered or that an in-flight
     * connect ends at once.
     */
    internal fun <T> startingConnection(connect: () -> T): T {
        synchronized(decision) {
            if (cancelled.get()) throw GatewayCancelled("请求已被取消")
        }
        return connect()
    }

    /** The gateway's checkpoint: a cancelled request throws instead of sending the next byte. */
    internal fun check() {
        if (cancelled.get()) throw GatewayCancelled("请求已被取消")
    }

    /**
     * Hands back the stream the body is written into. The acquisition passes [startingConnection],
     * because `connection.outputStream` is where a real connection is established: a stop that won
     * the decision means the stream — and the connection behind it — is never created. The switch is
     * read once more the moment the stream exists, so a stop that landed while it was being opened
     * is caught before the first body byte even when the connection did start anyway — what that
     * checkpoint stops is the body, not the socket. The stream that goes back asks the same switch
     * before every write and every flush, so no further byte leaves once the stop is seen.
     */
    internal fun bodyStream(open: () -> OutputStream): OutputStream {
        val stream = startingConnection(open)
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
 *
 * ## What a stop does and does not guarantee
 *
 * Stated precisely, because the three statements have different strengths and only the first two
 * are covered by tests ([GatewayTest]):
 *
 *  * **Guaranteed, for one window: a stop that reaches the start gate before its check means the
 *    connection is never started.** The decision to stop and the decision to start the connection
 *    share one critical section ([GatewayCall.startingConnection]), so a stop that gets there first
 *    means the platform call behind `getOutputStream`, `getResponseCode` and `getInputStream()` is
 *    never invoked and no socket is opened. Covered by `aCancelledRequestNeverOpensAConnection` and
 *    `aStopBeforeTheConnectionStartsMeansThePeerNeverSeesAConnect`. This does **not** generalise to
 *    "a stopped request never starts a connection": a stop that lands after the gate's check and
 *    before the platform call still lets that call run.
 *  * **Guaranteed, for one window: a stop during body streaming is caught before the body is
 *    written.** Every body byte passes a checkpoint that reads the stop first: before the stream is
 *    acquired, once more the moment it exists, and again before every write and every flush
 *    ([GatewayCall.bodyStream], `StoppedBodyStream`). Covered by
 *    `aStopInTheRegistrationWindowNeverWritesTheBody` and
 *    `aStopWhileTheConnectionIsBeingStartedEndsThatConnectAtOnce`. This does **not** generalise to
 *    "a stop at any time after the connection began writes no body": bytes already written, or being
 *    written when the stop arrives, cannot be recalled.
 *  * **Not guaranteed: a stop cannot be promised to cover every start window or to end an in-flight
 *    `connect()` at once.** Once that platform call is running, this implementation has no direct
 *    handle on it: on its own the call ends only when it returns, fails or times out, and a thread
 *    interrupt does not abort a blocking socket connect. `disconnect()` may or may not reach it,
 *    depending on whether the platform has created an engine yet. On Android the connection implementation returns
 *    immediately from `disconnect()` while no engine exists yet, so a stop inside that window closes
 *    nothing and the connection starts anyway; in the same implementation the branch where the engine
 *    does exist routes `disconnect()` to `httpEngine.cancel()`, whose lower layers can cancel the
 *    connection and close the underlying socket. So the honest statement is that this implementation
 *    guarantees coverage of neither all start windows nor an immediate end to an in-flight connect. A
 *    stop that lands between the gate and the running `connect()` therefore may have to wait for that
 *    call; [GatewayRequest.connectTimeoutMs] bounds one TCP connect attempt, not the whole call, so it
 *    is not a deadline for that wait. No test claims this branch: a fixture must not be given a
 *    cancellation hook the platform does not have.
 *
 * Holding [GatewayCall]'s lock across the blocking `connect()` was considered and rejected. It would
 * queue the stop *behind* the very call it wants to end, leaving the socket alive with the stop
 * already requested until that connect finishes or times out. That is strictly worse than the short
 * wait this design costs, so the lock is held only for the decision itself.
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
            // Both values are bounded before they reach the platform: 0 means "wait forever" there,
            // and an unbounded connect attempt is an unbounded wait for a stop that lands
            // mid-connect. This bounds that one TCP connect attempt; it is not a deadline for the
            // whole call, because the TLS handshake, the read and any further route attempt after a
            // failure each carry their own wait.
            connection.connectTimeout = request.connectTimeoutMs
                .takeIf { it > 0 }
                ?.coerceAtMost(GatewayRequest.MAX_CONNECT_TIMEOUT_MS)
                ?: GatewayRequest.DEFAULT_CONNECT_TIMEOUT_MS
            connection.readTimeout = request.readTimeoutMs
                .takeIf { it > 0 }
                ?: GatewayRequest.DEFAULT_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            // A stop that landed while the socket was being opened, or while the switch was being
            // handed over: no request byte has gone out yet, and none may.
            call.check()
            if (request.body != null) {
                connection.doOutput = true
                // The acquisition passes the connection-start gate, so a stop that won the decision
                // means the stream is never created and the connection behind it never starts; a
                // stop that lands while the stream is being opened — even one that let the
                // connection start — is read by bodyStream's second checkpoint before the first body
                // byte. Either way the stop is seen before this write begins.
                call.bodyStream { connection.outputStream }
                    .use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                // The body is on the wire; a stop must still catch whatever comes back.
                call.check()
            }
            // The other place a connection starts: a request without a body goes straight here, so
            // the response read passes the same gate as the stream above.
            val status = call.startingConnection { connection.responseCode }
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
