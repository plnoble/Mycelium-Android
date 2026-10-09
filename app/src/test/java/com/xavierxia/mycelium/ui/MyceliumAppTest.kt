package com.xavierxia.mycelium.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xavierxia.mycelium.update.GitHubReleaseClient
import com.xavierxia.mycelium.update.ReleaseSource
import com.xavierxia.mycelium.update.UpdateManager
import java.io.IOException
import java.net.HttpURLConnection
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
        val manager = UpdateManager(GitHubReleaseClient { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode() = HTTP_NOT_FOUND
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        })
        compose.setContent { MyceliumApp(manager) }

        repeat(2) {
            compose.onNodeWithText("Check GitHub for updates").performClick()
            awaitText("No GitHub Release has been published yet.")
            compose.onNodeWithText("Check GitHub for updates").assertIsEnabled()
        }
    }

    @Test
    fun failedCheckDisplaysErrorAndAllowsRetry() {
        val manager = UpdateManager(ReleaseSource { throw IOException("Network unavailable") })
        compose.setContent { MyceliumApp(manager) }

        compose.onNodeWithText("Check GitHub for updates").performClick()

        awaitText("Update check failed: Network unavailable")
        compose.onNodeWithText("Check GitHub for updates").assertIsEnabled()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Check GitHub for updates").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
