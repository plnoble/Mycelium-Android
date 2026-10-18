package com.xavierxia.mycelium.contract

import com.xavierxia.mycelium.arch.ModuleBoundaryCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 模块边界：HTTP 只许待在 `:net`。
 *
 * 这条守住什么：
 *  1. 被检查的模块集合来自 `settings.gradle.kts` 里真实登记的 `include(...)`，多参数
 *     `include(":a", ":b")`、跨行与空格写法都要解析出来，不再靠「单参数 include」的正则猜；
 *  2. 除 `:net` 外，任何模块的**生产依赖**声明与 `src/main` 源码里都不许出现 HTTP 记号；
 *  3. 任何以 `hearth` 开头的模块，生产依赖的**传递闭包**（直接 + 间接）里都不许出现
 *     `:net`，也不许出现任何 HTTP 库。
 *
 * 3 的判定与三条「必须会失败」的合成用例共用 [ModuleBoundaryCheck]：合成用例只喂字符串，
 * 不碰真实仓库，所以「判定会报错」是可证明的。
 */
class ModuleBoundaryTest {

    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().isFile() }

    private val repo = ModuleBoundaryCheck.RepoModel(root)

    @Test
    @DisplayName("真实仓库：settings.gradle.kts 的模块集合 = :app :contract :net :roundtable :model-api :llm :hearth（含多参数 include 与 projectDir 改写）")
    fun moduleSetComesFromEveryIncludeFormInSettings() {
        // 真实仓库里 :model-api / :llm / :hearth 写在同一个多参数 include 里：只认
        // include(":x") 单参数的正则会漏掉后两个。顺带锁住 settings 里 projectDir 改写的解析。
        assertEquals(
            listOf(":app", ":contract", ":net", ":roundtable", ":model-api", ":llm", ":hearth"),
            repo.modules,
        )
        assertEquals("contract-lib", repo.moduleDirectory(":contract").fileName.toString())
        repo.modules.forEach { module ->
            assertTrue(
                Files.isRegularFile(repo.buildFile(module)),
                "$module 应当有构建脚本：${repo.buildFile(module)}",
            )
        }
    }

    @Test
    fun singleMultiArgumentAndMultiLineIncludeFormsAreAllParsed() {
        val settings = """
            // 注释里的 include(":not-a-module") 不算登记
            include(":app")
            include(":a", ":b" ,   ":c")
            include(
                ":hearth",
                ":hearth-ui",
            )
            includeBuild("build-logic")
            project(":contract").projectDir = file("contract-lib")
        """.trimIndent()
        assertEquals(
            listOf(":app", ":a", ":b", ":c", ":hearth", ":hearth-ui"),
            ModuleBoundaryCheck.includedModules(settings),
        )
        assertEquals(
            mapOf(":contract" to "contract-lib"),
            ModuleBoundaryCheck.projectDirOverrides(settings),
        )
    }

    @Test
    @DisplayName("真实仓库：七个模块（:app :contract :net :roundtable :model-api :llm :hearth）里只有 :net 碰 HTTP，其它模块的生产依赖与 src/main 都不许出现 HTTP 记号")
    fun onlyNetDependsOnAnHttpLibrary() {
        assertTrue(":net" in repo.modules, "settings.gradle.kts 里必须有 :net：${repo.modules}")
        val offenders = mutableListOf<String>()
        repo.modules.forEach { module ->
            val sources = repo.mainSourceTexts(module)
            if (module == ":net") {
                // :net 是唯一允许打开连接的模块，反向锁住它确实在干这件事。
                assertTrue(
                    sources.any { (_, text) -> "HttpURLConnection" in text },
                    ":net 应该是唯一打开连接的模块",
                )
                return@forEach
            }
            repo.externalCoordinates[module].orEmpty().forEach { coordinate ->
                ModuleBoundaryCheck.httpTokensIn(coordinate).forEach { token ->
                    offenders += "$module 的生产依赖 $coordinate 命中 $token"
                }
            }
            sources.forEach { (path, text) ->
                ModuleBoundaryCheck.httpTokensIn(text).forEach { token ->
                    offenders += "$path 命中 $token"
                }
            }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("；"))
    }

    @Test
    @DisplayName("真实仓库：hearth 开头的模块（当前只有 :hearth）经生产依赖传递闭包既到不了 :net，也没有任何 HTTP 库")
    fun hearthModulesReachNeitherNetNorHttpLibraryThroughProductionDependencies() {
        val hearthModules = ModuleBoundaryCheck.hearthModules(repo.modules)
        assertTrue(hearthModules.isNotEmpty(), "settings 里必须有 hearth 开头的模块：${repo.modules}")
        // 这条断言真正检查的对象：settings.gradle.kts 里所有 hearth 开头的模块。
        // 真实仓库此刻是 [:hearth]（其余登记模块为 :app :contract :net :roundtable :model-api :llm）。
        assertEquals(listOf(":hearth"), hearthModules, "被检查的 hearth 模块应当就是 settings 里登记的那些")

        // 正向对照：依赖图和传递闭包确实在工作 —— :roundtable 生产依赖里就有 :net。
        assertTrue(
            ":net" in ModuleBoundaryCheck.transitiveClosure(":roundtable", repo.projectDependencies),
            ":roundtable 应当经生产依赖到达 :net，验明依赖图不是空的",
        )
        hearthModules.forEach { module ->
            assertFalse(
                ":net" in repo.projectDependencies[module].orEmpty(),
                "$module 不许直接依赖 :net",
            )
        }
        assertTrue(repo.boundaryViolations.isEmpty(), repo.boundaryViolations.joinToString("；"))
    }

    // ------------------------------------------------------------ 合成失败用例
    // 下面四条只喂字符串，不碰真实仓库：证明判定在三种（外加一种传递引入 HTTP 库的）
    // 越界场景下确实会报错，而不是「恒真通过」。

    @Test
    fun syntheticHearthModuleDependingDirectlyOnNetIsRejected() {
        val violations = syntheticViolations(
            settings = """include(":hearth", ":net", ":model-api")""",
            builds = mapOf(
                ":hearth" to """dependencies { api(project(":model-api")); implementation(project(":net")) }""",
                ":model-api" to """dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") }""",
                ":net" to """dependencies { implementation("org.json:json:20240303") }""",
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
            settings = """include(":hearth", ":hearth-adapter", ":roundtable", ":net")""",
            builds = mapOf(
                ":hearth" to """dependencies { implementation(project(":hearth-adapter")) }""",
                ":hearth-adapter" to """dependencies { implementation(project(":roundtable")) }""",
                ":roundtable" to """dependencies { implementation(project(":net")) }""",
                ":net" to """dependencies { implementation("org.json:json:20240303") }""",
            ),
        )
        assertTrue(violations.isNotEmpty(), "间接依赖 :net 必须判违规")
        assertTrue(
            violations.any { it.startsWith(":hearth ") && "间接依赖 :net" in it },
            violations.toString(),
        )
        // 两跳以外才发现，说明是靠传递闭包而不是直接依赖那一行判出来的。
        assertTrue(
            violations.any { it.contains(":hearth -> :hearth-adapter -> :roundtable -> :net") },
            violations.toString(),
        )
    }

    @Test
    fun syntheticHearthModuleDeclaringAnHttpLibraryIsRejected() {
        val violations = syntheticViolations(
            settings = """include(":hearth", ":net")""",
            builds = mapOf(
                ":hearth" to """
                    dependencies {
                        implementation(project(":model-api"))
                        implementation("com.squareup.okhttp3:okhttp:4.12.0")
                        testImplementation("io.ktor:ktor-client-core:3.0.0")
                    }
                """.trimIndent(),
                ":net" to """dependencies { implementation("org.json:json:20240303") }""",
            ),
        )
        assertTrue(violations.isNotEmpty(), "生产依赖里直接引入 okhttp 必须判违规")
        assertTrue(
            violations.any { it.startsWith(":hearth ") && "直接引入" in it && "okhttp" in it },
            violations.toString(),
        )
        // testImplementation 里的 ktor 不是生产依赖，不该被算进来。
        assertFalse(violations.any { "ktor" in it }, violations.toString())
    }

    @Test
    fun syntheticHearthModuleInheritingAnHttpLibraryThroughAnotherModuleIsRejected() {
        val violations = syntheticViolations(
            settings = """include(":hearth", ":adapter")""",
            builds = mapOf(
                ":hearth" to """dependencies { implementation(project(":adapter")) }""",
                ":adapter" to """dependencies { implementation("io.ktor:ktor-client-core:3.0.0") }""",
            ),
        )
        assertTrue(violations.isNotEmpty(), "经别的模块间接引入 ktor 必须判违规")
        assertTrue(
            violations.any { it.startsWith(":hearth ") && "间接引入" in it && "ktor" in it },
            violations.toString(),
        )
        // 越界的是闭包里的 :adapter，违规必须记在 :hearth 名下才说明穿透了传递关系。
        assertFalse(violations.any { it.startsWith(":adapter") }, violations.toString())
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
