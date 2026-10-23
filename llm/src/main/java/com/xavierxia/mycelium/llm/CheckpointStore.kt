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
     *
     * **先腾空间、再拷贝**（方案审必修项 6）：第一版是「先 `copyOf()` 再淘汰」，于是淘汰旧那份之前
     * 堆里同时有旧+新两份（原生缓冲与 JNI 数组另算），峰值比 `totalBytes` 高得多。现在先用要存的
     * 字节数腾地方，腾不出来就不拷。
     */
    fun put(key: String, pos: Int, bytes: ByteArray): Boolean {
        require(pos >= 0) { "检查点位置不能为负" }
        if (bytes.isEmpty()) return false
        val incoming = bytes.size.toLong()
        if (incoming > maxTotalBytes) return false
        // 全量审必修项 9：全局淘汰先做，**淘汰完再拿当前键的 list**。
        // 如果先拿 list 再全局淘汰，当前键若正好是最旧的被 remove(key)，追加就会打到
        // 脱离 byKey 映射的悬空 list 上，造成「返回 true 实际查不到」的假成功。
        while (totalBytes + incoming > maxTotalBytes) {
            val oldestKey = byKey.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { entry -> entry.value.minOf { it.sequence } }
                ?: break
            oldestKey.value.removeAt(0)
            if (oldestKey.value.isEmpty()) byKey.remove(oldestKey.key)
        }
        if (totalBytes + incoming > maxTotalBytes) return false
        val list = byKey.getOrPut(key) { mutableListOf() }
        while (list.size >= maxPerKey) list.removeAt(0)
        list += Checkpoint(pos, bytes.copyOf(), ++nextSequence)
        return true
    }

    /**
     * 作废同一键下「位置超过 [validPrefix]」的检查点（方案审必修项 3）。
     *
     * 为什么需要：本轮在 400 分叉之后，仓库里可能还留着 700 那一份；之后公共前缀长过 700，`find`
     * 又会把它选中——可它对应的是**另一条分支**上的状态，恢复出来就答非所问。上游 server 也会删
     * 回退点之后的检查点（`tools/server/server-context.cpp`）。
     *
     * @param validPrefix 这一轮真实公共前缀的长度；超过它的位置都不算数。
     */
    fun invalidateBeyond(key: String, validPrefix: Int) {
        val list = byKey[key] ?: return
        list.removeAll { it.pos > validPrefix }
        if (list.isEmpty()) byKey.remove(key)
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
        /**
         * 每个缓存键最多留几份（2026-10-23 统筹裁定 + Codex R2 必修项 4）：
         * 设为 1。对话只前进，同一会话里旧检查点直接覆盖；换会话整批作废（与 P2-1c cacheOwner 一致）。
         */
        const val MAX_PER_KEY = 1

        /**
         * 所有检查点加起来的上限：300 **MiB**（2026-10-23 统筹裁定实测整份约 94.4 MiB 后的上限）。
         *
         * 约可保留 2~3 份快照。主动预算检查是防 OOM 的主防线（Codex R2 必修项 1/2：Android 16 起
         * onTrimMemory RUNNING_* 级别不再下发，不能靠回调兜底）。
         */
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    }
}
