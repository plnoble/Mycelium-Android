package com.xavierxia.mycelium.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * 结果回执的落点：**唯一在清单里注册的更新组件**，`android:exported="false"`。
 *
 * 只做两件事：
 *  1. 把系统状态**幂等落盘**（[InstallResultHandler]）——这是真正的结果，界面回来时读它；
 *  2. 需要用户确认时**尝试**拉起系统确认界面。这里不假定一定拉得起来：前台不在（后台启动限制）
 *     或进程刚被唤起时都可能失败，失败也还有 `onResume` 读 `longVersionCode` 的权威判定兜底
 *     （C5 第 3 条：不能假定接收器总能直接拉起确认 Activity）。
 *
 * 不在这里更新界面：界面只读落盘状态（C5 建议做法），进程被回收后也能在新进程里认回结果。
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        val statusCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)
        if (sessionId == NO_SESSION || statusCode == NO_STATUS) return
        // targetSdk 37 上 getParcelableExtra(String) 已废弃 → 用 IntentCompat。
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        InstallResultHandler(
            state = UpdatePreferences(context.applicationContext),
            launchConfirm = { confirmIntent -> launch(context.applicationContext, confirmIntent) },
        ).onStatus(sessionId, statusCode, confirm)
    }

    /** 拉起系统确认界面。失败（返回 false）不算错误：结果仍会按落盘状态 + 版本比对认回来。 */
    private fun launch(context: Context, confirmIntent: Intent): Boolean = runCatching {
        context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private companion object {
        const val NO_SESSION = -1
        const val NO_STATUS = Int.MIN_VALUE
    }
}

/**
 * 回执 → 阶段 → 落盘 的那一步。抽出来是为了让「结果分类」与「幂等」这两件必须成立的事
 * 能在 JVM 上直接测（简报 §5.3 测例 3/5/6/7、C5 验收证据里的「迟到/重复回调」）。
 */
class InstallResultHandler(
    private val state: InstallSessionState,
    /** 拉起系统确认界面的口子。返回有没有拉起来；默认不拉（测例只断言 Intent 被交出去）。 */
    private val launchConfirm: (Intent) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * @return 落库成功时返回分类结果；**会话 id 对不上**或**已经到终态**（迟到/重复回调）时返回 null。
     */
    fun onStatus(sessionId: Int, statusCode: Int, confirmIntent: Intent?): InstallStatus? {
        val current = state.read() ?: return null
        // 只认本应用落盘的那一次会话：别的会话（或别人的会话）一概不理。
        if (current.sessionId != sessionId) return null
        val status = if (statusCode == InstallStatusCode.PENDING_USER_ACTION) {
            // 拿不到 EXTRA_INTENT 也照样落盘「等用户确认」，不谎报失败（真机 D3 要验的就是这一条）。
            InstallStatus.PendingUserAction(confirmIntent)
        } else {
            installStatusOf(statusCode)
        }
        val advanced = state.advance(
            current.copy(
                phase = installPhaseOf(statusCode),
                statusCode = statusCode,
                updatedAtMs = clock(),
            ),
        )
        if (!advanced) return null
        val pending = status as? InstallStatus.PendingUserAction
        if (pending?.intent != null) launchConfirm(pending.intent)
        return status
    }
}