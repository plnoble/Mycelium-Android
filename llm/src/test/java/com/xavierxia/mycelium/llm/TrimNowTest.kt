package com.xavierxia.mycelium.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全量审 P2-6：低内存清理必须「**现在**就释放」，而不是等下一轮生成开始。
 * 假桥记录被释放的句柄；清完之后仓库必须为空、每个句柄都必须释放过。
 */
class TrimNowTest {

    @Test
    fun trimNowDropsEveryCheckpointEvenIfNoGenerationFollows() = runBlocking {
        val released = mutableListOf<Int>()
        val bridge = object : Recorder() {
            override fun releaseCheckpointHandle(handle: Int) {
                super.releaseCheckpointHandle(handle)
                released += handle
            }
        }
        val engine = engineWith(bridge)
        engine.checkpointEnabled = true
        runBlocking {
            engine.load(model, EngineOptions()) { Descriptor() }
            round(engine)                                   // 存一份
            assertEquals(1, engine.checkpointUsage().first)
            engine.trimCheckpointsNow()                     // 系统低内存：立即清
            assertEquals("清完仓库必须为空", 0, engine.checkpointUsage().first)
            assertTrue("至少释放了一个句柄", released.isNotEmpty())
            // 之后**不再生成**：仓库保持为空（不会凭空长回来）。
            assertEquals(0, engine.checkpointUsage().first)
        }
    }
}
