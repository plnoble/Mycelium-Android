package com.xavierxia.mycelium.net

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What one [FileDownloader.download] ended as. Only [Completed] means the bytes are the right bytes. */
sealed interface DownloadOutcome {
    data class Completed(val result: DownloadResult, val sha256: String) : DownloadOutcome

    /** The entity never matched the expected hash; the file was deleted before this was returned. */
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadOutcome

    /** Refused before (or while) talking to the network; the target file was deleted. */
    data class Rejected(val message: String) : DownloadOutcome

    /** The transfer was stopped; the bytes already on disk are kept so a resume can use them. */
    data object Cancelled : DownloadOutcome

    /** Network or IO failure after the retries ran out; the target file was deleted. */
    data class Failed(val message: String) : DownloadOutcome
}

/**
 * The policy layer on top of [OutboundGateway.download]: it owns the retries, the checksum decision
 * and the "delete the half file" rule, so the app never wraps a second retry around a download.
 *
 *  * a network or IO failure is retried at most [attempts] times, waiting [RETRY_DELAYS_MS] (2s, 5s,
 *    and then 10s), and each retry resumes from the bytes already on disk;
 *  * a wrong hash deletes the file and downloads it once more from zero; a second wrong hash gives up;
 *  * a whitelist refusal or an HTTP refusal deletes the file and is never retried;
 *  * a cancellation returns immediately and leaves the bytes on disk — a resume needs them.
 *
 * Cancellation is checked before and after every wait, before every request and while hashing, so a
 * cancelled call never starts another request. The wait itself is the injected [pause], which is why
 * the app must use a cancelling UI: a `Thread.sleep` in flight is only observed when it ends.
 */
class FileDownloader(
    private val gateway: OutboundGateway,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val attempts: Int = 3,
) {
    fun download(
        request: DownloadRequest,
        expectedSha256: String? = null,
        onProgress: DownloadProgress? = null,
        call: GatewayCall = request.call,
    ): DownloadOutcome {
        val path = request.target.absoluteFile.normalize().path
        // One download per target at a time: a second request for the same file is refused at once
        // instead of letting two writers interleave into one target.
        if (!IN_FLIGHT.add(path)) return DownloadOutcome.Rejected("同一个文件已经在下载：$path")
        try {
            var current = request
            var correctedOnce = false
            while (true) {
                var retries = 0
                var result: DownloadResult? = null
                var settled: DownloadOutcome? = null
                while (result == null && settled == null) {
                    try {
                        result = gateway.download(current, onProgress)
                    } catch (_: GatewayCancelled) {
                        settled = DownloadOutcome.Cancelled
                    } catch (rejected: GatewayRejected) {
                        current.target.delete()
                        settled = DownloadOutcome.Rejected(rejected.message.orEmpty())
                    } catch (refused: GatewayHttpError) {
                        current.target.delete()
                        settled = DownloadOutcome.Failed(refused.message.orEmpty())
                    } catch (failure: Exception) {
                        if (call.isCancelled) {
                            settled = DownloadOutcome.Cancelled
                        } else if (retries >= attempts) {
                            current.target.delete()
                            settled = DownloadOutcome.Failed(failure.message ?: failure.toString())
                        } else {
                            val delayMs = RETRY_DELAYS_MS[retries.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                            retries++
                            if (!waitOrCancel(delayMs, call)) settled = DownloadOutcome.Cancelled
                        }
                    }
                }
                if (settled != null) return settled
                val downloaded = requireNotNull(result)
                val actual = try {
                    sha256Of(current.target, call)
                } catch (_: GatewayCancelled) {
                    return DownloadOutcome.Cancelled
                } catch (failure: Exception) {
                    current.target.delete()
                    return DownloadOutcome.Failed(failure.message ?: failure.toString())
                }
                if (expectedSha256 == null || actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    return DownloadOutcome.Completed(downloaded, actual)
                }
                current.target.delete()
                if (correctedOnce) return DownloadOutcome.ChecksumMismatch(expectedSha256, actual)
                // A wrong hash means the bytes on disk are not what the release says; the second try
                // must not resume onto them, so the target is gone and resume is switched off.
                correctedOnce = true
                current = current.copy(resume = false)
            }
        } finally {
            IN_FLIGHT.remove(path)
        }
    }

    /** True when the wait finished, false when the stop arrived around it. */
    private fun waitOrCancel(delayMs: Long, call: GatewayCall): Boolean = try {
        call.check()
        pause(delayMs)
        call.check()
        true
    } catch (_: GatewayCancelled) {
        false
    }

    private companion object {
        /** 2s, 5s, 10s: the review's bound of three network retries beyond the first request. */
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000)

        /** Targets with a download running in this process, keyed by normalized path. */
        val IN_FLIGHT: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/** Streams the file through SHA-256; the cancel switch is checked between chunks. */
private fun sha256Of(file: File, call: GatewayCall): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            call.check()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}