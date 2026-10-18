package com.xavierxia.mycelium.update

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

/** 一次安装会话的结果（含用户取消）。 */
sealed interface InstallOutcome {
    data object Installed : InstallOutcome
    data object Cancelled : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

interface InstallSessionGateway {
    val isAvailable: Boolean

    suspend fun install(apk: File): InstallOutcome

    /** 用户在界面上点取消：会话要能停下来。 */
    fun cancel()
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

    override suspend fun install(apk: File): InstallOutcome = InstallOutcome.Failed(NOT_WIRED)

    override fun cancel() = Unit
}

private const val NOT_WIRED = "应用内下载还没接线"