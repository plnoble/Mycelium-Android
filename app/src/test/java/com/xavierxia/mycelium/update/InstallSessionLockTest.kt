package com.xavierxia.mycelium.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M7：安装会话状态锁必须**全进程唯一**，两套实现（内存里的接收端、落盘的偏好存储）共用同一把，
 * 否则「读—判断—写」会交错，终态可以被更早的阶段盖回去。
 */
@RunWith(RobolectricTestRunner::class)
class InstallSessionLockTest {
    private fun record(phase: InstallPhase, status: Int = 0) = InstallSessionRecord(
        sessionId = 42,
        tagName = "v0.5.0",
        packageName = "com.xavierxia.mycelium",
        baselineVersionCode = 4L,
        phase = phase,
        statusCode = status,
        updatedAtMs = 1L,
        targetVersionCode = 5L,
    )

    @Test
    fun concurrentWritersCannotLetATerminalSessionGoBackwards() {
        val prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
        val plan = listOf(InstallPhase.Pending, InstallPhase.Committed, InstallPhase.Succeeded)
        try {
            prefs.advance(record(InstallPhase.Created))
            val failures = ConcurrentLinkedQueue<Throwable>()
            val threads = (0 until 8).map { id ->
                Thread {
                    try {
                        for (round in 0 until 500) {
                            prefs.advance(record(plan[(id + round) % plan.size]))
                        }
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(30_000) }

            assertEquals("线程里不该有异常：$failures", 0, failures.size)
            assertEquals(
                "终态写下去之后，别的线程不许再把它写回更早的阶段",
                InstallPhase.Succeeded,
                prefs.read()?.phase,
            )
        } finally {
            prefs.clear(42)
        }
    }

    /**
     * 两套实现必须争**同一把**锁：测试自己拿着它，落盘实现的读就得等在外面。
     * 如果哪天有人给其中一个实现换成自己的锁，这一条会立刻失败。
     */
    @Test
    fun aReadOfTheDiskStoreWaitsForTheOneSharedLock() {
        val prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
        val released = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        val holder = Thread {
            synchronized(installSessionLock) {
                released.countDown()
                Thread.sleep(400)
            }
        }
        holder.start()
        assertTrue("没拿到共享锁", released.await(5, TimeUnit.SECONDS))

        val reader = Thread {
            prefs.read()
            readDone.countDown()
        }
        reader.start()
        assertFalse(
            "落盘实现的读没有被这把锁挡住，说明它用的是自己的锁",
            readDone.await(120, TimeUnit.MILLISECONDS),
        )
        holder.join(5_000)
        assertTrue("锁放行之后读应该完成", readDone.await(5, TimeUnit.SECONDS))
        reader.join(5_000)
    }

    @Test
    fun bothImplementationsApplyTheSameFirstWriteRule() {
        // 空记录只允许从初始阶段写：内存实现与落盘实现走的是同一条规则。
        val memory = MemoryInstallSessionState()
        assertFalse(memory.advance(record(InstallPhase.Succeeded, InstallStatusCode.SUCCESS)))

        val prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
        prefs.clear(42)
        assertFalse(prefs.advance(record(InstallPhase.Succeeded, InstallStatusCode.SUCCESS)))
    }
}