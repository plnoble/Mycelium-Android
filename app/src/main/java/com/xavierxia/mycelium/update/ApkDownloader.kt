package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.DownloadOutcome
import com.xavierxia.mycelium.net.DownloadProgress
import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 第 4 步的编排：**空间 → 下到 `.part`（带期望哈希与取消句柄）→ 长度复查 → 改名成待安装文件**。
 * 四项校验由 [ApkVerifier] 负责（`UpdateCenter` 接着调），这里只做「下载成功才算有文件」。
 *
 * 几条口径：
 *  * 空间不够**根本不发起下载**：先抛 [SpaceShortage]（文案带差值），`downloadApk` 的调用方
 *    `UpdateCenter` 会把它变成界面上的「存储空间不够」（C4 第 3 条）；
 *  * `apkSizeBytes` 未知时只按 64 MiB 门槛过一遍，**拿到真实长度后再复查一次**——
 *    进度里第一次出现非空 `total` 就是复查时机；不够就取消这次下载（`.part` 留下，下次续传）；
 *  * 取消**保留** `.part`（下次接着下）；只有放弃版本 / 校验不过才删（C4 第 4 条）；
 *  * 下载成功才 `promote` 改名，并把期望哈希写进边车文件给校验器读（校验器不联网）。
 *
 * 线程（M1）：本类里的网络请求、空间计算、改名与边车写入都是**阻塞**调用，全部放在 [io] 上执行。
 * `suspend` 本身不会换线程 —— `UpdateCenter` 的 scope 是 `Dispatchers.Main.immediate`，不显式切走
 * 就会在主线程上联网和读写文件，真机会直接抛 NetworkOnMainThreadException 或卡住界面。
 */
class GatewayApkDownloader(
    private val files: UpdateFiles,
    private val transport: UpdateTransport,
    /** 正在被别的下载写着的文件；清理时不得删（C4 第 4 条）。 */
    private val inUse: (File) -> Boolean = { false },
    /** 阻塞网络与文件 IO 的执行边界；真机是 [Dispatchers.IO]，测例换成能记录线程的实现。 */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ApkDownloader {
    override val isAvailable: Boolean = true

    override suspend fun fetchExpectedSha256(release: ReleaseInfo, call: GatewayCall): String? {
        val url = release.sha256Url ?: return null
        val text = try {
            withContext(io) { transport.fetchChecksumText(url, call, CHECKSUM_LIMIT_BYTES) }
        } catch (cancelled: GatewayCancelled) {
            // 取消不是失败：往上抛，界面据此回到「没在下」而不是显示「网络失败」。
            throw cancelled
        } catch (_: Exception) {
            // 超 4 KiB 上限、跳转被拒、HTTP 非 2xx、正文没有 64 位 hex：都当作「拿不到期望哈希」。
            return null
        }
        return ChecksumText.parseFirstSha256(text)
    }

    override suspend fun downloadApk(
        release: ReleaseInfo,
        target: File,
        expectedSha256: String,
        call: GatewayCall,
        onProgress: DownloadProgress?,
    ): DownloadOutcome {
        val url = release.apkUrl ?: return DownloadOutcome.Rejected("这个发布没有 APK 附件")
        return withContext(io) {
            files.ensureDirectory()
            // 换 tag 时把别的 tag 的半成品清掉，但不碰正在被写的文件。
            files.deleteForeignParts(release.tagName, inUse)

            val part = files.partFile(release.tagName)
            files.requireSpace(release.apkSizeBytes, files.existingBytes(release.tagName))

            var shortage: SpaceShortage? = null
            var rechecked = false
            val outcome = transport.downloadApk(url, part, expectedSha256, call) { written, total ->
                // 这个回调在下载线程上；它只写 StateFlow，不碰界面组件。
                onProgress?.onProgress(written, total)
                // 拿到真实长度后的复查（未知大小或报小了都在这里拦住），只查一次。
                if (!rechecked && total != null && total > 0L) {
                    rechecked = true
                    try {
                        files.requireSpace(total, written)
                    } catch (lack: SpaceShortage) {
                        shortage = lack
                        call.cancel()
                    }
                }
            }
            shortage?.let { throw it }

            if (outcome is DownloadOutcome.Completed) {
                files.checksumFile(release.tagName)
                    .writeText(expectedSha256.trim().lowercase(Locale.US))
                val promoted = files.promote(release.tagName)
                outcome.copy(result = outcome.result.copy(fileSize = promoted.length()))
            } else {
                // 取消：`.part` 留着续传；失败/拒绝：具体字节的去留已由 FileDownloader 决定。
                outcome
            }
        }
    }
}
