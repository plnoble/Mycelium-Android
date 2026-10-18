package com.xavierxia.mycelium.update

/**
 * 自动检查更新的节流口径（C3 定稿）：节流看的是**自动尝试**的时间戳，成功和失败都算 —— 也就是
 * 任务书和工作包里的「一天最多一次」。失败后不更新时间会让每次启动都再打一次 GitHub，
 * 这是方案审明确要求改掉的。
 *
 * [[UpdatePreferences.lastSuccessAtMs]] 是另一个字段，只用来显示「上次检查：……」。
 * 手动检查不经过这里。
 */
object UpdateCheckPolicy {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * 时钟回拨（now < last）视为到期：宁可多查一次，也不卡死。
     * `lastAutoAttemptAtMs <= 0` 就是「从来没查过」，也必须到期 —— 不能靠「现在是 1970 年之后
     * 好几十年」这种巧合：设备时钟不对时，全新安装会永远不查（`nowMs - 0 >= INTERVAL_MS` 这时是假）。
     */
    fun isDue(nowMs: Long, lastAutoAttemptAtMs: Long): Boolean =
        lastAutoAttemptAtMs <= 0L ||
            nowMs - lastAutoAttemptAtMs >= INTERVAL_MS ||
            nowMs < lastAutoAttemptAtMs
}