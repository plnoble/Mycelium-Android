package com.nivra.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nivra.app.BuildConfig
import com.nivra.app.MainActivity
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
    fun launcherActivityDisplaysPhase0() {
        compose.onNodeWithText("Nivra").assertIsDisplayed()
        compose.onNodeWithText("Phase 0 · Foundation").assertIsDisplayed()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .assertIsDisplayed()
        compose.onNodeWithText("Check GitHub for updates").assertIsEnabled()
    }
}
