package com.xavierxia.mycelium.update

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

/**
 * M7（用户 2026-10-20 定的简化口径）：不做跨进程恢复安装。
 *
 *  * 进程重建时发现上一个进程留下的会话 → 凭**落盘的 sessionId** 放弃并清记录，文件还在就回「已下载、待安装」；
 *  * 安装前再核一次文件（还在、长度没变），这一版要是已经装上了就不建会话；
 *  * 落盘状态**单点串行写、阶段单调**：迟到的 `Committed` 不许把 `Pending` 盖回去，终态不许被越过去。
 */
@RunWith(RobolectricTestRunner::class)
class InstallSessionRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: UpdatePreferences
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
    }

    /** 测试专用：空记录只允许从初始阶段写（M7），先放一条 Created 再按规则推进到目标记录。 */
    private fun MemoryInstallSessionState.seededTo(target: InstallSessionRecord): Boolean {
        forceSeed(record(InstallPhase.Created))
        return advance(target)
    }

    private fun record(phase: InstallPhase, status: Int = 0) = InstallSessionRecord(
        sessionId = 42,
        tagName = "v0.6.0",
        packageName = "com.xavierxia.mycelium",
        baselineVersionCode = 4L,
        phase = phase,
        statusCode = status,
        updatedAtMs = 1L,
        targetVersionCode = 5L,
    )

    private fun files() = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })

    /** 落盘里躺着「上个进程提交过、但还没结果」的会话，外加一个已下好的包。 */
    private fun previousProcessLeftASession(): Triple<UpdateFiles, java.io.File, MemoryInstallSessionState> {
        val store = files()
        val apk = store.finalFile("v0.6.0")
        apk.parentFile?.mkdirs()
        apk.writeBytes(ByteArray(16))
        prefs.pendingReleaseTag = "v0.6.0"
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0"))
        prefs.pendingApkPath = apk.path
        val state = MemoryInstallSessionState()
        // 真实实现总是先写 Created：空记录只接受初始阶段（M7）。
        state.seededTo(record(InstallPhase.Created))
        state.seededTo(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION))
        return Triple(store, apk, state)
    }

    @Test
    fun aSessionLeftByAPreviousProcessIsAbandonedAndThePackageGoesBackToReady() {
        val (store, apk, state) = previousProcessLeftASession()
        val installer = RecordingInstaller()

        val center = testCenter(
            source = CountingReleaseSource { testRelease("0.6.0") },
            prefs = prefs,
            clock = { 100 * hour },
            files = store,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
            sessionState = state,
        )

        assertEquals("上个进程留下的会话要凭 id 放弃一次", 1, installer.abandons)
        assertNull("记录要清掉", state.read())
        assertTrue("包还在，就该回到「已下载、待安装」", center.state.value.download is DownloadUiState.Ready)
        assertEquals(apk, (center.state.value.download as DownloadUiState.Ready).file)
    }

    @Test
    fun installingAfterThePackageDisappearedFailsCleanly() {
        val (store, apk, state) = previousProcessLeftASession()
        val installer = RecordingInstaller()
        val center = testCenter(
            source = CountingReleaseSource { testRelease("0.6.0") },
            prefs = prefs,
            clock = { 100 * hour },
            files = store,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
            sessionState = state,
        )
        assertTrue(center.state.value.download is DownloadUiState.Ready)

        // 用户点「继续安装」之前文件被系统清掉了。
        apk.delete()
        center.install()

        assertEquals("文件不在就不许建会话", 0, installer.installs)
        assertTrue("要给明确的失败，而不是静默不动", center.state.value.download is DownloadUiState.Failed)
        assertNull(prefs.pendingApkPath)
    }

    @Test
    fun aTargetThatIsAlreadyInstalledStartsNoSession() {
        val installer = RecordingInstaller()
        val center = testCenter(
            source = CountingReleaseSource { testRelease("0.6.0") },
            prefs = prefs,
            clock = { 100 * hour },
            files = files(),
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(VerifyOutcome.Verified(5L)),
            installer = installer,
            installedVersionCode = { 5L },
        )
        runBlocking { center.checkIfDue() }

        center.startDownload()

        assertEquals("这一版已经装上了，不该再建会话", 0, installer.installs)
        assertEquals(INSTALLED_NOTICE, center.state.value.installNotice)
    }

    @Test
    fun aLateCommittedCannotRollBackAPendingRecord() {
        val state = MemoryInstallSessionState()
        assertTrue(state.advance(record(InstallPhase.Created)))
        assertTrue(state.advance(record(InstallPhase.Committed)))
        assertTrue(state.seededTo(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)))

        assertFalse("迟到的 Committed 不许把 Pending 盖回去", state.advance(record(InstallPhase.Committed)))
        assertEquals(InstallPhase.Pending, state.read()?.phase)
    }

    @Test
    fun aRepeatedPendingIsRejected() {
        val state = MemoryInstallSessionState()
        assertTrue(state.seededTo(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)))

        assertFalse("重复的 Pending 不该再落一次盘", state.advance(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)))
    }

    @Test
    fun aTerminalRecordIsAbsorbing() {
        val state = MemoryInstallSessionState()
        assertTrue(state.seededTo(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)))
        assertTrue(state.advance(record(InstallPhase.Cancelled, status = InstallStatusCode.FAILURE_ABORTED)))

        assertFalse("终态之后成功也不作数", state.advance(record(InstallPhase.Succeeded, status = InstallStatusCode.SUCCESS)))
        assertFalse("终态之后不许回到 Pending", state.advance(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)))
        assertEquals(InstallPhase.Cancelled, state.read()?.phase)
    }
    /** M7：空记录只允许从初始阶段写——凭空一条 Pending/Succeeded 不许落盘。 */
    @Test
    fun anEmptyStoreOnlyAcceptsTheInitialPhase() {
        val fresh = MemoryInstallSessionState()
        assertFalse(
            "空记录不许直接写成 Pending",
            fresh.advance(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)),
        )
        assertFalse(
            "空记录不许直接写成 Succeeded",
            fresh.advance(record(InstallPhase.Succeeded, status = InstallStatusCode.SUCCESS)),
        )
        assertTrue("初始阶段可以写", fresh.advance(record(InstallPhase.Created)))
        assertTrue(
            "写成 Created 之后就能按规则往前推",
            fresh.advance(record(InstallPhase.Pending, status = InstallStatusCode.PENDING_USER_ACTION)),
        )
    }
}