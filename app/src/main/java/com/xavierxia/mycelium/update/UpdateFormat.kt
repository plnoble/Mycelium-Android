package com.xavierxia.mycelium.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 界面上的数字与时间格式。文案在 strings.xml，这里只做纯计算，方便单测（固定 zone，不受机器时区影响）。
 */
object UpdateFormat {
    private val lastCheckFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** 安装包大小：MiB，一位小数。用的是 1 MiB = 1024 KiB，和「我的」页已有的 KB 口径一致。 */
    fun mebibytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0)

    /** 「上次检查：……」里的时间部分。 */
    fun lastCheckAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        lastCheckFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
}