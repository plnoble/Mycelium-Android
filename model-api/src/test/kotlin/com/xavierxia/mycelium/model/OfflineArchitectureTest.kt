package com.xavierxia.mycelium.model
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class OfflineArchitectureTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
    @Test fun localModulesHaveNoNetworkCodeOrProjectEdge() {
        val forbidden = Regex("""(java\.net\.|javax\.net\.|okhttp|retrofit|io\.ktor|HttpURLConnection|https?://|<sys/socket\.h>|<netdb\.h>|cpp-httplib)""")
        for (module in listOf("model-api", "llm")) {
            val build = root.resolve("$module/build.gradle.kts").readText()
            assertFalse(build.contains("project(\":net\")"))
            Files.walk(root.resolve("$module/src/main")).use { files ->
                files.filter { it.toString().endsWith(".kt") || it.toString().endsWith(".cpp") }.forEach {
                    assertFalse(forbidden.containsMatchIn(it.readText()), "Network reference in $it")
                }
            }
        }
        val manifest = root.resolve("llm/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.INTERNET"))
        val cmake = root.resolve("llm/src/main/cpp/CMakeLists.txt").readText()
        assertTrue(cmake.contains("set(LLAMA_BUILD_COMMON OFF"))
        assertTrue(cmake.contains("set(LLAMA_BUILD_SERVER OFF"))
        assertFalse(cmake.contains("target_link_libraries(mycelium_llm PRIVATE llama-common"))
    }
}

