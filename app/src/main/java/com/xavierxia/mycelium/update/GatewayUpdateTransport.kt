package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.DownloadRequest
import com.xavierxia.mycelium.net.FileDownloader
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.net.GatewayRequest
import com.xavierxia.mycelium.net.OutboundGateway
import com.xavierxia.mycelium.net.readFollowingRedirects
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 真机的 [UpdateTransport]：复用 `ChatService` 已经持有的 `AndroidGateway.gateway`，
 * 所以下载与取校验和都会写进**同一份** `privacy/audit.json`（用途都是 [GatewayPurpose.Update]，
 * 一次安装两条记录：APK + `.sha256`）。
 *
 * 重试、续传、「哈希不符删掉重下」全在 [FileDownloader] 里，这一层不再包一层重试
 * （方案审 C4 第 1、2 条：网络策略只有一处）。
 */
class GatewayUpdateTransport(
    private val gateway: OutboundGateway,
    private val fileDownloader: FileDownloader = FileDownloader(gateway),
) : UpdateTransport {
    override fun downloadApk(
        url: String,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        progress: DownloadProgress?,
    ): DownloadOutcome = fileDownloader.download(
        request = DownloadRequest(
            url = url,
            purpose = GatewayPurpose.Update,
            target = target,
            call = call,
        ),
        expectedSha256 = expectedSha256,
        onProgress = progress,
        call = call,
    )

    override fun fetchChecksumText(url: String, call: GatewayCall, limitBytes: Int): String =
        gateway.readFollowingRedirects(
            GatewayRequest(url = url, purpose = GatewayPurpose.Update, call = call),
            limitBytes = limitBytes,
        ).body

    override fun sha256(file: File, call: GatewayCall): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                // `GatewayCall.check()` 是 :net 的内部方法，app 侧只看公开的 isCancelled，
                // 语义相同：取消后不再继续读盘。
                if (call.isCancelled) throw GatewayCancelled("请求已被取消")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/** 一个十六进制指纹（sha256 字符串）的小工具：真机实现要把证书字节变成这种形式。 */
internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        .lowercase(Locale.US)