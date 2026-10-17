package com.xavierxia.mycelium.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun threeSpacesAreReachableAndTheUpdateCheckStaysOnMine() {
        compose.onAllNodesWithText("圆桌")[0].assertIsDisplayed()
        compose.onNodeWithText("导入角色包").assertIsDisplayed()
        compose.onNodeWithText("在电脑版知衍的角色页导出角色包，传到手机后在这里导入。").assertIsDisplayed()

        compose.onNodeWithText("炉边").performClick()
        compose.onNodeWithText("炉边在下一阶段开发：只用手机上的模型，不联网。").assertIsDisplayed()

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsEnabled()
    }
}
