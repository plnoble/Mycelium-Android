package com.xavierxia.mycelium.net

import java.net.HttpURLConnection
import java.net.URI

/** Why a request leaves the phone. The audit log stores this, never the credentials. */
enum class GatewayPurpose { Update, FetchModels, RoundtableChat }

data class GatewayRequest(
    val url: String,
    val purpose: GatewayPurpose,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000,
)

data class GatewayResponse(
    val status: Int,
    val body: String,
    val sentCharacters: Int,
)

class GatewayRejected(message: String) : Exception(message)

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
                    sentBody = request.body.orEmpty(),
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
                sentBody = request.body.orEmpty(),
                status = response.status,
                succeeded = response.status in 200..299,
                promptTokens = usage?.first,
                completionTokens = usage?.second,
            ),
        )
        return response
    }

    private fun exchange(target: URI, request: GatewayRequest): GatewayResponse {
        val connection = open(target)
        return try {
            connection.requestMethod = request.method
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            GatewayResponse(status, body, request.body?.codePointCount() ?: 0)
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
            GatewayPurpose.FetchModels, GatewayPurpose.RoundtableChat -> modelHost(host, target.scheme)
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
