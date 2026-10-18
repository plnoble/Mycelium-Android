package com.xavierxia.mycelium.hearth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xavierxia.mycelium.llm.AndroidModelStore
import com.xavierxia.mycelium.llm.EngineOptions
import com.xavierxia.mycelium.llm.LocalModelEngine
import com.xavierxia.mycelium.llm.LocalModelRecord
import com.xavierxia.mycelium.model.ChatMessage
import com.xavierxia.mycelium.model.ChatModel
import com.xavierxia.mycelium.model.ChatRole
import com.xavierxia.mycelium.net.AndroidGateway
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.roundtable.CloudChatModel
import com.xavierxia.mycelium.roundtable.CloudService
import com.xavierxia.mycelium.roundtable.CloudSettingsStore
import com.xavierxia.mycelium.roundtable.KeystoreSecretBox
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
    val services: List<CloudService> = emptyList(),
    /** Saved model names per cloud service, so the editor can offer them without another screen. */
    val cloudModels: Map<String, List<String>> = emptyMap(),
    val openCharacter: HearthCharacter? = null,
    val editing: CharacterDraft? = null,
    /** The draft waiting for the one-time cloud confirmation; the editor shows the dialog for it. */
    val cloudConfirm: CharacterDraft? = null,
    val conversations: List<HearthConversation> = emptyList(),
    val openConversation: String? = null,
    val messages: List<HearthMessage> = emptyList(),
    val streaming: String? = null,
    val streamLabel: String? = null,
    val droppedTurns: Int = 0,
    val busy: Boolean = false,
    val mode: HearthMode = HearthMode.IMMERSIVE,
    val notice: String? = null,
)

private data class Snapshot(
    val characters: List<HearthCharacter>,
    val activity: Map<String, String>,
    val models: List<LocalModelRecord>,
    val services: List<CloudService>,
    val cloudModels: Map<String, List<String>>,
)

/** What a character's answer will come from, once the stored choice is resolved. */
private sealed interface Chosen {
    val label: String

    data class Local(val record: LocalModelRecord, override val label: String) : Chosen
    data class Cloud(val service: CloudService, override val label: String) : Chosen
}

class HearthViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HearthDatabase(application)
    private val characters = CharacterStore { database.session() }
    private val log = ConversationLog { database.session() }
    private val seeder = PresetSeeder(characters, HearthMeta { database.session() })
    private val chat = HearthChat(log)
    private val store = AndroidModelStore(application)
    private val engine = LocalModelEngine.shared
    private val cloudSettings = CloudSettingsStore(application, KeystoreSecretBox("mycelium-cloud"))
    private val prefs = application.getSharedPreferences("local-model-options", 0)
    private val options = EngineOptions(
        prefs.getInt("threads", 6).coerceIn(1, 16),
        prefs.getInt("context", 8192).coerceIn(512, 32768),
    )
    private val mutable = MutableStateFlow(HearthState())
    val state: StateFlow<HearthState> = mutable.asStateFlow()
    private var operation: Job? = null
    private var cloudInFlight = false

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val snapshot = try {
            withContext(Dispatchers.IO) {
                seeder.seedOnce(now())
                val services = cloudSettings.services()
                Snapshot(
                    characters.characters(),
                    characters.lastActivity(),
                    store.registry.list(),
                    services,
                    services.associate { it.id to cloudSettings.models(it.id) },
                )
            }
        } catch (failure: Exception) {
            mutable.update { it.copy(notice = failure.message ?: "本地数据打不开") }
            return
        } catch (failure: UnsatisfiedLinkError) {
            // Robolectric and other non-device runs cannot load SQLCipher.
            mutable.update { it.copy(notice = "加密库在这个环境里不可用") }
            return
        }
        mutable.update {
            it.copy(
                characters = snapshot.characters,
                activity = snapshot.activity,
                models = snapshot.models,
                services = snapshot.services,
                cloudModels = snapshot.cloudModels,
            )
        }
    }

    fun clearNotice() = mutable.update { it.copy(notice = null) }

    /** Reloads characters, models and cloud services; the screen calls it every time it appears. */
    fun refresh() = viewModelScope.launch { reload() }

    // --- characters ----------------------------------------------------------

    fun startCreate() = mutable.update {
        it.copy(editing = CharacterDraft(name = ""), openCharacter = null, cloudConfirm = null, notice = null)
    }

    fun startEdit(character: HearthCharacter) = mutable.update {
        it.copy(editing = draftOf(character), openCharacter = character, cloudConfirm = null, notice = null)
    }

    fun cancelEditing() = mutable.update { it.copy(editing = null, openCharacter = null, cloudConfirm = null, notice = null) }

    fun updateEditing(transform: (CharacterDraft) -> CharacterDraft) =
        mutable.update { it.copy(editing = it.editing?.let(transform)) }

    /**
     * Picks a cloud service. The first time a character is pointed at a service the user has to
     * agree to that service receiving this character's chats; the agreement is remembered per
     * character and per service.
     */
    fun chooseCloudService(service: CloudService, model: String) = mutable.update { current ->
        val draft = current.editing ?: return@update current
        val picked = draft.copy(
            modelKind = ModelKind.CLOUD,
            serviceId = service.id,
            serviceName = service.name,
            modelId = "",
            modelName = model,
        )
        if (draft.cloudConfirmedService == service.id) {
            current.copy(editing = picked, cloudConfirm = null, notice = null)
        } else {
            current.copy(cloudConfirm = picked, notice = null)
        }
    }

    fun confirmCloud() = mutable.update { current ->
        val pending = current.cloudConfirm ?: return@update current
        current.copy(
            editing = pending.copy(cloudConfirmedService = pending.serviceId),
            cloudConfirm = null,
            notice = null,
        )
    }

    fun dismissCloudConfirm() = mutable.update { it.copy(cloudConfirm = null) }

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
        mutable.update { it.copy(editing = null, openCharacter = null, cloudConfirm = null, notice = "已保存") }
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

    // --- conversations -------------------------------------------------------

    fun openChat(character: HearthCharacter) = viewModelScope.launch {
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val first = conversations.firstOrNull()?.id
        val messages = withContext(Dispatchers.IO) { if (first == null) emptyList() else log.messages(first) }
        mutable.update {
            it.copy(
                openCharacter = character,
                editing = null,
                cloudConfirm = null,
                conversations = conversations,
                openConversation = first,
                messages = messages,
                streaming = null,
                streamLabel = null,
                droppedTurns = 0,
                mode = HearthMode.IMMERSIVE,
                notice = null,
            )
        }
    }

    fun closeCharacter() = mutable.update {
        it.copy(openCharacter = null, editing = null, streaming = null, streamLabel = null, notice = null)
    }

    /** Test mode starts from a clean page and keeps everything in memory only. */
    fun setMode(mode: HearthMode) = viewModelScope.launch {
        if (mutable.value.mode == mode) return@launch
        if (mode == HearthMode.TEST) {
            mutable.update {
                it.copy(mode = mode, openConversation = null, messages = emptyList(), streaming = null, streamLabel = null, droppedTurns = 0, notice = null)
            }
            return@launch
        }
        val character = mutable.value.openCharacter
        val conversations = if (character == null) emptyList() else withContext(Dispatchers.IO) { log.conversations(character.id) }
        val first = conversations.firstOrNull()?.id
        val messages = withContext(Dispatchers.IO) { if (first == null) emptyList() else log.messages(first) }
        mutable.update {
            it.copy(mode = mode, conversations = conversations, openConversation = first, messages = messages, streaming = null, streamLabel = null, droppedTurns = 0, notice = null)
        }
    }

    fun newConversation() = mutable.update {
        it.copy(openConversation = null, messages = emptyList(), streaming = null, streamLabel = null, droppedTurns = 0)
    }

    fun openConversation(id: String) = viewModelScope.launch {
        val messages = withContext(Dispatchers.IO) { log.messages(id) }
        mutable.update { it.copy(openConversation = id, messages = messages, streaming = null, streamLabel = null, droppedTurns = 0) }
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        val character = mutable.value.openCharacter ?: return@launch
        withContext(Dispatchers.IO) { log.delete(id) }
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val keep = mutable.value.openConversation?.takeIf { it != id }
        val messages = withContext(Dispatchers.IO) { if (keep == null) emptyList() else log.messages(keep) }
        mutable.update { it.copy(conversations = conversations, openConversation = keep, messages = messages) }
    }

    // --- generation ----------------------------------------------------------

    /** Streams an answer for [text]. Stopping keeps whatever was written so far. */
    fun send(text: String) {
        val character = mutable.value.openCharacter ?: return
        val question = text.trim()
        if (question.isEmpty() || operation?.isActive == true) return
        val chosen = choose(character) ?: return
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            try {
                val window = withContext(Dispatchers.IO) {
                    chat.window(
                        character,
                        mutable.value.openConversation,
                        question,
                        budgetFor(character),
                        mutable.value.mode,
                        mutable.value.messages,
                    )
                }
                mutable.update {
                    it.copy(busy = true, streaming = "", streamLabel = chosen.label, droppedTurns = window.droppedTurns, notice = null)
                }
                stream(chosen, window.messages, character) { delta ->
                    buffer.append(delta)
                    mutable.update { it.copy(streaming = buffer.toString()) }
                }
                finishTurn(character, question, buffer.toString(), chosen)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finishTurn(character, question, buffer.toString(), chosen) }
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(notice = failure.message ?: "生成失败") }
            } finally {
                cloudInFlight = false
                mutable.update { it.copy(busy = false, streaming = null, streamLabel = null) }
            }
        }
    }

    /** Redoes the last answer. Stopping keeps whatever was written so far. */
    fun regenerate() {
        val character = mutable.value.openCharacter ?: return
        if (operation?.isActive == true) return
        val chosen = choose(character) ?: return
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { regeneration(character) } ?: return@launch
                val (question, window) = prepared
                mutable.update {
                    it.copy(busy = true, streaming = "", streamLabel = chosen.label, droppedTurns = window.droppedTurns, notice = null)
                }
                stream(chosen, window.messages, character) { delta ->
                    buffer.append(delta)
                    mutable.update { it.copy(streaming = buffer.toString()) }
                }
                replaceTurn(character, buffer.toString(), chosen)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { replaceTurn(character, buffer.toString(), chosen) }
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(notice = failure.message ?: "生成失败") }
            } finally {
                cloudInFlight = false
                mutable.update { it.copy(busy = false, streaming = null, streamLabel = null) }
            }
        }
    }

    fun stop() {
        engine.stop()
        // The cloud call has nothing to abort inside the engine, so the collection is cancelled.
        if (cloudInFlight) operation?.cancel()
    }

    override fun onCleared() {
        engine.stop()
        operation?.cancel()
    }

    /** The window for a redo: the last question stays, its old answer is left out. */
    private fun regeneration(character: HearthCharacter): Pair<String, ContextWindow>? =
        if (mutable.value.mode == HearthMode.TEST) {
            chat.regenerationWindow(character, mutable.value.messages, budgetFor(character))
        } else {
            mutable.value.openConversation?.let { chat.regenerationWindow(character, it, budgetFor(character)) }
        }

    private fun budgetFor(character: HearthCharacter): Int = options.contextLength - character.maxTokens

    private suspend fun stream(chosen: Chosen, messages: List<ChatMessage>, character: HearthCharacter, onDelta: (String) -> Unit) {
        val model: ChatModel = when (chosen) {
            is Chosen.Local -> {
                if (engine.loadedModel.value?.id != chosen.record.sha256) {
                    mutable.update { it.copy(notice = "正在加载 ${chosen.record.name} …") }
                    engine.load(chosen.record.info, options) { store.open(chosen.record) }
                    mutable.update { it.copy(notice = null) }
                }
                engine
            }
            is Chosen.Cloud -> {
                cloudInFlight = true
                CloudChatModel(
                    gateway = gateway().gateway,
                    serviceName = chosen.service.name,
                    baseUrl = chosen.service.baseUrl,
                    apiKey = cloudSettings.apiKey(chosen.service.id),
                    model = character.modelName,
                    purpose = if (mutable.value.mode == HearthMode.TEST) GatewayPurpose.HearthChatTest else GatewayPurpose.HearthChat,
                )
            }
        }
        // The cloud model keeps its request on the caller's dispatcher, so the collection runs on IO;
        // the local engine switches by itself, so this is harmless for it.
        withContext(Dispatchers.IO) { model.generate(messages, character.parameters()).collect(onDelta) }
    }

    /** Resolves the character's stored choice; a missing model or service is reported, not guessed. */
    private fun choose(character: HearthCharacter): Chosen? {
        val current = mutable.value
        return when (character.modelKind) {
            ModelKind.CLOUD -> {
                val service = current.services.firstOrNull { it.id == character.serviceId }
                if (service == null) {
                    mutable.update { it.copy(notice = "这个人物选的云端服务已经不在了，请在人物编辑里重新选") }
                    return null
                }
                if (character.modelName.isBlank()) {
                    mutable.update { it.copy(notice = "这个人物还没有选云端模型，请在人物编辑里选一个") }
                    return null
                }
                Chosen.Cloud(service, "云端 · ${service.name} ${character.modelName}")
            }
            ModelKind.LOCAL -> {
                val record = current.models.firstOrNull { it.sha256 == character.modelId }
                if (record == null) {
                    mutable.update { it.copy(notice = "这个人物选的本地模型不在登记里了，请在人物编辑里重新选") }
                    return null
                }
                Chosen.Local(record, "本机 · ${record.name}")
            }
            ModelKind.DEFAULT -> {
                val record = current.models.firstOrNull()
                if (record == null) {
                    mutable.update { it.copy(notice = "先在「我的 → 本地模型」导入一个模型，再和人物聊天") }
                    return null
                }
                Chosen.Local(record, "本机 · ${record.name}")
            }
        }
    }

    private suspend fun finishTurn(character: HearthCharacter, question: String, answer: String, chosen: Chosen) {
        if (answer.isBlank()) return
        val stopped = engine.lastStats?.stopped == true
        val conversationId = withContext(Dispatchers.IO) {
            chat.finish(character, mutable.value.openConversation, question, answer, chosen.label, now(), mutable.value.mode)
        }
        if (conversationId == null) {
            // Test mode: the turn only lives on this screen.
            mutable.update {
                it.copy(
                    messages = it.messages + HearthMessage(ChatRole.USER, question, null) + HearthMessage(ChatRole.ASSISTANT, answer, chosen.label),
                    streaming = null,
                    notice = if (stopped) "已停止（测试模式，不留记录）" else it.notice,
                )
            }
            return
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

    private suspend fun replaceTurn(character: HearthCharacter, answer: String, chosen: Chosen) {
        if (answer.isBlank()) return
        val stopped = engine.lastStats?.stopped == true
        val mode = mutable.value.mode
        val stored = mutable.value.openConversation?.let { id ->
            if (withContext(Dispatchers.IO) { chat.redo(id, answer, chosen.label, now(), mode) }) id else null
        }
        if (stored == null) {
            // Test mode (or nothing stored yet): replace the answer on screen only.
            mutable.update { current ->
                current.copy(
                    messages = current.messages.dropLastWhile { it.role == ChatRole.ASSISTANT } +
                        HearthMessage(ChatRole.ASSISTANT, answer, chosen.label),
                    streaming = null,
                    notice = if (stopped) "已停止（测试模式，不留记录）" else null,
                )
            }
            return
        }
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        val messages = withContext(Dispatchers.IO) { log.messages(stored) }
        mutable.update {
            it.copy(conversations = conversations, messages = messages, streaming = null, notice = if (stopped) "已停止" else null)
        }
    }

    private fun gateway(): AndroidGateway = AndroidGateway(getApplication(), cloudSettings.allBaseUrls())

    private fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
