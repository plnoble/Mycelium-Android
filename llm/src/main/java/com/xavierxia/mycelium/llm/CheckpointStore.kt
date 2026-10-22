package com.xavierxia.mycelium.llm

/**
 * 一份检查点（P2-1d）：原生在「用户这一轮结束、助手角色头之前」那一刻存下来的**循环层状态**。
 *
 * 只保存字节，不持有任何原生指针——卸载模型以后它不会悬空；要到恢复时才交给原生。
 *
 * [pos] 是这份检查点对应的 token 位置（提示里第几个 token）。恢复时只能用「位置不超过本轮公共前缀」
 * 的那一份，否则恢复出来的状态对应的是别的位置，模型会答非所问。
 */
internal class Checkpoint(
    val pos: Int,
    val bytes: ByteArray,
    /** 同一位置可能被重复存（比如同一轮被重跑）；插入序号用来在淘汰时区分先后。 */
    val sequence: Long,
)

/**
 * 检查点仓库（P2-1d 的 S1）。按 P2-1c 的缓存键分组，负责四件事：
 *
 *  1. **挑选**：给一个公共前缀长度，找出「位置不超过它、且位置最大」的那一份；同位置取较新的。
 *  2. **限量**：每个缓存键最多 [maxPerKey] 份；全部加起来不超过 [maxTotalBytes]。超了淘汰最旧的
 *     （先按插入序号，不看位置——位置大的一般也更新，但重跑会打乱这个关系，所以只认插入顺序）。
 *  3. **作废**：换人物 / 换会话 / 换模型 / 卸载 / 停止 / 出错，调用方按键作废或整个清空。
 *  4. **记账**：当前总占用、份数，给统计与真机日志用。
 *
 * **并发**：只由持有引擎那一把 `gate` 锁的代码调用（本来每一轮就是 single-flight），所以这里不再
 * 自己加锁；别在锁外调用它。这一点写在这里是因为检查点跨轮次存活，很容易误以为它是线程安全的。
 */
internal class CheckpointStore(
    private val maxPerKey: Int = MAX_PER_KEY,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    private val byKey = LinkedHashMap<String, MutableList<Checkpoint>>()
    private var nextSequence = 0L

    init {
        require(maxPerKey > 0) { "每个缓存键至少留一份检查点" }
        require(maxTotalBytes > 0) { "检查点总占用上限必须为正" }
    }

    /** 当前总占用（字节）。 */
    val totalBytes: Long get() = byKey.values.sumOf { list -> list.sumOf { it.bytes.size.toLong() } }

    /** 当前份数（所有键加起来）。 */
    val size: Int get() = byKey.values.sumOf { it.size }

    /** 某个键下现有的份数。 */
    fun countFor(key: String): Int = byKey[key]?.size ?: 0

    /**
     * 存一份。返回是否真的存下了：单份就超过总上限时**不存**（返回 false），交给调用方记日志——
     * 直接拒绝比先塞进去再淘汰自己更清楚。
     */
    fun put(key: String, pos: Int, bytes: ByteArray): Boolean {
        require(pos >= 0) { "检查点位置不能为负" }
        if (bytes.isEmpty()) return false
        if (bytes.size.toLong() > maxTotalBytes) return false
        val list = byKey.getOrPut(key) { mutableListOf() }
        list += Checkpoint(pos, bytes.copyOf(), ++nextSequence)
        // 单键超限：先淘汰这个键里最旧的。
        while (list.size > maxPerKey) list.removeAt(0)
        // 总量超限：全局淘汰最旧的（跨键），直到回到上限以内。
        while (totalBytes > maxTotalBytes) {
            val oldestKey = byKey.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { entry -> entry.value.minOf { it.sequence } }
                ?: break
            oldestKey.value.removeAt(0)
            if (oldestKey.value.isEmpty()) byKey.remove(oldestKey.key)
        }
        return true
    }

    /**
     * 挑一份用来恢复：位置不超过 [maxPos]、位置最大的那一份；同位置取较新的。
     *
     * 没有可用的一份就返回 null —— 调用方据此整段重读，并记回退原因 5（见方案 2.5）。
     */
    fun find(key: String, maxPos: Int): Checkpoint? =
        byKey[key]
            ?.filter { it.pos in 0..maxPos }
            ?.maxWithOrNull(compareBy({ it.pos }, { it.sequence }))

    /** 作废一个键（换人物 / 换会话 / 换模型 / 停止 / 出错）。 */
    fun invalidate(key: String) {
        byKey.remove(key)
    }

    /** 全部作废（卸载模型、引擎清理）。 */
    fun clear() {
        byKey.clear()
    }

    companion object {
        /** 每个缓存键最多留几份（方案 2.4；真机实测之后再调）。 */
        const val MAX_PER_KEY = 3

        /** 所有检查点加起来的上限：256 MB（方案 2.4）。 */
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
