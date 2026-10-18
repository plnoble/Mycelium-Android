package com.xavierxia.mycelium.update

import androidx.lifecycle.ViewModel
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 下载/校验/安装的界面状态。第 3 步先把形状定下来，第 4/5 步往里填。 */
sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Running(val written: Long, val total: Long?) : DownloadUiState
    data object Verifying : DownloadUiState
    data class Ready(val file: File, val size: Long) : DownloadUiState
    data object Installing : DownloadUiState

    /**
     * [message] 是失败项的**人话**：四项校验的第 4 步要「说明是哪一项」，空间不足要说「还差多少」。
     * 第 3 步的既有调用点不传它（null = 只有类型），界面据此回退到按 [reason] 的通用文案。
     */
    data class Failed(val reason: DownloadFailureReason, val message: String? = null) : DownloadUiState
}

/**
 * 下载/校验/安装失败的原因**类型**。校验与空间不足的具体人话在 `Failed.message` 里
 * （校验器逐项给文案；空间不足由 `SpaceShortage` 给出差值），这里只保留可判定的类型。
 */
enum class DownloadFailureReason { ChecksumMismatch, NotAllowed, Network, Verification, Storage, Installation }

/**
 * 「我的」页与底栏红点都读这一份状态。
 *
 * 两个时间戳是分开的（C3 第 2 条定稿）：
 *  * [lastAutoAttemptAtMs] —— 上次**自动**尝试的时间，成功失败都写，节流只看它；
 *  * [lastSuccessAtMs] —— 上次真正跑完的时间，只用于「上次检查：……」。
 */
data class UpdateUiState(
    val checking: Boolean = false,
    /** 需要提示的新版本；已被忽略的版本不进这里。 */
    val release: ReleaseInfo? = null,
    /** 最近一次检查的结果，用来区分「已是最新」「没有发布过」和「还没查过」。 */
    val lastResult: UpdateResult? = null,
    /** 只有手动检查才填：自动检查失败不打扰用户。 */
    val error: UpdateError? = null,
    val autoCheck: Boolean = true,
    val lastAutoAttemptAtMs: Long = 0L,
    val lastSuccessAtMs: Long = 0L,
    val ignoredTag: String? = null,
    val download: DownloadUiState = DownloadUiState.Idle,
)

/**
 * 一条链路的状态持有者：检查、忽略、开关、进程重建后的恢复，以及第 4/5 步下载与安装的入口。
 *
 * 它是 Activity 级的 [ViewModel]：界面（切底栏、重建）不能把状态打回原形，`onCleared` 里
 * 取消 [scope]，下载任务才不会在 Activity 结束后继续跑。
 */
