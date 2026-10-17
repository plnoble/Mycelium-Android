package com.xavierxia.mycelium.net

import android.content.Context
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.roundtable.ModelPrices
import com.xavierxia.mycelium.roundtable.actualCost
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** The one gateway the app uses. Its address list follows the cloud-model settings. */
class AndroidGateway(
    context: Context,
    modelBaseUrl: String,
    private val prices: ModelPrices,
) {
    val audit = AuditLog(File(context.filesDir, "privacy/audit.json"))
    val gateway = OutboundGateway(
        hosts = AllowedHosts(modelBaseUrl, BuildConfig.ALLOW_CLEARTEXT_LOCALHOST),
        audit = audit,
        clock = { Instant.now().toString() },
        open = ::openHttp,
        costOf = { _, response -> tokenCost(response.body, prices) },
    )
}

private fun tokenCost(body: String, prices: ModelPrices): Double {
    val usage = JSONObject(body).optJSONObject("usage") ?: return 0.0
    return actualCost(usage.optInt("prompt_tokens"), usage.optInt("completion_tokens"), prices)
}
