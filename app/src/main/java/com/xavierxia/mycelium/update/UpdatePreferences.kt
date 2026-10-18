package com.xavierxia.mycelium.update

import android.content.Context
import androidx.core.content.edit

/**
 * 更新相关的普通偏好存储（不是个人数据，所以不走 SecretStore）。
 *
 * 四个键一次定完（C3 建议做法）：上次**自动尝试**时间、上次**成功**时间、忽略的版本、
 * 当前候选发布的元数据 + 目标 tag；另加已落盘的候选 APK 路径，供进程重建后核对文件是否还在。
 */
class UpdatePreferences(context: Context) {
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

    private companion object {
        const val KEY_AUTO_CHECK = "autoCheck"
        const val KEY_LAST_AUTO_ATTEMPT_AT = "lastAutoAttemptAtMs"
        const val KEY_LAST_SUCCESS_AT = "lastSuccessAtMs"
        const val KEY_IGNORED_TAG = "ignoredTag"
        const val KEY_PENDING_RELEASE = "pendingReleaseJson"
        const val KEY_PENDING_TAG = "pendingReleaseTag"
        const val KEY_PENDING_APK = "pendingApkPath"
    }
}