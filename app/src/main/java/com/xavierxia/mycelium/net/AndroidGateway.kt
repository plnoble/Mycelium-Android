package com.xavierxia.mycelium.net

import android.content.Context
import com.xavierxia.mycelium.BuildConfig
import java.io.File
import java.time.Instant

/** The one gateway the app uses. Its address list follows every saved cloud service. */
class AndroidGateway(context: Context, modelBaseUrls: List<String>) {
    val audit = AuditLog(File(context.filesDir, "privacy/audit.json"))
    val gateway = OutboundGateway(
        hosts = AllowedHosts(modelBaseUrls, BuildConfig.ALLOW_CLEARTEXT_LOCALHOST),
        audit = audit,
        clock = { Instant.now().toString() },
        open = ::openHttp,
    )
}
