package com.xavierxia.mycelium.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * M3：退避等待必须**可被取消**。
 *
 * 原来的默认 `pause` 是 `Thread.sleep`，一次 10 秒的等待要睡满才醒——用户按了取消，界面还要再等十秒。
 * 这几例直接钉住分片等待的三个性质：取消能立刻打断、没取消就照常等够、已取消就不开始等。
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class CancellablePauseTest {
    @Test
    fun aStopInterruptsALongWaitWithinOneSlice() {
        val call = GatewayCall()
        val returned = CountDownLatch(1)
        val elapsed = AtomicLong(0)
        val worker = Thread {
            val started = System.nanoTime()
            try {
                cancellableSleep(10_000, call)
            } catch (_: GatewayCancelled) {
                // 预期：等待被取消打断
            } finally {
                elapsed.set((System.nanoTime() - started) / 1_000_000)
                returned.countDown()
            }
        }
        worker.start()
        Thread.sleep(100)
        call.cancel()
        assertTrue(returned.await(3, TimeUnit.SECONDS), "十秒的等待应该在取消后很快结束")
        assertTrue(elapsed.get() < 2_000, "实际等了 ${elapsed.get()} ms，太久了")
        // returned 在 worker 的 finally 里就放行了，那一刻线程还没真正退出；先等它退出再断言，
        // 否则机器忙时偶尔会看到 isAlive=true（CI run 39598001295 就是这样红的）。
        worker.join(2_000)
        assertFalse(worker.isAlive)
    }

    @Test
    fun aWaitWithoutAStopStillRunsToItsEnd() {
        val call = GatewayCall()
        val started = System.nanoTime()
        cancellableSleep(200, call)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed >= 180, "没取消就要等够，实际 ${elapsed}ms")
    }

    @Test
    fun anAlreadyCancelledCallDoesNotWaitAtAll() {
        val call = GatewayCall()
        call.cancel()
        val started = System.nanoTime()
        var cancelled = false
        try {
            cancellableSleep(10_000, call)
        } catch (_: GatewayCancelled) {
            cancelled = true
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(cancelled, "已经取消的等待不该开始")
        assertTrue(elapsed < 200, "不该睡，实际 ${elapsed}ms")
    }
}
