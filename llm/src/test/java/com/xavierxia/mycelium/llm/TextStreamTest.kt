package com.xavierxia.mycelium.llm
import org.junit.Assert.*
import org.junit.Test

class TextStreamTest {
    @Test fun splitUnicodeNeverCorruptsText() {
        val original = "你好🌿 café"
        val bytes = original.toByteArray()
        for (split in 0..bytes.size) {
            val stream = Utf8Stream()
            assertEquals(original, stream.push(bytes.copyOfRange(0, split)) +
                stream.push(bytes.copyOfRange(split, bytes.size), true))
        }
    }
    @Test fun thoughtTagsAreHiddenAcrossEveryBoundary() {
        val original = "前<think>秘密😀</think>正文<think>更多秘密</think>末"
        for (split in 0..original.length) {
            val filter = ThinkingFilter()
            assertEquals("前正文末", filter.push(original.take(split)) + filter.push(original.drop(split)))
        }
        val filter = ThinkingFilter()
        assertEquals("前正文末", original.map { filter.push(it.toString()) }.joinToString(""))
    }
    @Test fun incompleteAndTemplateOpenedThoughtsStayHidden() {
        assertEquals("正文", ThinkingFilter(true).push("秘密</think>正文"))
        assertEquals("", ThinkingFilter().push("<think>尚未结束"))
        assertEquals("正文", ThinkingFilter().push("正文<thi"))
    }
}

