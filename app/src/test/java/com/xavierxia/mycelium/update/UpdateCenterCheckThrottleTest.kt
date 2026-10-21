package com.xavierxia.mycelium.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * M6：一天的「一次」要按**尝试开始**算，而且同时只发一次。
 *
 * 原来那次时间写在请求回来之后：中途退出、界面重建或被取消，就不算尝试过，下次启动立刻再发；
 * 两个入口同时进来也都能通过旧的判断。这里两件一起钉：先记时间再发请求，以及在途互斥。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCenterCheckThrottleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: UpdatePreferences

    @Before
    fun setUp() {
        prefs = UpdatePreferences(RuntimeEnvironment.getApplication())
    }

    /** 可以被卡住的发布源：进入时 +1 并放行闸门，等测例说「继续」。 */
    private class BlockingSource : ReleaseSource {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        @Volatile var block = false

        override fun latestRelease(): ReleaseInfo? {
            calls.incrementAndGet()
            entered.countDown()
            if (block) gate.await(10, TimeUnit.SECONDS)
            return testRelease("0.5.0")
        }
    }

    private val hour = 3_600_000L

    private fun center(source: ReleaseSource, clock: () -> Long) = testCenter(
        source = source,
        prefs = prefs,
        clock = clock,
        files = UpdateFiles(tmp.root, availableBytes = { Long.MAX_VALUE / 4 }),
        downloader = RecordingDownloader(),
        verifier = RecordingVerifier(),
        installer = RecordingInstaller(),
    )

    @Test(timeout = 30_000)
    fun twoOverlappingAutomaticChecksOnlyAskTheSourceOnce() {
        val source = BlockingSource().apply { block = true }
        val center = center(source) { 0L }
        var background: kotlinx.coroutines.Job? = null
        runBlocking(Dispatchers.Default) {
            val job = launch(Dispatchers.Default) { center.checkIfDue() }
            background = job
            assertTrue("第一次自动检查应当已经发起", source.entered.await(5, TimeUnit.SECONDS))
            // 第二个入口此刻进来：应当被在途闸门挡下，不再问发布源。
            center.checkIfDue()
            assertEquals("并发自动检查只能发一次", 1, source.calls.get())
            source.gate.countDown()
            withTimeout(10_000) { job.join() }
        }
        assertEquals(1, source.calls.get())
        background = null
    }

    @Test(timeout = 30_000)
    fun anAutomaticCheckThatIsCancelledStillCountsAsTodayAttempt() {
        val source = BlockingSource().apply { block = true }
        val clock = { 5 * hour }
        val center = center(source, clock)
        runBlocking(Dispatchers.Default) {
            val job = launch(Dispatchers.Default) { center.checkIfDue() }
            assertTrue(source.entered.await(5, TimeUnit.SECONDS))
            assertNotEquals("时间必须在请求发出之前就记下", 0L, prefs.lastAutoAttemptAtMs)
            job.cancel()
            job.join()
            source.gate.countDown()
        }
        assertEquals(clock(), prefs.lastAutoAttemptAtMs)

        // 同一小时内重建（模拟重启）：不再自动请求。
        val rebuilt = center(source, clock)
        runBlocking { rebuilt.checkIfDue() }
        assertEquals("取消过的那次也算尝试，24 小时内不重发", 1, source.calls.get())
    }

    @Test(timeout = 30_000)
    fun aManualCheckIsNotThrottledByTheAutomaticOne() {
        val source = BlockingSource()
        val center = center(source) { 0L }
        runBlocking { center.checkIfDue() }
        assertEquals(1, source.calls.get())

        runBlocking { center.checkNow() }
        assertEquals("手动检查不受 24 小时限制", 2, source.calls.get())
    }
}
