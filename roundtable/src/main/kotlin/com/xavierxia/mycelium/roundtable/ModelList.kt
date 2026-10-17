package com.xavierxia.mycelium.roundtable

import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.GatewayRequest
import com.xavierxia.mycelium.net.OutboundGateway
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/** A message the settings screen can show as-is when a model pull fails. */
class ModelListError(message: String) : Exception(message)

/**
 * Calls `GET {baseUrl}/models`, the OpenAI-compatible listing. The request goes through the
 * gateway, so it is whitelisted, audited, and its key never reaches the audit log.
 */
/** Pulls `GET {baseUrl}/models`. Named apart from the screen-level helper to keep one meaning. */
fun pullModels(
    gateway: OutboundGateway,
    baseUrl: String,
    apiKey: String?,
    readTimeoutMs: Int = 30_000,
): List<String> {
    val key = apiKey ?: throw ModelListError("这个服务还没有填写 API key")
    val response = try {
        gateway.send(
            GatewayRequest(
                url = baseUrl.trimEnd('/') + "/models",
                purpose = GatewayPurpose.FetchModels,
                method = "GET",
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Accept" to "application/json",
                ),
                readTimeoutMs = readTimeoutMs,
            ),
        )
    } catch (_: SocketTimeoutException) {
        throw ModelListError("服务没有在时间内响应，这次没有拉到模型")
    } catch (failure: IOException) {
        throw ModelListError("连不上这个地址：${failure.message ?: "网络错误"}")
    }
    if (response.status !in 200..299) {
        throw ModelListError(
            if (response.status == 401) "云端拒绝了密钥（401）" else "云端返回 HTTP ${response.status}",
        )
    }
    return parseModelList(response.body)
}

/** Reads the `data[].id` list. Anything else is reported as an unrecognized format. */
fun parseModelList(body: String): List<String> {
    val data = try {
        JSONObject(body).optJSONArray("data")
    } catch (_: Exception) {
        null
    } ?: throw ModelListError("返回格式不认识，这次没有拉到模型")
    val models = buildList {
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index)
                ?: throw ModelListError("返回格式不认识，这次没有拉到模型")
            val id = item.optString("id").trim()
            if (id.isEmpty()) throw ModelListError("返回格式不认识，这次没有拉到模型")
            add(id)
        }
    }
    return models.distinct()
}
