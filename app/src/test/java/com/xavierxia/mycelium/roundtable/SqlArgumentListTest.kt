package com.xavierxia.mycelium.roundtable

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SqlArgumentListTest {
    @Test
    fun oneParameterStartsAtTheFirstSlot() {
        val arguments = SqlArgumentList()
        arguments.setString(1, "LC-T1")
        assertArrayEquals(arrayOf("LC-T1"), arguments.toTypedArray())
    }

    @Test
    fun consecutiveParametersKeepTheirPositions() {
        val arguments = SqlArgumentList()
        arguments.setString(1, "LC-T1")
        arguments.setLong(2, 20)
        assertArrayEquals(arrayOf<Any?>("LC-T1", 20L), arguments.toTypedArray())
    }

    @Test
    fun settingTheSameIndexAgainReplacesTheValue() {
        val arguments = SqlArgumentList()
        arguments.setString(1, "LC-T1")
        arguments.setString(1, "LC-T2")
        assertArrayEquals(arrayOf("LC-T2"), arguments.toTypedArray())
    }
}
