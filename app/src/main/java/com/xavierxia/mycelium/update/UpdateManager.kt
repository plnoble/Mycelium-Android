package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateManager(
    private val client: ReleaseSource = GitHubReleaseClient()
) {
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val latest = client.latestRelease()
                ?: return@withContext UpdateResult.NoPublishedRelease

            if (VersionComparator.isNewer(latest.versionName, BuildConfig.VERSION_NAME)) {
                UpdateResult.Available(latest)
            } else {
                UpdateResult.UpToDate
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            UpdateResult.Error(error.message ?: "Unknown error")
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
