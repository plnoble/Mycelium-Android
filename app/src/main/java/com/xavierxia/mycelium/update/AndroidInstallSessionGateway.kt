package com.xavierxia.mycelium.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话写入的一个可断言接缝：真机是 `PackageInstaller.Session`，单测是记事件的假实现。
 *
 * 「写 → `fsync` → **关流** → `commit`」这个顺序是 C5 第 2 条直接点名的验收证据
 * （简报里的 `fd.sync()` 和「先 commit 后关流」都不能照写），所以它必须能脱离系统单独测。
 */
internal interface InstallSessionHandle {
    fun openWrite(name: String, offset: Long, size: Long): OutputStream

    fun fsync(stream: OutputStream)

    fun commit()

    fun abandon()

    fun close()
}

/** 8 KiB 缓冲：38 MiB 的包按流进出，绝不 `readBytes()` 进内存（简报 §5.2）。 */
internal const val INSTALL_COPY_BUFFER_BYTES = 8 * 1024

/** 会话里那个包的名字。单包安装固定用它。 */
internal const val BASE_APK = "base.apk"

/**
 * 把 [apk] 写进会话：`openWrite` → 按流拷贝 → `fsync` → **先关流**再返回。
 * 返回之后调用方才允许 `commit`——顺序就是 C5 第 2 条要的证据。
 */
internal fun copyApkIntoSession(handle: InstallSessionHandle, apk: File) {
    apk.inputStream().use { input ->
        handle.openWrite(BASE_APK, 0L, apk.length()).use { output ->
            val buffer = ByteArray(INSTALL_COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) output.write(buffer, 0, read)
            }
            // 必须在关闭写流**之前**：关流之后系统可能已经开始处理这份包。
            handle.fsync(output)
        }
    }
}

/**
 * 唯一碰到 `PackageInstaller` 的地方（简报 §5.2）。编排（[UpdateCenter]）只认
 * [InstallSessionGateway]，测例给的是显式 fake。
 *
 * 会话参数按 SURVEY 定稿：`SessionParams(MODE_FULL_INSTALL)` + `setSize`。
 * **不调 `setAppPackageName`**（`MODE_FULL_INSTALL` 不需要它，目标本来就只能是本应用），
 * 但也**不**用「它被废弃了」当理由——查过 android-37 的 `PackageInstaller$SessionParams`，它没有废弃标记（C5 第 2 条）。
 */
