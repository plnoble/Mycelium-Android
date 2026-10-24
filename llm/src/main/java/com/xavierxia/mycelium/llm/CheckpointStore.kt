package com.xavierxia.mycelium.llm

/**
 * 一份检查点（P2-1d）：原生在「用户这一轮结束、助手角色头之前」那一刻存下来的**整份状态**。
 *
 * 从 2026-10-23 夜起，**字节归原生**（`NativeBridge.takeCheckpointHandle()` 交回的句柄），这里只留
 * 元数据：位置、句柄、字节数、插入序号。这样交给 Kotlin 时不必再拷一份几十 MB 的 `ByteArray`，
 * 托管堆峰值少一份；恢复时原生直接从那块内存装回，也不再有一次反向拷贝。
 *
 * [pos] 是这份检查点对应的 token 位置（提示里第几个 token）。恢复时只能用「位置不超过本轮公共前缀」
 * 的那一份，否则恢复出来的状态对应的是别的位置，模型会答非所问。
 */
internal class Checkpoint(
    val pos: Int,
    val handle: Int,
    val bytes: Long,
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
 * **谁释放原生内存**：字节归原生以后，「从仓库里移除」必须同时 `releaseCheckpointHandle()`，否则那块
 * 内存永远回不去。所以每条移除路径都会调 [onDrop]——仓库自己不碰原生，由引擎注入回调。
 *
 * **并发**：只由持有引擎那一把 `gate` 锁的代码调用（本来每一轮就是 single-flight），所以这里不再
 * 自己加锁；别在锁外调用它。这一点写在这里是因为检查点跨轮次存活，很容易误以为它是线程安全的。
 */
internal class CheckpointStore(
    private val maxPerKey: Int = MAX_PER_KEY,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val onDrop: (Int) -> Unit = {},
) {
    private val byKey = LinkedHashMap<String, MutableList<Checkpoint>>()
    private var nextSequence = 0L

    init {
        require(maxPerKey > 0) { "每个缓存键至少留一份检查点" }
        require(maxTotalBytes > 0) { "检查点总占用上限必须为正" }
    }

    /** 当前总占用（字节）。 */
    val totalBytes: Long get() = byKey.values.sumOf { list -> list.sumOf { it.bytes } }

    /** 当前份数（所有键加起来）。 */
    val size: Int get() = byKey.values.sumOf { it.size }

    /** 某个键下现有的份数。 */
    fun countFor(key: String): Int = byKey[key]?.size ?: 0

    /**
     * 存一份。返回是否真的存下了：单份就超过总上限时**不存**（返回 false），交给调用方记日志并
     * **释放它自己那份句柄**——直接拒绝比先塞进去再淘汰自己更清楚。
     *
     * 顺序是「先腾空间、再记账」：`incoming` 来自原生（`checkpointHandleBytes`），调用方在交进来之前
     * 已经知道大小，因此这里不需要为了算大小去碰那句内存。
     */
    fun put(key: String, pos: Int, handle: Int, bytes: Long): Boolean {
        require(pos >= 0) { "检查点位置不能为负" }
        // 零字节/负字节不是「太大」而是「不可用」：按拒收处理（返回 false），不抛异常——调用方要能
        // 把它当成「这份没存成」继续跑，而不是让生成失败。
        if (bytes <= 0) return false
        if (bytes > maxTotalBytes) return false
        val incoming = bytes
        // 全量审 R2（非阻断退化）：**先把当前键腾到位再算全局**。先全局淘汰再处理本键的话，
        // 「同一键再存一份」时旧的那份还被算在总量里，预算不够就把别的键挤掉了
        // （实测 A:40、B:40、B:40、预算 100 ⇒ A 被无必要地删掉）。
        byKey[key]?.let { existing ->
            while (existing.size >= maxPerKey) drop(existing.removeAt(0))
            if (existing.isEmpty()) byKey.remove(key)
        }
        // 再按总量全局淘汰（跨键，淘汰最旧的），腾到「再放一份也放得下」。
        while (totalBytes + incoming > maxTotalBytes) {
            val oldestKey = byKey.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { entry -> entry.value.minOf { it.sequence } }
                ?: break
            drop(oldestKey.value.removeAt(0))
            if (oldestKey.value.isEmpty()) byKey.remove(oldestKey.key)
        }
        if (totalBytes + incoming > maxTotalBytes) return false
        // 全量审必修项 9：淘汰完**重新**拿当前键的 list（它可能在上面被整个 remove 掉了）。
        val list = byKey.getOrPut(key) { mutableListOf() }
        list += Checkpoint(pos, handle, bytes, ++nextSequence)
        return true
    }

    /**
     * 把 [from] 名下的检查点**原样改挂**到 [to]（P2-1d，2026-10-23 夜）。
     *
     * 为什么需要它：新会话第一轮还没有正式编号，缓存键里暂时用「临时会话」顶替；这一轮存进仓库之后
     * 会话才拿到正式编号，于是第二轮的键变了——仓库按「换了键就是换了一段提示」处理，把第一轮那份
     * 当分叉作废掉，第二轮的检查点就永远用不上（真机读数：第 2 轮 `reason=5`，第 3 轮才恢复正常）。
     * 这里让调用方在**拿到正式编号的那一刻**把缓存与检查点一起改挂过去，第二轮就接上了。
     *
     * 合并规则与 [put] 一致：每个键最多 [maxPerKey] 份（保留位置最大、同位置较新的），总量超上限时
     * 按插入序号淘汰最旧的。`from == to` 或 `from` 没有内容时是空操作。
     */
    fun rekey(from: String, to: String) {
        if (from == to) return
        val moving = byKey[from] ?: return
        if (moving.isEmpty()) { byKey.remove(from); return }
        // 全量审 R2 P1-2：**所有权在迁移成功之前不能松手**。原来一上来就 `byKey.remove(from)`，
        // 之后目标侧任何一次分配抛异常（getOrPut / += / sortBy 都可能），这批句柄就既不在源键、
        // 也不在目标键——仓库查不到、原生还占着，trim 和卸载都释放不到它。
        val createdTarget = byKey[to] == null
        val target = try {
            byKey.remove(from)
            byKey.getOrPut(to) { mutableListOf() }
        } catch (failure: Throwable) {
            byKey.getOrPut(from) { mutableListOf() }.addAll(moving)
            throw failure
        }
        try {
            // 交过来的那几份是「更早存的」，但内容与位置都是对的：按插入顺序排序后再限量。
            target += moving
            target.sortBy { it.sequence }
            while (target.size > maxPerKey) drop(target.removeAt(0))
            if (target.isEmpty()) byKey.remove(to)
        } catch (failure: Throwable) {
            // 回滚：还在目标里的那几份放回源键；已经 drop 的不再放回（句柄已按 drop 语义释放）。
            val survivors = target.filter { entry -> moving.any { it === entry } }
            target.removeAll(survivors)
            if (target.isEmpty() && createdTarget) byKey.remove(to)
            if (survivors.isNotEmpty()) byKey.getOrPut(from) { mutableListOf() }.addAll(survivors)
            throw failure
        }
        while (totalBytes > maxTotalBytes && byKey.isNotEmpty()) {
            val oldestKey = byKey.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { entry -> entry.value.minOf { it.sequence } }
                ?: break
            drop(oldestKey.value.removeAt(0))
            if (oldestKey.value.isEmpty()) byKey.remove(oldestKey.key)
        }
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
        val kept = list.filter { it.pos <= validPrefix }
        list.filter { it.pos > validPrefix }.forEach { drop(it) }
        list.clear()
        list += kept
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
        byKey.remove(key)?.forEach { drop(it) }
    }

    /** 全部作废（卸载模型、引擎清理）。 */
    fun clear() {
        byKey.values.forEach { list -> list.forEach { drop(it) } }
        byKey.clear()
    }

    private fun drop(checkpoint: Checkpoint) {
        onDrop(checkpoint.handle)
    }

    companion object {
        /**
         * 每个缓存键只留**最新一份**：真机实测整份状态 54–68 MB（1412 token 时 94 MB），留多份在手机上
         * 没有意义——下一轮能用上的永远是最新那份，旧的只会在分叉时被作废。
         */
        const val MAX_PER_KEY = 1

        /**
         * 全部键加起来的上限 300 MiB：按实测单份约 55–65 MB，能放下 4 份左右（4 个不同会话），
         * 再多就按插入顺序淘汰最旧的。这是「放内存」这条路的硬边界（统筹裁定 2026-10-23 第 2 条）。
         */
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    }
}