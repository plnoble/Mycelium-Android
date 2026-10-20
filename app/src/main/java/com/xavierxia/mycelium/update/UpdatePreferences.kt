package com.xavierxia.mycelium.update

import android.content.Context
import androidx.core.content.edit

/**
 * 更新相关的普通偏好存储（不是个人数据，所以不走 SecretStore）。
 *
 * 四个键一次定完（C3 建议做法）：上次**自动尝试**时间、上次**成功**时间、忽略的版本、
 * 当前候选发布的元数据 + 目标 tag；另加已落盘的候选 APK 路径，供进程重建后核对文件是否还在。
 *
 * 第 5 步再加一组**安装会话**字段（C5 第 3 条）：成功安装一定会终结旧进程，会话状态不落盘
 * 就没法在新进程里把结果认回来。它就实现 [InstallSessionState]，接收器与编排器共用同一份存储。
 */
class UpdatePreferences(context: Context) : InstallSessionState {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** 「自动检查更新」开关，默认开。 */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    /**
     * 上次自动尝试的时间，**成功和失败都写**。
     * 取消（进程被杀/协程取消）不写：那次请求没有跑完，不该算一次尝试。
     */
    var lastAutoAttemptAtMs: Long
        get() = prefs.getLong(KEY_LAST_AUTO_ATTEMPT_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_AUTO_ATTEMPT_AT, value) }

    /** 上次真正跑完（不含失败）的时间，只用于显示。 */
    var lastSuccessAtMs: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SUCCESS_AT, value) }

    /** 用户点过「忽略这个版本」的 tag。 */
    var ignoredTag: String?
        get() = prefs.getString(KEY_IGNORED_TAG, null)
        set(value) = prefs.edit { putString(KEY_IGNORED_TAG, value) }

    /** 候选发布的元数据（JSON）。进程重建后据此恢复卡片，不用再请求一次。 */
    var pendingReleaseJson: String?
        get() = prefs.getString(KEY_PENDING_RELEASE, null)
        set(value) = prefs.edit { putString(KEY_PENDING_RELEASE, value) }

    /** 候选发布的目标 tag，单独存一份：恢复时要和元数据里的 tag 对得上。 */
    var pendingReleaseTag: String?
        get() = prefs.getString(KEY_PENDING_TAG, null)
        set(value) = prefs.edit { putString(KEY_PENDING_TAG, value) }

    /** 已下载待安装的 APK 路径；恢复时重新核对文件存在性。第 4 步开始写。 */
    var pendingApkPath: String?
        get() = prefs.getString(KEY_PENDING_APK, null)
        set(value) = prefs.edit { putString(KEY_PENDING_APK, value) }

    /** 丢掉候选发布（已是最新、已被忽略、恢复时对不上）。忽略的 tag 本身**不**清。 */
    fun clearPending() {
        prefs.edit {
            remove(KEY_PENDING_RELEASE)
            remove(KEY_PENDING_TAG)
            remove(KEY_PENDING_APK)
        }
    }

    /**
     * 落盘的安装会话。没有会话时返回 null；[sessionId] 用 -1 当「没有」的哨兵值
     * （系统给的会话 id 不会是负数）。
     */
    @Synchronized
    override fun read(): InstallSessionRecord? {
        val id = prefs.getInt(KEY_SESSION_ID, NO_SESSION)
        if (id == NO_SESSION) return null
        val phase = prefs.getString(KEY_SESSION_PHASE, null)?.let { stored ->
            InstallPhase.entries.firstOrNull { it.name == stored }
        } ?: return null
        return InstallSessionRecord(
            sessionId = id,
            tagName = prefs.getString(KEY_SESSION_TAG, null).orEmpty(),
            packageName = prefs.getString(KEY_SESSION_PACKAGE, null).orEmpty(),
            baselineVersionCode = prefs.getLong(KEY_SESSION_BASELINE, 0L),
            phase = phase,
            statusCode = prefs.getInt(KEY_SESSION_STATUS, 0),
            updatedAtMs = prefs.getLong(KEY_SESSION_UPDATED_AT, 0L),
            targetVersionCode = prefs.getLong(KEY_SESSION_TARGET, 0L),
        )
    }

    /**
     * 幂等且单调的落盘：会话 id 对不上、当前已是终态、或新阶段不比当前更靠后，就不写
     * （迟到/重复的回调不作数，C5 第 3 条；阶段不可回退见 M7）。规则与 [MemoryInstallSessionState]
     * 共用 [installSessionAccepts]，整个「读—判断—写」在同一个锁里完成，两个写者不会交错。
     */
    @Synchronized
    override fun advance(record: InstallSessionRecord): Boolean {
        if (!installSessionAccepts(read(), record)) return false
        prefs.edit {
            putInt(KEY_SESSION_ID, record.sessionId)
            putString(KEY_SESSION_TAG, record.tagName)
            putString(KEY_SESSION_PACKAGE, record.packageName)
            putLong(KEY_SESSION_BASELINE, record.baselineVersionCode)
            putString(KEY_SESSION_PHASE, record.phase.name)
            putInt(KEY_SESSION_STATUS, record.statusCode)
            putLong(KEY_SESSION_UPDATED_AT, record.updatedAtMs)
            putLong(KEY_SESSION_TARGET, record.targetVersionCode)
        }
        return true
    }

    /** 只清 [sessionId] 所属的那一条：别的会话不动（C5「只清理所属会话」）。 */
    @Synchronized
    override fun clear(sessionId: Int): Boolean {
        if (prefs.getInt(KEY_SESSION_ID, NO_SESSION) != sessionId) return false
        prefs.edit {
            remove(KEY_SESSION_ID)
            remove(KEY_SESSION_TAG)
            remove(KEY_SESSION_PACKAGE)
            remove(KEY_SESSION_BASELINE)
            remove(KEY_SESSION_TARGET)
            remove(KEY_SESSION_PHASE)
            remove(KEY_SESSION_STATUS)
            remove(KEY_SESSION_UPDATED_AT)
        }
        return true
    }

    private companion object {
        const val KEY_AUTO_CHECK = "autoCheck"
        const val KEY_LAST_AUTO_ATTEMPT_AT = "lastAutoAttemptAtMs"
        const val KEY_LAST_SUCCESS_AT = "lastSuccessAtMs"
        const val KEY_IGNORED_TAG = "ignoredTag"
        const val KEY_PENDING_RELEASE = "pendingReleaseJson"
        const val KEY_PENDING_TAG = "pendingReleaseTag"
        const val KEY_PENDING_APK = "pendingApkPath"

        const val NO_SESSION = -1
        const val KEY_SESSION_ID = "installSessionId"
        const val KEY_SESSION_TAG = "installSessionTag"
        const val KEY_SESSION_PACKAGE = "installSessionPackage"
        const val KEY_SESSION_BASELINE = "installSessionBaselineVersionCode"

        /** 目标包里读出来的版本号（M7）：进程重建后确认「这一版还没装上」。 */
        const val KEY_SESSION_TARGET = "installSessionTargetVersionCode"
        const val KEY_SESSION_PHASE = "installSessionPhase"
        const val KEY_SESSION_STATUS = "installSessionStatusCode"
        const val KEY_SESSION_UPDATED_AT = "installSessionUpdatedAtMs"
    }
}