package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * The download path, offline: real sockets against [LocalHttp], real files in a temp dir. Every
 * bound the plan review named has a test here; the ones that matter most are marked below.
 */
class DownloadGatewayTest {
    @TempDir
    lateinit var directory: File

    private val update = GatewayPurpose.Update

    // ---- transport: streaming, redirects, the whitelist on every hop ----

    @Test
    fun downloadWritesBodyThroughOneRedirect() {
        val bytes = bodyBytes()
        LocalHttp { call ->
            when (call.path) {
                "/start" -> HttpReply(302, ByteArray(0), mapOf("Location" to "/asset"))
                "/asset" -> HttpReply(200, bytes)
                else -> HttpReply(404, ByteArray(0))
            }
        }.use { server ->
            val file = target()
            val progress = mutableListOf<Pair<Long, Long?>>()
            val result = gateway(policyFor(server)).download(
                DownloadRequest("http://127.0.0.1:${server.port}/start", update, file),
                DownloadProgress { written, total -> progress += written to total },
            )
            assertEquals(200, result.status)
            assertEquals(2, result.hosts.size)
            assertEquals(1, result.redirects)
            assertEquals(bytes.size.toLong(), result.bytesWritten)
            assertEquals(bytes.size.toLong(), result.fileSize)
            assertFalse(result.restartedFromZero)
            assertTrue(result.finalUrl.endsWith("/asset"), result.finalUrl)
            assertArrayEquals(bytes, file.readBytes())
            // Without identity, HttpURLConnection may gzip and unzip behind the counter's back.
            assertEquals("identity", server.calls().first().headers["accept-encoding"])
            val counts = progress.map { it.first }
            assertEquals(counts.sorted(), counts, "进度必须单调不减")
            assertEquals(bytes.size.toLong(), counts.last(), "进度终值必须等于文件长度")
            assertEquals(bytes.size.toLong(), progress.first().second)
        }
    }

