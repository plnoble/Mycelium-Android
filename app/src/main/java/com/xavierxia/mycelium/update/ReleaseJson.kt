package com.xavierxia.mycelium.update

import org.json.JSONException
import org.json.JSONObject

/**
 * 候选发布的元数据进出偏好存储。进程重建后卡片要能原样回来，所以 [ReleaseInfo] 的每个字段
 * 都必须能往返（含 null）。
 *
 * 解不出来就当没有：宁可下次启动再查一遍，也不能拿半个候选发布去画卡片。
 */
internal object ReleaseJson {
    fun encode(release: ReleaseInfo): String = JSONObject()
        .put("versionName", release.versionName)
        .put("tagName", release.tagName)
        .put("releasePageUrl", release.releasePageUrl)
        .put("apkUrl", release.apkUrl ?: JSONObject.NULL)
        .put("apkSizeBytes", release.apkSizeBytes ?: JSONObject.NULL)
        .put("sha256Url", release.sha256Url ?: JSONObject.NULL)
        .put("releaseNotes", release.releaseNotes)
        .put("publishedAt", release.publishedAt ?: JSONObject.NULL)
        .toString()

    fun decode(text: String): ReleaseInfo? = try {
        val json = JSONObject(text)
        ReleaseInfo(
            versionName = json.getString("versionName"),
            tagName = json.getString("tagName"),
            releasePageUrl = json.getString("releasePageUrl"),
            apkUrl = json.textOrNull("apkUrl"),
            apkSizeBytes = if (json.isNull("apkSizeBytes")) null else json.getLong("apkSizeBytes"),
            sha256Url = json.textOrNull("sha256Url"),
            releaseNotes = json.textOrEmpty("releaseNotes"),
            publishedAt = json.textOrNull("publishedAt"),
        )
    } catch (_: JSONException) {
        null
    }

    // optString 对 JSON null 的表现和运行时有关（Android 上是 "null" 四个字符），所以先判 null。
    private fun JSONObject.textOrEmpty(name: String): String = if (isNull(name)) "" else optString(name)

    private fun JSONObject.textOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}