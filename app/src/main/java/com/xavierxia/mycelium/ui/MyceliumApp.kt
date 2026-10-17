package com.xavierxia.mycelium.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xavierxia.mycelium.localmodels.LocalModelsScreen
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.R
import com.xavierxia.mycelium.contract.AnswerMode
import com.xavierxia.mycelium.contract.ClaimDetail
import com.xavierxia.mycelium.contract.ClaimSummary
import com.xavierxia.mycelium.contract.SearchResult
import com.xavierxia.mycelium.contract.ThemeSummary
import com.xavierxia.mycelium.net.AuditEntry
import com.xavierxia.mycelium.net.GatewayPurpose
import com.xavierxia.mycelium.roundtable.BudgetExceeded
import com.xavierxia.mycelium.roundtable.ChatReply
import com.xavierxia.mycelium.roundtable.ChatService
import com.xavierxia.mycelium.roundtable.CitationCard
import com.xavierxia.mycelium.roundtable.DeepSeekDefaults
import com.xavierxia.mycelium.roundtable.ImportOutcome
import com.xavierxia.mycelium.roundtable.InstalledVersion
import com.xavierxia.mycelium.roundtable.Roundtable
import com.xavierxia.mycelium.roundtable.SqlCipherPackDatabase
import com.xavierxia.mycelium.roundtable.StoredMessage
import com.xavierxia.mycelium.update.UpdateManager
import com.xavierxia.mycelium.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Space(val label: String) { Roundtable("圆桌"), Hearth("炉边"), Mine("我的") }

private val roundtableColors = lightColorScheme(primary = Color(0xFF8D4A2B), secondary = Color(0xFF8D4A2B))
private val hearthColors = darkColorScheme(primary = Color(0xFF7EB6A4), secondary = Color(0xFF7EB6A4))

