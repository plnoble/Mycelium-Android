package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

class ChecksumTest {
    @Test
    fun copiedFilesMatchChecksums() {
        val root = contractRoot()
        val expected = parseJson(root.resolve("checksums.json").readText())
        val listed = expected.fieldNames()
        listed.forEach { name ->
            val path = root.resolve(name)
            assertTrue(path.isRegularFile(), "契约文件缺失：$name")
            val digest = MessageDigest.getInstance("SHA-256").digest(path.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected[name]!!.asText(), digest, name)
        }
        val present = root.toFile().walkTopDown().filter { it.isFile }
            .map { it.toPath().relativeTo(root).toString().replace('\\', '/') }
            .filter { it != "checksums.json" && it != "UPSTREAM.md" }
            .toSet()
        assertEquals(emptySet<String>(), present - listed, "契约目录里有 checksums.json 未登记的文件")
        assertEquals("0.1.0", root.resolve("VERSION").readText().trim())
    }
}
