package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.GatewayCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * M5：候选版本和下载状态、文件必须绑在一起。
 *
 * 两个真问题：
 *  * 已经有 v0.6.0 的包（就绪或待安装），又查到 v0.7.0 —— 旧包不能按新 tag 交出去；
 *  * 检查失败（断网）不能把已知候选清掉，否则卡片、续传目标和 `.part` 一起丢。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCenterCandidateBindingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
    }

    /** 可切换、可抛错的发布源：同一个 center 上模拟「又查了一次」。 */
    private class SwitchableSource : ReleaseSource {
        var next: () -> ReleaseInfo? = { testRelease("0.6.0") }
        override fun latestRelease(): ReleaseInfo? = next()
    }

    private fun center(installer: InstallSessionGateway): Pair<UpdateCenter, SwitchableSource> {
        val source = SwitchableSource()
        val center = testCenter(
            source = source,
            prefs = prefs,
            clock = { 100 * 3_600_000L },
            files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 }),
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
        )
        return center to source
    }

    @Test
    fun switchingToANewerTagDropsTheOldPackageAndItsCache() {
        // 没允许「安装未知应用」：下载与校验都会成功，但停在「说明」上，文件还留着。
        val installer = RecordingInstaller(canInstall = false)
        val (center, source) = center(installer)
        runBlocking { center.checkIfDue() }
        center.startDownload()

        val oldTag = "v0.6.0"
        val oldFile = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 }).finalFile(oldTag)
        assertTrue("旧版本的包应该已经下好", oldFile.isFile)
        assertTrue(center.state.value.download is DownloadUiState.NeedsUnknownSources)

        // 又查到更新的版本：旧包与旧状态必须一起作废。
        source.next = { testRelease("0.7.0") }
        runBlocking { center.checkNow() }

        assertEquals("v0.7.0", center.state.value.release?.tagName)
        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertFalse("旧版本的包要清掉，不能留给新 tag", oldFile.exists())
        assertNull(prefs.pendingApkPath)

        // 回前台也不会把旧包装上去。
        installer.canInstall = true
        center.onResume()
        assertEquals("旧包不许按新版本安装", 0, installer.installs)
    }

    @Test
    fun aFailedCheckKeepsTheKnownCandidateAndItsResumeTarget() {
        val (center, source) = center(RecordingInstaller())
        runBlocking { center.checkIfDue() }
        assertEquals("v0.6.0", center.state.value.release?.tagName)
        val tagBefore = prefs.pendingReleaseTag
        val jsonBefore = prefs.pendingReleaseJson

        source.next = { throw IOException("offline") }
        runBlocking { center.checkNow() }

        assertTrue("失败要报错", center.state.value.lastResult is UpdateResult.Error)
        assertEquals("断网不能把卡片弄丢", "v0.6.0", center.state.value.release?.tagName)
        assertEquals("候选 tag 要留着，续传才接得上", tagBefore, prefs.pendingReleaseTag)
        assertEquals("候选元数据也要留着", jsonBefore, prefs.pendingReleaseJson)
    }

    @Test
    fun aReadyFileThatBelongsToAnotherTagIsNeverInstalled() {
        // 落盘的候选是 v0.6.0，但待安装路径指着一个属于 v0.4.9 的文件：恢复时必须丢掉它。
        val files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
        val stale = files.finalFile("v0.4.9")
        stale.parentFile?.mkdirs()
        stale.writeBytes(ByteArray(8))
        prefs.pendingReleaseTag = "v0.6.0"
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0"))
        prefs.pendingApkPath = stale.path

        val (center, _) = center(RecordingInstaller())
        runBlocking { center.checkIfDue() }

        assertTrue(
            "别的 tag 的文件不能成为可安装状态",
            center.state.value.download !is DownloadUiState.Ready,
        )
        assertNull("对不上的待安装路径要清掉", prefs.pendingApkPath)
    }
    /** M5：候选消失（检查成功但不再有更新）也算候选变了。 */
    @Test
    fun aCandidateThatDisappearsAlsoDropsTheOldPackageAndItsCache() {
        val installer = RecordingInstaller(canInstall = false)
        val store = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
        val source = SwitchableSource()
        val center = testCenter(
            source = source,
            prefs = prefs,
            clock = { 100 * 3_600_000L },
            files = store,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
        )
        runBlocking { center.checkIfDue() }
        center.startDownload()
        runBlocking { kotlinx.coroutines.delay(300) }
        val tag = center.state.value.release!!.tagName
        assertTrue("先得有一个下好的包", store.apkFile(tag).exists())

        source.next = { null } // 上游不再有更新：候选消失
        runBlocking { center.checkNow() }
        runBlocking { kotlinx.coroutines.delay(300) }

        assertNull("候选消失后卡片要清掉", center.state.value.release)
        assertTrue(center.state.value.download is DownloadUiState.Idle)
        assertFalse("候选消失后旧包也要删掉", store.apkFile(tag).exists())
    }

    /** M5：上个候选的迟到安装回执不许改这一轮的状态。 */
    @Test
    fun aTerminalRecordForAnOlderCandidateIsIgnoredInsteadOfApplied() {
        val installer = RecordingInstaller(canInstall = false)
        val state = MemoryInstallSessionState()
        val store = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
        val source = SwitchableSource()
        val center = testCenter(
            source = source,
            prefs = prefs,
            clock = { 100 * 3_600_000L },
            files = store,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
            sessionState = state,
        )
        runBlocking { center.checkIfDue() } // 当前候选 v0.6.0
        state.advance(oldRecord(InstallPhase.Created))
        state.advance(oldRecord(InstallPhase.Succeeded, InstallStatusCode.SUCCESS))

        center.onResume()

        assertEquals("旧候选的回执不许改状态", "v0.6.0", center.state.value.release?.tagName)
        assertTrue("旧候选的记录要被清掉", state.read() == null)
    }

    /** 一个**不是**当前候选的旧记录。 */
    private fun oldRecord(phase: InstallPhase, status: Int = 0) = InstallSessionRecord(
        sessionId = 7,
        tagName = "v0.4.9",
        packageName = "com.xavierxia.mycelium",
        baselineVersionCode = 4L,
        phase = phase,
        statusCode = status,
        updatedAtMs = 1L,
        targetVersionCode = 5L,
    )
}