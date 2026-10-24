package com.xavierxia.mycelium.update

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCenterTest {
    private val hour = 60L * 60 * 1000
    private val downloadDir = File(System.getProperty("java.io.tmpdir"), "mycelium-update-test")

    private lateinit var context: Context
    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = UpdatePreferences(context)
    }

    @Test
    fun checksOnStartWhenNeverChecked() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 1_000L
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkIfDue() }

        assertEquals(1, source.calls)
        assertEquals("0.6.0", center.state.value.release?.versionName)
        assertEquals(now, prefs.lastAutoAttemptAtMs)
        assertEquals(now, prefs.lastSuccessAtMs)
    }

    @Test
    fun doesNotCheckTwiceWithinTwentyFourHours() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        prefs.lastAutoAttemptAtMs = now - 23 * hour
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkIfDue() }

        assertEquals(0, source.calls)
        assertNull(center.state.value.release)
    }

    @Test
    fun checksAgainAfterTwentyFourHours() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        prefs.lastAutoAttemptAtMs = now - 25 * hour

        runBlocking { testCenter(source, prefs, clock = { now }).checkIfDue() }

        assertEquals(1, source.calls)
    }

    @Test
    fun checksWhenTheClockMovesBackwards() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        prefs.lastAutoAttemptAtMs = now + hour

        runBlocking { testCenter(source, prefs, clock = { now }).checkIfDue() }

        assertEquals(1, source.calls)
    }

    @Test
    fun manualCheckIgnoresTheScheduleAndTheAutoAttemptTime() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        prefs.lastAutoAttemptAtMs = now
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkNow() }

        assertEquals(1, source.calls)
        // 手动检查不动自动尝试时间：自动检查的 24 小时节奏由它自己维持。
        assertEquals(now, prefs.lastAutoAttemptAtMs)
        assertEquals(now, prefs.lastSuccessAtMs)
    }

    @Test
    fun autoCheckOffSkipsTheAutomaticCheckButManualStillWorks() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        val center = testCenter(source, prefs, clock = { now })

        center.setAutoCheck(false)
        runBlocking { center.checkIfDue() }
        assertEquals(0, source.calls)
        assertFalse(prefs.autoCheck)

        runBlocking { center.checkNow() }
        assertEquals(1, source.calls)
    }

    @Test
    fun ignoredVersionIsNotShownAgain() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        var now = 100 * hour
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkIfDue() }
        assertEquals("v0.6.0", center.state.value.release?.tagName)

        center.ignore("v0.6.0")

        assertNull(center.state.value.release)
        assertEquals("v0.6.0", prefs.ignoredTag)
        assertNull(prefs.pendingReleaseJson)

        now += 25 * hour
        runBlocking { center.checkIfDue() }

        assertEquals(2, source.calls)
        assertNull(center.state.value.release)
    }

    @Test
    fun newerVersionAfterIgnoreIsShownAgain() {
        var next: ReleaseInfo? = testRelease("0.6.0")
        val source = CountingReleaseSource { next }
        var now = 100 * hour
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkIfDue() }
        center.ignore("v0.6.0")

        next = testRelease("0.5.1")
        now += 25 * hour
        runBlocking { center.checkIfDue() }

        assertEquals(2, source.calls)
        assertEquals("0.5.1", center.state.value.release?.versionName)
    }

    @Test
    fun failedAutomaticCheckShowsNoErrorAndKeepsTheSuccessTime() {
        val source = CountingReleaseSource { throw IOException("Network unavailable") }
        val now = 100 * hour
        prefs.lastSuccessAtMs = 500L
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkIfDue() }

        assertEquals(1, source.calls)
        // 失败不打扰用户。
        assertNull(center.state.value.error)
        assertNull(center.state.value.release)
        // 「上次检查」显示的是成功跑完的时间，失败不改它。
        assertEquals(500L, prefs.lastSuccessAtMs)
        // 但自动尝试时间要记：这就是「一天最多一次」。
        assertEquals(now, prefs.lastAutoAttemptAtMs)
    }

    @Test
    fun aFailedAutomaticCheckStillThrottlesTheNextAutomaticAttempt() {
        val source = CountingReleaseSource { throw IOException("Network unavailable") }
        var now = 100 * hour
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkIfDue() }
        assertEquals(1, source.calls)

        now += 23 * hour
        runBlocking { center.checkIfDue() }
        assertEquals(1, source.calls)

        now += 2 * hour
        runBlocking { center.checkIfDue() }
        assertEquals(2, source.calls)
    }

    @Test
    fun manualCheckShowsTheReasonTypeOnly() {
        val source = CountingReleaseSource { throw UpdateCheckException(UpdateError.RateLimited) }
        val now = 100 * hour
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkNow() }

        assertEquals(UpdateError.RateLimited, center.state.value.error)
    }

    @Test
    fun manualCheckDoesNotPushTheAutomaticScheduleAway() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        prefs.lastAutoAttemptAtMs = now - 23 * hour
        val center = testCenter(source, prefs, clock = { now })

        runBlocking { center.checkNow() }
        assertEquals(1, source.calls)
        assertEquals(now - 23 * hour, prefs.lastAutoAttemptAtMs)

        runBlocking { center.checkIfDue() }
        assertEquals(1, source.calls)
    }

    @Test
    fun restoresTheCandidateAfterProcessRecreation() {
        var now = 1_000L
        val source = CountingReleaseSource { testRelease("0.6.0") }
        runBlocking { testCenter(source, prefs, clock = { now }).checkIfDue() }
        assertEquals(1, source.calls)
        assertEquals("v0.6.0", prefs.pendingReleaseTag)

        // 进程被杀后重开：同一份偏好存储，新的 center；恢复不等于再查一次。
        val rebuiltSource = CountingReleaseSource { testRelease("0.6.0") }
        val rebuilt = testCenter(rebuiltSource, UpdatePreferences(context), clock = { now })

        assertEquals("0.6.0", rebuilt.state.value.release?.versionName)
        assertEquals(0, rebuiltSource.calls)

        runBlocking { rebuilt.checkIfDue() }
        assertEquals(0, rebuiltSource.calls)

        now += 25 * hour
        runBlocking { rebuilt.checkIfDue() }
        assertEquals(1, rebuiltSource.calls)
    }

    @Test
    fun aRestoredDownloadIsDroppedWhenItsFileIsGone() {
        val now = 1_000L
        runBlocking { testCenter(CountingReleaseSource { testRelease("0.6.0") }, prefs, clock = { now }).checkIfDue() }
        // 这个路径不存在；名字带上时间戳，避免和别的测例写下的文件撞车。
        prefs.pendingApkPath = File(downloadDir, "missing-${System.nanoTime()}.apk").path

        val rebuilt = testCenter(CountingReleaseSource { null }, UpdatePreferences(context), clock = { now })

        assertEquals(DownloadUiState.Idle, rebuilt.state.value.download)
        assertNull(prefs.pendingApkPath)
        // 卡片还在，只是不能再显示「可以安装」。
        assertEquals("0.6.0", rebuilt.state.value.release?.versionName)
    }

    @Test
    fun aRestoredDownloadStaysReadyWhileTheFileIsStillThere() {
        // M5：恢复出来的可安装状态必须**属于候选这个 tag**，所以文件要落在 UpdateFiles 的正式名字上。
        val apk = File(downloadDir, "mycelium-v0.6.0.apk")
        apk.parentFile?.mkdirs()
        apk.writeBytes(ByteArray(48))
        val now = 1_000L
        runBlocking { testCenter(CountingReleaseSource { testRelease("0.6.0") }, prefs, clock = { now }).checkIfDue() }
        prefs.pendingApkPath = apk.path

        val rebuilt = testCenter(CountingReleaseSource { null }, UpdatePreferences(context), clock = { now })

        val ready = rebuilt.state.value.download as? DownloadUiState.Ready
        assertNotNull(ready)
        assertEquals(apk, ready?.file)
        assertEquals(48L, ready?.size)

        // 回到前台时再核一次：文件被清掉就不能再显示「可以安装」。
        apk.delete()
        rebuilt.onResume()

        assertEquals(DownloadUiState.Idle, rebuilt.state.value.download)
        assertNull(prefs.pendingApkPath)
    }

    @Test
    fun dropsTheCandidateWhenTheVersionIsNoLongerNewer() {
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.3.0"))
        prefs.pendingReleaseTag = "v0.3.0"

        val center = testCenter(CountingReleaseSource { null }, prefs)

        assertNull(center.state.value.release)
        assertNull(prefs.pendingReleaseJson)
    }

    @Test
    fun dropsTheCandidateWhenItsTagWasIgnored() {
        prefs.ignoredTag = "v0.6.0"
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0"))
        prefs.pendingReleaseTag = "v0.6.0"

        val center = testCenter(CountingReleaseSource { null }, prefs)

        assertNull(center.state.value.release)
        assertNull(prefs.pendingReleaseJson)
    }

    @Test
    fun dropsTheCandidateWhenTheStoredTagDoesNotMatchTheMetadata() {
        // storedTag 与元数据里的 tag 故意不一致（v0.7.0 vs v0.6.0）：恢复时必须丢弃。
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0"))
        prefs.pendingReleaseTag = "v0.7.0"

        val center = testCenter(CountingReleaseSource { null }, prefs)

        assertNull(center.state.value.release)
        assertNull(prefs.pendingReleaseTag)
    }

    @Test
    fun aDebugPackageNeverAutoChecksAndHasNoInstallEntry() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        val center = testCenter(
            source,
            prefs,
            clock = { now },
            isDebugPackage = true,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = RecordingInstaller(),
        )

        assertFalse(center.installReady)

        runBlocking { center.checkIfDue() }
        assertEquals(0, source.calls)

        // 手动检查仍然显示有没有新版（调试包装正式包会变成另一个 App，所以不给安装入口）。
        runBlocking { center.checkNow() }
        assertEquals(1, source.calls)
        assertEquals("0.6.0", center.state.value.release?.versionName)
    }

    @Test
    fun aReleasePackageAutoChecksOnStartAndOffersTheInstallEntry() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        val center = testCenter(
            source,
            prefs,
            clock = { now },
            isDebugPackage = false,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = RecordingInstaller(),
        )

        assertTrue(center.installReady)

        runBlocking { center.checkIfDue() }

        assertEquals(1, source.calls)
    }

    @Test
    fun theInstallEntryStaysHiddenWhileTheDownloadPathIsNotWired() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val now = 100 * hour
        val center = testCenter(source, prefs, clock = { now })

        assertFalse(center.installReady)

        runBlocking { center.checkIfDue() }
        center.startDownload()

        // 没接线时是个 no-op：不放一个按了没反应的下载入口。
        assertEquals(DownloadUiState.Idle, center.state.value.download)
    }

    @Test
    fun theDownloadFlowGoesThroughTheExplicitFakes() {
        val source = CountingReleaseSource { testRelease("0.6.0") }
        val downloader = RecordingDownloader()
        val verifier = RecordingVerifier()
        val installer = RecordingInstaller()
        // M4 起安装前要复查空间，这里必须给一个明确的可用空间（默认走 StatFs，单测里没有真实值）。
        val files = UpdateFiles(downloadDir, availableBytes = { Long.MAX_VALUE / 4 })
        val now = 100 * hour
        val center = testCenter(
            source,
            prefs,
            clock = { now },
            files = files,
            downloader = downloader,
            verifier = verifier,
            installer = installer,
        )

        runBlocking { center.checkIfDue() }
        center.startDownload()

        val target = files.apkFile("v0.6.0")
        assertEquals(1, downloader.hashRequests)
        assertEquals(1, downloader.downloads)
        assertEquals(target, downloader.lastTarget)
        assertEquals("a".repeat(64), downloader.lastExpected)
        assertEquals(1, verifier.verifications)

        // M2：校验一过就自动接着装，不再停在 Ready 等第二次点击。
        // 这个 fake 同步返回 Installed，所以整条链会一路走到「已安装」并清掉候选与文件。
        assertEquals("校验通过就该自动建会话", 1, installer.installs)
        assertEquals(target, installer.lastApk)
        assertNull("装完就清掉待安装路径", prefs.pendingApkPath)

        // 再点一次「安装」也不会重复建会话。
        center.install()

        assertEquals(1, installer.installs)
        assertNull(center.state.value.release)
        assertNull(prefs.pendingReleaseJson)
    }

    @Test
    fun ignoredTagSurvivesRestart() {
        val center = testCenter(CountingReleaseSource { testRelease("0.6.0") }, prefs, clock = { 100 * hour })
        runBlocking { center.checkIfDue() }
        center.ignore("v0.6.0")

        val rebuilt = testCenter(CountingReleaseSource { null }, UpdatePreferences(context))

        assertEquals("v0.6.0", rebuilt.state.value.ignoredTag)
        assertNull(rebuilt.state.value.release)
    }

    @Test
    fun theAutoCheckSwitchSurvivesRestart() {
        testCenter(CountingReleaseSource { null }, prefs).setAutoCheck(false)

        assertEquals(false, UpdatePreferences(context).autoCheck)
    }
}