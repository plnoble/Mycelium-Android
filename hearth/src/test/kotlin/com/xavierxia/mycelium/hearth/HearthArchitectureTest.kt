package com.xavierxia.mycelium.hearth

import com.xavierxia.mycelium.arch.ModuleBoundaryCheck
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 炉边模块的边界守卫。
 *
 * 这条守住什么：
 *  1. 模块集合来自 `settings.gradle.kts` 真实登记的 `include(...)`（含多参数、跨行写法），
 *     所有以 `hearth` 开头的模块都要被检查，而不是只认 `:hearth` 一个；
 *  2. 生产依赖层面（`dependencies { }` 里的 project 依赖，排除 test / debug 配置）：
 *     hearth 模块的生产依赖**传递闭包**里不许出现 `:net`，也不许出现 HTTP 库；
 *  3. 源码原文层面：hearth 模块的构建脚本与 `src/main` 里不许出现 HTTP 记号、
 *     `java.net.` / `javax.net.` / `java.sql`；`sqlite-jdbc` 只能是测试依赖。
 *
 * 2 的判定与三条「必须会失败」的合成用例共用 [ModuleBoundaryCheck]：合成用例只喂字符串
 * （settings 用跨行 `include(...)` 写法，顺带证明跨行解析），不碰真实仓库。
 */
class HearthArchitectureTest {

    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private val repo = ModuleBoundaryCheck.RepoModel(root)

    /** settings.gradle.kts 里真实登记的 hearth 模块（现在只有 :hearth）。 */
    private val hearthModules = ModuleBoundaryCheck.hearthModules(repo.modules)

    /** HTTP 记号沿用 ModuleBoundaryTest 的清单，再补上源码里直接写死的 URL。 */
    private val forbiddenTokens = ModuleBoundaryCheck.HTTP_TOKENS + listOf("http://", "https://")

    private val forbiddenSourceTokens = forbiddenTokens + listOf("java.net.", "javax.net.", "java.sql")

    @Test
    @DisplayName("真实仓库：:hearth（settings 里唯一的 hearth 模块，全仓库登记 :app :contract :net :roundtable :model-api :llm :hearth）的构建脚本与 src/main 都不含 HTTP / java.net / java.sql")
    fun hearthDoesNotDependOnNetOrAnHttpLibrary() {
        assertTrue(hearthModules.isNotEmpty(), "settings.gradle.kts 里没有 hearth 开头的模块：${repo.modules}")
        assertEquals(listOf(":hearth"), hearthModules, "被检查的 hearth 模块应当就是 settings 里登记的那些")
        hearthModules.forEach { module ->
            val build = repo.buildText(module)
            assertFalse(build.isBlank(), "$module 的构建脚本应当非空：${repo.buildFile(module)}")
            assertFalse(build.contains("""project(":net")"""), "$module 的构建脚本不许依赖 :net")
            forbiddenTokens.forEach { token ->
                assertFalse(build.lowercase().contains(token), "$module 的构建脚本引用了 $token")
            }
            // 只在依赖声明行里判：注释里恰好提到驱动不算依赖，算进去会因为错误的理由失败。
            val declarations = build.lines().filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            assertFalse(
                declarations.any { "sqlite-jdbc" in it && "testImplementation" !in it },
                "$module 的 sqlite-jdbc 只能是测试依赖，模块自己不带数据库实现",
            )

            val sources = repo.mainSourceTexts(module)
            assertTrue(sources.isNotEmpty(), "$module 的 src/main 里应当有 Kotlin/Java 源码")
            sources.forEach { (source, text) ->
                forbiddenSourceTokens.forEach { token ->
                    assertFalse(text.lowercase().contains(token), "$source 引用了 $token")
                }
            }
        }
        // 炉边的公开 API 带着 :model-api 的 ChatMessage/ChatRole，这条同时证明查的是真实文件。
        assertTrue(repo.buildText(":hearth").contains("project(\":model-api\")"))
    }