    @Test
    fun downloadRejectsRedirectOutOfWhitelist() {
        LocalHttp { call ->
            if (call.path == "/start") {
                HttpReply(302, ByteArray(0), mapOf("Location" to "https://evil.example/asset"))
            } else {
                HttpReply(200, bodyBytes(64))
            }
        }.use { server ->
            val file = target()
            val rejected = assertThrows(GatewayRejected::class.java) {
                gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, file))
            }
            assertTrue(rejected.message!!.contains("evil.example"), rejected.message!!)
            assertFalse(file.exists(), "被拒的下载不该创建目标文件")
            assertEquals(1, server.count.get(), "被拒的那一跳不该收到请求")
            val entry = auditEntries().single()
            assertEquals(listOf("127.0.0.1"), entry.hostPath)
            assertNull(entry.status)
            assertFalse(entry.succeeded)
        }
    }

    @Test
    fun downloadRejectsWhenTheThirdHopLeavesWhitelist() {
        LocalHttp { call ->
            when (call.path) {
                "/a" -> HttpReply(302, ByteArray(0), mapOf("Location" to "/b"))
                "/b" -> HttpReply(302, ByteArray(0), mapOf("Location" to "https://evil.example/c"))
                else -> HttpReply(404, ByteArray(0))
            }
        }.use { server ->
            val file = target()
            assertThrows(GatewayRejected::class.java) {
                gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/a", update, file))
            }
            assertEquals(2, server.count.get())
            assertFalse(file.exists())
            val entry = auditEntries().single()
            assertEquals(listOf("127.0.0.1", "127.0.0.1"), entry.hostPath, "只记已经联系过的跳")
        }
    }

    @Test
    fun downloadRejectsCleartextRedirectUnderTheUpdatePolicy() {
        val production = AllowedHosts(emptyList<String>(), allowCleartextLocalhost = false)
        LocalHttp { HttpReply(302, ByteArray(0), mapOf("Location" to "http://github.com/x/asset")) }.use { server ->
            val entryUrl = "http://127.0.0.1:${server.port}/start"
            // The production rule for Update, plus exactly the loopback entry this test must connect to.
            val policy = HostPolicy { uri, purpose -> uri.toString() == entryUrl || production.allows(uri, purpose) }
            val file = target()
            val rejected = assertThrows(GatewayRejected::class.java) {
                gateway(policy).download(DownloadRequest(entryUrl, update, file))
            }
            assertTrue(rejected.message!!.contains("github.com"), rejected.message!!)
            assertEquals(1, server.count.get(), "明文的第二跳不该被联系")
            assertFalse(file.exists())
        }
        // Starting on cleartext is refused before the first request, not after it.
        LocalHttp { HttpReply(200, bodyBytes(16)) }.use { server ->
            val strict = gateway(AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false))
            assertThrows(GatewayRejected::class.java) {
                strict.download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, target("updates/plain.apk")))
            }
            assertEquals(0, server.count.get())
        }
    }

    @Test
    fun downloadRejectsMoreThanFiveRedirects() {
        LocalHttp { call ->
            val hop = call.path.removePrefix("/r").toIntOrNull()
            if (hop == null) HttpReply(404, ByteArray(0)) else HttpReply(302, ByteArray(0), mapOf("Location" to "/r${hop + 1}"))
        }.use { server ->
            val file = target()
            val rejected = assertThrows(GatewayRejected::class.java) {
                gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/r0", update, file))
            }
            assertTrue(rejected.message!!.contains("跳转"), rejected.message!!)
            // Start address plus five hops is six addresses; the sixth redirect response must not
            // turn into a seventh request.
            assertEquals(MAX_REDIRECTS + 1, server.count.get())
            assertFalse(file.exists())
        }
    }

    // ---- resume, 416, the unusable 206 ----

    @Test
    fun downloadResumesFromExistingBytes() {
        val bytes = bodyBytes(1 shl 20)
        val already = 300 * 1024
        LocalHttp { call -> partial(call, bytes) }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(bytes.copyOfRange(0, already))
            }
            val result = gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            assertEquals(206, result.status)
            assertEquals("bytes=$already-", server.calls().single().headers["range"])
            assertEquals((bytes.size - already).toLong(), result.bytesWritten)
            assertEquals(bytes.size.toLong(), result.fileSize)
            assertFalse(result.restartedFromZero)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun downloadRestartsFromZeroWhenServerIgnoresRange() {
        val bytes = bodyBytes(64 * 1024)
        LocalHttp { HttpReply(200, bytes) }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(200) { 0x7F })
            }
            val result = gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            assertEquals(200, result.status)
            assertTrue(result.restartedFromZero)
            assertEquals("bytes=200-", server.calls().single().headers["range"])
            assertArrayEquals(bytes, file.readBytes(), "200 必须从 0 重写，不能拼接")
        }
    }

    @Test
    fun downloadTreatsASatisfiedRangeHintAsAlreadyComplete() {
        val bytes = bodyBytes(4096)
        LocalHttp { HttpReply(416, ByteArray(0), mapOf("Content-Range" to "bytes */${bytes.size}")) }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
            val result = gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            assertEquals(416, result.status)
            assertEquals(0L, result.bytesWritten, "服务器认定已经完整时不该写文件")
            assertEquals(1, server.count.get(), "已经完整的文件不该白下一整包")
            assertArrayEquals(bytes, file.readBytes())
            val entry = auditEntries().single()
            assertEquals(416, entry.status)
            assertEquals(0L, entry.receivedBytes)
        }
    }

    @Test
    fun unsatisfiedRangeIsRetriedWithoutRangeAndThenFails() {
        val bytes = bodyBytes(8192)
        LocalHttp { HttpReply(416, ByteArray(0), mapOf("Content-Range" to "bytes */${bytes.size + 100}")) }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(bytes.copyOfRange(0, 100))
            }
            val failure = assertThrows(GatewayHttpError::class.java) {
                gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            }
            assertEquals(416, failure.status)
            assertEquals(2, server.count.get(), "恢复上界只允许一次重来")
            assertNotNull(server.calls()[0].headers["range"])
            assertNull(server.calls()[1].headers["range"], "重来的一次必须不带 Range")
            assertEquals(100L, file.length(), "416 不该写文件")
        }
    }

    @Test
    fun malformedRangeHintIsRetriedFromTheStart() {
        val bytes = bodyBytes(8192)
        LocalHttp { call ->
            if (call.headers["range"] == null) HttpReply(200, bytes) else HttpReply(416, ByteArray(0))
        }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(bytes.copyOfRange(0, 100))
            }
            val result = gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            assertEquals(200, result.status)
            assertTrue(result.restartedFromZero)
            assertEquals(2, server.count.get())
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun abnormalPartialAnswerIsNotWrittenAndTheWholeEntityIsRefetched() {
        val bytes = bodyBytes(32 * 1024)
        val poison = "POISONED-PARTIAL-BODY".toByteArray()
        val already = 100L
        LocalHttp { call ->
            val from = call.headers["range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
            if (from == null) {
                HttpReply(200, bytes)
            } else {
                // A 206 whose start is not what was asked for: not one byte of it may be written.
                HttpReply(206, poison, mapOf("Content-Range" to "bytes ${from + 7}-${bytes.size - 1}/${bytes.size}"))
            }
        }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(bytes.copyOfRange(0, already.toInt()))
            }
            val result = gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            assertEquals(200, result.status)
            assertTrue(result.restartedFromZero)
            assertEquals(2, server.count.get())
            assertNull(server.calls()[1].headers["range"], "重来的一次必须不带 Range")
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(file.readBytes().containsText("POISONED"), "异常 206 的正文被写进目标文件了")
        }
    }

    @Test
    fun downloadRewalksRedirectsOnResume() {
        val bytes = bodyBytes(32 * 1024)
        val already = 4096
        val finalPath = AtomicReference("/asset-a")
        LocalHttp { call ->
            when (call.path) {
                "/start" -> HttpReply(302, ByteArray(0), mapOf("Location" to finalPath.get()))
                finalPath.get() -> partial(call, bytes)
                else -> HttpReply(404, ByteArray(0))
            }
        }.use { server ->
            val g = gateway(policyFor(server))
            val file = target()
            assertEquals("/asset-a", URI(g.download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, file)).finalUrl).path)
            file.writeBytes(bytes.copyOfRange(0, already))
            // The redirect target changed: a resume must walk the redirects again, never reuse
            // the address the previous call ended on.
            finalPath.set("/asset-b")
            val resumed = g.download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, file))
            assertEquals("/asset-b", URI(resumed.finalUrl).path)
            assertEquals(2, server.calls().count { it.path == "/start" }, "续传必须重新走跳转")
            assertTrue(server.calls().any { it.path == "/asset-b" && it.headers["range"] == "bytes=$already-" })
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun resumeIsRejectedWhenAHopLeavesTheWhitelistAgain() {
        val bytes = bodyBytes(32 * 1024)
        val destination = AtomicReference("/asset-a")
        LocalHttp { call ->
            when (call.path) {
                "/start" -> HttpReply(302, ByteArray(0), mapOf("Location" to destination.get()))
                destination.get() -> partial(call, bytes)
                else -> HttpReply(200, bytes)
            }
        }.use { server ->
            val g = gateway(policyFor(server))
            val file = target()
            assertEquals(200, g.download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, file)).status)
            file.writeBytes(bytes.copyOfRange(0, 4096))
            val before = file.readBytes()
            destination.set("https://evil.example/asset-b")
            val rejected = assertThrows(GatewayRejected::class.java) {
                g.download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, file))
            }
            assertTrue(rejected.message!!.contains("evil.example"), rejected.message!!)
            assertArrayEquals(before, file.readBytes(), "续传被拒时一个字节都不该写")
            assertEquals(3, server.count.get(), "被拒的那一跳不该被联系")
            val entry = auditEntries().last()
            assertEquals(listOf("127.0.0.1"), entry.hostPath, "只有已经联系过的跳")
            assertFalse(entry.succeeded)
            // "本次字节数" is what this call wrote: the resume was refused before any write.
            assertEquals(0L, entry.receivedBytes)
        }
    }

    // ---- cancel ----

    @Test
    fun cancelKeepsPartialBytesAndNeverReportsComplete() {
        val bytes = bodyBytes(1 shl 20)
        LocalHttp { HttpReply(200, bytes) }.use { server ->
            val call = GatewayCall()
            val file = target()
            val stopAt = 200L * 1024
            val failure = assertThrows(GatewayCancelled::class.java) {
                gateway(policyFor(server)).download(
                    DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file, call = call),
                    DownloadProgress { written, _ -> if (written >= stopAt) call.cancel() },
                )
            }
            assertTrue(failure.message!!.contains("取消"), failure.message!!)
            // The bytes stay on disk on purpose: that file is what the next call resumes from.
            assertTrue(file.exists(), "取消必须留下已写字节")
            val length = file.length()
            assertTrue(length in stopAt until bytes.size.toLong(), "半截长度异常：$length")
            assertArrayEquals(bytes.copyOfRange(0, length.toInt()), file.readBytes())
            val entry = auditEntries().single()
            assertFalse(entry.succeeded)
            // The server did answer 200; the stop cut the transfer short. Recording the real status
            // with succeeded = false is the honest version of that.
            assertEquals(200, entry.status)
            assertEquals(length, entry.receivedBytes)
        }
    }

    @Test
    fun cancelBeforeOpenNeverConnects() {
        LocalHttp { HttpReply(200, bodyBytes(16)) }.use { server ->
            val call = GatewayCall()
            call.cancel()
            val file = target()
            assertThrows(GatewayCancelled::class.java) {
                gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file, call = call))
            }
            assertEquals(0, server.count.get(), "取消之后一个请求都不该发出去")
            assertFalse(file.exists())
            val entry = auditEntries().single()
            assertTrue(
                entry.hostPath.isEmpty(),
                "什么都没联系过，域名链必须是空的，实际：hostPath=${entry.hostPath} host=${entry.host} status=${entry.status}",
            )
            assertNull(entry.status)
            assertFalse(entry.succeeded)
        }
    }

    // ---- audit ----

    @Test
    fun downloadAuditRecordsHostPathAndBytesWithoutContent() {
        val marker = "MYCELIUM-APK-CONTENT-MARKER"
        val bytes = bodyBytes(1 shl 20, marker)
        LocalHttp { call ->
            if (call.path == "/start") {
                HttpReply(302, ByteArray(0), mapOf("Location" to "/asset"))
            } else {
                HttpReply(200, bytes)
            }
        }.use { server ->
            val file = target()
            val result = gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/start", update, file))
            assertTrue(bytes.containsText(marker), "正例本身要能测出内容特征串")
            val entries = auditEntries()
            assertEquals(1, entries.size, "一次下载一条审计")
            val entry = entries.single()
            assertEquals(listOf("127.0.0.1", "127.0.0.1"), entry.hostPath)
            assertEquals(result.fileSize, entry.receivedBytes)
            assertEquals(200, entry.status)
            assertTrue(entry.succeeded)
            assertEquals("", entry.sentBody)
            assertEquals(0, entry.sentCharacters)
            val text = File(directory, "privacy/audit.json").readText()
            assertFalse(text.contains(marker), "审计里出现了下载内容")
        }
    }

    @Test
    fun auditReadsEntriesFromBeforeTheNewFields() {
        val privacy = File(directory, "privacy").apply { mkdirs() }
        val file = File(privacy, "audit.json")
        file.writeText(
            """{"entries":[{"at":"2026-10-01T00:00:00Z","host":"api.github.com",""" +
                """"purpose":"Update","sentCharacters":0,"sentBody":"","status":200,""" +
                """"succeeded":true,"promptTokens":null,"completionTokens":null}]}""",
        )
        val entry = AuditLog(file).read().single()
        assertEquals("api.github.com", entry.host)
        assertEquals(listOf("api.github.com"), entry.hostPath, "老记录回落成单跳")
        assertNull(entry.receivedBytes, "老记录没有字节数")
        assertEquals(200, entry.status)
    }

    @Test
    fun allowedHostsCoversTheMeasuredChainAndRefusesTheRest() {
        val hosts = AllowedHosts("https://api.deepseek.com", allowCleartextLocalhost = false)
        assertTrue(hosts.allows(URI("https://github.com/x/releases/download/v0.4.0/a.apk"), update))
        assertTrue(hosts.allows(URI("https://release-assets.githubusercontent.com/x/a.apk"), update), "实测域名必须在白名单里")
        assertTrue(hosts.allows(URI("https://objects.githubusercontent.com/x/a.apk"), update))
        assertTrue(hosts.allows(URI("https://api.github.com/repos/x/releases/latest"), update))
        assertFalse(hosts.allows(URI("http://github.com/x/releases/download/a.apk"), update))
        assertFalse(hosts.allows(URI("https://evil.example/a.apk"), update))
        assertFalse(hosts.allows(URI("https://github.com.evil.example/a.apk"), update))
        assertFalse(hosts.allows(URI("https://github-releases.githubusercontent.com/a.apk"), update), "没实测过的域名必须失败关闭")
    }

    @Test
    fun downloadReportsAnHttpRefusalWithItsStatus() {
        LocalHttp { HttpReply(404, ByteArray(0)) }.use { server ->
            val file = target()
            val failure = assertThrows(GatewayHttpError::class.java) {
                gateway(policyFor(server)).download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file))
            }
            assertEquals(404, failure.status)
            assertFalse(file.exists())
            assertEquals(1, server.count.get())
            val entry = auditEntries().single()
            assertEquals(404, entry.status)
            assertFalse(entry.succeeded)
        }
    }

    // ---- the metadata channel (.sha256): small, bounded, same whitelist ----

    @Test
    fun metadataReadFollowsRedirectsAndRefusesAnOversizedAnswer() {
        LocalHttp { call ->
            if (call.path == "/sha") {
                HttpReply(302, ByteArray(0), mapOf("Location" to "/sha.txt"))
            } else {
                HttpReply(200, "abcd".toByteArray())
            }
        }.use { server ->
            val g = gateway(policyFor(server))
            val response = g.readFollowingRedirects(GatewayRequest("http://127.0.0.1:${server.port}/sha", update))
            assertEquals(200, response.status)
            assertEquals("abcd", response.body)
            val entry = auditEntries().single()
            assertEquals(listOf("127.0.0.1", "127.0.0.1"), entry.hostPath)
            assertEquals(4L, entry.receivedBytes)
            // The 4 KiB ceiling the .sha256 path uses: oversized must be refused, never truncated.
            assertThrows(GatewayRejected::class.java) {
                g.readFollowingRedirects(
                    GatewayRequest("http://127.0.0.1:${server.port}/sha.txt", update),
                    limitBytes = 2,
                )
            }
        }
    }

    @Test
    fun metadataReadChecksEveryHopAndNeverReadsAnErrorBody() {
        LocalHttp { HttpReply(302, ByteArray(0), mapOf("Location" to "https://evil.example/sha.txt")) }.use { server ->
            assertThrows(GatewayRejected::class.java) {
                gateway(policyFor(server)).readFollowingRedirects(
                    GatewayRequest("http://127.0.0.1:${server.port}/sha", update),
                )
            }
            assertEquals(1, server.count.get())
        }
    }

    // ---- the policy layer: retries, checksum, deletion ----

    @Test
    fun fileDownloaderDeletesAndRedownloadsOnChecksumMismatch() {
        val bytes = bodyBytes(64 * 1024)
        LocalHttp { call -> partial(call, bytes) }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(4096) { 0x7F }) // a poisoned half file
            }
            val outcome = FileDownloader(gateway(policyFor(server)), pause = { _, _ -> fail("不该重试") }).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
                expectedSha256 = sha256(bytes),
            )
            val completed = outcome as? DownloadOutcome.Completed
            assertNotNull(completed, "期望 Completed，实际 $outcome")
            assertEquals(sha256(bytes), completed!!.sha256)
            assertArrayEquals(bytes, file.readBytes(), "磁盘上只该剩一个正确的文件")
            assertNull(server.calls().last().headers["range"], "哈希不符后的重下必须从 0 开始")
        }
    }

    @Test
    fun fileDownloaderGivesUpAfterASecondMismatch() {
        val good = bodyBytes(8192)
        val wrong = good.copyOf().also { it[0] = (it[0] + 1).toByte() }
        LocalHttp { HttpReply(200, wrong) }.use { server ->
            val file = target()
            val outcome = FileDownloader(gateway(policyFor(server))).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
                expectedSha256 = sha256(good),
            )
            val mismatch = outcome as? DownloadOutcome.ChecksumMismatch
            assertNotNull(mismatch, "期望 ChecksumMismatch，实际 $outcome")
            assertEquals(sha256(good), mismatch!!.expected)
            assertEquals(sha256(wrong), mismatch.actual)
            assertFalse(file.exists(), "对不上的文件必须删掉")
            assertEquals(2, server.count.get(), "全量重下只允许一次")
        }
    }

    @Test
    fun fileDownloaderRetriesNetworkFailureThenResumes() {
        val bytes = bodyBytes(256 * 1024)
        val cut = 32 * 1024
        LocalHttp { call ->
            val from = call.headers["range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
            if (from == null) {
                // Announced in full, delivered short: a broken transfer, not a small file.
                HttpReply(200, bytes.copyOfRange(0, cut), mapOf("Content-Length" to bytes.size.toString()))
            } else {
                HttpReply(
                    206,
                    bytes.copyOfRange(from.toInt(), bytes.size),
                    mapOf("Content-Range" to "bytes $from-${bytes.size - 1}/${bytes.size}"),
                )
            }
        }.use { server ->
            val waits = mutableListOf<Long>()
            val file = target()
            val outcome = FileDownloader(gateway(policyFor(server)), pause = { ms, _ -> waits += ms }).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
                expectedSha256 = sha256(bytes),
            )
            assertTrue(outcome is DownloadOutcome.Completed, "实际 $outcome")
            assertEquals(listOf(2_000L), waits, "第一次退避必须是 2 秒")
            assertEquals("bytes=$cut-", server.calls()[1].headers["range"], "重试必须接着下")
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun fileDownloaderRefusesASecondConcurrentDownloadOfTheSameTarget() {
        val bytes = bodyBytes(128 * 1024)
        LocalHttp { HttpReply(200, bytes) }.use { server ->
            val file = target()
            val downloader = FileDownloader(gateway(policyFor(server)))
            val second = AtomicReference<DownloadOutcome?>(null)
            var firstChunk = true
            val outcome = downloader.download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
                onProgress = DownloadProgress { _, _ ->
                    if (firstChunk) {
                        firstChunk = false
                        second.set(downloader.download(DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file)))
                    }
                },
            )
            assertTrue(outcome is DownloadOutcome.Completed, "实际 $outcome")
            assertTrue(second.get() is DownloadOutcome.Rejected, "并发重入必须立即被拒：${second.get()}")
            assertEquals(1, server.count.get(), "第二次请求根本不该发出去")
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun fileDownloaderDeletesThePartialFileWhenTheServerRefuses() {
        LocalHttp { HttpReply(404, ByteArray(0)) }.use { server ->
            val waits = mutableListOf<Long>()
            val file = target()
            val outcome = FileDownloader(gateway(policyFor(server)), pause = { ms, _ -> waits += ms }).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
            )
            assertNotNull(outcome as? DownloadOutcome.Failed, "期望 Failed，实际 $outcome")
            assertTrue(waits.isEmpty(), "HTTP 拒绝不该重试")
            assertFalse(file.exists(), "失败后不该留下半成品")
            assertEquals(1, server.count.get())
        }
    }

    @Test
    fun fileDownloaderKeepsThePartialFileWhenCancelled() {
        val bytes = bodyBytes(1 shl 20)
        LocalHttp { HttpReply(200, bytes) }.use { server ->
            val call = GatewayCall()
            val file = target()
            val stopAt = 128L * 1024
            val outcome = FileDownloader(gateway(policyFor(server))).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file, call = call),
                onProgress = DownloadProgress { written, _ -> if (written >= stopAt) call.cancel() },
            )
            assertEquals(DownloadOutcome.Cancelled, outcome)
            assertTrue(file.exists(), "取消保留半截文件，续传要用")
            assertTrue(file.length() in stopAt until bytes.size.toLong(), "半截长度异常：${file.length()}")
        }
    }

    @Test
    fun fileDownloaderAcceptsA416TheServerCallsCompleteWhenTheHashMatches() {
        val bytes = bodyBytes(16 * 1024)
        LocalHttp { HttpReply(416, ByteArray(0), mapOf("Content-Range" to "bytes */${bytes.size}")) }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
            val outcome = FileDownloader(gateway(policyFor(server))).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
                expectedSha256 = sha256(bytes),
            )
            val completed = outcome as? DownloadOutcome.Completed
            assertNotNull(completed, "期望 Completed，实际 $outcome")
            assertEquals(416, completed!!.result.status)
            assertEquals(1, server.count.get())
        }
    }

    @Test
    fun fileDownloaderRedownloadsWhenTheSatisfiedRangeHintIsWrong() {
        val good = bodyBytes(8192)
        LocalHttp { call ->
            if (call.headers["range"] == null) {
                HttpReply(200, good)
            } else {
                // Right length, wrong bytes: 416 "complete" must still lose to the hash.
                HttpReply(416, ByteArray(0), mapOf("Content-Range" to "bytes */${good.size}"))
            }
        }.use { server ->
            val file = target().apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(good.size) { 0x2A })
            }
            val outcome = FileDownloader(gateway(policyFor(server))).download(
                DownloadRequest("http://127.0.0.1:${server.port}/asset", update, file),
                expectedSha256 = sha256(good),
            )
            assertTrue(outcome is DownloadOutcome.Completed, "实际 $outcome")
            assertArrayEquals(good, file.readBytes())
            assertEquals(2, server.count.get())
            assertNull(server.calls()[1].headers["range"], "哈希不符后的重下必须从 0 开始")
        }
    }

    // ---- helpers ----

    private fun gateway(policy: HostPolicy): OutboundGateway = OutboundGateway(
        hosts = policy,
        audit = AuditLog(File(directory, "privacy/audit.json")),
        clock = { "2026-10-19T00:00:00Z" },
        open = ::openHttp,
    )

    /** The production rule shape: only this test's loopback address may be called. */
    private fun policyFor(server: LocalHttp) = HostPolicy { uri, _ -> uri.host == "127.0.0.1" && uri.port == server.port }

    private fun target(name: String = "updates/mycelium-v0.4.0.apk") = File(directory, name)

    /** A 206 for the requested offset, with the `Content-Range` the gateway must check. */
    private fun partial(call: HttpCall, bytes: ByteArray): HttpReply {
        val from = call.headers["range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
            ?: return HttpReply(200, bytes)
        return HttpReply(
            206,
            bytes.copyOfRange(from.toInt(), bytes.size),
            mapOf("Content-Range" to "bytes $from-${bytes.size - 1}/${bytes.size}"),
        )
    }

    private fun bodyBytes(size: Int = 1 shl 20, marker: String? = null): ByteArray {
        val bytes = ByteArray(size) { index -> (index % 251).toByte() }
        if (marker != null) marker.toByteArray(Charsets.UTF_8).copyInto(bytes, size / 3)
        return bytes
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun ByteArray.containsText(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

    private fun auditEntries(): List<AuditEntry> = AuditLog(File(directory, "privacy/audit.json")).read()
}