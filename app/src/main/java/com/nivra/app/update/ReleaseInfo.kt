package com.nivra.app.update

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data object NoPublishedRelease : UpdateResult
    data class Available(val release: ReleaseInfo) : UpdateResult
    data class Error(val message: String) : UpdateResult
}
