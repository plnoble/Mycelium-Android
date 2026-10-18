package com.xavierxia.mycelium.arch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 炉边（hearth）边界的纯函数判定工具。
 *
 * 这条守住什么：`settings.gradle.kts` 里真实登记的模块集合是唯一事实来源；把每个模块
 * `build.gradle.kts` / `build.gradle` 里 `dependencies { }` 的**生产**依赖抓出来
 * （`testImplementation` / `androidTestImplementation` / `debugImplementation` 这类带
 * test / debug 的配置不算生产依赖），按模块建图做传递闭包，然后判定：
 *
 *  * 任何以 `hearth` 开头的模块，生产依赖闭包里都不许出现 `:net`（无论直接还是间接）；
 *  * 任何以 `hearth` 开头的模块，自己或闭包内任何模块都不许声明 HTTP 库 /
 *    直接用 HTTP（清单见 [HTTP_TOKENS]，沿用 ModuleBoundaryTest 原有的写法）。
 *
 * 真实仓库那条断言与「会失败」的合成用例共用这里的函数：合成用例只喂字符串，不碰真实
 * 仓库，所以「判定确实会报错」是可证明的，而不是靠改仓库试出来的。
 *
 * 本文件在 `:contract` 与 `:hearth` 的测试源码集里各存一份，内容逐字相同：两个模块的测试
 * 运行时类路径互相看不见，想让它们共享代码就得给 `:hearth` 加 testImplementation 依赖
 * （要动 build.gradle.kts），而这次约束是只碰测试文件。
 */
object ModuleBoundaryCheck {

    /** 判定为「HTTP 库 / 直接用 HTTP」的记号，沿用 ModuleBoundaryTest 原有的清单。 */
    val HTTP_TOKENS: List<String> = listOf(
        "okhttp",
        "retrofit",
        "ktor",
        "java.net.http",
        "HttpURLConnection",
        "openConnection",
    )

    /** `include(` —— 注意 `includeBuild(` 不会被匹配（include 后面不是左括号）。 */
    private val INCLUDE_CALL = Regex("\\binclude\\s*\\(")

    /** `project(":contract").projectDir = file("contract-lib")` 这种目录改写。 */
    private val PROJECT_DIR_OVERRIDE = Regex(
        "project\\s*\\(\\s*\"(:[^\"]+)\"\\s*\\)\\s*\\.projectDir\\s*=\\s*file\\s*\\(\\s*\"([^\"]+)\"\\s*\\)",
    )

    private val DEPENDENCIES_BLOCK = Regex("\\bdependencies\\s*\\{")

    private val CONFIGURATION_CALL = Regex("([A-Za-z][A-Za-z0-9]*)\\s*\\(")

    /** 认得出来的依赖配置名：api / implementation / compileOnly / runtimeOnly / kapt / … */
    private val KNOWN_CONFIGURATION = Regex(
        "([A-Za-z]*(Implementation|Api)|compileOnly|runtimeOnly|kapt|ksp|annotationProcessor)",
        RegexOption.IGNORE_CASE,
    )

    private val PROJECT_DEPENDENCY = Regex("project\\s*\\(\\s*\"([^\"]+)\"")

    private val STRING_LITERAL = Regex("\"([^\"]*)\"")

    /** 一个模块 build 脚本里的生产依赖声明。 */
    data class ProductionDependencies(
        /** `project(":x")` 形式的模块依赖，规范成 `:x`。 */
        val projectModules: Set<String>,
        /** 外部坐标（`group:artifact:version`），不含 project(...) 里的模块路径。 */
        val externalCoordinates: Set<String>,
    )

    // ---------------------------------------------------------------- settings

    /** 解析 `settings.gradle.kts` 里所有 `include(...)`，支持单参数与 `include(":a", ":b")` 多参数、跨行与空格。 */
    fun includedModules(settingsText: String): List<String> {
        val text = stripComments(settingsText)
        val modules = LinkedHashSet<String>()
        for (call in INCLUDE_CALL.findAll(text)) {
            val open = text.indexOf('(', call.range.first)
            val arguments = extractBalanced(text, open, '(', ')') ?: continue
            STRING_LITERAL.findAll(arguments).forEach { literal ->
                val value = literal.groupValues[1]
                if (value.startsWith(":")) modules += normalizeModulePath(value)
            }
        }
        return modules.toList()
    }