class AndroidInstallSessionGateway(
    private val context: Context,
    private val sessionState: InstallSessionState,
    private val clock: () -> Long = System::currentTimeMillis,
) : InstallSessionGateway {
    override val isAvailable: Boolean = true

    private var openSession: PackageInstaller.Session? = null
    private var currentSessionId: Int = NO_SESSION

    override fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /**
     * 结果回执的 `PendingIntent` 方案（C5 第 1 条定稿，没有第二条路线）：
     * **显式 component**（清单里的 [InstallResultReceiver]，`exported="false"`）+ `FLAG_MUTABLE`。
     *
     *  * 显式 Intent 不会撞 lint 的 `MutableImplicitPendingIntent`；
     *  * `FLAG_MUTABLE` 才能让系统把 `EXTRA_STATUS` / `Intent.EXTRA_INTENT` 填进来；
     *    targetSdk 37 + `FLAG_IMMUTABLE` 会直接抛 `IllegalArgumentException`（`PackageInstaller.java:2336`），
     *    所以动态接收器 + immutable 那条退路已经删掉；
     *  * `exported="false"` 的清单接收器在进程被回收后仍能把结果带回来，动态注册的不能。
     */
    internal fun receiptIntent(sessionId: Int): Intent =
        Intent(context, InstallResultReceiver::class.java)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)

    internal fun receiptPendingIntent(sessionId: Int): PendingIntent =
        PendingIntent.getBroadcast(context, sessionId, receiptIntent(sessionId), RECEIPT_FLAGS)

    override suspend fun install(apk: File, tagName: String, baselineVersionCode: Long): InstallOutcome =
        withContext(Dispatchers.IO) {
            if (!apk.isFile) return@withContext InstallOutcome.Failed("安装包不见了，请重新下载。")
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // setSize 让系统提前算空间：不然要写到一半才报 STATUS_FAILURE_STORAGE。
            params.setSize(apk.length())

            val sessionId = try {
                installer.createSession(params)
            } catch (error: Exception) {
                return@withContext InstallOutcome.Failed(
                    "建不了安装会话：${error.message ?: "系统拒绝了这次安装"}。",
                )
            }
            currentSessionId = sessionId
            val session = try {
                installer.openSession(sessionId)
            } catch (error: Exception) {
                runCatching { installer.abandonSession(sessionId) }
                currentSessionId = NO_SESSION
                return@withContext InstallOutcome.Failed(
                    "打不开安装会话：${error.message ?: "系统拒绝了这次安装"}。",
                )
            }
            openSession = session
            record(sessionId, tagName, baselineVersionCode, InstallPhase.Created)

            try {
                val handle = SessionHandle(session, receiptPendingIntent(sessionId))
                copyApkIntoSession(handle, apk)
                record(sessionId, tagName, baselineVersionCode, InstallPhase.Written)
                handle.commit()
                record(sessionId, tagName, baselineVersionCode, InstallPhase.Committed)
                InstallOutcome.Committed(sessionId)
            } catch (error: Exception) {
                // commit 之前的任何异常：放弃会话、清掉这条记录，给人话（别留半个会话占着磁盘）。
                runCatching { session.abandon() }
                sessionState.clear(sessionId)
                currentSessionId = NO_SESSION
                InstallOutcome.Failed("把安装包交给系统失败：${error.message ?: "未知原因"}。")
            } finally {
                runCatching { session.close() }
                openSession = null
            }
        }

    override fun cancel() {
        runCatching { openSession?.abandon() }
        openSession = null
        if (currentSessionId != NO_SESSION) {
            sessionState.clear(currentSessionId)
            currentSessionId = NO_SESSION
        }
    }

    override fun sessionAlive(sessionId: Int): Boolean = runCatching {
        context.packageManager.packageInstaller.getSessionInfo(sessionId) != null
    }.getOrDefault(false)

    /** 每个阶段都落盘：进程随时可能被回收（成功安装必然终结旧进程）。 */
    private fun record(sessionId: Int, tagName: String, baselineVersionCode: Long, phase: InstallPhase) {
        sessionState.advance(
            InstallSessionRecord(
                sessionId = sessionId,
                tagName = tagName,
                packageName = context.packageName,
                baselineVersionCode = baselineVersionCode,
                phase = phase,
                updatedAtMs = clock(),
            ),
        )
    }

    /** 真机侧的最小包装：一个字节都不在这里改，只把 `Session` 的调用对上号。 */
    private class SessionHandle(
        private val session: PackageInstaller.Session,
        private val receipt: PendingIntent,
    ) : InstallSessionHandle {
        override fun openWrite(name: String, offset: Long, size: Long): OutputStream =
            session.openWrite(name, offset, size)

        override fun fsync(stream: OutputStream) = session.fsync(stream)

        override fun commit() = session.commit(receipt.intentSender)

        override fun abandon() = session.abandon()

        override fun close() = session.close()
    }

    companion object {
        private const val NO_SESSION = -1

        /**
         * 显式 Intent + 明确的 flag：lint 的 `UnspecifiedImmutableFlag` / `MutableImplicitPendingIntent` 都靠它过。
         *
         * `FLAG_MUTABLE` 是 API 31 才有的：minSdk 29 上要按版本分支（31 之前的 PendingIntent 本来就允许
         * 系统往里面填 extras），否则 lint 的 `NewApi` 会红——本仓 lint 警告即失败。
         */
        internal val RECEIPT_FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
}