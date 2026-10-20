package com.xavierxia.mycelium.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.net.AllowedHosts
import com.xavierxia.mycelium.net.AuditLog
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.OutboundGateway
import com.xavierxia.mycelium.net.openHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * 工作包 1.3.3：**真实网络**。在手机上按 Update 用途走真网关，下载 GitHub 上 v0.1.1 的 APK 与
 * `.sha256`，只下载、只比对哈希，**不安装**。
 *
 * 顺便把走查要看的三件事量下来：每一跳的域名与耗时、下载耗时、哈希耗时，以及这个 v2-only 签名的
 * 正式包在这台手机上 `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` 读不读得到。
 *
 * 结果写进设备上的 `update-flow/real-network.txt`（`adb pull` 取回，不入库原文路径之外的任何东西）。
 */
@RunWith(AndroidJUnit4::class)
class UpdateRealNetworkDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val apkUrl =
        "https://github.com/plnoble/Mycelium-Android/releases/download/v0.1.1/mycelium-v0.1.1.apk"
    private val sha256Url = "$apkUrl.sha256"

    @Test
    fun downloadsTheRealReleaseAssetAndComparesItsOwnChecksum() {
        val audit = AuditLog(File(context.filesDir, "privacy/audit-net.json"))
        val gateway = OutboundGateway(
            hosts = AllowedHosts(emptyList(), allowCleartextLocalhost = false),
            audit = audit,
            clock = { Instant.now().toString() },
            open = ::openHttp,
        )
        val transport = GatewayUpdateTransport(gateway)
        val call = GatewayCall()
        val directory = File(context.cacheDir, "net-walkthrough").apply { mkdirs() }
        val target = File(directory, "mycelium-v0.1.1.apk")
        if (target.exists()) target.delete()

        val checksumStarted = System.currentTimeMillis()
        val expected = transport.fetchChecksumText(sha256Url, call).trim().lowercase()
        val checksumMs = System.currentTimeMillis() - checksumStarted
        assertEquals("校验文件只有一行 64 位十六进制", 64, expected.length)

        var lastWritten = 0L
        val downloadStarted = System.currentTimeMillis()
        val outcome = transport.downloadApk(
            url = apkUrl,
            target = target,
            expectedSha256 = expected,
            call = call,
            progress = DownloadProgress { written, total ->
                lastWritten = written
                if (total != null) assertTrue(written <= total)
            },
        )
        val downloadMs = System.currentTimeMillis() - downloadStarted
        assertTrue("下载要成功，实际是 $outcome", outcome is DownloadOutcome.Completed)
        assertEquals("进度最后一次要说已经写完整包", target.length(), lastWritten)

        val hashStarted = System.currentTimeMillis()
        val actual = transport.sha256(target, call)
        val hashMs = System.currentTimeMillis() - hashStarted
        assertEquals("下载下来的字节要和 GitHub 上的 .sha256 一致", expected, actual)

        val factsStarted = System.currentTimeMillis()
        val facts = PackageManagerApkFactsReader(context).read(target.absolutePath)
        val factsMs = System.currentTimeMillis() - factsStarted
        // 复审要求：只打印不断言等于没验。这台手机必须能读出来，且只报一个签名者——
        // 校验器接受的正是这一条；读不出签名就等于应用内安装在这台机器上走不通。
        assertNotNull("getPackageArchiveInfo(GET_SIGNING_CERTIFICATES) 读不出这个正式包", facts)
        assertEquals("签名者个数", 1, facts!!.signerCount)
        assertNotNull("读到了包信息但没有签名者", facts.signerSha256)

        val entries = audit.read().filter { it.purpose == com.xavierxia.mycelium.net.GatewayPurpose.Update }
        val hops = entries.joinToString(" -> ") { entry -> entry.hostPath.joinToString(" | ").ifBlank { entry.host } }

        val report = buildString {
            appendLine("real-network walkthrough (Update purpose, no install)")
            appendLine("apk bytes: ${target.length()}")
            appendLine("expected sha256: $expected")
            appendLine("actual   sha256: $actual")
            appendLine("checksum fetch ms: $checksumMs")
            appendLine("download ms: $downloadMs")
            appendLine("hash ms: $hashMs")
            appendLine("apk facts (getPackageArchiveInfo) ms: $factsMs")
            appendLine("hop chains (one audit entry per request): $hops")
            appendLine("distinct hosts contacted: ${entries.flatMap { it.hostPath }.distinct()}")
            appendLine(
                "archive facts readable: yes package=${facts.packageName} versionCode=${facts.versionCode} " +
                    "signerCount=${facts.signerCount} signerSha256=${facts.signerSha256?.take(16)}...",
            )
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(1, android.os.Bundle().apply {
            putString("update-flow", report)
        })
        println(report)
        File(File(context.getExternalFilesDir(null), "update-flow").apply { mkdirs() }, "real-network.txt")
            .writeText(report)
    }
}