    /** 解析 `project(":x").projectDir = file("some-dir")`，返回模块路径 → 目录名。 */
    fun projectDirOverrides(settingsText: String): Map<String, String> {
        val text = stripComments(settingsText)
        return PROJECT_DIR_OVERRIDE.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // ------------------------------------------------------------ dependencies

    /**
     * 抓出一个模块 build 脚本里的**生产**依赖：
     * 只认已知的依赖配置调用，且配置名里带 test / debug 的一律排除
     * （testImplementation / androidTestImplementation / debugImplementation / testFixturesApi …）。
     */
    fun productionDependencies(buildText: String): ProductionDependencies {
        val text = stripComments(withoutBuildscriptBlocks(buildText))
        val projectModules = LinkedHashSet<String>()
        val externalCoordinates = LinkedHashSet<String>()
        for (block in dependenciesBlocks(text)) {
            var cursor = 0
            while (cursor < block.length) {
                val call = CONFIGURATION_CALL.find(block, cursor) ?: break
                val open = block.indexOf('(', call.range.first)
                val arguments = extractBalanced(block, open, '(', ')')
                cursor = if (arguments == null) block.length else open + arguments.length + 2
                if (arguments == null) break
                if (!isProductionConfiguration(call.groupValues[1])) continue
                PROJECT_DEPENDENCY.findAll(arguments).forEach { projectModules += normalizeModulePath(it.groupValues[1]) }
                val withoutProjects = PROJECT_DEPENDENCY.replace(arguments, " ")
                STRING_LITERAL.findAll(withoutProjects).forEach { externalCoordinates += it.groupValues[1] }
            }
        }
        return ProductionDependencies(projectModules, externalCoordinates)
    }

    private fun isProductionConfiguration(name: String): Boolean {
        if (!KNOWN_CONFIGURATION.matches(name)) return false
        val lower = name.lowercase()
        return "test" !in lower && "debug" !in lower
    }

    private fun dependenciesBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val match = DEPENDENCIES_BLOCK.find(text, searchFrom) ?: break
            val open = text.indexOf('{', match.range.first)
            val body = extractBalanced(text, open, '{', '}') ?: break
            blocks += body
            searchFrom = open + body.length + 2
        }
        return blocks
    }

    /** `buildscript { dependencies { classpath(...) } }` 不是模块依赖，整体抠掉。 */
    private fun withoutBuildscriptBlocks(text: String): String {
        val result = StringBuilder(text.length)
        val marker = Regex("\\bbuildscript\\b")
        var cursor = 0
        while (cursor < text.length) {
            val found = marker.find(text, cursor) ?: break
            result.append(text, cursor, found.range.first)
            val open = text.indexOf('{', found.range.last)
            val body = extractBalanced(text, open, '{', '}') ?: return result.toString()
            cursor = open + body.length + 2
        }
        result.append(text, cursor, text.length)
        return result.toString()
    }

    // ------------------------------------------------------------ graph logic

    /** 传递闭包（不含起点自身，除非图里有环绕回自己）。 */
    fun transitiveClosure(start: String, edges: Map<String, Set<String>>): Set<String> {
        val reached = LinkedHashSet<String>()
        val queue = ArrayDeque(edges[start].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (reached.add(next)) queue.addAll(edges[next].orEmpty())
        }
        return reached
    }

    /** 从 start 到 target 的一条依赖路径，找不到返回 null。用于把「怎么绕过去的」写进失败信息。 */
    fun dependencyPath(start: String, target: String, edges: Map<String, Set<String>>): List<String>? {
        if (start == target) return listOf(start)
        val seen = mutableSetOf(start)
        val queue = ArrayDeque<List<String>>()
        queue += listOf(start)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            for (next in edges[path.last()].orEmpty()) {
                if (next == target) return path + next
                if (seen.add(next)) queue += path + next
            }
        }
        return null
    }

    /** `hearth` 开头的模块（`:hearth`、`:hearth-ui`…）。 */
    fun hearthModules(modules: List<String>, prefix: String = "hearth"): List<String> =
        modules.map { normalizeModulePath(it) }.filter { it.removePrefix(":").startsWith(prefix) }

    /**
     * 文本里命中的 HTTP 记号。
     *
     * 匹配方式沿用 ModuleBoundaryTest 原有的写法：同一份清单 + 大小写敏感的 `contains`。
     * 刻意不做大小写折叠 —— 否则 `java.net.http` 会命中 `java.net.HttpURLConnection`，
     * 报出来的记号就不是代码里真正写的那个了（Maven 坐标本身就都是小写，不受影响）。
     */
    fun httpTokensIn(text: String): List<String> = HTTP_TOKENS.filter { text.contains(it) }

    /**
     * 炉边边界违规清单。空 = 通过。
     *
     * @param modules settings.gradle.kts 解析出来的真实模块集合
     * @param productionProjectDependencies 模块 → 生产依赖里的 project 模块
     * @param productionExternalCoordinates 模块 → 生产依赖里的外部坐标
     */
    fun hearthViolations(
        modules: List<String>,
        productionProjectDependencies: Map<String, Set<String>>,
        productionExternalCoordinates: Map<String, Set<String>>,
        prefix: String = "hearth",
        netModule: String = ":net",
    ): List<String> {
        val violations = mutableListOf<String>()
        for (module in hearthModules(modules, prefix)) {
            val closure = transitiveClosure(module, productionProjectDependencies)
            if (netModule in closure) {
                val path = dependencyPath(module, netModule, productionProjectDependencies)
                    ?: listOf(module, netModule)
                val how = if (netModule in productionProjectDependencies[module].orEmpty()) "直接" else "间接"
                violations += "$module ${how}依赖 $netModule（路径 ${path.joinToString(" -> ")}）"
            }
            val scanned = LinkedHashSet<String>()
            scanned += module
            scanned += closure.sorted()
            for (reached in scanned) {
                val declarations = productionExternalCoordinates[reached].orEmpty()
                for (coordinate in declarations.sorted()) {
                    val hits = httpTokensIn(coordinate)
                    if (hits.isEmpty()) continue
                    val how = if (reached == module) "直接引入" else "经 $reached 间接引入"
                    val via = if (reached == module) "" else "（${module} -> $reached）"
                    violations += "$module ${how} HTTP 库 $coordinate（命中 ${hits.joinToString(", ")}）$via"
                }
            }
        }
        return violations
    }

    // -------------------------------------------------------- repository model

    /** 真实仓库模型：settings + 各模块 build 脚本 → 生产依赖图 → 违规清单。 */
    class RepoModel(val root: Path) {
        val settingsText: String = root.resolve("settings.gradle.kts").readText()

        /** 真实登记的模块集合（含多参数 / 跨行 include）。 */
        val modules: List<String> = includedModules(settingsText)

        private val dirOverrides: Map<String, String> = projectDirOverrides(settingsText)

        fun moduleDirectory(module: String): Path {
            val name = module.removePrefix(":")
            val candidates = listOfNotNull(dirOverrides[module]?.let { root.resolve(it) }, root.resolve(name))
            return candidates.firstOrNull { Files.isDirectory(it) }
                ?: error("settings.gradle.kts 里登记的 $module 找不到源码目录，试过 $candidates")
        }

        /** 模块的构建脚本；优先 build.gradle.kts，退到 build.gradle。 */
        fun buildFile(module: String): Path {
            val directory = moduleDirectory(module)
            for (fileName in listOf("build.gradle.kts", "build.gradle")) {
                val candidate = directory.resolve(fileName)
                if (Files.isRegularFile(candidate)) return candidate
            }
            error("$module 既没有 build.gradle.kts 也没有 build.gradle：$directory")
        }

        fun buildText(module: String): String = buildFile(module).readText()

        val dependenciesByModule: Map<String, ProductionDependencies> =
            modules.associateWith { productionDependencies(buildText(it)) }

        val projectDependencies: Map<String, Set<String>> =
            dependenciesByModule.mapValues { it.value.projectModules }

        val externalCoordinates: Map<String, Set<String>> =
            dependenciesByModule.mapValues { it.value.externalCoordinates }

        /** src/main 下的 .kt / .java，路径 + 原文，供源码原文层检查。 */
        fun mainSourceTexts(module: String): List<Pair<Path, String>> {
            val main = moduleDirectory(module).resolve("src").resolve("main")
            if (!Files.isDirectory(main)) return emptyList()
            val found = mutableListOf<Path>()
            Files.walk(main).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { path ->
                    val name = path.fileName.toString()
                    if (name.endsWith(".kt") || name.endsWith(".java")) found.add(path)
                }
            }
            return found.sorted().map { it to it.readText() }
        }

        /** 真实仓库的炉边边界违规清单（空 = 通过）。 */
        val boundaryViolations: List<String> =
            hearthViolations(modules, projectDependencies, externalCoordinates)
    }

    // ------------------------------------------------------------------ helpers

    private fun normalizeModulePath(value: String): String =
        if (value.startsWith(":")) value else ":$value"

    private fun extractBalanced(text: String, openIndex: Int, open: Char, close: Char): String? {
        if (openIndex !in text.indices || text[openIndex] != open) return null
        var depth = 0
        var index = openIndex
        while (index < text.length) {
            val char = text[index]
            when {
                char == open -> depth++
                char == close -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex + 1, index)
                }
            }
            index++
        }
        return null
    }

    /**
     * 抠掉 `//` 行注释与 `/* */` 块注释，字符串字面量（含 `"""` 原始字符串）原样保留，
     * 这样 `"https://…"` 这种字面量里的斜杠不会被误当注释。
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val next = text.getOrNull(index + 1)
            when {
                char == '/' && next == '/' -> {
                    while (index < text.length && text[index] != '\n') index++
                }
                char == '/' && next == '*' -> {
                    index += 2
                    while (index < text.length && !(text[index] == '*' && text.getOrNull(index + 1) == '/')) index++
                    index += 2
                }
                char == '"' && next == '"' && text.getOrNull(index + 2) == '"' -> {
                    out.append("\"\"\"")
                    index += 3
                    while (index < text.length &&
                        !(text[index] == '"' && text.getOrNull(index + 1) == '"' && text.getOrNull(index + 2) == '"')
                    ) {
                        out.append(text[index])
                        index++
                    }
                    if (index < text.length) {
                        out.append("\"\"\"")
                        index += 3
                    }
                }
                char == '"' -> {
                    out.append(char)
                    index++
                    while (index < text.length) {
                        val inner = text[index]
                        out.append(inner)
                        index++
                        if (inner == '\\' && index < text.length) {
                            out.append(text[index])
                            index++
                        } else if (inner == '"') {
                            break
                        }
                    }
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }
}
