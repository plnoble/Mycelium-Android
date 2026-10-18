package com.xavierxia.mycelium.localmodels

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xavierxia.mycelium.llm.LocalModelRecord
import java.util.Locale

private class LocalDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
fun LocalModelsScreen(padding: PaddingValues, onBack: () -> Unit, vm: LocalModelsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val loaded by vm.engine.loadedModel.collectAsStateWithLifecycle()
    var threads by rememberSaveable { mutableStateOf(vm.options.threads.toString()) }
    var context by rememberSaveable { mutableStateOf(vm.options.contextLength.toString()) }
    var maximum by rememberSaveable { mutableStateOf("256") }
    var prompt by rememberSaveable { mutableStateOf("请用中文简短地介绍你自己。") }
    var removing by remember { mutableStateOf<LocalModelRecord?>(null) }
    val picker = rememberLauncherForActivityResult(LocalDocument()) { uri -> if (uri != null) vm.import(uri) }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onBack)
    DisposableEffect(vm) { onDispose { vm.leave() } }

    Box(Modifier.fillMaxSize().padding(padding)) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("返回") }
            Text("本地模型", style = MaterialTheme.typography.headlineSmall)
            Text("选择手机上的 GGUF 文件。导入会校验文件；移除仅删除登记和应用内复制件。")
        }
        item {
            Button(enabled = !state.busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入 GGUF") }
        }
        state.progress?.let { progress ->
            item {
                Text("正在校验 SHA256：${sizeText(progress.bytes)} / ${progress.total?.let(::sizeText) ?: "大小未知"}")
                val total = progress.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (progress.bytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        state.notice?.let { notice -> item { Text(notice) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(threads, { threads = it }, label = { Text("线程数") }, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(context, { context = it }, label = { Text("上下文长度") }, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        }
        if (state.models.isEmpty()) item { Text("还没有本地模型") }
        items(state.models, key = { it.sha256 }) { model ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${sizeText(model.size)} · ${if (model.copied) "应用内复制件" else "读取原文件"}")
                    Text("SHA256：${model.sha256}", style = MaterialTheme.typography.bodySmall)
                    if (loaded?.id == model.sha256) Text("当前已加载")
                    Row {
                        TextButton(enabled = !state.busy, onClick = { vm.load(model, threads, context) }) { Text("加载") }
                        TextButton(enabled = !state.busy, onClick = { removing = model }) { Text("移除") }
                    }
                }
            }
        }
        item {
            Text("试运行", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(prompt, { prompt = it }, label = { Text("测试消息") }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(maximum, { maximum = it }, label = { Text("最多生成 token 数") }, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = loaded != null && !state.busy, onClick = { vm.generate(prompt, maximum) }) { Text("生成") }

                TextButton(enabled = loaded != null && !state.busy, onClick = vm::unload) { Text("卸载") }
            }
            if (state.busy && state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.output.ifEmpty { "生成的正文会逐步显示在这里，思考段默认隐藏。" })
            state.stats?.let { Text("${it.generatedTokens} token · ${String.format(Locale.ROOT, "%.2f", it.tokensPerSecond)} token/秒") }
        }
    }
    if (state.busy) {
        Button(onClick = vm::stop, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("停止") }
    }
    }
    state.copyRequest?.let { (uri, document) ->
        AlertDialog(onDismissRequest = vm::dismissCopy, title = { Text("需要复制模型") },
            text = { Text("文件提供器不支持持久直接读取。复制「${document.name}」将额外占用${document.size?.let(::sizeText) ?: "未知大小的空间（提供器未报告文件大小）"}。原文件会保留。") },
            confirmButton = { TextButton(onClick = { vm.import(uri.toUri(), true) }) { Text("复制并导入") } },
            dismissButton = { TextButton(onClick = vm::dismissCopy) { Text("取消") } })
    }
    removing?.let { model ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除模型？") },
            text = { Text("移除「${model.name}」的登记${if (model.copied) "和应用内复制件" else ""}，原文件会保留。") },
            confirmButton = { TextButton(onClick = { removing = null; vm.remove(model) }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}
private fun sizeText(bytes: Long): String =
    String.format(Locale.ROOT, "%.2f GiB（%,d 字节）", bytes / (1024.0 * 1024 * 1024), bytes)




