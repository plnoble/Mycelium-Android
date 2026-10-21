package com.xavierxia.mycelium.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1.5 第 2 条：卡片里的发布说明不该原样显示 Markdown 符号（真机上看到过 `**Full Changelog**:`）。
 * 这里钉住「去标记」的每一条规则，以及「没点名的写法原样保留」这条保守口径。
 */
class UpdateNotesTextTest {
    @Test
    fun boldMarkersAreRemoved() {
        assertEquals(
            "Full Changelog:",
            plainReleaseNotes("**Full Changelog**: ").trim(),
        )
        assertEquals("这一版修了几处", plainReleaseNotes("__这一版修了几处__"))
    }

    @Test
    fun headingAndQuoteMarkersAtTheLineStartAreRemoved() {
        assertEquals("这一版\n改了什么", plainReleaseNotes("## 这一版\n> 改了什么"))
    }

    @Test
    fun inlineCodeBackticksAreRemoved() {
        assertEquals("去看 v0.4.0 的说明", plainReleaseNotes("去看 `v0.4.0` 的说明"))
    }

    @Test
    fun angleBracketsAroundAUrlAreRemoved() {
        assertEquals(
            "https://github.com/x/y/compare/v0.1.0...v0.1.1",
            plainReleaseNotes("<https://github.com/x/y/compare/v0.1.0...v0.1.1>"),
        )
    }

    @Test
    fun aLeadingDashBecomesABullet() {
        assertEquals("• 修了更新检查\n• 修了安装", plainReleaseNotes("- 修了更新检查\n- 修了安装"))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        val plain = "没装过的用户：这次升级不会动你的数据。\n64 位 ARM 系统。"
        assertEquals(plain, plainReleaseNotes(plain))
    }

    @Test
    fun aHyphenInsideALineIsNotAListMarker() {
        assertEquals("v0.1.0 - v0.1.1 之间", plainReleaseNotes("v0.1.0 - v0.1.1 之间"))
    }

    @Test
    fun indentationIsKept() {
        assertEquals("  一条缩进的话", plainReleaseNotes("  - 一条缩进的话").replace("• ", ""))
        assertEquals("  引用", plainReleaseNotes("  > 引用"))
    }
}