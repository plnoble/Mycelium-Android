package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 落点诊断：`tokenizeBoundary()` 在真机上到底返回什么。
 *
 * 若「带/不带 generation prompt」两次长度一样，落点就算不出来（-1），检查点永远不存——界面上就表现为
 * 「第 2 轮没有变快、甚至更慢」（提示更长、又没复用）。
 */
@RunWith(AndroidJUnit4::class)
class CheckpointPositionDiagnosisDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val record = LocalModelRecord(
        uri = "content://com.android.externalstorage.documents/document/primary%3AModels%2FQwen3.5-4B-Q4_K_M.gguf",
        name = "Qwen3.5-4B-Q4_K_M.gguf",
        size = 2740937888L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        copied = false,
    )

    @Test
    fun theBoundaryIsMeasuredOnTheRealTemplate() = runBlocking {
        val store = AndroidModelStore(context)
        val engine = LocalModelEngine.shared
        val log = mutableListOf<String>()
        try {
            engine.load(record.info, EngineOptions(threads = 8, contextLength = 8192)) { store.open(record) }
            val history = listOf(
                ChatMessage(ChatRole.SYSTEM, "你是一个安静、说话很短的人。"),
                ChatMessage(ChatRole.USER, "今天有点累"),
            )
            val payload = org.json.JSONArray().apply {
                history.forEach { put(org.json.JSONObject().put("role", it.role.name.lowercase()).put("content", it.content)) }
            }.toString().toByteArray()

            val bounds = engine.boundaryForTest(payload)
            log += "tokenizeBoundary = ${bounds?.toList()}"
            log += "带 generation prompt = ${bounds?.getOrNull(0)}，不带 = ${bounds?.getOrNull(1)}"
            log += "落点 checkpointPositionFor = ${engine.checkpointPositionFor(history)}"
            log += "（落点必须 >0 且 < 带提示长度，否则检查点不会存）"

            File(context.filesDir, "p2-1d-boundary-diagnosis.txt").writeText(
                buildString {
                    appendLine("设备：${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                    log.forEach { appendLine(it) }
                },
            )
            assertTrue("要写出诊断文件", File(context.filesDir, "p2-1d-boundary-diagnosis.txt").exists())
        } finally {
            engine.unload()
        }
    }
}
