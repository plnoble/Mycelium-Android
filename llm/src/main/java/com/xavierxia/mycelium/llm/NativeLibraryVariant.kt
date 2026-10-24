package com.xavierxia.mycelium.llm

/**
 * 加载哪一份 ggml CPU 内核库（P2-1d，2026-10-23 夜）。
 *
 * APK 里带着两份：
 * - `libmycelium_llm.so`：**基线**（`armv8-a`）+ 引擎，任何 arm64 设备都跑得起来，是保底那一份；
 * - `libmycelium_llm_fast.so`：`armv8.2-a+dotprod+i8mm+fp16`，真机实测提示读取快 3 倍。
 *
 * 为什么要挑：带 `i8mm`/`dotprod` 的内核在不支持这些指令的老手机上一加载就会 SIGILL。所以先看 CPU
 * 特性，只在不缺任何一条时才加载快的那一份。
 *
 * 判定只看**这个进程真的能用的**特性：`/proc/cpuinfo` 的 `Features` 行（Android 上由内核填，含
 * `asimddp` 表示 dotprod、`i8mm` 表示 i8mm）。缺任何一条就退基线——宁可慢，不许崩。
 */
object NativeLibraryVariant {

    /** 基线库名：任何 arm64 都能加载。 */
    const val BASELINE = "mycelium_llm"

    /** 快变体库名：要求 dotprod + i8mm 都可用。 */
    const val FAST = "mycelium_llm_fast"

    /**
     * 选哪个库名。
     *
     * @param features `/proc/cpuinfo` 里 `Features` 那一行的内容（空格分隔）；读不到就传空串。
     * @param forceBaseline 强制基线（只给测例与真机对照用，正常路径永远传 false）。
     */
    fun pick(features: String, forceBaseline: Boolean = false): String {
        if (forceBaseline) return BASELINE
        val tokens = features.split(' ', '\t', '\n').filter { it.isNotBlank() }.toSet()
        // 三条都必须有，缺任何一条都会在老设备上 SIGILL（全量审 P1-2）：
        //  - `asimddp` = dotprod（编译参数里的 +dotprod）；
        //  - `i8mm`    = 整数矩阵乘；
        //  - `fphp` / `asimdhp` = FP16 向量算术（编译参数里的 +fp16）。**这条以前漏了**：快库的
        //    `ggml_vec_dot_f16` 里确实有 `fmla v*.8h`，缺 FP16 时会在推理时炸，而不是在 dlopen 时炸。
        val hasFp16 = "fphp" in tokens || "asimdhp" in tokens
        return if ("asimddp" in tokens && "i8mm" in tokens && hasFp16) FAST else BASELINE
    }

    /** 读这台设备 CPU 的特性行；读不到（或不是 Linux）就当空串，也就是基线。 */
    fun readCpuFeatures(): String = runCatching {
        java.io.File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Features") }
            ?.substringAfter(':')
            .orEmpty()
    }.getOrDefault("")

    /** 已经加载的库名（同一个进程只加载一份；测试要换变体必须另起进程）。 */
    @Volatile
    var loaded: String = ""
        private set

    /**
     * 加载选定的那一份，并返回**实际加载的库名**。
     *
     * 快变体加载失败时退回基线：一个 `dlopen` 层面的失败（比如库没打进 APK）不该让整个应用起不来。
     */
    fun load(forceBaseline: Boolean = false): String {
        loaded.takeIf { it.isNotEmpty() }?.let { return it }
        val choice = pick(readCpuFeatures(), forceBaseline)
        if (choice == FAST) {
            if (runCatching { System.loadLibrary(FAST) }.isSuccess) {
                loaded = FAST
                return FAST
            }
        }
        System.loadLibrary(BASELINE)
        loaded = BASELINE
        return BASELINE
    }
}