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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
            Text("这里的人物是你自己写的。聊天只用手机上的模型，不联网。")
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
                            Text(character.name, style = MaterialTheme.typography.titleSmall)
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
        item { Text("用哪个本地模型", style = MaterialTheme.typography.titleSmall) }
        if (state.models.isEmpty()) {
            item { Text("还没有导入本地模型。先去「我的 → 本地模型」导入一个 GGUF 文件。") }
        } else {
            items(state.models, key = { it.sha256 }) { record ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vm.updateEditing { it.copy(modelId = record.sha256, modelName = record.name) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.modelId == record.sha256,
                        onClick = { vm.updateEditing { it.copy(modelId = record.sha256, modelName = record.name) } },
                    )
                    Text(record.name)
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
                TextButton(onClick = vm::newConversation) { Text("新对话") }
            }
            state.notice?.let { note -> Text(note) }
            if (state.droppedTurns > 0) {
                Text("较早的 ${state.droppedTurns} 轮对话放不下了，已经省略；人物设定一直都在。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.conversations.isNotEmpty()) {
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
            state.streaming?.let { partial -> item { StreamingRow(partial, character) } }
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
private fun StreamingRow(partial: String, character: HearthCharacter) {
    Column(Modifier.fillMaxWidth()) {
        Text("本机 · ${character.modelName}", style = MaterialTheme.typography.labelSmall)
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