package com.xavierxia.mycelium.update

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class CountingArchive(private val facts: ApkFacts?) : ApkFactsReader {
    var reads = 0
        private set

    override fun read(path: String): ApkFacts? {
        reads += 1
        return facts
    }
}

private class CountingInstalled(private val facts: ApkFacts) : InstalledAppFacts {
    var calls = 0
        private set

    override fun installed(): ApkFacts {
        calls += 1
        return facts
    }
}

/**
 * 四项校验：`sha256 → 包名 → 签名 → versionCode`（任务书与 C4 第 5 条的顺序），
 * 任何一项不过都**先删文件**、并给出是哪一项的人话。全部 JVM：APK 内容与事实都是注入的。
 */
class ApkVerifierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val transport = FakeUpdateTransport()
    private val release = testRelease("0.6.0")
    private val currentSigner = "f".repeat(64)
    private val installed = factsOf(versionCode = 4L, signerSha256 = currentSigner)

    private fun verifier(archive: ApkFacts?, installedFacts: InstalledAppFacts = CountingInstalled(installed)) =
        LocalApkVerifier(transport, CountingArchive(archive), installedFacts, "com.xavierxia.mycelium")

    /** 断言是「校验不过」并拿到那句人话；不是 Failed 就直接失败。 */
    private fun failureMessage(outcome: VerifyOutcome): String {
        assertTrue("期望 Failed，实际是 $outcome", outcome is VerifyOutcome.Failed)
        return (outcome as VerifyOutcome.Failed).message
    }

    // ---- `.sha256` 正文解析 ----

    @Test
    fun checksumTextAcceptsTheTwoSpaceForm() {
        val hash = "a".repeat(64)
        assertEquals(hash, ChecksumText.parseFirstSha256("$hash  mycelium-v0.4.0.apk\n"))
    }

    @Test
    fun checksumTextAcceptsTheGitBashAsteriskForm() {
        val hash = "B".repeat(64)
        assertEquals(hash.lowercase(), ChecksumText.parseFirstSha256("$hash */d/work/app-release.apk"))
    }

    @Test
    fun checksumTextRejectsTextWithoutAHash() {
        assertNull(ChecksumText.parseFirstSha256(""))
        assertNull(ChecksumText.parseFirstSha256("abc123"))
        assertNull(ChecksumText.parseFirstSha256("z".repeat(64)))
        // 65 个 hex 连在一起不是一个 64 位哈希，不能截一段出来当哈希用。
        assertNull(ChecksumText.parseFirstSha256("a".repeat(65)))
    }

    @Test
    fun checksumTextTakesTheFirstHashAndIgnoresTheRest() {
        val first = "1".repeat(64)
        val second = "2".repeat(64)
        assertEquals(first, ChecksumText.parseFirstSha256("$first\n$second\n"))
    }

    // ---- 四项校验 ----

    @Test
    fun verifierAcceptsAMatchingApk() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val outcome = runBlocking { verifier(factsOf(versionCode = 5L)).verify(apk, release) }

        // M7 起 Verified 带包里读出来的版本号：它要随会话落盘，安装前用来复查。
        assertEquals(VerifyOutcome.Verified(5L), outcome)
        assertTrue(apk.isFile)
    }

    @Test
    fun verifierRejectsChecksumMismatchAndDeletesTheFile() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk", expected = "0".repeat(64))

        val text = failureMessage(runBlocking { verifier(factsOf()).verify(apk, release) })

        assertTrue(text, text.contains("校验和不符"))
        assertFalse(apk.exists())
    }

    @Test
    fun checksumIsCheckedBeforeTheArchiveIsRead() {
        val archive = CountingArchive(factsOf())
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk", expected = "0".repeat(64))

        val outcome = runBlocking {
            LocalApkVerifier(transport, archive, CountingInstalled(installed), "com.xavierxia.mycelium")
                .verify(apk, release)
        }

        failureMessage(outcome)
        assertEquals(0, archive.reads)
    }

    @Test
    fun verifierRejectsAForeignPackageName() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val text = failureMessage(
            runBlocking { verifier(factsOf(packageName = "com.example.other")).verify(apk, release) },
        )

        assertTrue(text, text.contains("包名不对"))
        assertTrue(text, text.contains("com.example.other"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierRejectsDifferentSigner() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val text = failureMessage(
            runBlocking { verifier(factsOf(signerSha256 = "a".repeat(64))).verify(apk, release) },
        )

        assertTrue(text, text.contains("签名不一致"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierSeparatesUnreadableSignerFromMismatch() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val text = failureMessage(
            runBlocking { verifier(factsOf(signerSha256 = null, signerCount = 0)).verify(apk, release) },
        )

        assertTrue(text, text.contains("读不出包的签名"))
        assertFalse(text, text.contains("签名不一致"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierRejectsAMultiSignerApk() {
        // C4 第 6 条：只接受单签名，不能无条件取 `[0]`。
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val text = failureMessage(
            runBlocking {
                verifier(factsOf(signerSha256 = currentSigner, signerCount = 2)).verify(apk, release)
            },
        )

        assertTrue(text, text.contains("签名数量异常"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierRejectsSameOrLowerVersionCode() {
        // 第一次校验失败会连边车一起删，所以第二次要重新造一份（同一路径）。
        val same = apkWithSidecar(tmp.root, release.tagName, "real-apk")
        val sameText = failureMessage(runBlocking { verifier(factsOf(versionCode = 4L)).verify(same, release) })
        assertFalse(same.exists())

        val older = apkWithSidecar(tmp.root, release.tagName, "real-apk")
        val olderText = failureMessage(runBlocking { verifier(factsOf(versionCode = 3L)).verify(older, release) })

        assertTrue(sameText, sameText.contains("版本号没涨"))
        assertTrue(olderText, olderText.contains("版本号没涨"))
        assertFalse(older.exists())
    }

    @Test
    fun verifierDeletesTheFileWhenTheExpectedChecksumIsMissing() {
        val apk = File(tmp.root, "mycelium-${release.tagName}.apk").apply { writeText("real-apk") }

        val text = failureMessage(runBlocking { verifier(factsOf()).verify(apk, release) })

        assertTrue(text, text.contains("找不到期望的校验和"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierFailsClosedWhenTheArchiveCannotBeRead() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val text = failureMessage(runBlocking { verifier(null).verify(apk, release) })

        assertTrue(text, text.contains("读不出这个安装包"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierFailsClosedWhenTheInstalledSignerIsUnreadable() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        val text = failureMessage(
            runBlocking {
                verifier(factsOf(), CountingInstalled(factsOf(versionCode = 4L, signerSha256 = null)))
                    .verify(apk, release)
            },
        )

        assertTrue(text, text.contains("读不出当前安装版本的签名"))
        assertFalse(apk.exists())
    }

    @Test
    fun verifierDoesNotTouchTheNetwork() {
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        runBlocking { verifier(factsOf(versionCode = 5L)).verify(apk, release) }

        // C4 第 2 条：校验器不再重新联网取校验文件，期望哈希来自本地边车文件。
        assertEquals(0, transport.checksumCalls)
        assertEquals(0, transport.downloads)
        assertEquals(1, transport.hashes)
    }

    @Test
    fun installedVersionCodeComesFromTheCurrentAppOnce() {
        val installedFacts = CountingInstalled(installed)
        val apk = apkWithSidecar(tmp.root, release.tagName, "real-apk")

        runBlocking { verifier(factsOf(versionCode = 5L), installedFacts).verify(apk, release) }

        assertEquals(1, installedFacts.calls)
    }
}