package com.xavierxia.mycelium.hearth

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 每个用例的硬上限：用例超过 [timeoutMs] 还没结束，就把**全部线程栈**打进 instrument 输出，
 * 再把这一例判为失败，后面的用例照常跑。挂死从此是一次有证据的失败，而不是没人管的十六分钟。
 *
 * 界限说明：这个上限防的是「用例自己的等待没有边界」。如果整个进程被系统的冻结器冻住（本机确实
 * 出现过：进程所有线程停在 `do_freezer_trap`，见 docs 里的设备证据），进程内的任何看门狗也一起
 * 被冻住，那种情况只能靠主机侧的 instrument 看门狗 force-stop 兜底。
 */
class BoundedDeviceTest(private val timeoutMs: Long = 240_000) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            val worker = Thread(
                {
                    try {
                        base.evaluate()
                    } catch (problem: Throwable) {
                        failure = problem
                    } finally {
                        done.countDown()
                    }
                },
                "bounded-${description.methodName}",
            )
            worker.isDaemon = true
            worker.start()
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                failure?.let { throw it }
                return
            }
            worker.interrupt()
            val dump = threadDump()
            println("用例 ${description.className}#${description.methodName} 超过 ${timeoutMs}ms 没有结束。全部线程栈：\n$dump")
            throw AssertionError(
                "用例 ${description.methodName} 超过 ${timeoutMs}ms 没有结束（判定为挂住）；全部线程栈已打进 instrument 输出",
            )
        }
    }
}