@Composable
fun MyceliumApp(updateManager: UpdateManager? = null) {
    val context = LocalContext.current
    val roundtable = remember { Roundtable(context) }
    val chat = remember { ChatService(context) }
    val updates = updateManager ?: remember { UpdateManager(chat.releaseSource()) }
    var space by remember { mutableStateOf(Space.Roundtable) }
    var revision by remember { mutableIntStateOf(0) }
    val colors = if (space == Space.Hearth) hearthColors else roundtableColors

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = { TopBar(space) },
            bottomBar = {
                NavigationBar {
                    Space.entries.forEach { item ->
                        NavigationBarItem(
                            selected = space == item,
                            onClick = { space = item },
                            icon = { Text(if (item == Space.Roundtable) "桌" else if (item == Space.Hearth) "炉" else "我") },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (space) {
                Space.Roundtable -> RoundtableScreen(roundtable, chat, revision, padding) { revision++ }
                Space.Hearth -> HearthScreen(padding)
                Space.Mine -> MineScreen(roundtable, chat, updates, revision, padding) { revision++ }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(space: Space) {
    TopAppBar(title = { Text(if (space == Space.Roundtable) "圆桌" else space.label) })
}

@Composable
private fun RoundtableScreen(
    roundtable: Roundtable,
    chat: ChatService,
    revision: Int,
    padding: PaddingValues,
    onChanged: () -> Unit,
) {
    var selected by remember { mutableStateOf<InstalledVersion?>(null) }
    if (selected == null) {
        PersonaList(roundtable, revision, padding, onChanged) { selected = it }
    } else {
        PersonaScreen(roundtable, chat, selected!!, padding) { selected = null }
    }
}

@Composable
private fun PersonaList(
    roundtable: Roundtable,
    revision: Int,
    padding: PaddingValues,
    onChanged: () -> Unit,
    onOpen: (InstalledVersion) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val personas = remember(revision) { roundtable.personas() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val outcome = withContext(Dispatchers.IO) {
                    roundtable.importUri(uri, context) { bytes -> openBytes(context, bytes) }
                }
                notice = outcome.notice()
                if (outcome is ImportOutcome.Imported) onChanged()
            } catch (failure: Exception) {
                notice = failure.message ?: "角色包没有通过校验"
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (busy) "正在校验" else "导入角色包")
        }
        notice?.let { Text(it) }
        if (personas.isEmpty()) {
            Text("在电脑版知衍的角色页导出角色包，传到手机后在这里导入。")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(personas, key = { it.personaId }) { persona ->
                Card(onClick = { onOpen(persona) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(persona.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${persona.versionLabel} · ${persona.publishedAt.take(10)} · ${persona.claims} 条观点")
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaScreen(
    roundtable: Roundtable,
    chat: ChatService,
    version: InstalledVersion,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    var theme by remember { mutableStateOf<ThemeSummary?>(null) }
    var claimId by remember { mutableStateOf<String?>(null) }
    var chatting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row {
            TextButton(onClick = {
                when {
                    chatting -> chatting = false
                    claimId != null -> claimId = null
                    theme != null -> theme = null
                    else -> onBack()
                }
            }) { Text("返回") }
            if (!chatting) TextButton(onClick = { chatting = true }) { Text("聊天") }
        }
        if (chatting) {
            ChatScreen(chat, version, Modifier.padding(horizontal = 16.dp))
            return
        }
        Text(version.displayName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "${version.versionLabel} · 发布 ${version.publishedAt.take(10)} · 截至 ${version.dataCutoff.take(10)} · ${version.claims} 条观点 · ${version.themes} 个主题 · ${version.sources} 个来源",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (claimId != null) {
            ClaimScreen(roundtable, version, claimId!!)
            return
        }
        if (theme != null) {
            ClaimList(roundtable, version, theme!!.themeId) { claimId = it }
            return
        }
        PrimaryTabRow(selectedTabIndex = page) {
            listOf("主题", "观点", "搜索").forEachIndexed { index, label ->
                Tab(selected = page == index, onClick = { page = index }, text = { Text(label) })
            }
        }
        when (page) {
            0 -> ThemeList(roundtable, version) { theme = it }
            1 -> ClaimList(roundtable, version, null) { claimId = it }
            else -> SearchPage(roundtable, version) { claimId = it }
        }
    }
}

@Composable
private fun ThemeList(roundtable: Roundtable, version: InstalledVersion, onOpen: (ThemeSummary) -> Unit) {
    var themes by remember { mutableStateOf<List<ThemeSummary>>(emptyList()) }
    LaunchedEffect(version.versionId) {
        themes = withContext(Dispatchers.IO) { roundtable.themes(version, ::openFile) }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(themes, key = { it.themeId }) { theme ->
            Card(onClick = { onOpen(theme) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(theme.name, style = MaterialTheme.typography.titleMedium)
                    Text(theme.summary)
                    Text("${theme.claimCount} 条观点")
                }
            }
        }
    }
}

@Composable
private fun ClaimList(
    roundtable: Roundtable,
    version: InstalledVersion,
    themeId: String?,
    onOpen: (String) -> Unit,
) {
    var claims by remember { mutableStateOf<List<ClaimSummary>>(emptyList()) }
    LaunchedEffect(version.versionId, themeId) {
        claims = withContext(Dispatchers.IO) { roundtable.claims(version, themeId, ::openFile) }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(claims, key = { it.claimId }) { claim ->
            Card(onClick = { onOpen(claim.claimId) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(claimKind(claim.kind))
                    Text(claim.text)
                    Text(claim.sourceTitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SearchPage(roundtable: Roundtable, version: InstalledVersion, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("关键词") })
        Button(onClick = {
            scope.launch {
                try {
                    error = null
                    result = withContext(Dispatchers.IO) { roundtable.search(version, query, ::openFile) }
                } catch (failure: Exception) {
                    result = null
                    error = failure.message
                }
            }
        }) { Text("搜索") }
        error?.let { Text(it) }
        result?.let { found ->
            Text(found.hint)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found.hits, key = { it.rank }) { hit ->
                    Card(
                        onClick = {
                            scope.launch {
                                val claim = withContext(Dispatchers.IO) {
                                    roundtable.claimForHit(version, hit.documentKey, ::openFile)
                                }
                                claim?.let(onOpen)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(hit.title)
                            val excerpt = stripHighlight(hit.excerpt)
                            if (excerpt.isNotEmpty() && excerpt != hit.title) Text(excerpt)
                            Text(hit.sourcePublishedAt?.take(10) ?: "无来源日期")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimScreen(roundtable: Roundtable, version: InstalledVersion, claimId: String) {
    var detail by remember { mutableStateOf<ClaimDetail?>(null) }
    LaunchedEffect(claimId) {
        detail = withContext(Dispatchers.IO) { roundtable.claim(version, claimId, ::openFile) }
    }
    val claim = detail ?: return
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(claimKind(claim.kind)) }
        item { Text(claim.text, style = MaterialTheme.typography.titleMedium) }
        item { Text("所属主题：${claim.themes.joinToString("、").ifEmpty { "无" }}") }
        item { Text("原文依据", style = MaterialTheme.typography.titleSmall) }
        items(claim.evidence) { item ->
            Text(item.text)
            Text("${item.sourceTitle} · ${item.platform ?: "未注明平台"} · ${(item.publishedAt ?: "").take(10)}")
        }
        item { Text("与其他观点的关系", style = MaterialTheme.typography.titleSmall) }
        items(claim.relations) { relation ->
            Text("${relationLabel(relation.kind)} · ${relation.direction}「${relation.otherText}」")
            Text(relation.rationale)
        }
    }
}

@Composable
private fun HearthScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("炉边", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("炉边在下一阶段开发：只用手机上的模型，不联网。")
    }
}

@Composable
private fun MineScreen(
    roundtable: Roundtable,
    chat: ChatService,
    updateManager: UpdateManager,
    revision: Int,
    padding: PaddingValues,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateResult?>(null) }
    var pendingDelete by remember { mutableStateOf<InstalledVersion?>(null) }
    var page by remember { mutableStateOf("packs") }
    var localModels by remember { mutableStateOf(false) }
    if (localModels) {
        LocalModelsScreen(padding, onBack = { localModels = false })
        return
    }
    val installed = remember(revision) { roundtable.installed() }
    if (page == "cloud") {
        CloudSettingsScreen(chat, padding) { page = "packs" }
        return
    }
    if (page == "privacy") {
        PrivacyLogScreen(chat, padding) { page = "packs" }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") }
        item {
            Text("模型", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { localModels = true }) { Text("本地模型") }
        }
        item {
            Button(enabled = !checking, onClick = {
                scope.launch {
                    checking = true
                    try {
                        update = updateManager.checkForUpdate()
                    } finally {
                        checking = false
                    }
                }
            }) {
                if (checking) CircularProgressIndicator() else Text(stringResource(R.string.home_check_updates))
            }
        }
        item { UpdateText(update, context) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { page = "cloud" }) { Text("云端模型") }
                TextButton(onClick = { page = "privacy" }) { Text("隐私记录") }
            }
        }
        item { Text("已导入的角色包", style = MaterialTheme.typography.titleMedium) }
        if (installed.isEmpty()) {
            item { Text("还没有导入角色包。在圆桌页选择电脑版导出的文件。") }
        }
        items(installed, key = { "${it.personaId}/${it.versionId}" }) { version ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${version.displayName} · ${version.versionLabel}")
                    Text("导入于 ${version.importedAt.take(16).replace('T', ' ')} · ${version.bytes / 1024} KB")
                    TextButton(onClick = { pendingDelete = version }) { Text("删除") }
                }
            }
        }
    }
    pendingDelete?.let { version ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个版本？") },
            text = { Text("${version.displayName} ${version.versionLabel} 会从手机上移除。") },
            confirmButton = {
                TextButton(onClick = {
                    roundtable.delete(version)
                    pendingDelete = null
                    onChanged()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun UpdateText(result: UpdateResult?, context: Context) {
    when (result) {
        null -> Unit
        is UpdateResult.UpToDate -> Text(stringResource(R.string.update_latest))
        is UpdateResult.NoPublishedRelease -> Text(stringResource(R.string.update_none_published))
        is UpdateResult.Error -> Text(stringResource(R.string.update_failed, result.message))
        is UpdateResult.Available -> {
            Text(stringResource(R.string.update_new_version, result.release.versionName))
            Row {
                Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.release.apkDownloadUrl))) }) {
                    Text(stringResource(R.string.update_open_apk))
                }
            }
        }
    }
}

private fun ImportOutcome.notice(): String = when (this) {
    is ImportOutcome.Imported -> if (added != null && removed != null) {
        "${version.displayName}更新：新增 $added 条观点、移除 $removed 条"
    } else {
        "已导入${version.displayName}"
    }
    is ImportOutcome.AlreadyImported -> "已经导入过"
    is ImportOutcome.Rejected -> message
}

@Composable
private fun ChatScreen(chat: ChatService, version: InstalledVersion, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val template = remember { context.assets.open("contract/answer/system-prompt.zh.txt").bufferedReader().use { it.readText() } }
    val rules = remember { context.assets.open("contract/answer/mode-rules.zh.json").bufferedReader().use { it.readText() } }
    var mode by remember { mutableStateOf(AnswerMode.ThoughtInference) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<StoredMessage>>(emptyList()) }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var rejection by remember { mutableStateOf<String?>(null) }
    var showReason by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<CitationCard?>(null) }
    var estimate by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${version.displayName} · ${version.versionLabel}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AnswerMode.ThoughtInference to "允许推演",
                AnswerMode.StrictEvidence to "只说有依据的",
                AnswerMode.RoleExpression to "人物表达",
            ).forEach { (value, label) ->
                TextButton(onClick = { mode = value }) { Text(if (mode == value) "● $label" else label) }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                if (message.role == "user") {
                    Text(message.text, style = MaterialTheme.typography.titleSmall)
                } else {
                    AnswerText(message.text) { mark -> cardFrom(message, mark)?.let { card = it } }
                    Text("云端 · ${message.provider ?: "DeepSeek"} ${message.model.orEmpty()}")
                }
            }
        }
        notice?.let { Text(it) }
        rejection?.let {
            Text("这次回答不合格，没有显示")
            TextButton(onClick = { showReason = !showReason }) { Text(if (showReason) "收起原因" else "展开原因") }
            if (showReason) Text(it)
        }
        estimate?.let { Text(it) }
        OutlinedTextField(question, { question = it }, modifier = Modifier.fillMaxWidth(), label = { Text("问题") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && question.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    notice = null
                    rejection = null
                    try {
                        val preview = withContext(Dispatchers.IO) { chat.previewCost(version, question, mode, template, rules) }
                        estimate = "本次估算不超过 ${"%.4f".format(preview.usd)} 美元"
                        val (id, reply) = withContext(Dispatchers.IO) {
                            chat.ask(version, conversationId, question, mode, template, rules)
                        }
                        when (reply) {
                            is ChatReply.Shown -> {
                                conversationId = id
                                messages = withContext(Dispatchers.IO) { chat.messages(id) }
                                question = ""
                                estimate = "本次估算 ${"%.4f".format(reply.estimatedCostUsd)} 美元"
                            }
                            is ChatReply.Rejected -> rejection = reply.reason
                        }
                    } catch (over: BudgetExceeded) {
                        notice = over.message
                    } catch (failure: Exception) {
                        notice = failure.message ?: "这次没有发出去"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "正在回答" else "发送") }
            if (conversationId != null) {
                TextButton(onClick = {
                    val id = conversationId ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) { chat.delete(id) }
                        conversationId = null
                        messages = emptyList()
                    }
                }) { Text("删除这段对话") }
            }
        }
    }
    card?.let { citation ->
        AlertDialog(
            onDismissRequest = { card = null },
            title = { Text(citation.title) },
            text = { Text("${stripHighlight(citation.excerpt)}\n${citation.publishedAt?.take(10) ?: "无来源日期"}") },
            confirmButton = { TextButton(onClick = { card = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun AnswerText(rendered: String, onCitation: (String) -> Unit) {
    val marks = Regex("\\[C\\d+\\]")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rendered.split("\n\n").forEach { paragraph ->
            Text(paragraph.replace(marks, "").trim())
            Row {
                marks.findAll(paragraph).forEach { match ->
                    TextButton(onClick = { onCitation(match.value.removeSurrounding("[", "]")) }) {
                        Text(match.value)
                    }
                }
            }
        }
    }
}

private fun cardFrom(message: StoredMessage, citationId: String): CitationCard? {
    val array = org.json.JSONArray(message.citationsJson)
    for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        if (item.getString("id") != citationId) continue
        return CitationCard(
            citationId,
            "",
            item.optString("title"),
            item.optString("excerpt"),
            if (item.isNull("publishedAt")) null else item.optString("publishedAt"),
        )
    }
    return null
}

@Composable
private fun CloudSettingsScreen(chat: ChatService, padding: PaddingValues, onBack: () -> Unit) {
    val current = remember { chat.settings() }
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var model by remember { mutableStateOf(current.model) }
    var input by remember { mutableStateOf(current.prices.inputPerMillion.toString()) }
    var output by remember { mutableStateOf(current.prices.outputPerMillion.toString()) }
    var limit by remember { mutableStateOf(current.monthlyLimitUsd.toString()) }
    var key by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(chat.keyHint()) }
    var notice by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = onBack) { Text("返回") } }
        item { Text("云端模型", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(baseUrl, { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("接口地址") }) }
        item { OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名") }) }
        item {
            OutlinedTextField(
                key,
                { typed -> key = typed },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hint == null) "API key" else "API key（已保存 $hint，留空则不改）") },
            )
        }
        item { OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("输入单价（美元 / 百万 token）") }) }
        item { OutlinedTextField(output, { output = it }, modifier = Modifier.fillMaxWidth(), label = { Text("输出单价（美元 / 百万 token）") }) }
        item { OutlinedTextField(limit, { limit = it }, modifier = Modifier.fillMaxWidth(), label = { Text("每月上限（美元）") }) }
        item { Text("API 数据是否用于训练：${DeepSeekDefaults.TRAINING_POLICY}") }
        item { Text(chat.privacyPolicyUrl(), style = MaterialTheme.typography.bodySmall) }
        item {
            Button(onClick = {
                val inputPrice = input.toDoubleOrNull()
                val outputPrice = output.toDoubleOrNull()
                val monthly = limit.toDoubleOrNull()
                if (inputPrice == null || outputPrice == null || monthly == null) {
                    notice = "单价和上限要填数字"
                    return@Button
                }
                chat.saveSettings(baseUrl, model, inputPrice, outputPrice, monthly, key.ifBlank { null })
                key = ""
                hint = chat.keyHint()
                notice = "已保存"
            }) { Text("保存") }
        }
        item { notice?.let { Text(it) } }
    }
}

@Composable
private fun PrivacyLogScreen(chat: ChatService, padding: PaddingValues, onBack: () -> Unit) {
    var entries by remember { mutableStateOf(chat.audit()) }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = onBack) { Text("返回") } }
        item { Text("隐私记录", style = MaterialTheme.typography.titleMedium) }
        item {
            TextButton(onClick = {
                chat.clearAudit()
                entries = emptyList()
            }) { Text("清空") }
        }
        if (entries.isEmpty()) item { Text("还没有出网记录。") }
        items(entries.reversed()) { entry -> AuditCard(entry) }
    }
}

