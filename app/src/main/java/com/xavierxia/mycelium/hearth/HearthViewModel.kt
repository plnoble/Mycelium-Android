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

/** A cloud turn that is waiting for the user to agree to the service that would receive it. */
data class PendingSend(
    val character: HearthCharacter,
    val question: String,
    /** The mode and conversation the question was written in; a later mode switch must not move it. */
    val mode: HearthMode,
    val conversationId: String?,
    /** The receiver the user is being asked about, so a service change can be noticed. */
    val target: String,
    /** The turn this question belongs to; anything that moves on makes this stale. */
    val token: Int,
)

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
    /** A cloud question held back until the user agrees to the receiving service. */
    val sendConfirm: PendingSend? = null,
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
    /** The label may be refreshed from the live service snapshot just before the request. */
    var chosen: Chosen,
) {
    /** A turn is stored at most once, whatever the cancellation timing turns out to be. */
    val written = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The user pressed stop while this turn was still running. A cloud answer that arrives late is
     * thrown away; a local one keeps whatever it had already produced.
     */
    @Volatile
    var userStopped = false
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

    /** The turn that is running right now, so stop can mark it as stopped. */
    private var activeTurn: Turn? = null

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
        activeTurn = null
        engine.stop()
        operation?.cancel()
        operation = null
        // The page has to be usable straight away: a turn that is gone cannot keep the send button
        // disabled, and a question waiting for confirmation belongs to the page that asked it.
        mutable.update { it.copy(busy = false, streaming = null, streamLabel = null, sendConfirm = null) }
    }

    fun consumePendingQuestion() = mutable.update { it.copy(pendingQuestion = null) }

    /**
     * The user agreed to the cloud receiver that the send was waiting on. The agreement is written
     * to the character first, so the turn that follows passes the same check it was stopped by.
     */
    fun confirmSend() = viewModelScope.launch {
        val pending = mutable.value.sendConfirm ?: return@launch
        // This question belongs to the page and the turn that asked about it. Cancelling the dialog,
        // switching mode, closing the page or leaving all make it stale; then nothing is written and
        // nothing is sent, however far this task already got.
        fun stillWanted() = generationToken == pending.token && mutable.value.sendConfirm === pending
        val service = withContext(Dispatchers.IO) { cloudSettings.service(pending.character.serviceId) }
        if (!stillWanted()) return@launch
        if (service == null) {
            mutable.update { it.copy(sendConfirm = null, notice = "这个人物选的云端服务已经不在了，请在人物编辑里重新选") }
            return@launch
        }
        val target = cloudTarget(service.id, service.baseUrl)
        if (target != pending.target) {
            // The receiver moved while the question waited. Ask again about the receiver that is
            // actually there now instead of sending to one the user never agreed to.
            mutable.update { it.copy(sendConfirm = pending.copy(target = target), notice = "这家服务的地址又变了，请再确认一次") }
            return@launch
        }
        withContext(Dispatchers.IO) {
            characters.update(pending.character.id, draftOf(pending.character).copy(cloudConfirmedTarget = pending.target), now())
        }
        if (!stillWanted()) return@launch
        val refreshed = withContext(Dispatchers.IO) { characters.character(pending.character.id) }
        if (!stillWanted()) return@launch
        if (refreshed == null) {
            mutable.update { it.copy(sendConfirm = null, notice = "这个人物已经不在了") }
            return@launch
        }
        mutable.update { it.copy(sendConfirm = null, openCharacter = refreshed, notice = null) }
        // The captured mode and conversation are used, not the ones on screen now.
        startTurn(pending.question, pending.mode, pending.conversationId)
    }

    /** Refused: nothing is sent and the question goes back to the box. */
    fun dismissSendConfirm() = mutable.update {
        it.copy(sendConfirm = null, pendingQuestion = it.sendConfirm?.question, notice = "已取消，这一轮没有发出去")
    }

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
        // Switching conversation stops the running turn; it must never keep generating into the
        // page that is now showing something else (and it must not leave the page busy).
        abandonTurn()
        mutable.update {
            it.copy(openConversation = null, messages = emptyList(), droppedTurns = 0)
        }
    }

    fun openConversation(id: String) = viewModelScope.launch {
        abandonTurn()
        val token = generationToken
        val messages = withContext(Dispatchers.IO) { log.messages(id) }
        if (token != generationToken) return@launch
        mutable.update { it.copy(openConversation = id, messages = messages, droppedTurns = 0) }
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
    fun send(text: String) = startTurn(text, mutable.value.mode, mutable.value.openConversation)

    /**
     * Starts a turn in [mode] inside [conversationId]. A confirmed cloud question passes the mode and
     * the conversation it was written in, so a mode switch while the dialog was up cannot move it.
     */
    private fun startTurn(text: String, mode: HearthMode, conversationId: String?) {
        val current = mutable.value
        val character = current.openCharacter ?: return
        val question = text.trim()
        if (question.isEmpty() || operation?.isActive == true) return
        if (character.modelKind == ModelKind.CLOUD) {
            // Nothing leaves the phone until this exact receiver has been agreed to. One read of the
            // settings decides it; a changed service or address asks here, where the user is
            // actually sending, instead of only in the editor.
            val service = cloudSettings.service(character.serviceId)
            if (service == null) {
                mutable.update { it.copy(notice = "这个人物选的云端服务已经不在了，请在人物编辑里重新选") }
                return
            }
            val target = cloudTarget(service.id, service.baseUrl)
            if (character.cloudConfirmedTarget != target) {
                mutable.update {
                    it.copy(sendConfirm = PendingSend(character, question, mode, conversationId, target, generationToken), notice = null)
                }
                return
            }
        }
        val chosen = choose(character) ?: return
        val turn = Turn(character, mode, conversationId, question, chosen)
        val token = ++generationToken
        val buffer = StringBuilder()
        operation = viewModelScope.launch {
            activeTurn = turn
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
                // A stop during loading reaches here as a plain failure from the engine. It is still
                // a stop: the question goes back and nothing is stored.
                if (turn.userStopped) {
                    withContext(NonCancellable) {
                        finishTurn(turn, buffer.toString(), stopped = true, cancelled = turn.chosen.isCloud, token = token)
                    }
                } else if (token == generationToken) {
                    mutable.update { it.copy(notice = failure.message ?: "生成失败") }
                }
            } finally {
                if (activeTurn === turn) activeTurn = null
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
                activeTurn = turn
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
                // Same rule as send: a stop during loading is a stop, not an error to report.
                val running = turn
                if (running != null && running.userStopped) {
                    withContext(NonCancellable) {
                        replaceTurn(running, buffer.toString(), stopped = true, cancelled = running.chosen.isCloud, token = token)
                    }
                } else if (token == generationToken) {
                    mutable.update { it.copy(notice = failure.message ?: "生成失败") }
                }
            } finally {
                if (activeTurn === turn) activeTurn = null
                if (token == generationToken) {
                    cloudModel = null
                    currentChosen = null
                    mutable.update { it.copy(busy = false, streaming = null, streamLabel = null) }
                }
            }
        }
    }

    fun stop() {
        // Record the intent on the running turn first: an engine that fails while loading reports a
        // plain exception, and that must still end as a stop (question back, nothing stored).
        activeTurn?.userStopped = true
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
                // One authoritative read per send: the address, the name, the key and the agreement
                // check all come from the same snapshot, so a request can never mix an old address
                // with a new key, and a changed address can never slip through a stale cache.
                val service = cloudSettings.endpoint(chosen.serviceId)
                    ?: throw IllegalStateException("这个人物选的云端服务已经不在了，请在人物编辑里重新选")
                if (turn.character.cloudConfirmedTarget != cloudTarget(service.id, service.baseUrl)) {
                    throw IllegalStateException("这个人物要发的云端服务或地址变了，请在人物编辑里重新确认一次")
                }
                // The label follows the service as it is called now, not the name saved with the
                // character (a rename would otherwise keep showing the old one).
                turn.chosen = Chosen.Cloud(service.id, "云端 · ${service.name} ${turn.character.modelName}")
                val test = turn.mode == HearthMode.TEST
                val built = CloudChatModel(
                    gateway = gateway().gateway,
                    serviceName = service.name,
                    baseUrl = service.baseUrl,
                    apiKey = service.apiKey,
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

    /**
     * Whether this turn may still be written. It is asked **inside** the write transaction: opening
     * the database or waiting for its write lock can take a while, and the user may stop, switch
     * conversation or leave the page while that happens. A stopped cloud answer is dropped whole; a
     * stopped local answer keeps whatever it had already produced.
     */
    private fun stillCommittable(turn: Turn, token: Int): Boolean =
        token == generationToken && !(turn.chosen.isCloud && turn.userStopped)

    private suspend fun finishTurn(turn: Turn, answer: String, stopped: Boolean = false, cancelled: Boolean = false, token: Int) {
        // A turn from a page the user has already left is dropped whole: nothing is shown and
        // nothing is stored, no matter where the cancellation happened to land.
        if (token != generationToken) return
        val byEngine = !turn.chosen.isCloud && engine.lastStats?.stopped == true
        val wasStopped = stopped || turn.userStopped || byEngine
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
            chat.finish(turn.character, turn.conversationId, turn.question, answer, turn.chosen.label, now(), turn.mode) {
                stillCommittable(turn, token)
            }
        }
        if (conversationId == null) {
            // Test mode (or a turn that was abandoned while the write waited): the turn lives on this
            // screen only, and only while it is still this turn.
            if (token != generationToken) return
            if (turn.chosen.isCloud && turn.userStopped) return
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
        val wasStopped = stopped || turn.userStopped || (!turn.chosen.isCloud && engine.lastStats?.stopped == true)
        if (answer.isBlank()) {
            if (wasStopped) {
                mutable.update { it.copy(streaming = null, pendingQuestion = turn.question, notice = "已停止，还没有回答") }
            }
            return
        }
        if (cancelled && turn.chosen.isCloud) return
        if (!turn.written.compareAndSet(false, true)) return
        val stored = turn.conversationId?.let { id ->
            if (
                withContext(Dispatchers.IO) {
                    chat.redo(id, answer, turn.chosen.label, now(), turn.mode) { stillCommittable(turn, token) }
                }
            ) {
                id
            } else {
                null
            }
        }
        if (stored == null) {
            // Test mode (or an abandoned turn): replace the answer on screen only.
            if (turn.chosen.isCloud && turn.userStopped) return
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
