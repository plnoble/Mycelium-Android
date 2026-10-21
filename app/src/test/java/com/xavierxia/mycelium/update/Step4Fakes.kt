package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.DownloadResult
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import com.xavierxia.mycelium.net.GatewayHttpError
import java.io.File
import java.security.MessageDigest

/**
 * 第 4 步的显式 fake：调用次数、参数、剧本都看得见（照第 3 步 `UpdateFakes.kt` 的做法）。
 * 默认剧本模仿 `FileDownloader` 成功时的行为：把 [payload] 写进 target，然后回 `Completed`。
 */
internal class FakeUpdateTransport : UpdateTransport {
    var payload: ByteArray = "apk-bytes".toByteArray()

    /** `.sha256` 附件正文；null 表示这一跳直接 404。 */
    var checksumText: String? = null

    /** 取校验和时抛的异常（超限 / 取消 / 网络）。 */
    var checksumFailure: Exception? = null

    var checksumCalls = 0
        private set
    var downloads = 0
        private set
    var hashes = 0
        private set
    var lastExpected: String? = null
        private set
    var lastTarget: File? = null
        private set
    var lastUrl: String? = null
        private set

    var script: (attempt: Int, target: File, progress: DownloadProgress?) -> DownloadOutcome =
        { _, target, progress ->
            target.parentFile?.mkdirs()
            target.writeBytes(payload)
            progress?.onProgress(payload.size.toLong(), payload.size.toLong())
            completed(target)
        }

    override fun fetchChecksumText(url: String, call: GatewayCall, limitBytes: Int): String {
        checksumCalls += 1
        lastUrl = url
        if (call.isCancelled) throw GatewayCancelled("请求已被取消")
        checksumFailure?.let { throw it }
        return checksumText ?: throw GatewayHttpError(404, "元数据地址返回了 HTTP 404")
    }

    override fun downloadApk(
        url: String,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        progress: DownloadProgress?,
    ): DownloadOutcome {
        downloads += 1
        lastUrl = url
        lastExpected = expectedSha256
        lastTarget = target
        return script(downloads, target, progress)
    }

    override fun sha256(file: File, call: GatewayCall): String {
        hashes += 1
        return sha256Of(file)
    }
}

internal fun completed(target: File): DownloadOutcome.Completed = DownloadOutcome.Completed(
    DownloadResult(
        status = 200,
        finalUrl = "https://release-assets.githubusercontent.com/x.apk",
        hosts = listOf("github.com", "release-assets.githubusercontent.com"),
        bytesWritten = target.length(),
        fileSize = target.length(),
        redirects = 1,
        restartedFromZero = false,
    ),
    sha256Of(target),
)

internal fun sha256Of(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun factsOf(
    packageName: String? = "com.xavierxia.mycelium",
    versionCode: Long = 5L,
    signerSha256: String? = "f".repeat(64),
    signerCount: Int = if (signerSha256 == null) 0 else 1,
): ApkFacts = ApkFacts(packageName, versionCode, signerSha256, signerCount)

/** 造一个「下载好并 promote 过」的 APK：内容 + 期望哈希边车文件。 */
internal fun apkWithSidecar(directory: File, tag: String, content: String, expected: String? = null): File {
    val apk = File(directory, "mycelium-$tag.apk")
    apk.writeText(content)
    File(directory, "mycelium-$tag.apk.sha256").writeText(expected ?: sha256Of(apk))
    return apk
}