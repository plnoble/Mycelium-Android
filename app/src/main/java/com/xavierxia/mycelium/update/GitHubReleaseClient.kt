package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.net.HttpText
import org.json.JSONObject

/** Status and body of one GET. The app's implementation goes through the outbound gateway. */
fun interface HttpGet {
    fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpText
}

/** Metadata boundary for UpdateManager's future download, verification and installation flow. */
fun interface ReleaseSource {
    fun latestRelease(): ReleaseInfo?
}

/** No default transport: the app passes the gateway, so every update check is whitelisted and audited. */
class GitHubReleaseClient(
    private val get: HttpGet,
) : ReleaseSource {
    override fun latestRelease(): ReleaseInfo? {
        val endpoint =
            "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        val response = get.get(
            endpoint,
            mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to "Mycelium-Android/${BuildConfig.VERSION_NAME}",
            ),
            10_000,
        )
        return when (response.status) {
            404 -> null
            200 -> parseRelease(JSONObject(response.body))
            else -> error("GitHub returned HTTP ${response.status}")
        }
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }

        return ReleaseInfo(
            versionName = tag.removePrefix("v"),
            tagName = tag,
            releasePageUrl = json.getString("html_url"),
            apkDownloadUrl = apkUrl ?: json.getString("html_url")
        )
    }
}