    @Test
    @DisplayName("真实仓库：:hearth（settings 里唯一的 hearth 模块）的生产依赖传递闭包不含 :net、不含 HTTP 库")
    fun hearthProductionDependencyGraphReachesNeitherNetNorHttpLibrary() {
        assertTrue(hearthModules.isNotEmpty(), "settings.gradle.kts 里没有 hearth 开头的模块：${repo.modules}")
        // 正向对照：依赖图与传递闭包确实在工作 —— :app -> :roundtable -> :net 是真实存在的链。
        assertTrue(
            ":net" in ModuleBoundaryCheck.transitiveClosure(":app", repo.projectDependencies),
            ":app 应当经生产依赖传递到达 :net，验明依赖图不是空的",
        )
        assertTrue(repo.boundaryViolations.isEmpty(), repo.boundaryViolations.joinToString("；"))
    }

    // ------------------------------------------------------------ 合成失败用例
    // 下面三条只喂字符串、不碰真实仓库，证明判定在三种越界场景下确实会报错。
    // settings 故意写成跨行的 include(...)：顺带证明跨行写法也能解析出模块集合。

    @Test
    fun syntheticHearthModuleDependingDirectlyOnNetIsRejected() {
        val settings = """
            include(
                ":hearth",
                ":net",
            )
        """.trimIndent()
        assertEquals(listOf(":hearth", ":net"), ModuleBoundaryCheck.includedModules(settings))
        val violations = syntheticViolations(
            settings,
            mapOf(
                ":hearth" to "dependencies { implementation(project(\":net\")) }",
                ":net" to "dependencies { implementation(\"org.json:json:20240303\") }",
            ),
        )
        assertTrue(violations.isNotEmpty(), "直接依赖 :net 必须判违规")
        assertTrue(
            violations.any { it.startsWith(":hearth ") && "直接依赖 :net" in it },
            violations.toString(),
        )
    }

    @Test
    fun syntheticHearthModuleReachingNetThroughAnotherModuleIsRejected() {
        val violations = syntheticViolations(
            """
            include(":hearth", ":adapter", ":net")
            """.trimIndent(),
            mapOf(
                ":hearth" to "dependencies { implementation(project(\":adapter\")) }",
                ":adapter" to "dependencies { implementation(project(\":net\")) }",
                ":net" to "dependencies { implementation(\"org.json:json:20240303\") }",
            ),
        )
        assertTrue(violations.isNotEmpty(), "间接依赖 :net 必须判违规")
        assertTrue(
            violations.any { it.startsWith(":hearth ") && "间接依赖 :net" in it },
            violations.toString(),
        )
        assertTrue(
            violations.any { it.contains(":hearth -> :adapter -> :net") },
            violations.toString(),
        )
        assertFalse(violations.any { it.startsWith(":hearth ") && "直接依赖" in it }, violations.toString())
    }

    @Test
    fun syntheticHearthModuleDeclaringAnHttpLibraryIsRejected() {
        val violations = syntheticViolations(
            """
            include(":hearth", ":net")
            """.trimIndent(),
            mapOf(
                ":hearth" to """
                    dependencies {
                        implementation(project(":model-api"))
                        implementation("io.ktor:ktor-client-core:3.0.0")
                        androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
                    }
                """.trimIndent(),
                ":net" to "dependencies { implementation(\"org.json:json:20240303\") }",
            ),
        )
        assertTrue(violations.isNotEmpty(), "生产依赖里直接引入 ktor 必须判违规")
        assertTrue(
            violations.any { it.startsWith(":hearth ") && "直接引入" in it && "ktor" in it },
            violations.toString(),
        )
        // androidTestImplementation 里的 okhttp 不是生产依赖，不该被算进来。
        assertFalse(violations.any { "okhttp" in it }, violations.toString())
    }

    /** 纯函数入口：合成 settings + 合成 build 脚本 → 违规清单。 */
    private fun syntheticViolations(settings: String, builds: Map<String, String>): List<String> {
        val modules = ModuleBoundaryCheck.includedModules(settings)
        val dependencies = builds.mapValues { (_, text) -> ModuleBoundaryCheck.productionDependencies(text) }
        return ModuleBoundaryCheck.hearthViolations(
            modules,
            dependencies.mapValues { it.value.projectModules },
            dependencies.mapValues { it.value.externalCoordinates },
        )
    }
}
