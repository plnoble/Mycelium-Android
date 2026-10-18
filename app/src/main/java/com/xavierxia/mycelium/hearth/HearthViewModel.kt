package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class HearthState(
    val characters: List<HearthCharacter> = emptyList(),
    val activity: Map<String, String> = emptyMap(),
    val models: List<LocalModelRecord> = emptyList(),
    val openCharacter: HearthCharacter? = null,
    val editing: CharacterDraft? = null,
    val conversations: List<HearthConversation> = emptyList(),
    val openConversation: String? = null,
    val messages: List<HearthMessage> = emptyList(),
    val streaming: String? = null,
    val droppedTurns: Int = 0,
    val busy: Boolean = false,
    val notice: String? = null,
)

class HearthViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HearthDatabase(application)
    private val characters = CharacterStore { database.session() }
    private val log = ConversationLog { database.session() }
    private val chat = HearthChat(log)
    private val store = AndroidModelStore(application)
    private val engine = LocalModelEngine.shared
    private val prefs = application.getSharedPreferences("local-model-options", 0)
    private val options = EngineOptions(
        prefs.getInt("threads", 6).coerceIn(1, 16),
        prefs.getInt("context", 8192).coerceIn(512, 32768),
    )
    private val mutable = MutableStateFlow(HearthState())
    val state: StateFlow<HearthState> = mutable.asStateFlow()
    private var operation: Job? = null

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val snapshot = try {
            withContext(Dispatchers.IO) {
                Triple(characters.characters(), characters.lastActivity(), store.registry.list())
            }
        } catch (failure: Exception) {
            mutable.update { it.copy(notice = failure.message ?: "本地数据打不开") }
            return
        } catch (failure: UnsatisfiedLinkError) {
            // Robolectric and other non-device runs cannot load SQLCipher.
            mutable.update { it.copy(notice = "加密库在这个环境里不可用") }
            return
        }
        mutable.update { it.copy(characters = snapshot.first, activity = snapshot.second, models = snapshot.third) }
    }

    fun clearNotice() = mutable.update { it.copy(notice = null) }

    fun startCreate() = mutable.update {
        it.copy(editing = CharacterDraft(name = ""), openCharacter = null, notice = null)
    }

    fun startEdit(character: HearthCharacter) = mutable.update {
        it.copy(editing = draftOf(character), openCharacter = character, notice = null)
    }

    fun cancelEditing() = mutable.update { it.copy(editing = null, openCharacter = null, notice = null) }

    fun updateEditing(transform: (CharacterDraft) -> CharacterDraft) =
        mutable.update { it.copy(editing = it.editing?.let(transform)) }

    fun saveEditing() = viewModelScope.launch {
        val draft = mutable.value.editing ?: return@launch
        val existing = mutable.value.openCharacter
        try {
            withContext(Dispatchers.IO) {
                if (existing == null) characters.create(draft, now()) else characters.update(existing.id, draft, now())
            }
        } catch (failure: IllegalArgumentException) {
            mutable.update { it.copy(notice = failure.message ?: "存不下这个人物") }
            return@launch
        }
        mutable.update { it.copy(editing = null, openCharacter = null, notice = "已保存") }
        reload()
    }

    fun copyCharacter() = viewModelScope.launch {
        val existing = mutable.value.openCharacter ?: return@launch
        try {
            withContext(Dispatchers.IO) { characters.copy(existing.id, now()) }
        } catch (failure: Exception) {
            mutable.update { it.copy(notice = failure.message ?: "复制失败") }
            return@launch
        }
        mutable.update { it.copy(editing = null, openCharacter = null, notice = "已复制一份") }
        reload()
    }

    fun deleteCharacter() = viewModelScope.launch {
        val existing = mutable.value.openCharacter ?: return@launch
        withContext(Dispatchers.IO) { characters.delete(existing.id) }
        mutable.update { it.copy(editing = null, openCharacter = null, notice = "已删除「${existing.name}」，对话一起删了") }
        reload()
    }

    fun openChat(character: HearthCharacter) = viewModelScope.launch {
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val first = conversations.firstOrNull()?.id
        val messages = withContext(Dispatchers.IO) { if (first == null) emptyList() else log.messages(first) }
        mutable.update {
            it.copy(
                openCharacter = character,
                editing = null,
                conversations = conversations,
                openConversation = first,
                messages = messages,
                streaming = null,
                droppedTurns = 0,
                notice = null,
            )
        }
    }

    fun closeCharacter() = mutable.update { it.copy(openCharacter = null, editing = null, streaming = null, notice = null) }

    fun newConversation() = mutable.update {
        it.copy(openConversation = null, messages = emptyList(), streaming = null, droppedTurns = 0)
    }

    fun openConversation(id: String) = viewModelScope.launch {
        val messages = withContext(Dispatchers.IO) { log.messages(id) }
        mutable.update { it.copy(openConversation = id, messages = messages, streaming = null, droppedTurns = 0) }
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        val character = mutable.value.openCharacter ?: return@launch
        withContext(Dispatchers.IO) { log.delete(id) }
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val keep = mutable.value.openConversation?.takeIf { it != id }
        val messages = withContext(Dispatchers.IO) { if (keep == null) emptyList() else log.messages(keep) }
        mutable.update { it.copy(conversations = conversations, openConversation = keep, messages = messages) }
    }

    /** Streams an answer for [text]. Stopping keeps whatever was written so far. */
    fun send(text: String) {
        val character = mutable.value.openCharacter ?: return
        val question = text.trim()
        if (question.isEmpty() || operation?.isActive == true) return
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            try {
                if (!ensureModel(character)) return@launch
                val window = withContext(Dispatchers.IO) {
                    chat.window(character, mutable.value.openConversation, question, budgetFor(character))
                }
                mutable.update { it.copy(busy = true, streaming = "", droppedTurns = window.droppedTurns, notice = null) }
                engine.generate(window.messages, character.parameters()).collect { delta ->
                    buffer.append(delta)
                    mutable.update { it.copy(streaming = buffer.toString()) }
                }
                finishTurn(character, question, buffer.toString())
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finishTurn(character, question, buffer.toString()) }
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(notice = failure.message ?: "生成失败") }
            } finally {
                mutable.update { it.copy(busy = false, streaming = null) }
            }
        }
    }

    /** Redoes the last answer. Stopping keeps whatever was written so far. */
    fun regenerate() {
        val character = mutable.value.openCharacter ?: return
        val conversationId = mutable.value.openConversation ?: return
        if (operation?.isActive == true) return
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            try {
                if (!ensureModel(character)) return@launch
                val (_, window) = withContext(Dispatchers.IO) {
                    chat.regenerationWindow(character, conversationId, budgetFor(character))
                }
                mutable.update { it.copy(busy = true, streaming = "", droppedTurns = window.droppedTurns, notice = null) }
                engine.generate(window.messages, character.parameters()).collect { delta ->
                    buffer.append(delta)
                    mutable.update { it.copy(streaming = buffer.toString()) }
                }
                replaceTurn(character, conversationId, buffer.toString())
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { replaceTurn(character, conversationId, buffer.toString()) }
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(notice = failure.message ?: "生成失败") }
            } finally {
                mutable.update { it.copy(busy = false, streaming = null) }
            }
        }
    }

    fun stop() {
        engine.stop()
    }

    override fun onCleared() {
        engine.stop()
    }

    private fun budgetFor(character: HearthCharacter): Int = options.contextLength - character.maxTokens

    private suspend fun ensureModel(character: HearthCharacter): Boolean {
        val record = mutable.value.models.firstOrNull { it.sha256 == character.modelId }
        if (record == null) {
            mutable.update { it.copy(notice = "先在「我的 → 本地模型」导入一个模型，再在人物里选它") }
            return false
        }
        if (engine.loadedModel.value?.id == record.sha256) return true
        mutable.update { it.copy(notice = "正在加载 ${record.name} …") }
        engine.load(record.info, options) { store.open(record) }
        mutable.update { it.copy(notice = null) }
        return true
    }

    private suspend fun finishTurn(character: HearthCharacter, question: String, answer: String) {
        if (answer.isBlank()) return
        val label = modelLabel(character)
        val stopped = engine.lastStats?.stopped == true
        val conversationId = withContext(Dispatchers.IO) {
            log.append(character.id, mutable.value.openConversation, question, answer, label, now())
        }
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val messages = withContext(Dispatchers.IO) { log.messages(conversationId) }
        mutable.update {
            it.copy(
                openConversation = conversationId,
                conversations = conversations,
                messages = messages,
                streaming = null,
                notice = if (stopped) "已停止" else it.notice,
            )
        }
    }

    private suspend fun replaceTurn(character: HearthCharacter, conversationId: String, answer: String) {
        if (answer.isBlank()) return
        val label = modelLabel(character)
        val stopped = engine.lastStats?.stopped == true
        withContext(Dispatchers.IO) { log.replaceLastAnswer(conversationId, answer, label, now()) }
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val messages = withContext(Dispatchers.IO) { log.messages(conversationId) }
        mutable.update {
            it.copy(
                conversations = conversations,
                messages = messages,
                streaming = null,
                notice = if (stopped) "已停止" else null,
            )
        }
    }

    private fun modelLabel(character: HearthCharacter): String =
        "本机 · ${character.modelName.ifBlank { "本地模型" }}"

    private fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
}