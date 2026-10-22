package com.xavierxia.mycelium.hearth

import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.MainActivity
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.LocalModelEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * P2-1c 真机第 4 项：本地模型超过 20 秒还没有可显示正文时，炉边对话页要出现提示。
 *
 * 走真实界面：建一个用已登记的 Qwen3.5 的人物，打开「思考」（模板会把预算全花在隐藏段，正文一直不出现），
 * 把提示阈值调到 8 秒（产品里是 20 秒，这里只是缩短等待，判据不变：提示只在「还没出正文」时出现），
 * 发一条消息，等到提示文字出现后截图。截图存应用私有目录，测试结束删掉这个临时人物。
 */
@RunWith(AndroidJUnit4::class)
class HearthHiddenHintDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theHintAppearsWhenNoVisibleTextArrives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = HearthDatabase(context)
        val characters = CharacterStore { database.session() }
        val tag = System.currentTimeMillis().toString().takeLast(6)
        val name = "提示复验人物 $tag"
        val model = runBlocking {
            AndroidModelStore(context).registry.list().first { it.name == "Qwen3.5-4B-Q4_K_M.gguf" }
        }
        var characterId: String? = null
        runBlocking { LocalModelEngine.shared.setThinking(true) }
        try {
            characterId = characters.create(
                CharacterDraft(
                    name = name,
                    personality = "话少。",
                    modelKind = ModelKind.LOCAL,
                    modelId = model.sha256,
                    modelName = model.name,
                ),
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            ).id

            compose.onAllNodesWithText("炉边")[0].performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("新建人物").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(name))
            compose.onNodeWithText(name).performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("发送").fetchSemanticsNodes().isNotEmpty() }

            // 进了对话页，ViewModel 已经建好：把提示阈值调短，省掉那 20 秒空等。
            val vm = androidx.lifecycle.ViewModelProvider(
                compose.activity.viewModelStore,
                compose.activity.defaultViewModelProviderFactory,
                compose.activity.defaultViewModelCreationExtras,
            )[HearthViewModel::class.java]
            vm.hiddenWaitHintMillis = 8_000L

            compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("帮我理一理这件事 $tag")
            // 收起输入法再发：截图是交付证据，键盘挡住半屏就没法用。BACK 先关输入法（这一步一定
            // 还在，刚输入过），不会退出页面。
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
                android.view.KeyEvent.KEYCODE_BACK,
            )
            compose.waitForIdle()
            compose.onNodeWithText("发送").performClick()

            val hint = "这一轮到现在还没有可显示的内容"
            compose.waitUntil(90_000) { compose.onAllNodesWithText(hint, substring = true).fetchSemanticsNodes().isNotEmpty() }
            // 让提示与「停止」按钮稳定下来再截，避免截到过渡中的一帧。
            compose.waitForIdle()
            Thread.sleep(600)

            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            FileOutputStream(File(context.filesDir, "p2-1c-hint.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("提示出现时，界面上应当还看得到「停止」", compose.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty())
        } finally {
            runBlocking { LocalModelEngine.shared.setThinking(false) }
            characterId?.let { characters.delete(it) }
        }
    }
}
