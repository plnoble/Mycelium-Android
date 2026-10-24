package com.xavierxia.mycelium.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 空间公式与清理语义（方案审 C4 第 3、4 条）。全部 JVM：可用空间是注入的假值，
 * 真机的 `StatFs` 取值只能白天真机看（简报验收清单）。
 */
class UpdateFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mib = 1024L * 1024
    private val size38 = 38 * mib

    private fun files(free: Long) = UpdateFiles(tmp.root, availableBytes = { free })

    // ---- 空间公式：剩余下载量 + 一份安装会话大小 + 余量预算 ----

    @Test
    fun requiredSpaceCountsTheSessionCopyAndTheMargin() {
        // 38 MiB 的包：会话副本再来一份 38 MiB，余量 max(32 MiB, 38/4 MiB) = 32 MiB。
        assertEquals(38 * mib + 38 * mib + 32 * mib, files(0).requiredBytes(size38))

        // 大包自动放宽：200 MiB → 200 + 200 + 50。
        assertEquals(200 * mib + 200 * mib + 50 * mib, files(0).requiredBytes(200 * mib))
    }

    @Test
    fun resumedBytesAreNotCountedTwice() {
        val fresh = files(0).requiredBytes(size38, existingBytes = 0)
        val half = files(0).requiredBytes(size38, existingBytes = size38 / 2)
        val full = files(0).requiredBytes(size38, existingBytes = size38)

        // M4 修正后的口径：已在盘上的字节不占新增空间。
        assertEquals(38 * mib + 38 * mib + 32 * mib, fresh)
        assertEquals(19 * mib + 38 * mib + 32 * mib, half)
        assertEquals(38 * mib + 32 * mib, full)
        // 续传必须比从头下更省 —— 旧公式让两者相等，那正是审核说的「把续传误判成不够」。
        assertTrue("续传要比从头下更省", half < fresh)
        assertTrue("下完了只剩会话副本与余量", full < half)
    }

    @Test
    fun aHalfDownloadedPackagePassesWithNinetyMiBFree() {
        // 审核点名的场景：38 MiB 的包已下 19 MiB、可用 90 MiB → 需要 89 MiB，必须放行；少一字节才拦。
        files(90 * mib).requireSpace(size38, existingBytes = size38 / 2)
        assertThrows(SpaceShortage::class.java) {
            files(89 * mib - 1).requireSpace(size38, existingBytes = size38 / 2)
        }
    }

    @Test
    fun theInstallStepNeedsTheSessionCopyAgain() {
        // 下载完只剩 70 MiB：会话副本 38 + 余量 32，安装前必须被拦下（M4）。
        files(70 * mib).requireSessionSpace(size38)
        assertThrows(SpaceShortage::class.java) { files(70 * mib - 1).requireSessionSpace(size38) }
    }

    @Test
    fun unknownSizeUsesTheSixtyFourMiBFloor() {
        assertEquals(64 * mib, files(0).requiredBytes(null))
        assertEquals(64 * mib, files(0).requiredBytes(0L))
    }

    @Test
    fun requireSpaceIsSilentExactlyAtTheBoundary() {
        val needed = files(0).requiredBytes(size38)
        files(needed).requireSpace(size38)
        assertThrows(SpaceShortage::class.java) { files(needed - 1).requireSpace(size38) }
    }

    @Test
    fun spaceShortageMessageSaysHowMuchIsMissing() {
        val lack = assertThrows(SpaceShortage::class.java) {
            files(90 * mib).requireSpace(size38, existingBytes = 0)
        }

        val text = lack.message.orEmpty()
        assertTrue(text, text.contains("需要 ${UpdateFiles.humanMiB(108 * mib)}"))
        assertTrue(text, text.contains("可用只有 ${UpdateFiles.humanMiB(90 * mib)}"))
        assertTrue(text, text.contains("还差 ${UpdateFiles.humanMiB(18 * mib)}"))
    }

    @Test
    fun unknownSizeOverTheFloorIsRefused() {
        assertThrows(SpaceShortage::class.java) { files(64 * mib - 1).requireSpace(null) }
    }

    // ---- `.part` / promote / 清理 ----

    @Test
    fun promoteRenamesThePartFile() {
        val files = files(0)
        files.ensureDirectory()
        val part = files.partFile("v0.6.0").apply { writeText("half") }

        val promoted = files.promote("v0.6.0")

        assertEquals(files.finalFile("v0.6.0"), promoted)
        assertTrue(promoted.isFile)
        assertFalse(part.exists())
    }

    @Test
    fun deleteForTagRemovesTheApkThePartAndTheChecksum() {
        val files = files(0)
        files.ensureDirectory()
        files.finalFile("v0.6.0").writeText("apk")
        files.partFile("v0.6.0").writeText("part")
        files.checksumFile("v0.6.0").writeText("hash")

        files.deleteForTag("v0.6.0")

        assertFalse(files.finalFile("v0.6.0").exists())
        assertFalse(files.partFile("v0.6.0").exists())
        assertFalse(files.checksumFile("v0.6.0").exists())
    }

    @Test
    fun partialFileOfAnotherTagIsDeletedWhenANewTagStarts() {
        val files = files(0)
        files.ensureDirectory()
        val old = files.partFile("v0.4.0").apply { writeText("old") }
        val current = files.partFile("v0.6.0").apply { writeText("current") }

        files.deleteForeignParts("v0.6.0")

        assertFalse(old.exists())
        assertTrue(current.exists())
    }

    @Test
    fun deleteForeignPartsSkipsFilesInUse() {
        val files = files(0)
        files.ensureDirectory()
        val running = files.partFile("v0.4.0").apply { writeText("running") }

        files.deleteForeignParts("v0.6.0", inUse = { it == running })

        assertTrue(running.exists())
    }

    @Test
    fun purgeDeletesFilesOlderThanSevenDays() {
        val files = files(0)
        files.ensureDirectory()
        val now = 1_000L * UpdateFiles.PURGE_AGE_MS
        val old = now - 8 * 24 * 60 * 60 * 1000L
        val apk = files.finalFile("v0.4.0").apply { writeText("apk") }.also { it.setLastModified(old) }
        val part = files.partFile("v0.4.0").apply { writeText("part") }.also { it.setLastModified(old) }
        val sha = files.checksumFile("v0.4.0").apply { writeText("hash") }.also { it.setLastModified(old) }

        val purged = files.purge(now, keepTagName = null)

        assertEquals(3, purged.size)
        assertFalse(apk.exists())
        assertFalse(part.exists())
        assertFalse(sha.exists())
    }

    @Test
    fun purgeKeepsTheCurrentPartialFile() {
        val files = files(0)
        files.ensureDirectory()
        val now = 1_000L * UpdateFiles.PURGE_AGE_MS
        val old = now - 8 * 24 * 60 * 60 * 1000L
        // 明明过期了，但因为它是当前 tag 的续传前提，必须留着。
        val current = files.partFile("v0.6.0").apply { writeText("resume") }.also { it.setLastModified(old) }

        files.purge(now, keepTagName = "v0.6.0")

        assertTrue(current.exists())
    }

    @Test
    fun purgeSkipsFilesInUse() {
        val files = files(0)
        files.ensureDirectory()
        val now = 1_000L * UpdateFiles.PURGE_AGE_MS
        val old = now - 8 * 24 * 60 * 60 * 1000L
        val busy = files.partFile("v0.4.0").apply { writeText("busy") }.also { it.setLastModified(old) }

        files.purge(now, keepTagName = "v0.6.0", inUse = { it == busy })

        assertTrue(busy.exists())
    }

    @Test
    fun purgeLeavesTheFreshFilesAlone() {
        val files = files(0)
        files.ensureDirectory()
        val now = 1_000L * UpdateFiles.PURGE_AGE_MS
        val fresh = files.finalFile("v0.6.0").apply { writeText("apk") }

        assertTrue(files.purge(now, keepTagName = null).isEmpty())
        assertTrue(fresh.exists())
    }

    @Test
    fun existingBytesPrefersTheLargerOfPartAndFinal() {
        val files = files(0)
        files.ensureDirectory()
        files.partFile("v0.6.0").writeText("12345")
        files.finalFile("v0.6.0").writeText("1234567")

        assertEquals(7L, files.existingBytes("v0.6.0"))
    }

    @Test
    fun freeBytesComesFromTheInjectedProbe() {
        assertEquals(1234L, files(1234L).freeBytes())
    }

    @Test
    fun checksumFileNameMatchesTheVerifierSidecar()  {
        val files = files(0)
        val apk = files.finalFile("v0.6.0")
        assertEquals(File(tmp.root, "mycelium-v0.6.0.apk.sha256"), files.checksumFile("v0.6.0"))
        assertEquals("mycelium-v0.6.0.apk.sha256", File(apk.parentFile, apk.name + ".sha256").name)
    }
}