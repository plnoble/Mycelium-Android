package com.xavierxia.mycelium.llm

/**
 * 只给真机验收用：报告当前**已经加载**的那份原生库是哪个编译变体。
 *
 * 为什么要单独开一个入口：`NativeBridge` 是 `internal`，app 模块的 androidTest 看不见。
 * 这里只做一件事——把已经加载的库里的编译期常量 `-march` 读回来，好让真机日志能证明
 * 「默认挑了快变体 / 强制时挑了基线」。
 *
 * 注意：构造 `NativeBridge` 会触发它的 `init`（加载库）。如果测例要强制基线，必须**先**调
 * `NativeLibraryVariant.load(forceBaseline = true)`，再调这里——那时加载结果已经缓存，不会被改写。
 */
object NativeArchVariant {

    @Volatile
    private var cached: String? = null

    /** 当前加载的库的编译变体（例如 `armv8.2-a+dotprod+i8mm+fp16` 或 `armv8-a`）。 */
    fun current(): String {
        cached?.let { return it }
        NativeLibraryVariant.load()
        val value = NativeBridge().archVariant()
        cached = value
        return value
    }
}