class UpdateCenter(
    private val updates: UpdateManager,
    private val prefs: UpdatePreferences,
    val isDebugPackage: Boolean,
    private val files: UpdateFiles,
    private val downloader: ApkDownloader = UnwiredApkDownloader,
    private val verifier: ApkVerifier = UnwiredApkVerifier,
    private val installer: InstallSessionGateway = UnwiredInstallSessionGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * 「下载并安装」入口只有在这三件都接线、且不是调试包时才成立。
     * 调试包装上 GitHub 的正式包会变成另一个 App（工作包第 5 步），所以不提供应用内安装。
     * 第 3 步的生产依赖是 [UnwiredApkDownloader] 等明确未接线的实现，因此界面不会画这个按钮。
     */
    val installReady: Boolean
        get() = !isDebugPackage && downloader.isAvailable && verifier.isAvailable && installer.isAvailable

    private var downloadCall: GatewayCall? = null

    init {
        restore()
    }

    override fun onCleared() {
        scope.cancel()
    }

    /**
     * 打开 App 时调用。调试包不自动检查、关了开关不查、24 小时（按自动尝试时间）内不查。
     */
    suspend fun checkIfDue() {
        if (isDebugPackage) return
        if (!prefs.autoCheck) return
        if (!UpdateCheckPolicy.isDue(clock(), prefs.lastAutoAttemptAtMs)) return
        performCheck(auto = true)
    }

    /** 手动「检查更新」：不受 24 小时限制，也不改自动尝试时间。 */
    suspend fun checkNow() {
        performCheck(auto = false)
    }

    /** 忽略某个版本：卡片与红点都下去，直到出现更新的版本。 */
    fun ignore(tag: String) {
        prefs.ignoredTag = tag
        val current = _state.value
        if (current.release?.tagName == tag) {
            prefs.clearPending()
            downloadCall = null
            _state.value = current.copy(release = null, ignoredTag = tag, download = DownloadUiState.Idle)
        } else {
            _state.value = current.copy(ignoredTag = tag)
        }
    }

    fun setAutoCheck(on: Boolean) {
        prefs.autoCheck = on
        _state.value = _state.value.copy(autoCheck = on)
    }

    /** 回到前台：核一遍待安装的文件还在不在（文件被系统清掉就不能再显示「可以安装」）。 */
    fun onResume() {
        val current = _state.value
        val ready = current.download
        if (ready is DownloadUiState.Ready && !ready.file.isFile) {
            prefs.pendingApkPath = null
            _state.value = current.copy(download = DownloadUiState.Idle)
        }
    }

    /** 「下载并安装」。第 3 步的生产依赖没接线，所以这个入口在真机上还不存在。 */
    fun startDownload() {
        val release = _state.value.release ?: return
        if (!installReady) return
        // 缺 .apk 或缺 .sha256 的发布不提供应用内安装（工作包第 2 步）。
        if (release.apkUrl == null || release.sha256Url == null) return
        val call = GatewayCall()
        downloadCall = call
        _state.value = _state.value.copy(download = DownloadUiState.Running(0L, release.apkSizeBytes))
        scope.launch {
            val expected = try {
                downloader.fetchExpectedSha256(release, call)
            } catch (_: GatewayCancelled) {
                // 取消不是失败：不显示错误，`.part` 留给下次续传。
                finishCancelled()
                return@launch
            }
            if (expected == null) {
                finishFailed(DownloadFailureReason.Network)
                return@launch
            }
            val target = files.apkFile(release.tagName)
            val outcome = try {
                downloader.downloadApk(release, target, expected, call) { written, total ->
                    _state.value = _state.value.copy(download = DownloadUiState.Running(written, total))
                }
            } catch (shortage: SpaceShortage) {
                // 下载器在下任何字节之前就查了空间（未知大小时拿到真实长度后再查一次）；
                // C4 第 3 条要求文案里有差值，这里原样带给界面。
                finishFailed(DownloadFailureReason.Storage, shortage.message)
                return@launch
            }
            when (outcome) {
                is DownloadOutcome.Completed -> verifyDownloaded(release, target, outcome)
                is DownloadOutcome.Cancelled -> finishCancelled()
                is DownloadOutcome.ChecksumMismatch -> finishFailed(DownloadFailureReason.ChecksumMismatch)
                is DownloadOutcome.Rejected -> finishFailed(DownloadFailureReason.NotAllowed)
                is DownloadOutcome.Failed -> finishFailed(DownloadFailureReason.Network)
            }
        }
    }

    /** 点「取消」：立刻停（下载器按 C4 保留 `.part` 以便续传），界面马上回到「没在下」。 */
    fun cancelDownload() {
        downloadCall?.cancel()
        downloadCall = null
        val current = _state.value.download
        if (current is DownloadUiState.Running || current is DownloadUiState.Verifying) {
            _state.value = _state.value.copy(download = DownloadUiState.Idle)
        }
    }

    fun install() {
        val ready = _state.value.download as? DownloadUiState.Ready ?: return
        if (!installReady) return
        _state.value = _state.value.copy(download = DownloadUiState.Installing)
        scope.launch {
            when (installer.install(ready.file)) {
                is InstallOutcome.Installed -> {
                    // 装完之后当前版本要等重启才变，先把候选与文件一起收掉，别反复提示。
                    prefs.clearPending()
                    _state.value = _state.value.copy(download = DownloadUiState.Idle, release = null)
                }
                is InstallOutcome.Cancelled -> _state.value = _state.value.copy(download = ready)
                is InstallOutcome.Failed -> _state.value =
                    _state.value.copy(download = DownloadUiState.Failed(DownloadFailureReason.Installation))
            }
        }
    }

    private suspend fun performCheck(auto: Boolean) {
        _state.value = _state.value.copy(checking = true, error = null)
        val result = try {
            updates.checkForUpdate()
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(checking = false)
            throw cancelled
        }
        val now = clock()
        // 自动尝试：成功和失败都写，这就是「一天最多一次」。
        if (auto) prefs.lastAutoAttemptAtMs = now
        // 只有真正跑完（不是 Error）才更新「上次检查」的显示时间。
        if (result !is UpdateResult.Error) prefs.lastSuccessAtMs = now
        applyResult(result, auto)
    }

    private fun applyResult(result: UpdateResult, auto: Boolean) {
        val release = (result as? UpdateResult.Available)?.release
        val shown = release?.takeIf { it.tagName != prefs.ignoredTag }
        if (shown != null) {
            // C3 第 1 条：候选发布的元数据和目标 tag 一起落盘，进程重建后卡片还能回来。
            prefs.pendingReleaseJson = ReleaseJson.encode(shown)
            prefs.pendingReleaseTag = shown.tagName
        } else {
            prefs.clearPending()
        }
        _state.value = _state.value.copy(
            checking = false,
            release = shown,
            lastResult = result,
            // 失败不打扰：自动检查不填 error，只有手动检查才给人话。
            error = if (auto) null else (result as? UpdateResult.Error)?.error,
            lastAutoAttemptAtMs = prefs.lastAutoAttemptAtMs,
            lastSuccessAtMs = prefs.lastSuccessAtMs,
            ignoredTag = prefs.ignoredTag,
            download = if (shown == null) DownloadUiState.Idle else _state.value.download,
        )
    }

    private suspend fun verifyDownloaded(release: ReleaseInfo, target: File, outcome: DownloadOutcome.Completed) {
        _state.value = _state.value.copy(download = DownloadUiState.Verifying)
        when (val verdict = verifier.verify(target, release)) {
            is VerifyOutcome.Verified -> {
                prefs.pendingApkPath = target.path
                downloadCall = null
                _state.value = _state.value.copy(download = DownloadUiState.Ready(target, outcome.result.fileSize))
            }
            is VerifyOutcome.Failed -> {
                // 校验器已经删过一遍；这里再删一次是为了「校验失败绝不留文件」这条口径不依赖调用顺序。
                target.delete()
                finishFailed(DownloadFailureReason.Verification, verdict.message)
            }
        }
    }

    private fun finishCancelled() {
        downloadCall = null
        _state.value = _state.value.copy(download = DownloadUiState.Idle)
    }

    private fun finishFailed(reason: DownloadFailureReason, message: String? = null) {
        downloadCall = null
        _state.value = _state.value.copy(download = DownloadUiState.Failed(reason, message))
    }

    /**
     * 进程重建：从偏好存储恢复开关、两个时间戳、忽略状态和候选发布，并**重新核对**
     * 目标 tag 与元数据一致、比当前版本新、没被忽略、已下载的文件还在。
     */
    private fun restore() {
        // 启动时清一次过期文件（>7 天的 .apk / .part / .sha256）；当前 tag 的 .part 留着续传。
        files.purge(clock(), prefs.pendingReleaseTag)
        val tag = prefs.pendingReleaseTag
        val candidate = prefs.pendingReleaseJson
            ?.let(ReleaseJson::decode)
            ?.takeIf {
                it.tagName == tag &&
                    it.tagName != prefs.ignoredTag &&
                    VersionComparator.isNewer(it.versionName, currentVersionName)
            }
        // 对不上就整个丢掉：宁可下次启动再查一遍，也不留半个候选发布。
        if (candidate == null) prefs.clearPending()
        _state.value = UpdateUiState(
            release = candidate,
            autoCheck = prefs.autoCheck,
            lastAutoAttemptAtMs = prefs.lastAutoAttemptAtMs,
            lastSuccessAtMs = prefs.lastSuccessAtMs,
            ignoredTag = prefs.ignoredTag,
            download = restoredDownload(candidate),
        )
    }

    private fun restoredDownload(candidate: ReleaseInfo?): DownloadUiState {
        val path = prefs.pendingApkPath ?: return DownloadUiState.Idle
        val file = File(path)
        // 重新核对文件存在性：文件不在了就不能再显示「可以安装」。
        if (candidate == null || !file.isFile) {
            prefs.pendingApkPath = null
            return DownloadUiState.Idle
        }
        return DownloadUiState.Ready(file, file.length())
    }
}