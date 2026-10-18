package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.net.HttpText
import org.json.JSONArray
import org.json.JSONException
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
            200 -> parseRelease(response.body)
            // 限流和 GitHub 自己出问题是两码事，给用户的话也不一样，所以分开报。
            403, 429 -> throw UpdateCheckException(UpdateError.RateLimited)
            else -> throw UpdateCheckException(UpdateError.Server(response.status))
        }
    }

    private fun parseRelease(body: String): ReleaseInfo =
        try {
            readRelease(JSONObject(body))
        } catch (failure: JSONException) {
            // 缺字段和畸形 JSON 都走这里：都是「GitHub 返回的内容看不懂」，不把原文给用户。
            throw UpdateCheckException(UpdateError.Malformed, failure)
        }

    private fun readRelease(json: JSONObject): ReleaseInfo {
        val tag = json.getString("tag_name")
        val version = tag.removePrefix("v")
        val assets = json.getJSONArray("assets")
        // 附件名以发版流程实际产出的名字为准（实施方案 1.1 实测：mycelium-v0.1.1.apk / .apk.sha256）。
        val apkName = "mycelium-v$version.apk"
        val checksumName = "$apkName.sha256"
        // 只有这两个精确名字算数：别的附件（.asc/.sig/备份包）不能顶替，也不能二选一地配对。
        val apk = findAssetByName(assets, apkName)
        val checksum = findAssetByName(assets, checksumName)
        // 两个附件都在才提供应用内安装；缺任何一个都只留发布页 —— 绝不退回「随便挑一个 .apk」。
        val installable = apk != null && checksum != null
        return ReleaseInfo(
            versionName = version,
            tagName = tag,
            releasePageUrl = json.getString("html_url"),
            apkUrl = if (installable) apk.getString("browser_download_url") else null,
            apkSizeBytes = apk?.let { sizeOf(it) },
            sha256Url = if (installable) checksum.getString("browser_download_url") else null,
            releaseNotes = json.textOrEmpty("body"),
            publishedAt = json.textOrNull("published_at"),
        )
    }

    /**
     * 按名字找资产：只认忽略大小写的**精确**文件名，不看数组顺序，不做前缀/包含/后缀匹配。
     *
     * 精确匹配是有意收紧的：`mycelium-v0.4.0.apk.asc`、`mycelium-v0.4.0.apk.sig`、
     * `mycelium-v0.4.0.apk.sha256.asc`、`mycelium-v0.4.0.apk.backup.apk` 都只比目标名字长一点，
     * 前缀或包含匹配会把它们当成 APK/校验文件选中。也不能用「反正后面的解析会失败」兜底：
     * 带 64 位十六进制文本的其他文件并不一定被 sha256 解析拒绝。
     *
     * 名字不完全一样就当这份发布没有该附件，调用方据此不给应用内安装（两 URL 一起置空）。
     */
    private fun findAssetByName(assets: JSONArray, expectedName: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").equals(expectedName, ignoreCase = true)) return asset
        }
        return null
    }

    private fun sizeOf(asset: JSONObject): Long? =
        if (asset.isNull("size")) null else asset.getLong("size")

    /**
     * org.json 的 `optString` 对 JSON null 的表现和运行时有关：Android 上会返回 "null" 四个字符，
     * 独立 org.json 上返回空串。先判 null，别让发布说明或时间戳里多出个 `null`。
     */
    private fun JSONObject.textOrEmpty(name: String): String = if (isNull(name)) "" else optString(name)

    private fun JSONObject.textOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
