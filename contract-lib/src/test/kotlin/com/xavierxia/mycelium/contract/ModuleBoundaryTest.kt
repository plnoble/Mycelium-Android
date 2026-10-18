package com.xavierxia.mycelium.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText

/**
 * HTTP stays in :net. :roundtable and :contract call the gateway interface.
 * A future hearth module, named with that prefix, must not depend on :net.
 */
class ModuleBoundaryTest {
    private val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile() }

    @Test
    fun onlyNetDependsOnAnHttpLibrary() {
        val settings = root.resolve("settings.gradle.kts").readText()
        val modules = Regex("""include\("(:[^"]+)"\)""").findAll(settings).map { it.groupValues[1] }.toList()
        assertTrue(":net" in modules)
        modules.forEach { module ->
            val directory = if (module == ":contract") root.resolve("contract-lib") else root.resolve(module.removePrefix(":"))
            val build = directory.resolve("build.gradle.kts").readText()
            val sources = Files.walk(directory.resolve("src/main")).filter { it.toString().endsWith(".kt") }
            val production = build.substringBefore("testImplementation") + sources.toList().joinToString("\n") { it.readText() }
            val http = listOf("okhttp", "retrofit", "ktor", "java.net.http", "HttpURLConnection", "openConnection")
            if (module == ":net") {
                assertTrue(production.contains("HttpURLConnection"), ":net 应该是唯一打开连接的模块")
            } else {
                http.forEach { token ->
                    assertFalse(production.contains(token), "$module 含有 $token")
                }
            }
            if (module.startsWith(":hearth")) {
                assertFalse(build.contains("""project(":net")"""), "$module 不许依赖 :net")
            }
        }
    }
}
