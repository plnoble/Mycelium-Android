package com.xavierxia.mycelium.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第 5 步（安装与「安装未知应用」引导）的编排与状态机。
 *
 * 口径全部来自 C5 定稿：未知来源没开就**不建会话**；结果分类要分得开；回前台用 versionCode
 * 做**权威判定**；落盘状态**幂等**消费、只清自己那条会话；超时只标记**待核实**，不许冒充取消。
 *
 * 只有 `InstallSessionGateway` 是假的：真机那一段（`PackageInstaller` 会话、系统确认界面、
 * `EXTRA_STATUS`/`EXTRA_INTENT` 到不到）在简报 D1–D4，白天真机验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackageInstallFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val start = 100 * HOUR
    private val tag = "v0.6.0"
    private val sessionId = 7

    private lateinit var context: Context
    private lateinit var prefs: UpdatePreferences
    private lateinit var files: UpdateFiles

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = UpdatePreferences(context)
        files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })
    }

    /** 造一个「已经下载并校验通过、可以安装」的中心（走 restore() 那条真实路径）。 */
    private fun readyCenter(
        installer: RecordingInstaller = RecordingInstaller(),
        sessionState: InstallSessionState = MemoryInstallSessionState(),
        installedVersionCode: () -> Long = { BASELINE_VERSION_CODE },
        clock: () -> Long = { start },
        currentVersionName: String = CURRENT_VERSION,
        ignoredTag: String? = null,
    ): UpdateCenter {
        val apk = files.apkFile(tag).apply { writeBytes(ByteArray(64)) }
        files.checksumFile(tag).writeText("a".repeat(64))
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0", tag = tag))
        prefs.pendingReleaseTag = tag
        prefs.pendingApkPath = apk.path
        if (ignoredTag != null) prefs.ignoredTag = ignoredTag
        return testCenter(
            source = CountingReleaseSource { testRelease("0.6.0", tag = tag) },
            prefs = prefs,
            clock = clock,
            files = files,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
            currentVersionName = currentVersionName,
            installedVersionCode = installedVersionCode,
            sessionState = sessionState,
        )
    }

    /** 测试专用：空记录只允许从初始阶段写（M7），先放一条 Created 再按规则推进到目标记录。 */
    private fun MemoryInstallSessionState.seededTo(target: InstallSessionRecord): Boolean {
        forceSeed(record(InstallPhase.Created, InstallStatusCode.PENDING_USER_ACTION))
        return advance(target)
    }

    private fun record(
        phase: InstallPhase,
        statusCode: Int,
        baselineVersionCode: Long = BASELINE_VERSION_CODE,
        updatedAtMs: Long = start,
    ) = InstallSessionRecord(
        sessionId = sessionId,
        tagName = tag,
        packageName = "com.xavierxia.mycelium",
        baselineVersionCode = baselineVersionCode,
        phase = phase,
        statusCode = statusCode,
        updatedAtMs = updatedAtMs,
    )

    // 测例 1：未授权 → 只带路，没建会话。
    @Test
    fun asksForUnknownSourcesPermissionBeforeCreatingASession() {
        val installer = RecordingInstaller(canInstall = false)
        val center = readyCenter(installer = installer)

        center.install()

        assertEquals(0, installer.installs)
        val blocked = center.state.value.download as? DownloadUiState.NeedsUnknownSources
        assertNotNull(blocked)
        assertEquals(files.apkFile(tag).path, blocked!!.file.path)
        assertNull(center.state.value.installNotice)
        // 带路带到的就是本应用的「安装未知应用」页；只带路，不代开。
        val settingsIntent = center.unknownSourcesIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settingsIntent?.action)
    }

    // 测例 2：从设置页回来、已经允许 → 立刻接着装。
    @Test
    fun startsTheSessionAfterTheUserGrantsUnknownSources() {
        val installer = RecordingInstaller(canInstall = false)
        val center = readyCenter(installer = installer)
        center.install()
        assertEquals(0, installer.installs)

        installer.canInstall = true
        center.onResume()

        assertEquals(1, installer.installs)
        assertEquals(tag, installer.lastTag)
        // 基线是提交那一刻装着的 versionCode：回前台拿它做权威判定。
        assertEquals(BASELINE_VERSION_CODE, installer.lastBaseline)
        assertNull(center.unknownSourcesIntent())
    }

    // 测例 2b：「以后再说」停在说明上，不装、也不偷偷建会话。
    @Test
    fun dismissingTheUnknownSourcesExplanationDoesNotInstall() {
        val installer = RecordingInstaller(canInstall = false)
        val center = readyCenter(installer = installer)
        center.install()

        center.dismissUnknownSources()

        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertEquals(0, installer.installs)
    }

    // 测例 3：系统回「等用户确认」→ EXTRA_INTENT 交给 launcher 拉起系统确认界面。
    @Test
    fun pendingUserActionLaunchesTheSystemConfirm() {
        val state = MemoryInstallSessionState()
        state.seededTo(record(InstallPhase.Committed, InstallStatusCode.PENDING_USER_ACTION))
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        var launched: Intent? = null
        val handler = InstallResultHandler(state, launchConfirm = { launched = it; true }, clock = { start })

        val status = handler.onStatus(sessionId, InstallStatusCode.PENDING_USER_ACTION, confirm)

        assertEquals(confirm, launched)
        assertTrue(status is InstallStatus.PendingUserAction)
        assertEquals(InstallPhase.Pending, state.read()?.phase)
    }

    // 测例 3b：EXTRA_INTENT 取不到也不能谎报失败 —— 落盘「等用户确认」，交给回前台复查。
    @Test
    fun pendingUserActionWithoutTheConfirmIntentIsStillRecorded() {
        val state = MemoryInstallSessionState()
        state.seededTo(record(InstallPhase.Committed, InstallStatusCode.PENDING_USER_ACTION))
        var launches = 0
        val handler = InstallResultHandler(state, launchConfirm = { launches += 1; true })

        val status = handler.onStatus(sessionId, InstallStatusCode.PENDING_USER_ACTION, null)

        assertTrue(status is InstallStatus.PendingUserAction)
        assertNull((status as InstallStatus.PendingUserAction).intent)
        assertEquals(0, launches)
        assertEquals(InstallPhase.Pending, state.read()?.phase)
    }

    // 测例 4：成功 → 清该 tag 的文件、重置「上次检查」、清掉忽略状态、给人话。
    @Test
    fun successClearsTheUpdatesDirectoryAndResetsTheIgnoredVersion() {
        prefs.lastAutoAttemptAtMs = start
        prefs.lastSuccessAtMs = start
        val center = readyCenter(installer = RecordingInstaller(InstallOutcome.Installed), ignoredTag = "v0.4.9")

        center.install()

        assertFalse(files.apkFile(tag).exists())
        assertFalse(files.checksumFile(tag).exists())
        assertNull(prefs.ignoredTag)
        assertEquals(0L, prefs.lastAutoAttemptAtMs)
        assertEquals(0L, prefs.lastSuccessAtMs)
        assertNull(prefs.pendingReleaseJson)
        assertNull(center.state.value.release)
        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertEquals(INSTALLED_NOTICE, center.state.value.installNotice)
    }

    // 测例 5：空间不够单独一条文案（回执落盘后由回前台认出来）。
    @Test
    fun insufficientStorageShowsItsOwnMessage() {
        val state = MemoryInstallSessionState()
        val center = readyCenter(sessionState = state)
        state.seededTo(record(InstallPhase.Failed, InstallStatusCode.FAILURE_STORAGE))

        center.onResume()

        val failed = center.state.value.download as DownloadUiState.Failed
        assertEquals(DownloadFailureReason.Installation, failed.reason)
        assertTrue(failed.message.orEmpty(), failed.message.orEmpty().contains("空间不够"))
        // 终态已消费，但文件留着（失败还能重试，不冒充取消）。
        assertNull(state.read())
        assertTrue(files.apkFile(tag).exists())
    }

    // 测例 6：用户取消 → 「已取消，安装包已清理」+ 文件被删。
    @Test
    fun userCancellationShowsCancelledAndCleansUp() {
        val state = MemoryInstallSessionState()
        val center = readyCenter(sessionState = state)
        state.seededTo(record(InstallPhase.Cancelled, InstallStatusCode.FAILURE_ABORTED))

        center.onResume()

        assertEquals(CANCELLED_NOTICE, center.state.value.installNotice)
        assertEquals(DownloadUiState.Idle, center.state.value.download)
        assertFalse(files.apkFile(tag).exists())
        assertFalse(files.checksumFile(tag).exists())
        assertNull(state.read())
    }

    // 测例 7：blocked / conflict / incompatible / invalid 各有各的中文文案。
    @Test
    fun failuresGetDistinctChineseMessages() {
        val codes = listOf(
            InstallStatusCode.FAILURE_BLOCKED,
            InstallStatusCode.FAILURE_CONFLICT,
            InstallStatusCode.FAILURE_INCOMPATIBLE,
            InstallStatusCode.FAILURE_INVALID,
        )
        val messages = codes.map { code ->
            val state = MemoryInstallSessionState()
            val center = readyCenter(sessionState = state)
            state.seededTo(record(InstallPhase.Failed, code))
            center.onResume()
            val failed = center.state.value.download as DownloadUiState.Failed
            assertEquals(DownloadFailureReason.Installation, failed.reason)
            failed.message.orEmpty()
        }

        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages[0].contains("设备策略"))
        assertTrue(messages[1].contains("冲突"))
        assertTrue(messages[2].contains("不兼容"))
        assertTrue(messages[3].contains("无效"))
    }

    // 测例 8：一条广播都没到，但回前台读到新 versionCode → 判定成功。
    @Test
    fun versionCodeChangeOnResumeIsTheFinalConfirmation() {
        var installed = BASELINE_VERSION_CODE
        val state = MemoryInstallSessionState()
        val center = readyCenter(sessionState = state, installedVersionCode = { installed })
        state.seededTo(record(InstallPhase.Pending, InstallStatusCode.PENDING_USER_ACTION))

        installed = BASELINE_VERSION_CODE + 1
        center.onResume()

        assertEquals(INSTALLED_NOTICE, center.state.value.installNotice)
        assertFalse(files.apkFile(tag).exists())
        assertNull(state.read())
        assertNull(center.state.value.release)
    }

    // 测例 8b：同一个状态里，versionCode 没变就绝不能判成功；超时也只标记待核实。
    @Test
    fun unchangedVersionCodeAfterTheWindowIsMarkedForVerificationNotCancelled() {
        val state = MemoryInstallSessionState()
        // 「现在」已经超出窗口期：超时只能标记待核实（C5 第 4 条）。
        val center = readyCenter(
            sessionState = state,
            clock = { start + INSTALL_VERIFICATION_WINDOW_MS + 1 },
        )
        state.seededTo(record(InstallPhase.Pending, InstallStatusCode.PENDING_USER_ACTION, updatedAtMs = start))

        center.onResume()

        assertEquals(DownloadUiState.NeedsVerification, center.state.value.download)
        assertEquals(VERIFY_NOTICE, center.state.value.installNotice)
        // C5 第 4 条：不许把没完成的安装冒充取消——文件不删，落盘记录也不清。
        assertTrue(files.apkFile(tag).exists())
        assertEquals(InstallPhase.Pending, state.read()?.phase)
    }

    // 测例 8c：会话在系统里已经没了、版本又没变 → 同样是待核实，不是取消。
    @Test
    fun aSessionThatDisappearedWithoutAVersionChangeIsNotCancelled() {
        val state = MemoryInstallSessionState()
        val installer = RecordingInstaller(alive = false)
        val center = readyCenter(installer = installer, sessionState = state)
        state.seededTo(record(InstallPhase.Committed, InstallStatusCode.PENDING_USER_ACTION, updatedAtMs = start))

        center.onResume()

        // M7：确认界面根本没拉起来（会话已不在）→ 清记录、回「可以安装」，用户可再点一次安装。
        assertTrue(center.state.value.download is DownloadUiState.Ready)
        assertTrue(files.apkFile(tag).exists())
        assertTrue(state.read() == null)
    }

    // 测例 9：调试包没有安装入口，也不做未知来源引导。
    @Test
    fun debugPackageHasNoInstallEntry() {
        val installer = RecordingInstaller(canInstall = false)
        val apk = files.apkFile(tag).apply { writeBytes(ByteArray(64)) }
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0", tag = tag))
        prefs.pendingReleaseTag = tag
        prefs.pendingApkPath = apk.path
        val center = testCenter(
            source = CountingReleaseSource { testRelease("0.6.0", tag = tag) },
            prefs = prefs,
            clock = { start },
            isDebugPackage = true,
            files = files,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = installer,
        )

        assertFalse(center.installReady)
        center.install()

        assertEquals(0, installer.installs)
        assertFalse(center.state.value.download is DownloadUiState.NeedsUnknownSources)
        assertNull(center.unknownSourcesIntent())
    }

    // 测例 10：终态之后再来的迟到 / 重复回调一律不作数（按 sessionId 幂等落库）。
    @Test
    fun lateAndDuplicateCallbacksAreIgnoredAfterATerminalResult() {
        val state = MemoryInstallSessionState()
        state.seededTo(record(InstallPhase.Committed, InstallStatusCode.PENDING_USER_ACTION))
        val handler = InstallResultHandler(state, clock = { start })

        assertNotNull(handler.onStatus(sessionId, InstallStatusCode.SUCCESS, null))
        assertNull(handler.onStatus(sessionId, InstallStatusCode.FAILURE_ABORTED, null))
        assertNull(handler.onStatus(sessionId, InstallStatusCode.SUCCESS, null))
        assertEquals(InstallPhase.Succeeded, state.read()?.phase)
    }

    // 测例 11：别的会话的回执不理；清理只清自己那条。
    @Test
    fun onlyOurOwnSessionIsRecordedAndCleared() {
        val state = MemoryInstallSessionState()
        state.seededTo(record(InstallPhase.Committed, InstallStatusCode.PENDING_USER_ACTION))
        val handler = InstallResultHandler(state, clock = { start })

        assertNull(handler.onStatus(sessionId + 1, InstallStatusCode.SUCCESS, null))
        assertEquals(InstallPhase.Committed, state.read()?.phase)

        assertFalse(state.clear(sessionId + 1))
        assertNotNull(state.read())
        assertTrue(state.clear(sessionId))
        assertNull(state.read())
        // 清干净之后再来的回执也没有落点：不会凭空造出一条记录。
        assertNull(handler.onStatus(sessionId, InstallStatusCode.SUCCESS, null))
    }

    // 测例 12（C5 验收证据）：写 → fsync → **关流** → commit，而且整包按流写完。
    @Test
    fun writesThenFsyncsThenClosesBeforeCommit() {
        val apk = File(tmp.root, "big.apk").apply { writeBytes(ByteArray(INSTALL_COPY_BUFFER_BYTES * 2 + 5)) }
        val handle = RecordingSessionHandle()

        copyApkIntoSession(handle, apk)
        handle.commit()

        assertEquals(
            listOf("openWrite", "fsync", "closeWrite", "commit"),
            handle.events.filter { it != "write" },
        )
        assertEquals(apk.length(), handle.writtenBytes)
        // 关流必须发生在 commit 之前：真机上是「系统可能正在处理包」与「还没交出去」的分界。
        assertTrue(handle.events.indexOf("closeWrite") < handle.events.indexOf("commit"))
    }

    // 测例 13：进程死亡后，新进程靠落盘的候选发布认出「已经装上了」，清理与提示一个不少。
    @Test
    fun processDeathAfterSuccessIsRecognisedFromTheStoredCandidate() {
        val apk = files.apkFile(tag).apply { writeBytes(ByteArray(64)) }
        files.checksumFile(tag).writeText("a".repeat(64))
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.6.0", tag = tag))
        prefs.pendingReleaseTag = tag
        prefs.pendingApkPath = apk.path
        prefs.ignoredTag = tag

        // 新进程里跑着的已经是 0.6.0 了：候选发布不再比当前版本新。
        val center = testCenter(
            source = CountingReleaseSource { null },
            prefs = prefs,
            clock = { start },
            files = files,
            downloader = RecordingDownloader(),
            verifier = RecordingVerifier(),
            installer = RecordingInstaller(),
            currentVersionName = "0.6.0",
        )

        assertEquals(INSTALLED_NOTICE, center.state.value.installNotice)
        assertFalse(files.apkFile(tag).exists())
        assertNull(prefs.ignoredTag)
        assertNull(prefs.pendingReleaseJson)
        assertNull(center.state.value.release)
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val BASELINE_VERSION_CODE = 4L
    }
}

/**
 * 会话写入的假实现：把「谁在什么时候被调用」按顺序记下来。
 * 关流用的是 `use` 包在真流外面，所以 close 这件事也看得见。
 */
private class RecordingSessionHandle : InstallSessionHandle {
    val events = mutableListOf<String>()
    var writtenBytes = 0L
        private set

    override fun openWrite(name: String, offset: Long, size: Long): OutputStream {
        events += "openWrite"
        return object : OutputStream() {
            override fun write(b: Int) {
                events += "write"
                writtenBytes += 1
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                events += "write"
                writtenBytes += len
            }

            override fun close() {
                events += "closeWrite"
            }
        }
    }

    override fun fsync(stream: OutputStream) {
        events += "fsync"
    }

    override fun commit() {
        events += "commit"
    }

    override fun abandon() {
        events += "abandon"
    }

    override fun close() {
        events += "closeSession"
    }
}
