package com.xavierxia.mycelium.update

import android.content.Intent
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
     * 还没允许「安装未知应用」：先说明再带路，**这时没有建会话**。
     * 文件跟着状态一起带着：从设置页回来允许了就立刻接着装（工作包第 5 步）。
     */
    data class NeedsUnknownSources(val file: File, val size: Long) : DownloadUiState

    /**
     * 会话悬挂 / 回执没到 / 会话已经不在了：只能**标记待核实**。
     * C5 第 4 条否掉了原来「还是旧版本 + 超过 10 分钟就判取消」的写法，这一条不许冒充取消。
     */
    data object NeedsVerification : DownloadUiState

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
    /**
     * 安装结果的一句人话（成功 / 已取消 / 待核实 / 各自的失败）。
     * 成功与取消会把候选发布清掉，卡片也随之消失，所以这条提示不能挂在卡片上（工作包第 5 步）。
     */
    val installNotice: String? = null,
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
    /** 装着的**当前** versionCode：提交时记基线，回前台拿它做权威判定（C5 第 3 条）。 */
    private val installedVersionCode: () -> Long = { BuildConfig.VERSION_CODE.toLong() },
    /** 会话状态落盘口。生产装配传 `UpdatePreferences`，测例传内存实现。 */
    private val sessionState: InstallSessionState = MemoryInstallSessionState(),
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
    /** 任务身份：每次发起、取消、忽略都加一；旧任务回来的进度与终态一律作废（M3）。 */
    private var downloadGeneration = 0L

    init {
        restore()
    }

    override fun onCleared() {
        // 只取消 scope 的话，阻塞在网关里的那次请求要等它自己返回；先让取消闸门把请求停掉（M3）。
        downloadCall?.cancel()
        downloadCall = null
        downloadGeneration++
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
            // 取消在跑的那次任务，并清掉它留下的缓存。cancel() 由网关在下一个分片前看到，
            // 所以这里删文件不会删到正在写的字节；万一删失败，下次下载会重新下完整包。
            downloadCall?.cancel()
            downloadCall = null
            downloadGeneration++
            prefs.clearPending()
            files.deleteForTag(tag)
            _state.value = current.copy(release = null, ignoredTag = tag, download = DownloadUiState.Idle)
        } else {
            _state.value = current.copy(ignoredTag = tag)
        }
    }

    fun setAutoCheck(on: Boolean) {
        prefs.autoCheck = on
        _state.value = _state.value.copy(autoCheck = on)
    }

    /**
     * 回到前台：**先做权威判定**（装上了就是装上了），再认回回执落盘的结果，最后才做文件兜底。
     *
     * 三条都按 C5 定稿：
     *  * `longVersionCode` 比提交时的基线大 → 成功，哪怕广播一次都没到；
     *  * 落盘的会话已经是终态 → 按终态收尾（明确取消 / 终态结果才清理）；
     *  * 非终态里「会话不在了」或「超过窗口期」→ 只**标记待核实**，不判取消、不删文件（Q9 被否）。
     */
    fun onResume() {
        val record = sessionState.read()
        if (record != null) {
            if (installedVersionCode() > record.baselineVersionCode) {
                finishInstalled(record.tagName)
                return
            }
            if (record.phase.isTerminal) {
                consumeTerminal(record)
                return
            }
            // 非终态：会话已经不在系统里，或者已经超过窗口期 → 待核实（C5 第 4 条）。
            val stale = clock() - record.updatedAtMs >= INSTALL_VERIFICATION_WINDOW_MS
            if (stale || !installer.sessionAlive(record.sessionId)) {
                _state.value = _state.value.copy(
                    download = DownloadUiState.NeedsVerification,
                    installNotice = VERIFY_NOTICE,
                )
                return
            }
        }
        val current = _state.value
        // 从「安装未知应用」设置页回来且已经允许了：立刻接着装（工作包第 5 步）。
        val blocked = current.download
        if (blocked is DownloadUiState.NeedsUnknownSources && installer.canRequestPackageInstalls()) {
            startSession(DownloadUiState.Ready(blocked.file, blocked.size))
            return
        }
        // 文件被系统清掉就不能再显示「可以安装」。
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
        val generation = ++downloadGeneration
        downloadCall = call
        _state.value = _state.value.copy(download = DownloadUiState.Running(0L, release.apkSizeBytes))
        scope.launch {
            // 任务身份：这一轮结束之前，若用户取消、忽略或又发起了新的一次，旧任务不许再写状态。
            fun current() = generation == downloadGeneration
            val expected = try {
                downloader.fetchExpectedSha256(release, call)
            } catch (_: GatewayCancelled) {
                // 取消不是失败：不显示错误，`.part` 留给下次续传。
                if (current()) finishCancelled()
                return@launch
            }
            if (!current()) return@launch
            if (expected == null) {
                finishFailed(DownloadFailureReason.Network)
                return@launch
            }
            val target = files.apkFile(release.tagName)
            val outcome = try {
                downloader.downloadApk(release, target, expected, call) { written, total ->
                    if (current()) {
                        _state.value = _state.value.copy(download = DownloadUiState.Running(written, total))
                    }
                }
            } catch (shortage: SpaceShortage) {
                // 下载器在下任何字节之前就查了空间（未知大小时拿到真实长度后再查一次）；
                // C4 第 3 条要求文案里有差值，这里原样带给界面。
                if (current()) finishFailed(DownloadFailureReason.Storage, shortage.message)
                return@launch
            } catch (_: GatewayCancelled) {
                if (current()) finishCancelled()
                return@launch
            }
            if (!current()) return@launch
            when (outcome) {
                is DownloadOutcome.Completed -> verifyDownloaded(release, target, outcome, call, generation)
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
        // 取消后立刻重下时，上一轮的进度与终态不能再落到界面上（M3）。
        downloadGeneration++
        val current = _state.value.download
        if (current is DownloadUiState.Running || current is DownloadUiState.Verifying) {
            _state.value = _state.value.copy(download = DownloadUiState.Idle)
        }
    }

    /**
     * 「下载并安装」。三步定稿（C5 / 工作包第 5 步）：
     *  1. 没允许「安装未知应用」→ 只带路、**不建会话**，界面弹说明再去设置页；
     *  2. 允许了 → 建会话、按流写入、`commit`，把系统确认界面交给系统；
     *  3. 结果不靠猜：回前台读 `longVersionCode`（权威）+ 回执落盘的会话状态。
     */
    fun install() {
        val ready = _state.value.download as? DownloadUiState.Ready ?: return
        if (!installReady) return
        val release = _state.value.release ?: return
        // 状态与版本绑定（M5）：Ready 里的文件必须是**当前这个 tag** 的文件。
        // 候选换过而旧文件还在时，宁可丢掉重下，也不能把旧包按新 tag 交给安装器。
        if (ready.file != files.finalFile(release.tagName)) {
            ready.file.delete()
            prefs.pendingApkPath = null
            _state.value = _state.value.copy(download = DownloadUiState.Idle)
            return
        }
        if (!installer.canRequestPackageInstalls()) {
            _state.value = _state.value.copy(
                download = DownloadUiState.NeedsUnknownSources(ready.file, ready.size),
                installNotice = null,
            )
            return
        }
        startSession(ready)
    }

    /** 说明对话框上点「去设置」：把设置页 Intent 交给界面去拉起。返回 null 表示当前不是这个状态。 */
    fun unknownSourcesIntent(): Intent? =
        if (_state.value.download is DownloadUiState.NeedsUnknownSources) installer.unknownSourcesIntent() else null

    /** 说明对话框上点「以后再说」：停在说明上，不装也不建会话。 */
    fun dismissUnknownSources() {
        if (_state.value.download is DownloadUiState.NeedsUnknownSources) {
            _state.value = _state.value.copy(download = DownloadUiState.Idle)
        }
    }

    /** 用户放弃悬挂的会话（`abandon`）并清掉本应用那条记录：会话清理只认自己落盘的 sessionId。 */
    fun abandonInstall() {
        installer.cancel()
        sessionState.read()?.let { sessionState.clear(it.sessionId) }
        _state.value = _state.value.copy(download = DownloadUiState.Idle)
    }

    private fun startSession(ready: DownloadUiState.Ready) {
        val release = _state.value.release ?: return
        // 上一次会话还挂着（非终态）时再来一次：先放弃它，否则新记录会被幂等规则挡下。
        val previous = sessionState.read()
        if (previous != null) {
            if (!previous.phase.isTerminal) installer.cancel()
            sessionState.clear(previous.sessionId)
        }
        _state.value = _state.value.copy(download = DownloadUiState.Installing, installNotice = null)
        val baseline = installedVersionCode()
        scope.launch {
            when (val outcome = installer.install(ready.file, release.tagName, baseline)) {
                // 同步就确认成功（测例的显式 fake；真机的成功回执走落盘那条路）。
                is InstallOutcome.Installed -> finishInstalled(release.tagName)
                is InstallOutcome.Cancelled -> finishCancelledInstall(release.tagName)
                is InstallOutcome.Failed -> failInstall(outcome.message)
                // 交给系统了：界面停在「正在安装」，结果由回执 + 回前台复查认。
                is InstallOutcome.Committed -> Unit
            }
        }
    }

    /** 落盘状态已经是终态：只有明确取消与终态结果才收尾（C5 第 4 条）。 */
    private fun consumeTerminal(record: InstallSessionRecord) {
        when (val status = installStatusOf(record.statusCode)) {
            is InstallStatus.Succeeded -> finishInstalled(record.tagName)
            is InstallStatus.Cancelled -> finishCancelledInstall(record.tagName)
            is InstallStatus.PendingUserAction -> _state.value =
                _state.value.copy(download = DownloadUiState.Installing)
            else -> failInstall(installFailureText(status))
        }
    }

    /**
     * 成功：清这一版的文件、重置「上次检查」、清掉忽略状态，提示「更新完成，下次打开生效」。
     * 不自动重启（工作包第 5 步 / 简报 §5.2）。
     */
    private fun finishInstalled(tagName: String) {
        files.deleteForTag(tagName)
        prefs.clearPending()
        prefs.lastAutoAttemptAtMs = 0L
        prefs.lastSuccessAtMs = 0L
        prefs.ignoredTag = null
        sessionState.read()?.let { sessionState.clear(it.sessionId) }
        _state.value = UpdateUiState(
            autoCheck = prefs.autoCheck,
            lastAutoAttemptAtMs = prefs.lastAutoAttemptAtMs,
            lastSuccessAtMs = prefs.lastSuccessAtMs,
            ignoredTag = null,
            download = DownloadUiState.Idle,
            installNotice = INSTALLED_NOTICE,
        )
    }

    /** 明确取消：提示「已取消，安装包已清理」并删文件（工作包第 5 步）。 */
    private fun finishCancelledInstall(tagName: String) {
        files.deleteForTag(tagName)
        prefs.clearPending()
        sessionState.read()?.let { sessionState.clear(it.sessionId) }
        _state.value = UpdateUiState(
            autoCheck = prefs.autoCheck,
            lastAutoAttemptAtMs = prefs.lastAutoAttemptAtMs,
            lastSuccessAtMs = prefs.lastSuccessAtMs,
            ignoredTag = prefs.ignoredTag,
            download = DownloadUiState.Idle,
            installNotice = CANCELLED_NOTICE,
        )
    }

    /** 失败：文件留着（还能重试），提示按类型各不相同。 */
    private fun failInstall(message: String) {
        sessionState.read()?.let { sessionState.clear(it.sessionId) }
        _state.value = _state.value.copy(
            download = DownloadUiState.Failed(DownloadFailureReason.Installation, message),
            installNotice = null,
        )
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
        val previous = _state.value
        val previousTag = previous.release?.tagName
        // 检查失败**不动**候选：卡片、续传目标与 `.part` 都还属于上一个已知版本（M5）。
        val failed = result is UpdateResult.Error
        if (shown != null) {
            // C3 第 1 条：候选发布的元数据和目标 tag 一起落盘，进程重建后卡片还能回来。
            prefs.pendingReleaseJson = ReleaseJson.encode(shown)
            prefs.pendingReleaseTag = shown.tagName
        } else if (!failed) {
            prefs.clearPending()
        }
        // 候选换了版本：旧版本的下到一半 / 已就绪状态就地结束，旧文件也不属于任何候选了（M5）。
        // 不这么做，「已有 v0.5.0 的 Ready 文件、又查到 v0.6.0」会把旧包按新 tag 交给安装器。
        val switchedFrom = if (shown != null && previousTag != null && shown.tagName != previousTag) previousTag else null
        if (switchedFrom != null) {
            downloadCall?.cancel()
            downloadCall = null
            downloadGeneration++
            files.deleteForTag(switchedFrom)
            prefs.pendingApkPath = null
        }
        _state.value = previous.copy(
            checking = false,
            release = if (shown == null && failed) previous.release else shown,
            lastResult = result,
            // 失败不打扰：自动检查不填 error，只有手动检查才给人话。
            error = if (auto) null else (result as? UpdateResult.Error)?.error,
            lastAutoAttemptAtMs = prefs.lastAutoAttemptAtMs,
            lastSuccessAtMs = prefs.lastSuccessAtMs,
            ignoredTag = prefs.ignoredTag,
            // 换版本、或候选消失（且不是「检查失败」）→ 下载状态回到 Idle；
            // 检查失败时保留原状态下（卡片与续传目标都还在）。
            download = if (switchedFrom != null || (shown == null && !failed)) DownloadUiState.Idle else previous.download,
        )
    }

    private suspend fun verifyDownloaded(
        release: ReleaseInfo,
        target: File,
        outcome: DownloadOutcome.Completed,
        call: GatewayCall,
        generation: Long,
    ) {
        _state.value = _state.value.copy(download = DownloadUiState.Verifying)
        val verdict = try {
            verifier.verify(target, release, call)
        } catch (_: GatewayCancelled) {
            // 取消落在校验阶段：与下载中取消同一口径——不报错、不删已下好的文件。
            if (generation == downloadGeneration) finishCancelled()
            return
        }
        if (generation != downloadGeneration) return
        when (verdict) {
            is VerifyOutcome.Verified -> {
                prefs.pendingApkPath = target.path
                downloadCall = null
                _state.value = _state.value.copy(download = DownloadUiState.Ready(target, outcome.result.fileSize))
                // 「下载并安装」是一次点击到底：四项校验一过就接着装，不再要求用户点第二个按钮（M2）。
                // 没授权「安装未知应用」时 install() 只落到说明页，回前台会接着装；系统确认仍由用户完成。
                install()
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
     *
     * 另外多一条（C5 第 3 条：结果与清理要能在新进程完成）：候选发布是「当时比当前版本新」
     * 才落盘的，现在它**不再比当前版本新**了，只可能是这中间装上了——成功安装一定会终结旧进程，
     * 所以这条路径就是「新进程认回成功」。按成功收尾，人话与清理一个不少。
     */
    private fun restore() {
        // 启动时清一次过期文件（>7 天的 .apk / .part / .sha256）；当前 tag 的 .part 留着续传。
        files.purge(clock(), prefs.pendingReleaseTag)
        val tag = prefs.pendingReleaseTag
        val stored = prefs.pendingReleaseJson
            ?.let(ReleaseJson::decode)
            ?.takeIf { it.tagName == tag }
        if (stored != null && !VersionComparator.isNewer(stored.versionName, currentVersionName)) {
            finishInstalled(stored.tagName)
            return
        }
        val candidate = stored?.takeIf {
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
        // 会话还没到终态（进程可能在系统确认界面旁边被回收过）：先显示「正在安装」，
        // 真实的成功 / 取消 / 待核实由紧接着的 onResume 按落盘状态与 versionCode 决定。
        if (sessionState.read()?.phase?.isTerminal == false) return DownloadUiState.Installing
        val path = prefs.pendingApkPath ?: return DownloadUiState.Idle
        val file = File(path)
        // 重新核对：文件要在，而且必须**属于这个候选 tag**（M5：进程重建后也不能拿旧包当新版本）。
        if (candidate == null || !file.isFile || file != files.finalFile(candidate.tagName)) {
            prefs.pendingApkPath = null
            return DownloadUiState.Idle
        }
        return DownloadUiState.Ready(file, file.length())
    }
}

/** 成功 / 已取消两句人话（简报 §5.1 的原文口径）。 */
internal const val INSTALLED_NOTICE = "更新完成，下次打开生效。"
internal const val CANCELLED_NOTICE = "已取消，安装包已清理。"
internal const val VERIFY_NOTICE = "这次安装还没确认完成。可以回到系统界面确认，或者稍后再看这里。"

/**
 * 会话已经提交、但长时间没有终态回执的窗口期。
 *
 * 超过它**只标记待核实**：C5 第 4 条明确否掉了「还是旧版本 + 超过 10 分钟 = 用户取消」这个写法，
 * 超时不能把没完成的安装冒充取消（文件不删、会话也不假装放弃）。
 */
internal const val INSTALL_VERIFICATION_WINDOW_MS = 10 * 60 * 1000L

/** 安装失败的人话：四类失败各一条，界面照它显示（简报测例 5/7）。 */
internal fun installFailureText(status: InstallStatus): String = when (status) {
    is InstallStatus.InsufficientStorage -> "空间不够，安装没做完。清出一些空间再试。"
    is InstallStatus.Incompatible -> "这个安装包和当前系统不兼容，装不上。"
    is InstallStatus.Invalid -> "系统认为安装包无效，拒绝了。请重新下载。"
    is InstallStatus.Blocked -> "被设备策略挡住了，装不上。请联系设备管理员。"
    is InstallStatus.Conflict -> "和手机上已装的版本冲突，装不上。"
    is InstallStatus.Cancelled -> CANCELLED_NOTICE
    is InstallStatus.Succeeded -> INSTALLED_NOTICE
    is InstallStatus.PendingUserAction -> "还在等你在系统界面上确认安装。"
    is InstallStatus.Failed -> "安装失败（系统状态码 ${status.code}）。"
}