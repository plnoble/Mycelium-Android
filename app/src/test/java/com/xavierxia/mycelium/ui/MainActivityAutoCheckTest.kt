package com.xavierxia.mycelium.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xavierxia.mycelium.MainActivity
import com.xavierxia.mycelium.update.CountingReleaseSource
import com.xavierxia.mycelium.update.UpdatePreferences
import com.xavierxia.mycelium.update.testCenter
import com.xavierxia.mycelium.update.testRelease
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专测**非 debug 分支**：既有测例大多跑在 debug 包上（debug 不自动检查），自动检查那一路
 * 只有在这里才被真正走到。同时证明注入的工厂在 Activity 启动**之前**就生效了
 * —— `RuleChain.outerRule(...)` 包在 Activity 规则外面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MainActivityAutoCheckTest {
    private val source = CountingReleaseSource { testRelease("0.6.0") }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: TestRule = RuleChain
        .outerRule(
            UpdateCenterFactoryRule { context ->
                testCenter(
                    source,
                    UpdatePreferences(context),
                    clock = { 1_000L },
                    isDebugPackage = false,
                )
            },
        )
        .around(compose)

    @Test
    fun theInjectedFactoryTakesEffectBeforeTheActivityStartsAndTheBuildAutoChecks() {
        compose.waitUntil(timeoutMillis = 5_000) { source.calls == 1 }

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("发现新版本：0.6.0").assertIsDisplayed()
    }
}