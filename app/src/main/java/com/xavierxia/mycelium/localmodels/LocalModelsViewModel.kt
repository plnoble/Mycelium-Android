package com.xavierxia.mycelium.localmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xavierxia.mycelium.llm.*
import com.xavierxia.mycelium.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LocalModelsState(
    val models: List<LocalModelRecord> = emptyList(),
    val busy: Boolean = false,
    val progress: ImportProgress? = null,
    val copyRequest: Pair<String, DocumentDetails>? = null,
    val notice: String? = null,
    val output: String = "",
    val stats: GenerationStats? = null,
)

class LocalModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AndroidModelStore(application)
    val engine = LocalModelEngine.shared
    private val prefs = application.getSharedPreferences("local-model-options", 0)
    val options = EngineOptions(prefs.getInt("threads", 6).coerceIn(1, 16), prefs.getInt("context", 8192).coerceIn(512, 32768))
    private val mutable = MutableStateFlow(LocalModelsState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    init { task { refresh() } }

    private suspend fun refresh() { mutable.update { it.copy(models = store.registry.list()) } }
    private fun task(work: suspend () -> Unit) {
        if (operation?.isActive == true) return
        mutable.update { it.copy(busy = true, notice = null) }
        operation = viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) {
                mutable.update { it.copy(notice = "已停止") }
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(notice = failure.message ?: "操作失败") }
            } catch (_: UnsatisfiedLinkError) {
                mutable.update { it.copy(notice = "本地引擎不可用，需要 arm64 设备和完整安装包") }
            } finally { mutable.update { it.copy(busy = false, progress = null) } }
        }
    }
    fun import(uri: Uri, allowCopy: Boolean = false) = task {
        mutable.update { it.copy(copyRequest = null) }
        try {
            val result = store.registry.import(uri.toString(), allowCopy) { progress ->
                mutable.update { it.copy(progress = progress) }
            }
            refresh()
            mutable.update { it.copy(notice = if (result.duplicate) "该模型已登记" else "校验完成，已导入") }
        } catch (required: CopyRequired) {
            mutable.update { it.copy(copyRequest = uri.toString() to required.document) }
        }
    }
    fun dismissCopy() { mutable.update { it.copy(copyRequest = null) } }
    fun load(record: LocalModelRecord, threads: String, context: String) = task {
        val config = EngineOptions(threads.toIntOrNull() ?: 0, context.toIntOrNull() ?: 0)
        val started = android.os.SystemClock.elapsedRealtime()
        engine.load(record.info, config) { store.open(record) }
        prefs.edit().putInt("threads", config.threads).putInt("context", config.contextLength).apply()
        mutable.update { it.copy(notice = "已加载（${android.os.SystemClock.elapsedRealtime() - started} 毫秒）") }
    }
    fun remove(record: LocalModelRecord) = task {
        if (engine.loadedModel.value?.id == record.sha256) engine.unload()
        store.registry.remove(record)
        refresh()
        mutable.update { it.copy(notice = "已移除登记，原文件仍保留") }
    }
    fun generate(prompt: String, maximum: String) = task {
        require(prompt.isNotBlank()) { "请输入测试消息" }
        val parameters = GenerationParameters(maxTokens = maximum.toIntOrNull() ?: 0)
        mutable.update { it.copy(output = "", stats = null) }
        engine.generate(listOf(ChatMessage(ChatRole.USER, prompt)), parameters).collect { text ->
            mutable.update { it.copy(output = it.output + text) }
        }
        mutable.update { it.copy(stats = engine.lastStats, notice = if (engine.lastStats?.stopped == true) "已停止" else "生成完成") }
    }
    fun stop() {
        engine.stop()
        operation?.cancel()
    }
    fun unload() = task {
        engine.unload()
        mutable.update { it.copy(notice = "已卸载模型") }
    }
    fun leave() {
        stop()
        val previous = operation
        operation = viewModelScope.launch {
            previous?.join()
            engine.unload()
        }
    }
    override fun onCleared() {
        stop()
        // Cleanup must outlive viewModelScope cancellation (e.g. activity is finished).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { engine.unload() }
    }
}