@Composable
private fun AuditCard(entry: AuditEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.at.take(19).replace('T', ' '))
            Text("${purposeLabel(entry.purpose)} · ${entry.host} · HTTP ${entry.status ?: "无"} · ${if (entry.succeeded) "成功" else "失败"}")
            Text("${entry.sentCharacters} 字 · 估算 ${"%.4f".format(entry.estimatedCostUsd)} 美元")
            if (entry.sentBody.isNotEmpty()) Text(entry.sentBody)
        }
    }
}

private fun purposeLabel(purpose: GatewayPurpose): String = when (purpose) {
    GatewayPurpose.Update -> "更新"
    GatewayPurpose.RoundtableChat -> "圆桌聊天"
}

/** FTS marks the matched span with brackets. The card shows the words, not the markers. */
private fun stripHighlight(excerpt: String): String = excerpt.replace("[", "").replace("]", "")

private fun claimKind(kind: String): String = when (kind) {
    "observation" -> "观察"
    "method" -> "方法"
    "heuristic" -> "经验"
    "attitude" -> "态度"
    else -> kind
}

private fun relationLabel(kind: String): String = when (kind) {
    "supports" -> "支撑"
    "refines" -> "细化"
    else -> kind
}

private fun openFile(file: File) = SqlCipherPackDatabase(file.path)

private fun openBytes(context: Context, bytes: ByteArray): com.xavierxia.mycelium.contract.PackDatabase {
    val directory = File(context.cacheDir, "pack-check").apply { mkdirs() }
    val file = File.createTempFile("check-", ".sqlite", directory)
    file.writeBytes(bytes)
    return DeletingDatabase(file)
}

/** Deletes the temporary file after the one read that validates the pack. */
private class DeletingDatabase(private val file: File) : com.xavierxia.mycelium.contract.PackDatabase {
    override fun <T> read(block: (com.xavierxia.mycelium.contract.PackQuery) -> T): T =
        try {
            SqlCipherPackDatabase(file.path).read(block)
        } finally {
            file.delete()
        }
}

