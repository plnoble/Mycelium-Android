package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Metadata boundary for UpdateManager's future download, verification and installation flow. */
fun interface ReleaseSource {
    fun latestRelease(): ReleaseInfo?
}

class GitHubReleaseClient(
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) : ReleaseSource {
    override fun latestRelease(): ReleaseInfo? {
        val endpoint =
            "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

        val connection = openConnection(URL(endpoint)).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Mycelium-Android/${BuildConfig.VERSION_NAME}")
        }

        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                HttpURLConnection.HTTP_OK -> {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    parseRelease(JSONObject(json))
                }
                else -> error("GitHub returned HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
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
