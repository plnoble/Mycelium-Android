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
    /** A question that produced nothing (stopped before the first word) and goes back to the box. */
    val pendingQuestion: String? = null,
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
    val isCloud: Boolean

    data class Local(val record: LocalModelRecord, override val label: String) : Chosen {
        override val isCloud: Boolean get() = false
    }

    data class Cloud(val serviceId: String, override val label: String) : Chosen {
        override val isCloud: Boolean get() = true
    }
}

/**
 * Everything one turn needs, captured when the user sends it: the character, the page mode, the
 * conversation, the question and the model that was actually resolved. Stopping, failing and
 * finishing all use this snapshot, so switching mode or conversation mid-generation cannot move a
 * turn into the wrong place (and a test-mode turn can never end up in the log).
 */
private data class Turn(
    val character: HearthCharacter,
    val mode: HearthMode,
    val conversationId: String?,
    val question: String,
    val chosen: Chosen,
) {
    /** A turn is stored at most once, whatever the cancellation timing turns out to be. */
    val written = java.util.concurrent.atomic.AtomicBoolean(false)
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

    /** The model of the turn that is being generated right now, so stop knows what to abort. */
    private var currentChosen: Chosen? = null

    /** The cloud model of that turn, so stop can disconnect the request that is in flight. */
    private var cloudModel: CloudChatModel? = null

    /**
     * Bumped whenever a turn starts or the page moves on. Callbacks that come back with an older
     * token are late: they may finish the turn but never touch the screen again.
     */
    private var generationToken = 0

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
    fun refresh() = viewModelScope.launch {
        reload()
        // Coming back to the page with a character open: show its stored history again (the test
        // session that lived here is gone). Never while a turn is running.
        val current = mutable.value
        val character = current.openCharacter
        if (character != null && current.editing == null && current.mode == HearthMode.IMMERSIVE && operation?.isActive != true) {
            loadConversation(character)
        }
    }

    /** Reads one character's conversations and the newest one's messages. */
    private suspend fun loadConversation(character: HearthCharacter) {
        val token = generationToken
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        if (token != generationToken) return
        val first = conversations.firstOrNull()?.id
        val messages = withContext(Dispatchers.IO) { if (first == null) emptyList() else log.messages(first) }
        if (token != generationToken) return
        mutable.update {
            it.copy(
                conversations = conversations,
                openConversation = first,
                messages = messages,
                streaming = null,
                streamLabel = null,
                droppedTurns = 0,
            )
        }
    }

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
        if (draft.cloudConfirmedTarget == cloudTarget(service.id, service.baseUrl)) {
            current.copy(editing = picked, cloudConfirm = null, notice = null)
        } else {
            current.copy(cloudConfirm = picked, notice = null)
        }
    }

    fun confirmCloud() = mutable.update { current ->
        val pending = current.cloudConfirm ?: return@update current
        val service = current.services.firstOrNull { it.id == pending.serviceId }
        // The agreement is for this service at this address, so it is stored as that pair.
        val target = if (service == null) "" else cloudTarget(service.id, service.baseUrl)
        current.copy(
            editing = pending.copy(cloudConfirmedTarget = target),
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
        abandonTurn()
        mutable.update {
            it.copy(
                openCharacter = character,
                editing = null,
                cloudConfirm = null,
                mode = HearthMode.IMMERSIVE,
                notice = null,
            )
        }
        loadConversation(character)
    }

    fun closeCharacter() {
        abandonTurn()
        mutable.update {
            it.copy(openCharacter = null, editing = null, streaming = null, streamLabel = null, messages = emptyList(), notice = null)
        }
    }

    /**
     * Leaving the hearth space (bottom bar, back, whatever moves the page away). The running turn is
     * stopped and the temporary test session is dropped, so "leaving means it is gone" holds.
     */
    fun leaveHearth() {
        abandonTurn()
        mutable.update { current ->
            if (current.mode == HearthMode.TEST) {
                // The temporary session only lives while the page is here.
                current.copy(
                    mode = HearthMode.IMMERSIVE,
                    openConversation = null,
                    messages = emptyList(),
                    conversations = emptyList(),
                    droppedTurns = 0,
                    streaming = null,
                    streamLabel = null,
                    busy = false,
                    pendingQuestion = null,
                    notice = null,
                )
            } else {
                // Immersive history stays where it is; only the running turn is dropped.
                current.copy(
                    streaming = null,
                    streamLabel = null,
                    busy = false,
                    pendingQuestion = null,
                    notice = null,
                )
            }
        }
    }

    /** Stops the running turn (if any) and makes sure its later callbacks are ignored. */
    private fun abandonTurn() {
        generationToken++
        cloudModel?.stop()
        cloudModel = null
        currentChosen = null
        engine.stop()
        operation?.cancel()
        operation = null
    }

    fun consumePendingQuestion() = mutable.update { it.copy(pendingQuestion = null) }

    /** Test mode starts from a clean page and keeps everything in memory only. */
    fun setMode(mode: HearthMode) = viewModelScope.launch {
        if (mutable.value.mode == mode) return@launch
        abandonTurn()
        if (mode == HearthMode.TEST) {
            mutable.update {
                it.copy(mode = mode, openConversation = null, messages = emptyList(), streaming = null, streamLabel = null, droppedTurns = 0, pendingQuestion = null, notice = null)
            }
            return@launch
        }
        val character = mutable.value.openCharacter
        val token = generationToken
        val conversations = if (character == null) emptyList() else withContext(Dispatchers.IO) { log.conversations(character.id) }
        if (token != generationToken) return@launch
        val first = conversations.firstOrNull()?.id
        val messages = withContext(Dispatchers.IO) { if (first == null) emptyList() else log.messages(first) }
        if (token != generationToken) return@launch
        mutable.update {
            it.copy(mode = mode, conversations = conversations, openConversation = first, messages = messages, streaming = null, streamLabel = null, droppedTurns = 0, pendingQuestion = null, notice = null)
        }
    }

    fun newConversation() {
        generationToken++
        mutable.update {
            it.copy(openConversation = null, messages = emptyList(), streaming = null, streamLabel = null, droppedTurns = 0)
        }
    }

    fun openConversation(id: String) = viewModelScope.launch {
        val token = ++generationToken
        val messages = withContext(Dispatchers.IO) { log.messages(id) }
        if (token != generationToken) return@launch
        mutable.update { it.copy(openConversation = id, messages = messages, streaming = null, streamLabel = null, droppedTurns = 0) }
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        val character = mutable.value.openCharacter ?: return@launch
        val token = generationToken
        withContext(Dispatchers.IO) { log.delete(id) }
        val conversations = withContext(Dispatchers.IO) { log.conversations(character.id) }
        if (token != generationToken) return@launch
        val keep = mutable.value.openConversation?.takeIf { it != id }
        val messages = withContext(Dispatchers.IO) { if (keep == null) emptyList() else log.messages(keep) }
        if (token != generationToken) return@launch
        mutable.update { it.copy(conversations = conversations, openConversation = keep, messages = messages) }
    }

    // --- generation ----------------------------------------------------------

    /** Streams an answer for [text]. Stopping keeps whatever was written so far. */
    fun send(text: String) {
        val current = mutable.value
        val character = current.openCharacter ?: return
        val question = text.trim()
        if (question.isEmpty() || operation?.isActive == true) return
        val chosen = choose(character) ?: return
        val turn = Turn(character, current.mode, current.openConversation, question, chosen)
        val token = ++generationToken
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            try {
                val window = withContext(Dispatchers.IO) {
                    chat.window(turn.character, turn.conversationId, turn.question, budgetFor(turn.character), turn.mode, current.messages)
                }
                if (token == generationToken) {
                    mutable.update {
                        it.copy(busy = true, streaming = "", streamLabel = turn.chosen.label, droppedTurns = window.droppedTurns, notice = null)
                    }
                }
                stream(turn, window.messages) { delta ->
                    buffer.append(delta)
                    if (token == generationToken) mutable.update { it.copy(streaming = buffer.toString()) }
                }
                finishTurn(turn, buffer.toString(), token = token)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finishTurn(turn, buffer.toString(), stopped = true, cancelled = true, token = token) }
                throw cancelled
            } catch (failure: Exception) {
                if (token == generationToken) mutable.update { it.copy(notice = failure.message ?: "生成失败") }
            } finally {
                // A late finishing task must not clear the state of the turn that replaced it.
                if (token == generationToken) {
                    cloudModel = null
                    currentChosen = null
                    mutable.update { it.copy(busy = false, streaming = null, streamLabel = null) }
                }
            }
        }
    }

    /** Redoes the last answer. Stopping keeps whatever was written so far. */
    fun regenerate() {
        val current = mutable.value
        val character = current.openCharacter ?: return
        if (operation?.isActive == true) return
        val chosen = choose(character) ?: return
        val token = ++generationToken
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            var turn: Turn? = null
            try {
                val prepared = withContext(Dispatchers.IO) { regeneration(character) } ?: return@launch
                val (question, window) = prepared
                turn = Turn(character, current.mode, current.openConversation, question, chosen)
                if (token == generationToken) {
                    mutable.update {
                        it.copy(busy = true, streaming = "", streamLabel = chosen.label, droppedTurns = window.droppedTurns, notice = null)
                    }
                }
                stream(requireNotNull(turn), window.messages) { delta ->
                    buffer.append(delta)
                    if (token == generationToken) mutable.update { it.copy(streaming = buffer.toString()) }
                }
                replaceTurn(requireNotNull(turn), buffer.toString(), token = token)
            } catch (cancelled: CancellationException) {
                turn?.let { withContext(NonCancellable) { replaceTurn(it, buffer.toString(), stopped = true, cancelled = true, token = token) } }
                throw cancelled
            } catch (failure: Exception) {
                if (token == generationToken) mutable.update { it.copy(notice = failure.message ?: "生成失败") }
            } finally {
                if (token == generationToken) {
                    cloudModel = null
                    currentChosen = null
                    mutable.update { it.copy(busy = false, streaming = null, streamLabel = null) }
                }
            }
        }
    }

    fun stop() {
        when (currentChosen?.isCloud) {
            // A cloud request is aborted where it is: disconnect the connection, then cancel the job
            // so nothing that already arrived is shown or stored.
            true -> {
                cloudModel?.stop()
                operation?.cancel()
            }
            else -> engine.stop()
        }
    }

    override fun onCleared() {
        cloudModel?.stop()
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

    private suspend fun stream(turn: Turn, messages: List<ChatMessage>, onDelta: (String) -> Unit) {
        currentChosen = turn.chosen
        val model: ChatModel = when (val chosen = turn.chosen) {
            is Chosen.Local -> {
                if (engine.loadedModel.value?.id != chosen.record.sha256) {
                    mutable.update { it.copy(notice = "正在加载 ${chosen.record.name} …") }
                    engine.load(chosen.record.info, options) { store.open(chosen.record) }
                    mutable.update { it.copy(notice = null) }
                }
                engine
            }
            is Chosen.Cloud -> {
                // One authoritative read per send: the address, the key and the agreement check all
                // come from the same snapshot, so a request can never mix an old address with a new
                // key, and a changed address can never slip through a stale cache.
                val service = cloudSettings.services().firstOrNull { it.id == chosen.serviceId }
                    ?: throw IllegalStateException("这个人物选的云端服务已经不在了，请在人物编辑里重新选")
                if (turn.character.cloudConfirmedTarget != cloudTarget(service.id, service.baseUrl)) {
                    throw IllegalStateException("这个人物要发的云端服务或地址变了，请在人物编辑里重新确认一次")
                }
                val test = turn.mode == HearthMode.TEST
                val built = CloudChatModel(
                    gateway = gateway().gateway,
                    serviceName = service.name,
                    baseUrl = service.baseUrl,
                    apiKey = cloudSettings.apiKey(service.id),
                    model = turn.character.modelName,
                    purpose = if (test) GatewayPurpose.HearthChatTest else GatewayPurpose.HearthChat,
                    // The request-level option is the second guard: the purpose already protects,
                    // the option keeps protecting if a caller ever forgets the purpose.
                    omitAuditBody = test,
                    naturalChat = true,
                )
                cloudModel = built
                built
            }
        }
        // The cloud model keeps its request on the caller's dispatcher, so the collection runs on IO;
        // the local engine switches by itself, so this is harmless for it.
        withContext(Dispatchers.IO) { model.generate(messages, turn.character.parameters()).collect(onDelta) }
    }

    /** Resolves the character's stored choice; a missing model or service is reported, not guessed. */
    private fun choose(character: HearthCharacter): Chosen? {
        val current = mutable.value
        return when (character.modelKind) {
            ModelKind.CLOUD -> {
                if (character.modelName.isBlank()) {
                    mutable.update { it.copy(notice = "这个人物还没有选云端模型，请在人物编辑里选一个") }
                    return null
                }
                // The address, the key and the agreement check are all read again from the settings
                // at send time (see stream), so nothing here relies on a cached service list.
                Chosen.Cloud(
                    character.serviceId,
                    "云端 · ${character.serviceName.ifBlank { "云端服务" }} ${character.modelName}",
                )
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

    private suspend fun finishTurn(turn: Turn, answer: String, stopped: Boolean = false, cancelled: Boolean = false, token: Int) {
        // A turn from a page the user has already left is dropped whole: nothing is shown and
        // nothing is stored, no matter where the cancellation happened to land.
        if (token != generationToken) return
        val byEngine = !turn.chosen.isCloud && engine.lastStats?.stopped == true
        val wasStopped = stopped || byEngine
        if (answer.isBlank()) {
            // Nothing came out at all: nothing is stored, and the question goes back to the box so
            // stopping early does not make it vanish.
            if (wasStopped) {
                mutable.update { it.copy(streaming = null, pendingQuestion = turn.question, notice = "已停止，还没有回答") }
            }
            return
        }
        // A cloud answer arrives in one piece, so a cancelled one is thrown away completely. A
        // local answer that was already streaming keeps what it had produced.
        if (cancelled && turn.chosen.isCloud) return
        if (!turn.written.compareAndSet(false, true)) return
        val conversationId = withContext(Dispatchers.IO) {
            chat.finish(turn.character, turn.conversationId, turn.question, answer, turn.chosen.label, now(), turn.mode)
        }
        if (conversationId == null) {
            // Test mode: the turn only lives on this screen, and only while it is still this turn.
            if (token != generationToken) return
            mutable.update {
                it.copy(
                    messages = it.messages + HearthMessage(ChatRole.USER, turn.question, null) +
                        HearthMessage(ChatRole.ASSISTANT, answer, turn.chosen.label),
                    streaming = null,
                    notice = if (wasStopped) "已停止（测试模式，不留记录）" else it.notice,
                )
            }
            return
        }
        if (token != generationToken) return
        val conversations = withContext(Dispatchers.IO) { log.conversations(turn.character.id) }
        val messages = withContext(Dispatchers.IO) { log.messages(conversationId) }
        mutable.update {
            it.copy(
                openConversation = conversationId,
                conversations = conversations,
                messages = messages,
                streaming = null,
                notice = if (wasStopped) "已停止" else it.notice,
            )
        }
    }

    private suspend fun replaceTurn(turn: Turn, answer: String, stopped: Boolean = false, cancelled: Boolean = false, token: Int) {
        if (token != generationToken) return
        val wasStopped = stopped || (!turn.chosen.isCloud && engine.lastStats?.stopped == true)
        if (answer.isBlank()) {
            if (wasStopped) {
                mutable.update { it.copy(streaming = null, pendingQuestion = turn.question, notice = "已停止，还没有回答") }
            }
            return
        }
        if (cancelled && turn.chosen.isCloud) return
        if (!turn.written.compareAndSet(false, true)) return
        val stored = turn.conversationId?.let { id ->
            if (withContext(Dispatchers.IO) { chat.redo(id, answer, turn.chosen.label, now(), turn.mode) }) id else null
        }
        if (stored == null) {
            // Test mode (or nothing stored yet): replace the answer on screen only.
            mutable.update { current ->
                current.copy(
                    messages = current.messages.dropLastWhile { it.role == ChatRole.ASSISTANT } +
                        HearthMessage(ChatRole.ASSISTANT, answer, turn.chosen.label),
                    streaming = null,
                    notice = if (wasStopped) "已停止（测试模式，不留记录）" else null,
                )
            }
            return
        }
        val conversations = withContext(Dispatchers.IO) { log.conversations(turn.character.id) }
        val messages = withContext(Dispatchers.IO) { log.messages(stored) }
        mutable.update {
            it.copy(conversations = conversations, messages = messages, streaming = null, notice = if (wasStopped) "已停止" else null)
        }
    }

    private fun gateway(): AndroidGateway = AndroidGateway(getApplication(), cloudSettings.allBaseUrls())

    private fun now(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
