package com.xavierxia.mycelium.update

import com.xavierxia.mycelium.net.GatewayCall
import com.xavierxia.mycelium.net.GatewayCancelled
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 从 APK 里读出来、用于校验的四件事。
 *
 * [signerCount] 不是装饰：方案审 C4 第 6 条要求**明确拒绝异常多签名**，不能无条件只取 `[0]`；
 * [signerSha256] 为 null 表示「读不出签名」——它与「签名不一致」不是同一件事，文案与判定都要分开。
 */
data class ApkFacts(
    val packageName: String?,
    val versionCode: Long,
    val signerSha256: String?,
    val signerCount: Int = if (signerSha256 == null) 0 else 1,
)

/** 真机：`getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`。 */
fun interface ApkFactsReader {
    fun read(path: String): ApkFacts?
}

/** 真机：`getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)`，即**当前装着的**那个 App。 */
fun interface InstalledAppFacts {
    fun installed(): ApkFacts
}

/** `.sha256` 附件正文的解析：取第一个 64 位十六进制串，忽略其它一切。 */
object ChecksumText {
    /** 前后不允许再挨着十六进制字符：65 个 hex 连在一起不是「一个 64 位哈希」。 */
    private val SHA256 = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{64})(?![0-9a-fA-F])")

    fun parseFirstSha256(text: String): String? =
        SHA256.find(text)?.groupValues?.get(1)?.lowercase(Locale.US)
}

/**
 * 四项校验（顺序按任务书与 C4 第 5 条：`sha256 → 包名 → 签名 → versionCode`）。
 *
 * 三条口径：
 *  * 期望哈希从**本地边车文件** `mycelium-<tag>.apk.sha256` 读（下载成功时由下载器写下），
 *    校验器**不联网**（C4 第 2 条）；
 *  * 签名只和**当前安装的 App** 比、只比指纹；不比 CI 里固定的发布指纹（C4 第 6 条 / 简报 4.5）；
 *  * 任何一项不过都**先删文件再返回**，且文案说清是哪一项（工作包第 4 步）。
 *
 * 线程（M1）：读边车、算哈希、读 APK 都是阻塞调用，全部放在 [io] 上；只比版本号这类内存操作
 * 才留在调用线程，界面状态因此仍在原来的线程语义里更新。
 */
class LocalApkVerifier(
    private val transport: UpdateTransport,
    private val archive: ApkFactsReader,
    private val installed: InstalledAppFacts,
    /** 当前 App 的包名（真机传 `BuildConfig.APPLICATION_ID`）。 */
    private val selfPackageName: String,
    /** 读文件与算哈希的执行边界；真机是 [Dispatchers.IO]，测例换成能记录线程的实现。 */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ApkVerifier {
    override val isAvailable: Boolean = true

    override suspend fun verify(apk: File, release: ReleaseInfo, call: GatewayCall): VerifyOutcome = withContext(io) {
        if (!apk.isFile) return@withContext VerifyOutcome.Failed("下载的文件不见了，请重新下载。")

        // 1) sha256：先比哈希，坏包不进入更贵的读签名步骤。
        val sidecar = sidecar(apk)
        val expected = sidecar.takeIf { it.isFile }?.readText()?.trim()?.lowercase(Locale.US)
            ?: return@withContext fail(apk, "找不到期望的校验和（.sha256），已删除，请重新下载。")
        val actual = try {
            // 与下载同一个取消句柄：取消落在校验阶段也要能停下来，而不是把已下好的文件当失败删掉。
            transport.sha256(apk, call).lowercase(Locale.US)
        } catch (cancelled: GatewayCancelled) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext fail(apk, "读不出下载的文件，已删除，请重新下载。")
        }
        if (actual != expected) {
            return@withContext fail(apk, "校验和不符：下载的文件和发布里的 .sha256 对不上，已删除，请重新下载。")
        }

        // 2) 包名
        val facts = archive.read(apk.path)
            ?: return@withContext fail(apk, "读不出这个安装包（不是有效的 APK），已删除。")
        val foundName = facts.packageName
        if (foundName != selfPackageName) {
            return@withContext fail(apk, "包名不对：这个包是 ${foundName ?: "未知"}，当前 App 是 $selfPackageName。已删除。")
        }

        // 3) 签名：只与当前装着的 App 比指纹，且只接受单签名。
        val current = installed.installed()
        if (facts.signerCount > 1) {
            return@withContext fail(apk, "包的签名数量异常（${facts.signerCount} 个），当前只接受单签名。已删除。")
        }
        val candidateSigner = facts.signerSha256
            ?: return@withContext fail(apk, "读不出包的签名，无法确认是不是同一个来源。已删除。")
        if (current.signerCount > 1) {
            return@withContext fail(apk, "当前安装版本的签名数量异常（${current.signerCount} 个），没法比对。已删除。")
        }
        val currentSigner = current.signerSha256
            ?: return@withContext fail(apk, "读不出当前安装版本的签名，没法比对。已删除。")
        if (!candidateSigner.equals(currentSigner, ignoreCase = true)) {
            return@withContext fail(apk, "签名不一致：这个包的签名和当前安装的版本不一样，已删除。")
        }

        // 4) versionCode 必须比当前的大。
        if (facts.versionCode <= current.versionCode) {
            return@withContext fail(apk, "版本号没涨：新包是 ${facts.versionCode}，当前是 ${current.versionCode}。已删除。")
        }
        VerifyOutcome.Verified
    }

    private fun sidecar(apk: File): File = File(apk.parentFile, apk.name + ".sha256")

    private fun fail(apk: File, message: String): VerifyOutcome {
        apk.delete()
        sidecar(apk).delete()
        return VerifyOutcome.Failed(message)
    }
}
