package com.nivra.app.update

import android.content.Context
import com.nivra.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateManager(
    private val context: Context,
    private val client: GitHubReleaseClient = GitHubReleaseClient()
) {
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val latest = client.latestRelease()
                ?: return@withContext UpdateResult.NoPublishedRelease

            if (VersionComparator.isNewer(latest.versionName, BuildConfig.VERSION_NAME)) {
                UpdateResult.Available(latest)
            } else {
                UpdateResult.UpToDate
            }
        }.getOrElse {
            UpdateResult.Error(it.message ?: "Unknown error")
        }
    }
}

object VersionComparator {
    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = normalize(remote)
        val localParts = normalize(local)
        val size = maxOf(remoteParts.size, localParts.size)

        for (index in 0 until size) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }

        return false
    }

    private fun normalize(version: String): List<Int> =
        version
            .substringBefore("-")
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
}
