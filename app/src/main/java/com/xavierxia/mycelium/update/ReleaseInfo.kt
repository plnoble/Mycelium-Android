package com.xavierxia.mycelium.update

/**
 * 一次发布里应用内更新需要的全部信息。
 *
 * [apkUrl] 与 [sha256Url] **要么都有、要么都没有**：缺任何一个附件，应用内就不提供安装，
 * 界面只显示「有新版本」和 [releasePageUrl]。这就是为什么它们是可空的。
 */
data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releasePageUrl: String,
    val apkUrl: String?,
    val apkSizeBytes: Long?,
    val sha256Url: String?,
    val releaseNotes: String,
    val publishedAt: String?,
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data object NoPublishedRelease : UpdateResult
    data class Available(val release: ReleaseInfo) : UpdateResult
    data class Error(val error: UpdateError) : UpdateResult
}
