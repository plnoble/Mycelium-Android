package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * P2-1d：线程数在引擎入口夹到核数（2026-10-23 夜，任务书第一段第 3 条）。
 *
 * 真机实测（NX809J，8 核）：8 线程生成 10.25 tok/s，10 线程 0.18、12 线程 0.17、16 线程 0.09。
 * ggml 的线程在栅栏上自旋，超订就是互相抢核，生成速度掉两个数量级——所以界面上填多大都不能照单全收。
 */
class EngineOptionsThreadsTest {

    @Test
    fun `threads above the core count are clamped`() {
        assertEquals(8, EngineOptions(threads = 12).clampedThreads(cores = 8))
        assertEquals(8, EngineOptions(threads = 16).clampedThreads(cores = 8))
        assertEquals(8, EngineOptions(threads = 8).clampedThreads(cores = 8))
    }

    @Test
    fun `fewer threads than cores are left alone`() {
        // 少用几个核是合法的（用户可能想让后台任务有核可用），不能被抬上去。
        assertEquals(4, EngineOptions(threads = 4).clampedThreads(cores = 8))
        assertEquals(6, EngineOptions(threads = 6).clampedThreads(cores = 8))
    }

    @Test
    fun `invalid thread counts are rejected before clamping`() {
        // 0 与负数在构造时就拒绝（`require(threads in 1..16)`）：它们不是「太多」，是坏输入，
        // 静默改成 1 会掩盖界面上填错的事实。
        listOf(0, -3, 17).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { EngineOptions(threads = value) }
        }
    }

    @Test
    fun `a machine reporting zero cores still gets one thread`() {
        assertEquals(1, EngineOptions(threads = 8).clampedThreads(cores = 0))
    }

    @Test
    fun `the default is eight threads as measured`() {
        assertEquals(8, EngineOptions().threads)
        // 默认值在 8 核设备上原样通过（不触发夹取）。
        assertEquals(8, EngineOptions().clampedThreads(cores = 8))
    }
}
