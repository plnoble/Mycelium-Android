package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全量审 R2 P1-2：rekey **中途异常不得留下悬挂条目**——每个句柄要么在仓库里（可被 find/trim
 * 看到）、要么已经通过 onDrop 移交调用方，绝不能「仓库查不到、原生还占着」。
 *
 * 旧代码在 `byKey.remove(from)` 之后任何一步抛异常（插入目标 / 排序 / 淘汰）都会悬挂；
 * 新代码：所有权在迁移成功之前不松手（插入阶段失败回滚到源键），淘汰阶段异常发生在 onDrop
 * **内部**——那一刻句柄已经移交，语义上就是「淘汰」而不是「丢失」。
 *
 * 注入手段：onDrop 抛异常（JVM 层唯一可靠的注入点；插入阶段的分配失败无法在 JVM 注入，
 * 由实现结构保证——`remove+getOrPut` 与「插入+回滚」分别包在两个 try 里）。
 */
class RekeyRollbackTest {

    @Test
    fun aMidRekeyFailureLeavesNoOrphanHandleBehind() {
        val released = mutableListOf<Int>()
        var explode = false
        val store = CheckpointStore(maxPerKey = 1, onDrop = { handle ->
            released += handle
            if (explode) throw OutOfMemoryError("注入：淘汰阶段失败")
        })

        assertTrue(store.put("from", pos = 10, handle = 100, bytes = 1_000))
        assertTrue(store.put("to", pos = 5, handle = 200, bytes = 1_000))

        // 目标键已满：rekey 插入后按序号淘汰最旧的（= from 那份，它更早存）——onDrop 注入失败。
        explode = true
        val failure = runCatching { store.rekey("from", "to") }.exceptionOrNull()
        explode = false
        assertTrue("注入的失败必须抛出来，实际：$failure", failure is OutOfMemoryError)

        // 没有悬挂：from 的句柄已经通过 onDrop 移交（调用方负责释放），不在仓库里假装还在。
        assertTrue("句柄 100 必须已移交 onDrop（实际释放：$released）", 100 in released)
        assertNull("源键不得留半份", store.find("from", maxPos = Int.MAX_VALUE))
        // 目标键自己的那份原样保留，总量账目一致。
        assertEquals("目标键应只剩自己那份", 1, store.countFor("to"))
        assertEquals(200, store.find("to", maxPos = Int.MAX_VALUE)?.handle)
        assertEquals(1_000, store.totalBytes)
        // 之后一切如常：淘汰句柄 200 时 onDrop 正常收走。
        store.invalidate("to")
        assertTrue("作废目标键时应正常释放句柄 200", 200 in released)
        assertNull(store.find("to", maxPos = Int.MAX_VALUE))
    }
}
