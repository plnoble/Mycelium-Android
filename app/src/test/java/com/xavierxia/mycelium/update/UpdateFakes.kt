package com.xavierxia.mycelium.update

import android.content.Intent
import android.provider.Settings
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.DownloadResult
import com.xavierxia.mycelium.net.GatewayCall
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 第 3 步的测例只用**显式 fake**（没有 mock 框架）：调用次数、参数、结果都看得见，
 * 下载/校验/安装三件没接线时用 `Unwired*`，接线后在这里换掉。
 *
 * 仓库版本是 0.4.0，所以「有新版」用 0.6.0，「不比当前新」用 0.3.0。
 */
internal const val CURRENT_VERSION = "0.4.0"

internal fun testRelease(
    version: String,
    tag: String = "v$version",
    installable: Boolean = true,
): ReleaseInfo = ReleaseInfo(
    versionName = version,
    tagName = tag,
    releasePageUrl = "https://github.com/plnoble/Mycelium-Android/releases/tag/$tag",
    apkUrl = if (installable) {
        "https://github.com/plnoble/Mycelium-Android/releases/download/$tag/mycelium-$tag.apk"
    } else {
        null
    },
    apkSizeBytes = if (installable) 38L * 1024 * 1024 + 512 * 1024 else null,
    sha256Url = if (installable) {
        "https://github.com/plnoble/Mycelium-Android/releases/download/$tag/mycelium-$tag.apk.sha256"
    } else {
        null
    },
    releaseNotes = "修了几个问题",
    publishedAt = "2026-10-18T00:00:00Z",
)

internal class CountingReleaseSource(private val answer: () -> ReleaseInfo?) : ReleaseSource {
    var calls = 0
        private set

    override fun latestRelease(): ReleaseInfo? {
        calls += 1
        return answer()
    }
}

/** 接线后的下载器：记下取哈希与下载各几次、下到哪个文件，并真的写一个文件出来。 */
internal class RecordingDownloader(private val expected: String? = "a".repeat(64)) : ApkDownloader {
    override val isAvailable: Boolean = true
    var hashRequests = 0
    var downloads = 0
    var lastTarget: File? = null
    var lastExpected: String? = null

    override suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String? {
        hashRequests += 1
        return expected
    }

    override suspend fun downloadApk(
        release: ReleaseInfo,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        onProgress: DownloadProgress?,
    ): DownloadOutcome {
        downloads += 1
        lastTarget = target
        lastExpected = expectedSha256
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(32))
        onProgress?.onProgress(32, 32)
        return DownloadOutcome.Completed(
            DownloadResult(
                status = 200,
                finalUrl = release.apkUrl.orEmpty(),
                hosts = listOf("github.com"),
                bytesWritten = 32,
                fileSize = 32,
                redirects = 1,
                restartedFromZero = false,
            ),
            expectedSha256,
        )
    }
}

internal class RecordingVerifier(private val outcome: VerifyOutcome = VerifyOutcome.Verified(0L)) : ApkVerifier {
    override val isAvailable: Boolean = true
    var verifications = 0
        private set
    /** 最近一次校验拿到的取消句柄：测例据此断言「校验与下载共用同一个句柄」（M3）。 */
    var lastCall: GatewayCall? = null
        private set

    override suspend fun verify(apk: File, release: ReleaseInfo, call: GatewayCall): VerifyOutcome {
        verifications += 1
        lastCall = call
        return outcome
    }
}

/**
 * 显式 fake（没有 mock 框架）：装了几次、装了哪一版、基线是多少、有没有被放弃，全都看得见。
 * 第 5 步的网关多了「未知来源」两问与会话存活性，所以这三个开关都可变，方便测「授权之后接着装」。
 */
internal class RecordingInstaller(
    private val outcome: InstallOutcome = InstallOutcome.Installed,
    var canInstall: Boolean = true,
    var alive: Boolean = true,
) : InstallSessionGateway {
    override val isAvailable: Boolean = true
    var installs = 0
        private set
    var cancels = 0
        private set
    var lastApk: File? = null
        private set
    var lastTag: String? = null
        private set
    var lastBaseline: Long = 0L
        private set
    var lastTargetVersionCode: Long = 0L
        private set
    var abandons = 0
        private set

    override fun canRequestPackageInstalls(): Boolean = canInstall

    override fun unknownSourcesIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)

    override suspend fun install(
        apk: File,
        tagName: String,
        baselineVersionCode: Long,
        targetVersionCode: Long,
    ): InstallOutcome {
        installs += 1
        lastApk = apk
        lastTag = tagName
        lastBaseline = baselineVersionCode
        lastTargetVersionCode = targetVersionCode
        return outcome
    }

    override fun cancel() {
        cancels += 1
    }

    override fun abandon(sessionId: Int) {
        abandons += 1
    }

    override fun sessionAlive(sessionId: Int): Boolean = alive
}

internal fun testCenter(
    source: ReleaseSource,
    prefs: UpdatePreferences,
    clock: () -> Long = { 0L },
    isDebugPackage: Boolean = false,
    files: UpdateFiles = UpdateFiles(File(System.getProperty("java.io.tmpdir"), "mycelium-update-test")),
    downloader: ApkDownloader = UnwiredApkDownloader,
    verifier: ApkVerifier = UnwiredApkVerifier,
    installer: InstallSessionGateway = UnwiredInstallSessionGateway,
    currentVersionName: String = CURRENT_VERSION,
    installedVersionCode: () -> Long = { 4L },
    sessionState: InstallSessionState = MemoryInstallSessionState(),
): UpdateCenter = UpdateCenter(
    updates = UpdateManager(source),
    prefs = prefs,
    isDebugPackage = isDebugPackage,
    files = files,
    downloader = downloader,
    verifier = verifier,
    installer = installer,
    clock = clock,
    currentVersionName = currentVersionName,
    installedVersionCode = installedVersionCode,
    sessionState = sessionState,
    // 下载那一段通过 scope 启动；Unconfined + 不挂起的 fake 让它同步跑完，测例才确定。
    scope = CoroutineScope(Dispatchers.Unconfined),
)