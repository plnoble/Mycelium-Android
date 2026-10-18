package com.xavierxia.mycelium.update

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateFormatTest {
    @Test
    fun sizeIsShownInMebibytesWithOneDecimal() {
        assertEquals("1.0", UpdateFormat.mebibytes(1024L * 1024))
        assertEquals("38.0", UpdateFormat.mebibytes(38L * 1024 * 1024))
        // 不是整 MiB 时也只看一位小数：39_000_000 B = 37.19… MiB。
        assertEquals("37.2", UpdateFormat.mebibytes(39_000_000L))
        assertEquals("0.0", UpdateFormat.mebibytes(0))
    }

    @Test
    fun lastCheckTimeUsesTheFixedPattern() {
        val millis = Instant.parse("2026-10-18T03:30:00Z").toEpochMilli()

        assertEquals("2026-10-18 11:30", UpdateFormat.lastCheckAt(millis, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-18 03:30", UpdateFormat.lastCheckAt(millis, ZoneId.of("UTC")))
    }
}