package com.xavierxia.mycelium.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-1d：运行时指令集挑选（2026-10-23 夜，任务书第一段第 1 条）。
 *
 * 挑选判错的后果不对称：
 * - 该选基线却选了快变体 ⇒ **老手机一加载模型就 SIGILL**，应用直接崩；
 * - 该选快变体却选了基线 ⇒ 只是慢 3 倍。
 * 所以这里的用例全部围绕「宁可慢、不许崩」：缺任何一条特性都必须落到基线。
 */
class NativeLibraryVariantTest {

    @Test
    fun `the red magic features pick the fast variant`() {
        // 红魔（SM8850）真机 /proc/cpuinfo 里确实有这两条；实测提示读取快 3 倍。
        val features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid " +
            "asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat " +
            "ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 " +
            "svei8mm svef32mm svef64mm svebf16 i8mm bf16 dgh rng afp bf16eb"
        assertEquals(NativeLibraryVariant.FAST, NativeLibraryVariant.pick(features))
    }

    @Test
    fun `a phone without i8mm falls back to baseline`() {
        // 只有 dotprod（asimddp）、没有 i8mm：快变体里的 i8mm 内核照样会 SIGILL。
        val features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 asimddp fphp"
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick(features))
    }

    @Test
    fun `a phone without dotprod falls back to baseline`() {
        val features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 i8mm fphp"
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick(features))
    }

    @Test
    fun `a phone without fp16 arithmetic falls back to baseline`() {
        // 全量审 P1-2：快变体的编译参数含 +fp16，快库里确实有 fmla v*.8h。缺 FP16 时不会在 dlopen
        // 阶段炸，而是在推理时 SIGILL —— 那种崩法外面的 runCatching 兜不住，所以必须在挑选时就挡住。
        val features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 asimddp i8mm"
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick(features))
        // 这两种写法都算有 FP16（不同内核/不同 Android 版本写法不同）。
        assertEquals(NativeLibraryVariant.FAST, NativeLibraryVariant.pick("asimddp i8mm fphp"))
        assertEquals(NativeLibraryVariant.FAST, NativeLibraryVariant.pick("asimddp i8mm asimdhp"))
    }

    @Test
    fun `an unreadable feature line falls back to baseline`() {
        // 读不到（容器、非常规内核、解析失败）时绝不能猜：基线是唯一安全的选择。
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick(""))
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick("   "))
    }

    @Test
    fun `the forced baseline wins over every feature line`() {
        // 真机对照用的开关：即使这台机器支持快变体，也要能强制加载基线。
        val features = "asimddp i8mm fp asimd"
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick(features, forceBaseline = true))
    }

    @Test
    fun `tokens are matched whole so lookalike names do not count`() {
        // 前缀相同但不是一个特性：必须是整词匹配，否则 "i8mmx" 之类会误判成 i8mm。
        assertEquals(NativeLibraryVariant.BASELINE, NativeLibraryVariant.pick("asimddpx i8mmx"))
    }

    @Test
    fun `both variants are declared and different`() {
        // 名字必须不同：同一个 SO 名加载两次在 Android 上只会加载第一份。
        assertEquals(false, NativeLibraryVariant.FAST == NativeLibraryVariant.BASELINE)
        assertEquals("mycelium_llm", NativeLibraryVariant.BASELINE)
        assertEquals("mycelium_llm_fast", NativeLibraryVariant.FAST)
    }
}