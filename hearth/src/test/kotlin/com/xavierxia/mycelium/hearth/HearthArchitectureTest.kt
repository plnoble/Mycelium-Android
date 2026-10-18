package com.xavierxia.mycelium.hearth

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HearthArchitectureTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    @Test
    fun hearthDoesNotDependOnNetOrAnHttpLibrary() {
        val build = root.resolve("hearth/build.gradle.kts").readText()
        assertTrue(build.contains("project(\":model-api\")"))
        assertFalse(build.contains("project(\":net\")"))
        val forbidden = listOf("okhttp", "retrofit", "ktor", "java.net.http", "HttpURLConnection", "http://", "https://")
        forbidden.forEach { assertFalse(build.contains(it), it) }
        // Only dependency declarations count: a comment that happens to name the driver is not a
        // dependency, and treating it as one makes the check fail for the wrong reason.
        val declarations = build.lines().filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        assertFalse(
            declarations.any { "sqlite-jdbc" in it && "testImplementation" !in it },
            "sqlite-jdbc 只能是测试依赖，模块自己不带数据库实现",
        )
        Files.walk(root.resolve("hearth/src/main")).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { source ->
                val text = source.readText()
                forbidden.forEach { token -> assertFalse(text.contains(token), "$source 引用了 $token") }
                assertFalse(text.contains("java.net."))
                assertFalse(text.contains("javax.net."))
                assertFalse(text.contains("java.sql"), "$source 用了 JDBC；会话接口是模块自己的")
            }
        }
    }
}