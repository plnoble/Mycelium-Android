package com.xavierxia.mycelium.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * §1.5 第 1 条：**装成功以后不许再显示「可以安装」**。
 *
 * 真机走查里发生过：包已经装上了（versionCode 到 6），重启后卡片还是「下载并校验通过，可以安装」，
 * 因为老口径只比 `versionName`（待装包 0.9.9、当前 0.4.0，于是判定「还比当前新」）。现在按
 * **版本号口径**收尾：已安装的 versionCode 一旦不小于待装包的 versionCode，就清 Ready、清待装记录、
 * 删 `updates/` 里的文件。
 *
 * 三种情况都要测（统筹点名）：①装成功后进程重建；②同一个发布还在；③文件还在。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateInstalledCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: UpdatePreferences

    /** 假装装上的版本号；测例里现改，用来模拟「装完那一刻」。 */
    private var installedCode = 6L

    @Before
    fun setUp() {
        prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
        prefs.clearPending()
        prefs.pendingVersionCode = 0L
        installedCode = 6L
    }

    private fun files() = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 })

    /** 落一个「已下载待安装」的现场：候选 v0.9.9、文件在、待装包 versionCode 6。 */
    private fun pendingDownload(): Pair<UpdateFiles, File> {
        val store = files()
        val apk = store.finalFile("v0.9.9")
        apk.writeBytes(ByteArray(4096) { 7 })
        prefs.pendingReleaseJson = ReleaseJson.encode(testRelease("0.9.9"))
        prefs.pendingReleaseTag = "v0.9.9"
        prefs.pendingApkPath = apk.path
        prefs.pendingVersionCode = 6L
        return store to apk
    }

    private fun center(store: UpdateFiles, source: ReleaseSource) = testCenter(
        source = source,
        prefs = prefs,
        clock = { 100 * 3_600_000L },
        files = store,
        downloader = RecordingDownloader(),
        verifier = RecordingVerifier(),
        installer = RecordingInstaller(canInstall = false),
        installedVersionCode = { installedCode },
        currentVersionName = "0.4.0",
    )

    @Test
    fun aRestoredCandidateThatIsAlreadyInstalledIsCleanedUpInsteadOfOfferedAgain() {
        val (store, apk) = pendingDownload()

        // ① 装成功以后进程重建：新进程读盘时，已安装的 versionCode 已经等于待装包的。
        val center = center(store, SwitchableSource { testRelease("0.9.9") })

        assertEquals("不该再给「安装」", DownloadUiState.Idle, center.state.value.download)
        assertEquals("要给一句「更新完成」", INSTALLED_NOTICE, center.state.value.installNotice)
        assertEquals("待装记录要清掉", null, prefs.pendingReleaseTag)
        assertEquals("待装版本号也要清掉", 0L, prefs.pendingVersionCode)
        assertFalse("② 同一个发布还在也不该把安装入口带回来", center.state.value.download is DownloadUiState.Ready)
        assertFalse("③ 文件还在也不该给「安装」——而且文件本身要删掉", apk.exists())
    }

    @Test
    fun aCandidateThatIsNotInstalledYetStillComesBackAsReady() {
        val (store, apk) = pendingDownload()
        // 待装包比已安装的新（7 > 6）：这是正常情况，不许被上面那条清理误伤。
        prefs.pendingVersionCode = 7L

        val center = center(store, SwitchableSource { testRelease("0.9.9") })

        assertTrue("还没装上的包要照旧显示「可以安装」", center.state.value.download is DownloadUiState.Ready)
        assertTrue("文件要留着", apk.exists())
    }

    @Test
    fun aReadyFileForAVersionThatIsAlreadyInstalledIsClearedWhenTheAppComesBack() {
        val (store, apk) = pendingDownload()
        prefs.pendingVersionCode = 7L
        val center = center(store, SwitchableSource { testRelease("0.9.9") })
        assertTrue(center.state.value.download is DownloadUiState.Ready)

        // 用户在系统界面点了「安装」，装完回到前台：这时已安装的版本号追上了待装包。
        installedCode = 7L
        center.onResume()

        assertEquals("回前台后不该再显示「可以安装」", DownloadUiState.Idle, center.state.value.download)
        assertEquals(INSTALLED_NOTICE, center.state.value.installNotice)
        assertEquals("文件要删掉", false, apk.exists())
        assertEquals("待装记录要清掉", null, prefs.pendingReleaseTag)
    }

    @Test
    fun aFreshCheckForAnAlreadyInstalledReleaseDoesNotOfferInstall() {
        val (store, _) = pendingDownload()
        val source = SwitchableSource { testRelease("0.9.9") }
        val center = center(store, source)
        assertEquals(DownloadUiState.Idle, center.state.value.download)

        // 再查一次，还是那个「比当前新」的发布：可以给「下载并安装」，但绝不能凭旧文件给「安装」。
        kotlinx.coroutines.runBlocking { center.checkNow() }

        assertTrue("卡片可以还在", center.state.value.release != null)
        assertFalse("但不许拿旧文件给「安装」", center.state.value.download is DownloadUiState.Ready)
    }
}

/** 每次调用都返回同一个发布，用来模拟「同一个发布还在」。 */
private class SwitchableSource(private val release: () -> ReleaseInfo?) : ReleaseSource {
    override fun latestRelease(): ReleaseInfo? = release()
}