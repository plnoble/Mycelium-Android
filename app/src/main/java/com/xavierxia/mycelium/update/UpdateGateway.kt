package com.xavierxia.mycelium.update

import android.content.Intent
import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.GatewayCall
import java.io.File

/**
 * 第 4 步（下载与校验）与第 5 步（安装）的接口在**这里**先定稿，第 3 步就能用显式 fake 测到
 * 「检查 → 卡片 → 下载/安装入口」整条链路，而不是等实现写完再补测例。
 *
 * 三条约定（方案审 C3 第 3 条）：
 *  * 依赖**非空**注入；`UpdateCenter` 不接 null，也不给 TODO 占位实现。
 *  * 每个接口都有 [isAvailable]：实现还没接线时它是 false，界面据此**不画**「下载并安装」，
 *    而不是画一个点了没用的按钮。
 *  * 取消贯穿一条 [GatewayCall]：取哈希、下载、校验都看它。
 *
 * 第 4 步的 [FileDownloader] 把「整文件流式哈希」和续传放在实现内部，接口这一层只固定
 * 「期望哈希 + 取消句柄 + 进度」。
 */
interface ApkDownloader {
    /** 第 4 步实现接线后置 true。 */
    val isAvailable: Boolean

    /**
     * 取 `.sha256` 附件里的期望哈希。方案审 C4 要求这条读取走**上限 4 KiB**的通道，
     * 拿不到（网络失败、字节超限、格式不对）就返回 null。
     */
    suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String?

    /**
     * 把 APK 下到 [target]。取消、重试、哈希不符时的删除都由实现负责（见 C4 的明确分工）。
     */
    suspend fun downloadApk(
        release: ReleaseInfo,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        onProgress: DownloadProgress? = null,
    ): DownloadOutcome
}

/** 四项校验（sha256 → 包名 → 签名 → versionCode，顺序按任务书）的结果。 */
sealed interface VerifyOutcome {
    data object Verified : VerifyOutcome

    /** 哪一项不对、人话怎么说，由第 4 步的实现给出；界面只认这个类型。 */
    data class Failed(val message: String) : VerifyOutcome
}

interface ApkVerifier {
    val isAvailable: Boolean

    suspend fun verify(apk: File, release: ReleaseInfo): VerifyOutcome
}

/**
 * 一次安装的**即时**结果。终态不在这里：会话交给系统之后，成功/取消/失败由回执落盘
 * （`InstallSessionState`）和回前台时的 `longVersionCode` 复查给出（C5 第 3 条）。
 */
sealed interface InstallOutcome {
    data object Installed : InstallOutcome
    data object Cancelled : InstallOutcome
    data class Failed(val message: String) : InstallOutcome

    /** 会话已经 `commit` 给系统，等用户在系统确认界面点「安装」。 */
    data class Committed(val sessionId: Int) : InstallOutcome
}

/**
 * 系统回执的分类（简报 §5.2 + C5）。[PendingUserAction] 的 [PendingUserAction.intent] 允许为 null：
 * 真机上 `EXTRA_INTENT` 取不到时仍然要落盘「等用户确认」，交给回前台的复查兜底，而不是谎报失败。
 */
sealed interface InstallStatus {
    data class PendingUserAction(val intent: Intent?) : InstallStatus
    data object Succeeded : InstallStatus
    data object Cancelled : InstallStatus
    data object InsufficientStorage : InstallStatus
    data object Incompatible : InstallStatus
    data object Invalid : InstallStatus
    data object Blocked : InstallStatus
    data object Conflict : InstallStatus
    data class Failed(val code: Int) : InstallStatus
}

interface InstallSessionGateway {
    val isAvailable: Boolean

    /** 「安装未知应用」是否已授权。没授权时**不建会话**，先说明再带路（工作包第 5 步）。 */
    fun canRequestPackageInstalls(): Boolean

    /** 本应用的「安装未知应用」设置页。只带路，开不开由用户决定。 */
    fun unknownSourcesIntent(): Intent

    /**
     * 开会话 → 按流写入 → `commit`。只报「有没有把包交给系统」，终态由回执与前台复查认。
     *
     * [tagName] 与 [baselineVersionCode] 会连同 sessionId 一起落盘：前者用来在结果出来后清那
     * 一版的文件，后者是「装上了没有」的权威判定基线（C5 第 3 条）。
     */
    suspend fun install(apk: File, tagName: String, baselineVersionCode: Long): InstallOutcome

    /** 用户在界面上放弃：放弃悬挂的会话（`abandon`）并清掉本应用这一条记录。 */
    fun cancel()

    /**
     * 这个会话在系统里还活着吗。会话没了而版本又没变，只能判「待核实」，
     * 不能当成用户取消（C5 第 4 条否掉了 Q9 的原始写法）。假实现默认 true。
     */
    fun sessionAlive(sessionId: Int): Boolean = true
}

/**
 * 第 4 步之前的明确「未接线」实现：不是 TODO，也不是可空依赖。它把 [isAvailable] 报成 false，
 * 界面因此只给「忽略这个版本」和「打开发布页」，不会出现一个按了没反应的下载按钮。
 */
object UnwiredApkDownloader : ApkDownloader {
    override val isAvailable: Boolean = false

    override suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String? = null

    override suspend fun downloadApk(
        release: ReleaseInfo,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        onProgress: DownloadProgress?,
    ): DownloadOutcome = DownloadOutcome.Rejected(NOT_WIRED)
}

object UnwiredApkVerifier : ApkVerifier {
    override val isAvailable: Boolean = false

    override suspend fun verify(apk: File, release: ReleaseInfo): VerifyOutcome =
        VerifyOutcome.Failed(NOT_WIRED)
}

object UnwiredInstallSessionGateway : InstallSessionGateway {
    override val isAvailable: Boolean = false

    override fun canRequestPackageInstalls(): Boolean = false

    override fun unknownSourcesIntent(): Intent = Intent()

    override suspend fun install(apk: File, tagName: String, baselineVersionCode: Long): InstallOutcome =
        InstallOutcome.Failed(NOT_WIRED)

    override fun cancel() = Unit
}

private const val NOT_WIRED = "应用内下载还没接线"