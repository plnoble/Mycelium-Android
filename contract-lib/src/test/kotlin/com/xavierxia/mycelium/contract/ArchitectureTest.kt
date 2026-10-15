package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** The contract module stays pure Kotlin: no Android SDK and no HTTP client. */
class ArchitectureTest {
    @Test
    fun moduleDoesNotDependOnAndroidOrNetworking() {
        val buildFile = moduleRoot().resolve("build.gradle.kts").readText()
        val forbidden = listOf("com.android", "androidx.", "okhttp", "retrofit", "ktor", "java.net.http")
        forbidden.forEach { token ->
            assertTrue(token !in buildFile, "build.gradle.kts 含有禁止依赖 $token")
        }
        val sources = Files.walk(moduleRoot().resolve("src/main")).filter { it.toString().endsWith(".kt") }
        sources.forEach { source ->
            val text = source.readText()
            listOf("android.", "androidx.", "okhttp3.", "retrofit2.", "io.ktor.", "java.net.http.").forEach { token ->
                assertTrue(token !in text, "${source.fileName} 引用了 $token")
            }
        }
    }
}

private fun moduleRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
    .first { it.resolve("contract-lib/build.gradle.kts").toFile().isFile() }
    .resolve("contract-lib")
