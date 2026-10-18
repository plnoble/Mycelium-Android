package com.xavierxia.mycelium.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.MainActivity
import com.xavierxia.mycelium.update.CountingReleaseSource
import com.xavierxia.mycelium.update.UpdatePreferences
import com.xavierxia.mycelium.update.testCenter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MainActivityTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * 工厂规则必须包在 Activity 规则**外面**（`RuleChain.outerRule(...)`），否则 Activity 先起来。
     * 注入的 center 用假源：这个测例只看底栏能不能走通，绝不允许真的发网络请求。
     * 包名带 `.debug` 的调试包本来就不自动检查，这里照着真机行为注入。
     */
    @get:Rule
    val chain: TestRule = RuleChain
        .outerRule(
            UpdateCenterFactoryRule { context ->
                testCenter(
                    CountingReleaseSource { null },
                    UpdatePreferences(context),
                    clock = { 1_000L },
                    isDebugPackage = true,
                )
            },
        )
        .around(compose)

    @Test
    fun threeSpacesAreReachableAndTheUpdateCheckStaysOnMine() {
        compose.onAllNodesWithText("圆桌")[0].assertIsDisplayed()
        compose.onNodeWithText("导入角色包").assertIsDisplayed()
        compose.onNodeWithText("在电脑版知衍的角色页导出角色包，传到手机后在这里导入。").assertIsDisplayed()

        compose.onNodeWithText("炉边").performClick()
        compose.onNodeWithText("新建人物").assertIsDisplayed()
        compose.onNodeWithText("这里的人物是你自己写的。默认用手机上的模型，不联网；也可以给某个人物单独选云端模型。").assertIsDisplayed()

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsEnabled()
    }
}