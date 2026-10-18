package com.xavierxia.mycelium.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xavierxia.mycelium.net.HttpText
import com.xavierxia.mycelium.update.GitHubReleaseClient
import com.xavierxia.mycelium.update.ReleaseSource
import com.xavierxia.mycelium.update.UpdateManager
import java.io.IOException
import java.net.HttpURLConnection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MyceliumAppTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun github404DisplaysNoReleaseAndAllowsAnotherCheck() {
        val manager = UpdateManager(GitHubReleaseClient { _, _, _ -> HttpText(404, "") })
        compose.setContent { MyceliumApp(manager) }
        compose.onNodeWithText("我的").performClick()

        repeat(2) {
            compose.onNodeWithText("检查更新").performClick()
            awaitText("还没有发布过任何版本。")
            compose.onNodeWithText("检查更新").assertIsEnabled()
        }
    }

    @Test
    fun failedCheckDisplaysErrorAndAllowsRetry() {
        val manager = UpdateManager(ReleaseSource { throw IOException("Network unavailable") })
        compose.setContent { MyceliumApp(manager) }
        compose.onNodeWithText("我的").performClick()

        compose.onNodeWithText("检查更新").performClick()

        // 连不上归到「网络」一类，界面给人话；异常的英文原文不许漏出来。
        awaitText("检查更新失败：连不上 GitHub，检查网络后再试")
        assertTrue(compose.onAllNodesWithText("Network unavailable").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("检查更新").assertIsEnabled()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("检查更新").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
