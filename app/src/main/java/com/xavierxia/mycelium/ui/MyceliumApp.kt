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
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.R
import com.xavierxia.mycelium.contract.ClaimDetail
import com.xavierxia.mycelium.contract.ClaimSummary
import com.xavierxia.mycelium.contract.SearchResult
import com.xavierxia.mycelium.contract.ThemeSummary
import com.xavierxia.mycelium.roundtable.ImportOutcome
import com.xavierxia.mycelium.roundtable.InstalledVersion
import com.xavierxia.mycelium.roundtable.Roundtable
import com.xavierxia.mycelium.roundtable.SqlCipherPackDatabase
import com.xavierxia.mycelium.update.UpdateManager
import com.xavierxia.mycelium.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Space(val label: String) { Roundtable("圆桌"), Companion("伴侣"), Mine("我的") }

private val roundtableColors = lightColorScheme(primary = Color(0xFF8D4A2B), secondary = Color(0xFF8D4A2B))
private val companionColors = darkColorScheme(primary = Color(0xFF7EB6A4), secondary = Color(0xFF7EB6A4))

@Composable
fun MyceliumApp(updateManager: UpdateManager = remember { UpdateManager() }) {
    val context = LocalContext.current
    val roundtable = remember { Roundtable(context) }
    var space by remember { mutableStateOf(Space.Roundtable) }
    var revision by remember { mutableIntStateOf(0) }
    val colors = if (space == Space.Companion) companionColors else roundtableColors

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = { TopBar(space) },
            bottomBar = {
                NavigationBar {
                    Space.entries.forEach { item ->
                        NavigationBarItem(
                            selected = space == item,
                            onClick = { space = item },
                            icon = { Text(if (item == Space.Roundtable) "桌" else if (item == Space.Companion) "伴" else "我") },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (space) {
                Space.Roundtable -> RoundtableScreen(roundtable, revision, padding) { revision++ }
                Space.Companion -> CompanionScreen(padding)
                Space.Mine -> MineScreen(roundtable, updateManager, revision, padding) { revision++ }
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
    revision: Int,
    padding: PaddingValues,
    onChanged: () -> Unit,
) {
    var selected by remember { mutableStateOf<InstalledVersion?>(null) }
    if (selected == null) {
        PersonaList(roundtable, revision, padding, onChanged) { selected = it }
    } else {
        PersonaScreen(roundtable, selected!!, padding) { selected = null }
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
            val outcome = withContext(Dispatchers.IO) {
                roundtable.importUri(uri, context) { bytes -> openBytes(context, bytes) }
            }
            notice = outcome.notice()
            busy = false
            if (outcome is ImportOutcome.Imported) onChanged()
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
private fun PersonaScreen(roundtable: Roundtable, version: InstalledVersion, padding: PaddingValues, onBack: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var theme by remember { mutableStateOf<ThemeSummary?>(null) }
    var claimId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        TextButton(onClick = {
            when {
                claimId != null -> claimId = null
                theme != null -> theme = null
                else -> onBack()
            }
        }) { Text("返回") }
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
                            Text(hit.excerpt)
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
private fun CompanionScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("伴侣", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("伴侣空间在下一阶段开发：只用手机上的模型，不联网。")
    }
}

@Composable
private fun MineScreen(
    roundtable: Roundtable,
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
    val installed = remember(revision) { roundtable.installed() }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") }
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
        item { Text("已导入的角色包", style = MaterialTheme.typography.titleMedium) }
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
