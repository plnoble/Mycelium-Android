package com.xavierxia.mycelium.hearth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xavierxia.mycelium.model.ChatRole

private val presetColors = listOf(0xFF7EB6A4, 0xFF8D4A2B, 0xFF5B7DB1, 0xFFB18BC4, 0xFFC9A227, 0xFF6FA36F).map { it.toInt() }

@Composable
fun HearthSpace(padding: PaddingValues, vm: HearthViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Services, models and characters can change on the other tabs, so refresh on every entry.
    LaunchedEffect(Unit) { vm.refresh() }
    val editing = state.editing
    val character = state.openCharacter
    when {
        editing != null -> CharacterEditor(vm, state, editing, padding)
        character != null -> CharacterChat(vm, state, character, padding)
        else -> CharacterList(vm, state, padding)
    }
}

@Composable
private fun CharacterList(vm: HearthViewModel, state: HearthState, padding: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("炉边", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text("这里的人物是你自己写的。默认用手机上的模型，不联网；也可以给某个人物单独选云端模型。")
        }
        state.notice?.let { note -> item { Text(note) } }
        item { Button(onClick = vm::startCreate) { Text("新建人物") } }
        if (state.characters.isEmpty()) {
            item { Text("还没有人物。点「新建人物」，写个名字就能开始。") }
        } else {
            items(state.characters, key = { it.id }) { character ->
                Card(Modifier.fillMaxWidth().clickable { vm.openChat(character) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).background(Color(character.color), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(character.avatarChar.ifBlank { character.name.take(1) }, color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(character.name, style = MaterialTheme.typography.titleSmall)
                                if (character.modelKind == ModelKind.CLOUD) {
                                    Spacer(Modifier.size(6.dp))
                                    Text("云端", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(briefOf(character), style = MaterialTheme.typography.bodySmall)
                            Text("最近对话：${friendlyTime(state.activity[character.id])}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.startEdit(character) }) { Text("编辑") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterEditor(vm: HearthViewModel, state: HearthState, draft: CharacterDraft, padding: PaddingValues) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var temperatureText by rememberSaveable { mutableStateOf(draft.temperature.toString()) }
    var lengthText by rememberSaveable { mutableStateOf(draft.maxTokens.toString()) }
    BackHandler(onBack = vm::cancelEditing)
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = vm::cancelEditing) { Text("返回") }
            Text(if (state.openCharacter == null) "新建人物" else "编辑人物", style = MaterialTheme.typography.headlineSmall)
        }
        state.notice?.let { note -> item { Text(note) } }
        item {
            OutlinedTextField(
                draft.name,
                { value -> vm.updateEditing { it.copy(name = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名字") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                draft.avatarChar,
                { value -> vm.updateEditing { it.copy(avatarChar = value.take(1)) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("头像字（一个字，不填就用名字的第一个字）") },
                singleLine = true,
            )
        }
        item {
            Text("头像颜色")
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                presetColors.forEach { color ->
                    val ring = if (color == draft.color) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
                    Box(
                        Modifier.size(36.dp).background(Color(color), CircleShape).then(ring).clickable {
                            vm.updateEditing { it.copy(color = color) }
                        },
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                draft.personality,
                { value -> vm.updateEditing { it.copy(personality = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("性格") },
                minLines = 2,
            )
        }
        item {
            OutlinedTextField(
                draft.speech,
                { value -> vm.updateEditing { it.copy(speech = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("说话方式") },
                minLines = 2,
            )
        }
        item {
            OutlinedTextField(
                draft.background,
                { value -> vm.updateEditing { it.copy(background = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("背景设定") },
                minLines = 2,
            )
        }
        item {
            OutlinedTextField(
                draft.relationship,
                { value -> vm.updateEditing { it.copy(relationship = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("和你的关系（可不填）") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                draft.greeting,
                { value -> vm.updateEditing { it.copy(greeting = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("开场白（可不填）") },
                minLines = 2,
            )
        }
        item { Text("用哪个模型", style = MaterialTheme.typography.titleSmall) }
        item {
            ModelChoice(
                selected = draft.modelKind == ModelKind.DEFAULT,
                title = "默认本地模型",
                subtitle = "跟随全局默认；现在用「我的 → 本地模型」里登记的第一个",
                onSelect = { vm.updateEditing { it.copy(modelKind = ModelKind.DEFAULT, modelId = "", modelName = "", serviceId = "", serviceName = "") } },
            )
        }
        if (state.models.isEmpty()) {
            item { Text("还没有导入本地模型。先去「我的 → 本地模型」导入一个 GGUF 文件。") }
        } else {
            items(state.models, key = { it.sha256 }) { record ->
                ModelChoice(
                    selected = draft.modelKind == ModelKind.LOCAL && draft.modelId == record.sha256,
                    title = record.name,
                    subtitle = "本机模型",
                    onSelect = {
                        vm.updateEditing {
                            it.copy(modelKind = ModelKind.LOCAL, modelId = record.sha256, modelName = record.name, serviceId = "", serviceName = "")
                        }
                    },
                )
            }
        }
        if (state.services.isEmpty()) {
            item { Text("还没有云端服务。要让人物用云端，先去「我的 → 云端模型」添加服务并拉取模型。") }
        } else {
            state.services.forEach { service ->
                item(key = "service-${service.id}") {
                    ModelChoice(
                        selected = draft.modelKind == ModelKind.CLOUD && draft.serviceId == service.id,
                        title = "云端：${service.name}",
                        subtitle = service.baseUrl,
                        onSelect = { vm.updateEditing { it.copy(modelKind = ModelKind.CLOUD, serviceId = service.id, serviceName = service.name, modelId = "") } },
                    )
                }
                if (draft.modelKind == ModelKind.CLOUD && draft.serviceId == service.id) {
                    val models = state.cloudModels[service.id].orEmpty()
                    if (models.isEmpty()) {
                        item(key = "service-${service.id}-empty") {
                            Text("这个服务还没有保存的模型。先去「我的 → 云端模型」点「拉取模型」。")
                        }
                    } else {
                        models.forEach { model ->
                            item(key = "service-${service.id}-$model") {
                                ModelChoice(
                                    selected = draft.modelName == model,
                                    title = model,
                                    subtitle = "云端模型",
                                    indent = true,
                                    onSelect = { vm.chooseCloudService(service, model) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    temperatureText,
                    { value ->
                        temperatureText = value
                        value.toFloatOrNull()?.let { picked -> vm.updateEditing { it.copy(temperature = picked) } }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("温度 0–2") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    lengthText,
                    { value ->
                        lengthText = value
                        value.toIntOrNull()?.let { picked -> vm.updateEditing { it.copy(maxTokens = picked) } }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("生成长度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveEditing) { Text("保存") }
                TextButton(onClick = vm::cancelEditing) { Text("取消") }
                if (state.openCharacter != null) {
                    TextButton(onClick = vm::copyCharacter) { Text("复制") }
                    TextButton(onClick = { confirmingDelete = true }) { Text("删除") }
                }
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除「${state.openCharacter?.name}」？") },
            text = { Text("这个人物的所有对话会一起删除。") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; vm.deleteCharacter() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } },
        )
    }
    state.cloudConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissCloudConfirm,
            title = { Text("这个人物改用云端模型？") },
            text = {
                Text(
                    "这个人物的聊天内容（以后还有它对你的记忆）会发给「${pending.serviceName}」。" +
                        "确认后才会生效，你在对话页可以随时改回本地模型。",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmCloud) { Text("知道了，用云端") } },
            dismissButton = { TextButton(onClick = vm::dismissCloudConfirm) { Text("取消") } },
        )
    }
}

@Composable
private fun ModelChoice(selected: Boolean, title: String, subtitle: String, indent: Boolean = false, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = if (indent) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CharacterChat(vm: HearthViewModel, state: HearthState, character: HearthCharacter, padding: PaddingValues) {
    var text by rememberSaveable { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HearthConversation?>(null) }
    BackHandler(onBack = vm::closeCharacter)
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::closeCharacter) { Text("返回") }
                Text(character.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (character.modelKind == ModelKind.CLOUD) {
                    Text("云端", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = vm::newConversation) { Text("新对话") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("沉浸模式", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = state.mode == HearthMode.TEST,
                    onCheckedChange = { on -> vm.setMode(if (on) HearthMode.TEST else HearthMode.IMMERSIVE) },
                )
                Text("测试模式", style = MaterialTheme.typography.bodySmall)
            }
            if (state.mode == HearthMode.TEST) {
                Text("测试模式 · 不留记录：不写库、不留历史，离开这页就没有了；也不读不写这个人物的记忆。", style = MaterialTheme.typography.bodySmall)
            }
            state.notice?.let { note -> Text(note) }
            if (state.droppedTurns > 0) {
                Text("较早的 ${state.droppedTurns} 轮对话放不下了，已经省略；人物设定一直都在。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.mode == HearthMode.IMMERSIVE && state.conversations.isNotEmpty()) {
                TextButton(onClick = { historyOpen = !historyOpen }) { Text("历史对话（${state.conversations.size}）") }
                if (historyOpen) {
                    state.conversations.forEach { conversation ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                conversation.title.ifBlank { "未命名" },
                                modifier = Modifier.weight(1f).clickable {
                                    vm.openConversation(conversation.id)
                                    historyOpen = false
                                },
                            )
                            Text(friendlyTime(conversation.updatedAt), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { removing = conversation }) { Text("删除") }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty() && state.streaming == null) {
                item { Text(character.greeting.ifBlank { "说点什么吧。" }) }
            }
            items(state.messages) { message -> MessageRow(message, character) }
            state.streaming?.let { partial -> item { StreamingRow(partial, state.streamLabel) } }
        }
        if (!state.busy && state.messages.lastOrNull()?.role == ChatRole.ASSISTANT) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::regenerate) { Text("重新生成") }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                text,
                { value -> text = value },
                modifier = Modifier.weight(1f),
                label = { Text("说点什么") },
            )
            if (state.busy) {
                Button(onClick = vm::stop) { Text("停止") }
            } else {
                Button(
                    onClick = {
                        val question = text
                        text = ""
                        vm.send(question)
                    },
                    enabled = text.isNotBlank(),
                ) { Text("发送") }
            }
        }
    }
    removing?.let { conversation ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除这段对话？") },
            text = { Text("「${conversation.title.ifBlank { "未命名" }}」里的消息会一起删除。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteConversation(conversation.id); removing = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MessageRow(message: HearthMessage, character: HearthCharacter) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (message.role == ChatRole.USER) "你" else message.model ?: "本机 · ${character.modelName}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(message.text)
    }
}

@Composable
private fun StreamingRow(partial: String, label: String?) {
    Column(Modifier.fillMaxWidth()) {
        Text(label ?: "正在生成…", style = MaterialTheme.typography.labelSmall)
        Text(partial.ifEmpty { "…" })
    }
}

private fun briefOf(character: HearthCharacter): String =
    listOf(character.personality, character.speech, character.background)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(30)
        ?: "还没写设定，点「编辑」补上"

private fun friendlyTime(value: String?): String =
    if (value.isNullOrBlank()) "还没有聊过" else value.replace('T', ' ').take(16)
