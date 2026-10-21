package com.xavierxia.mycelium.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.xavierxia.mycelium.hearth.HearthSpace
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
import com.xavierxia.mycelium.roundtable.ChatReply
import com.xavierxia.mycelium.roundtable.ChatService
import com.xavierxia.mycelium.roundtable.CitationCard
import com.xavierxia.mycelium.roundtable.CloudService
import com.xavierxia.mycelium.roundtable.CloudSelection
import com.xavierxia.mycelium.roundtable.DeepSeekDefaults
import com.xavierxia.mycelium.roundtable.ImportOutcome
import com.xavierxia.mycelium.roundtable.InstalledVersion
import com.xavierxia.mycelium.roundtable.Roundtable
import com.xavierxia.mycelium.roundtable.SqlCipherPackDatabase
import com.xavierxia.mycelium.roundtable.StoredMessage
import com.xavierxia.mycelium.update.AndroidInstallSessionGateway
import com.xavierxia.mycelium.update.CurrentAppFacts
import com.xavierxia.mycelium.update.DownloadFailureReason
import com.xavierxia.mycelium.update.DownloadUiState
import com.xavierxia.mycelium.update.GatewayApkDownloader
import com.xavierxia.mycelium.update.LocalApkVerifier
import com.xavierxia.mycelium.update.PackageManagerApkFactsReader
import com.xavierxia.mycelium.update.ReleaseInfo
import com.xavierxia.mycelium.update.UpdateCenter
import com.xavierxia.mycelium.update.plainReleaseNotes
import com.xavierxia.mycelium.update.UpdateError
import com.xavierxia.mycelium.update.UpdateFiles
import com.xavierxia.mycelium.update.UpdateFormat
import com.xavierxia.mycelium.update.UpdateManager
import com.xavierxia.mycelium.update.UpdatePreferences
import com.xavierxia.mycelium.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Space(val label: String) { Roundtable("圆桌"), Hearth("炉边"), Mine("我的") }

private val roundtableColors = lightColorScheme(primary = Color(0xFF8D4A2B), secondary = Color(0xFF8D4A2B))
private val hearthColors = darkColorScheme(primary = Color(0xFF7EB6A4), secondary = Color(0xFF7EB6A4))

/** 点开了「我的」上的小红点时用的标记；红点本身没有文字，测例靠它断言。 */
internal const val UpdateBadgeTag = "update_badge"

/**
 * 正式入口：状态由 [MainActivity] 造的 [UpdateCenter]（Activity 级 ViewModel）持有。
 *
 * 这个重载**没有默认值**是有意的：两个都带 `= null` 的重载会让 `MyceliumApp()` 的调用歧义
 * （方案审 C3 第 3 条）。
 */
@Composable
fun MyceliumApp(updateCenter: UpdateCenter) {
    MyceliumAppScaffold(updateCenter)
}

/**
 * 第 2 步已提交的重载，`MyceliumAppTest` 按位置传 manager，签名保留不动：内部包一个最小 center。
 *
 * 调试包判定在这里算一次；Robolectric 下 debug 变体的 applicationId 带 `.debug`，所以单测里
 * 不会自动检查（也就不会真的打网络）。
 */
@Composable
fun MyceliumApp(updateManager: UpdateManager? = null) {
    val context = LocalContext.current
    val chat = remember { ChatService(context) }
    val scope = rememberCoroutineScope()
    val center = remember(updateManager, scope) {
        val manager = updateManager ?: UpdateManager(chat.releaseSource())
        val files = UpdateFiles(File(context.cacheDir, "updates"))
        // 第 4 步接线：应用内下载与四项校验都走 ChatService 手上的同一个网关，
        // 审计因此和聊天/检查更新写进同一份 privacy/audit.json。
        val transport = chat.updateTransport()
        val prefs = UpdatePreferences(context)
        UpdateCenter(
            updates = manager,
            prefs = prefs,
            isDebugPackage = BuildConfig.APPLICATION_ID.endsWith(".debug"),
            files = files,
            downloader = GatewayApkDownloader(files, transport),
            verifier = LocalApkVerifier(
                transport = transport,
                archive = PackageManagerApkFactsReader(context),
                installed = CurrentAppFacts(context),
                selfPackageName = BuildConfig.APPLICATION_ID,
            ),
            // 第 5 步接线：会话与回执共用同一份偏好存储，进程重建后还能把结果认回来。
            installer = AndroidInstallSessionGateway(context, prefs),
            sessionState = prefs,
            scope = scope,
        )
    }
    MyceliumAppScaffold(center)
}

@Composable
private fun MyceliumAppScaffold(center: UpdateCenter) {
    val context = LocalContext.current
    val roundtable = remember { Roundtable(context) }
    val chat = remember { ChatService(context) }
    var space by remember { mutableStateOf(Space.Roundtable) }
    var revision by remember { mutableIntStateOf(0) }
    val state by center.state.collectAsState()
    val colors = if (space == Space.Hearth) hearthColors else roundtableColors

    // 打开 App 时按 24 小时节流查一次；UpdateManager 自己在 Dispatchers.IO 上跑，不挡首帧。
    LaunchedEffect(Unit) { center.checkIfDue() }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = { TopBar(space) },
            bottomBar = {
                NavigationBar {
                    Space.entries.forEach { item ->
                        val badged = item == Space.Mine && state.release != null && !center.isDebugPackage
                        NavigationBarItem(
                            selected = space == item,
                            onClick = { space = item },
                            icon = {
                                if (badged) {
                                    // 不带数字的小圆点：只表示「我的」里有事。
                                    BadgedBox(badge = { Badge() }, modifier = Modifier.testTag(UpdateBadgeTag)) {
                                        Text(spaceIcon(item))
                                    }
                                } else {
                                    Text(spaceIcon(item))
                                }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            when (space) {
                Space.Roundtable -> RoundtableScreen(roundtable, chat, revision, padding) { revision++ }
                Space.Hearth -> HearthSpace(padding)
                Space.Mine -> MineScreen(roundtable, chat, center, revision, padding) { revision++ }
            }
        }
    }
}

private fun spaceIcon(item: Space): String = when (item) {
    Space.Roundtable -> "桌"
    Space.Hearth -> "炉"
    Space.Mine -> "我"
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
                notice = failure.message ?: "人物包没有通过校验"
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (busy) "正在校验" else "导入人物包")
        }
        notice?.let { Text(it) }
        if (personas.isEmpty()) {
            Text("在电脑版知衍的人物页导出人物包，传到手机后在这里导入。")
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
private fun MineScreen(
    roundtable: Roundtable,
    chat: ChatService,
    center: UpdateCenter,
    revision: Int,
    padding: PaddingValues,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by center.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<InstalledVersion?>(null) }
    var page by remember { mutableStateOf("packs") }
    var localModels by remember { mutableStateOf(false) }
    // 跳「安装未知应用」设置页。回来立刻复查：允许了就接着装，没允许就停在说明上（工作包第 5 步）。
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        center.onResume()
    }
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
            Text("本地", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { localModels = true }) { Text("本地模型") }
            Text("云端", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { page = "cloud" }) { Text("云端模型") }
        }
        item {
            Button(enabled = !state.checking, onClick = { scope.launch { center.checkNow() } }) {
                if (state.checking) CircularProgressIndicator() else Text(stringResource(R.string.home_check_updates))
            }
        }
        // 失败不打扰：自动检查不填 error，只有手动检查才在这里给人话。
        val updateError = state.error
        if (updateError != null) {
            item { Text(stringResource(R.string.update_failed, updateErrorText(updateError))) }
        }
        // 安装结果的一句话：成功与取消会把卡片收掉，所以它不挂在卡片上（工作包第 5 步）。
        val installNotice = state.installNotice
        if (installNotice != null) {
            item { Text(installNotice) }
        }
        val release = state.release
        if (release != null) {
            item {
                UpdateCard(
                    release = release,
                    installReady = center.installReady,
                    download = state.download,
                    onDownload = { center.startDownload() },
                    onInstall = { center.install() },
                    onCancel = { center.cancelDownload() },
                    onIgnore = { center.ignore(release.tagName) },
                )
            }
        } else {
            item { UpdateText(state.lastResult) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_auto_check))
                Switch(checked = state.autoCheck, onCheckedChange = { center.setAutoCheck(it) })
            }
        }
        item {
            // 「上次检查」显示的是**成功跑完**的时间；失败不会把它推后，也不会把它改掉。
            Text(
                if (state.lastSuccessAtMs == 0L) {
                    stringResource(R.string.update_last_check_never)
                } else {
                    stringResource(R.string.update_last_check, UpdateFormat.lastCheckAt(state.lastSuccessAtMs))
                },
            )
        }
        item {
            TextButton(onClick = { page = "privacy" }) { Text("隐私记录") }
        }
        item { Text("已导入的人物包", style = MaterialTheme.typography.titleMedium) }
        if (installed.isEmpty()) {
            item { Text("还没有导入人物包。在圆桌页选择电脑版导出的文件。") }
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
    // 「安装未知应用」先说明、再带路。只带路，开不开由用户决定；这时还没有建安装会话。
    if (state.download is DownloadUiState.NeedsUnknownSources) {
        AlertDialog(
            onDismissRequest = { center.dismissUnknownSources() },
            title = { Text("需要允许「安装未知应用」") },
            text = {
                Text(
                    "更新包已经下载并校验好了，要交给系统的安装器才能装上。" +
                        "系统要求你先允许本应用安装应用。点「去设置」打开本应用的设置页，" +
                        "打开那个开关再回来，就可以接着装。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    center.unknownSourcesIntent()?.let { settingsLauncher.launch(it) }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { center.dismissUnknownSources() }) { Text("以后再说") }
            },
        )
    }
}

@Composable
private fun UpdateText(result: UpdateResult?) {
    when (result) {
        null -> Unit
        is UpdateResult.UpToDate -> Text(stringResource(R.string.update_latest))
        is UpdateResult.NoPublishedRelease -> Text(stringResource(R.string.update_none_published))
        // 失败在错误行、有新版在卡片里，这里都不重复。
        is UpdateResult.Error -> Unit
        // 有新版但被用户忽略过：什么都不显示，直到出现更新的版本。
        is UpdateResult.Available -> Unit
    }
}

/**
 * 「我的」页顶部的更新卡片：版本号、大小、更新说明（纯文本，太长可展开）、忽略、
 * 以及（接线后）下载并安装。发布页按钮始终在：缺附件、被白名单拒绝、签名读不出都还能点到它。
 *
 * 下载中点「取消」立刻停，进度条按 `written / total` 前进；`total` 未知时是不确定态。
 * 校验失败的人话由 `DownloadUiState.Failed.message` 带上来（第 4 步：要说清是哪一项）。
 */
@Composable
private fun UpdateCard(
    release: ReleaseInfo,
    installReady: Boolean,
    download: DownloadUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onIgnore: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_new_version, release.versionName))
            Text(
                release.apkSizeBytes?.let { stringResource(R.string.update_size, UpdateFormat.mebibytes(it)) }
                    ?: stringResource(R.string.update_size_unknown),
            )
            if (release.releaseNotes.isNotBlank()) {
                UpdateNotes(release.releaseNotes)
            }
            when (download) {
                is DownloadUiState.Running -> {
                    val total = download.total
                    if (total != null && total > 0L) {
                        LinearProgressIndicator(
                            progress = { (download.written.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("下载中 ${UpdateFormat.mebibytes(download.written)} / ${UpdateFormat.mebibytes(total)}")
                    } else {
                        // 服务器没说长度：不确定态，不假装知道百分比。
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("下载中 ${UpdateFormat.mebibytes(download.written)}")
                    }
                }
                is DownloadUiState.Verifying -> Text("正在校验下载的文件…")
                is DownloadUiState.Ready -> Text("下载并校验通过，可以安装。")
                is DownloadUiState.Installing -> Text("正在安装…请在系统的安装界面上点「安装」。")
                is DownloadUiState.NeedsUnknownSources -> Text("要先允许「安装未知应用」，才能把安装包交给系统。")
                is DownloadUiState.NeedsVerification ->
                    Text("这次安装还没确认完成。可以回到系统界面确认，或者稍后再看这里。")
                is DownloadUiState.Failed -> Text(downloadFailureText(download.reason, download.message))
                is DownloadUiState.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 下载/校验/安装三件都接线（且非调试包、两个附件都在）才画这个按钮：
                // 不放按了没用的入口，也不给缺附件的发布画一个装不上的按钮。
                val installable = release.apkUrl != null && release.sha256Url != null
                if (download is DownloadUiState.Running) {
                    Button(onClick = onCancel) { Text("取消") }
                } else if (download is DownloadUiState.Ready) {
                    // 下载并校验通过之后才出现的安装入口（第 5 步）。
                    Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                } else if (installReady && installable && download !is DownloadUiState.Installing && download !is DownloadUiState.Verifying) {
                    Button(onClick = onDownload) { Text(stringResource(R.string.update_download_install)) }
                }
                TextButton(onClick = onIgnore) { Text(stringResource(R.string.update_ignore_version)) }
            }
            if (release.apkUrl == null || release.sha256Url == null) {
                // 真的缺附件才这么说；「还没接线」不冒充「没有安装包」。
                Text(stringResource(R.string.update_no_installable_asset))
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, release.releasePageUrl.toUri()))
            }) {
                Text(stringResource(R.string.update_open_release_page))
            }
        }
    }
}

/** 校验/下载失败的人话：第 4 步的实现给了具体项就用它，否则按类型回退到通用文案。 */
private fun downloadFailureText(reason: DownloadFailureReason, message: String?): String = message ?: when (reason) {
    DownloadFailureReason.ChecksumMismatch -> "校验和不符：下载的文件和发布里的不一致。"
    DownloadFailureReason.NotAllowed -> "下载被拒绝：地址不在允许的下载域名里。"
    DownloadFailureReason.Network -> "网络失败，稍后再试。"
    DownloadFailureReason.Verification -> "校验没通过，下载的文件已经删掉。"
    DownloadFailureReason.Storage -> "存储空间不够。"
    DownloadFailureReason.Installation -> "安装没成功。"
}

/** 更新说明是纯文本（不渲染 Markdown），显示前先去掉标记符号；超过几行折起来，给一个展开入口。 */
@Composable
private fun UpdateNotes(notes: String) {
    var expanded by remember { mutableStateOf(false) }
    val text = remember(notes) { plainReleaseNotes(notes) }
    val foldable = text.length > NOTES_FOLD_LENGTH
    Text(
        text = text,
        maxLines = if (foldable && !expanded) NOTES_COLLAPSED_LINES else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
    if (foldable) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.update_notes_collapse else R.string.update_notes_expand))
        }
    }
}

private const val NOTES_FOLD_LENGTH = 120
private const val NOTES_COLLAPSED_LINES = 4

/** 错误类型到文案只在这里对应：界面认类型，改文案不用碰解析逻辑。 */
@Composable
private fun updateErrorText(error: UpdateError): String = when (error) {
    UpdateError.Network -> stringResource(R.string.update_error_network)
    UpdateError.RateLimited -> stringResource(R.string.update_error_rate_limited)
    is UpdateError.Server -> stringResource(R.string.update_error_server, error.status)
    UpdateError.Malformed -> stringResource(R.string.update_error_malformed)
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
    var services by remember { mutableStateOf(chat.services()) }
    var selection by remember { mutableStateOf(chat.selection()) }
    var picking by remember { mutableStateOf(false) }
    val savedModels = remember(selection, services) {
        services.associate { service ->
            service.id to chat.models(service.id).map { SavedChoice(service, it) }
        }
    }
    Column(modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${version.displayName} · ${version.versionLabel}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                services = chat.services()
                picking = true
            }) {
                val name = selection?.let { choice ->
                    services.firstOrNull { it.id == choice.serviceId }?.name
                }
                Text(if (name == null) "选择模型" else "模型：$name ${selection?.model.orEmpty()}")
            }
        }
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
                    Text("云端 · ${message.provider ?: "云端"} ${message.model.orEmpty()}")
                }
            }
        }
        notice?.let { Text(it) }
        rejection?.let {
            Text("这次回答不合格，没有显示")
            TextButton(onClick = { showReason = !showReason }) { Text(if (showReason) "收起原因" else "展开原因") }
            if (showReason) Text(it)
        }
        OutlinedTextField(question, { question = it }, modifier = Modifier.fillMaxWidth(), label = { Text("问题") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && question.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    notice = null
                    rejection = null
                    try {
                        val (id, reply) = withContext(Dispatchers.IO) {
                            chat.ask(version, conversationId, question, mode, template, rules)
                        }
                        when (reply) {
                            is ChatReply.Shown -> {
                                conversationId = id
                                messages = withContext(Dispatchers.IO) { chat.messages(id) }
                                question = ""
                            }
                            is ChatReply.Rejected -> rejection = reply.reason
                        }
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
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("选择模型") },
            text = {
                val choices = savedModels.values.flatten()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (choices.isEmpty()) {
                        Text("还没有保存模型。在「我的 → 云端模型」里拉取并勾选。")
                    } else {
                        choices.forEach { choice ->
                            val chosen = selection?.serviceId == choice.service.id && selection?.model == choice.model
                            TextButton(onClick = {
                                chat.rememberSelection(choice.service.id, choice.model)
                                selection = chat.selection()
                                picking = false
                            }) { Text(if (chosen) "● ${choice.service.name} · ${choice.model}" else "${choice.service.name} · ${choice.model}") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("关闭") } },
        )
    }
}

private data class SavedChoice(val service: CloudService, val model: String)

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
    val scope = rememberCoroutineScope()
    var services by remember { mutableStateOf(chat.services()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(DeepSeekDefaults.BASE_URL) }
    var key by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pulling by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CloudService?>(null) }
    // Saving models or replacing a key does not change the service list, so the card needs its own
    // signal; without it the card keeps showing the pre-save text.
    var revision by remember { mutableIntStateOf(0) }

    fun startCreate() {
        creating = true
        editing = null
        name = ""
        baseUrl = DeepSeekDefaults.BASE_URL
        key = ""
        hint = null
        notice = null
        fetched = null
        checked = emptySet()
    }

    fun startEdit(service: CloudService) {
        creating = false
        editing = service.id
        name = service.name
        baseUrl = service.baseUrl
        key = ""
        hint = chat.keyHint(service.id)
        notice = null
        fetched = null
        checked = chat.models(service.id).toSet()
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = onBack) { Text("返回") } }
        item { Text("云端模型", style = MaterialTheme.typography.titleMedium) }
        item { Text("每个服务填名称、接口地址和 key。地址默认填 DeepSeek，可以改成别的。") }
        item { Button(onClick = { startCreate() }) { Text("添加服务") } }

        if (creating || editing != null) {
            item {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") })
            }
            item {
                OutlinedTextField(baseUrl, { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("接口地址") })
            }
            item {
                OutlinedTextField(
                    key,
                    { typed -> key = typed },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (hint == null) "API key" else "API key（已保存 $hint，留空则不改）") },
                )
            }
            item { Text("API 数据是否用于训练：${DeepSeekDefaults.TRAINING_POLICY}") }
            item { Text(chat.privacyPolicyUrl(), style = MaterialTheme.typography.bodySmall) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !pulling, onClick = {
                        scope.launch {
                            notice = null
                            try {
                                val serviceId = withContext(Dispatchers.IO) {
                                    chat.addService(editing, name, baseUrl, key.ifBlank { null })
                                }
                                editing = serviceId
                                creating = false
                                key = ""
                                hint = chat.keyHint(serviceId)
                                services = chat.services()
                                revision++
                                pulling = true
                                try {
                                    val models = withContext(Dispatchers.IO) { chat.fetchModels(serviceId) }
                                    fetched = models
                                    checked = chat.models(serviceId).toSet()
                                    if (models.isEmpty()) notice = "这个服务没有返回可用的模型"
                                } finally {
                                    pulling = false
                                }
                            } catch (failure: Exception) {
                                notice = failure.message ?: "这次没有拉到模型"
                            }
                        }
                    }) { Text(if (pulling) "正在拉取" else "拉取模型") }
                    TextButton(onClick = {
                        if (creating) {
                            creating = false
                        } else {
                            editing = null
                        }
                        notice = null
                    }) { Text("取消") }
                }
            }
            item { notice?.let { Text(it) } }
            fetched?.let { models ->
                if (models.isNotEmpty()) {
                    item { Text("勾选要保存的模型，可以多选：") }
                    items(models) { model ->
                        Row {
                            Checkbox(
                                checked = model in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + model else checked - model
                                },
                            )
                            Text(model)
                        }
                    }
                    item {
                        Button(onClick = {
                            val serviceId = editing ?: return@Button
                            chat.saveModels(serviceId, checked.toList())
                            services = chat.services()
                            revision++
                            notice = "已保存 ${checked.size} 个模型"
                        }) { Text("保存勾选的模型") }
                    }
                }
            }
        }

        item { Text("已保存的服务", style = MaterialTheme.typography.titleMedium) }
        if (services.isEmpty()) item { Text("还没有云端服务。点「添加服务」填地址和 key。") }
        items(services, key = { it.id }) { service ->
            val models = remember(service.id, revision) { chat.models(service.id) }
            val keyHint = remember(service.id, revision) { chat.keyHint(service.id) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(service.name, style = MaterialTheme.typography.titleSmall)
                    Text(service.baseUrl)
                    Text("key：${keyHint ?: "未填写"}")
                    Text(if (models.isEmpty()) "还没有保存模型" else "已保存：${models.joinToString("、")}")
                    Row {
                        TextButton(onClick = { startEdit(service) }) { Text("编辑") }
                        TextButton(onClick = { removing = service }) { Text("删除") }
                    }
                }
            }
        }
    }
    removing?.let { service ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除这个服务？") },
            text = { Text("「${service.name}」和它保存的模型列表会一起删掉。") },
            confirmButton = {
                TextButton(onClick = {
                    chat.removeService(service.id)
                    services = chat.services()
                    if (editing == service.id) editing = null
                    removing = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
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
            // 第 1 步起记的是**每一跳的域名链**。旧记录只有一个 host 且 hostPath 为空——那种记录
            // 一定是连上过才写下来的，所以「成功 + 空链」才按旧格式回填；失败且空链表示**一次都没连**，
            // 这时不能再显示成「联系过这个域名」（M11）。
            val hosts = if (entry.hostPath.isEmpty() && entry.succeeded) listOf(entry.host) else entry.hostPath
            val chain = if (hosts.isEmpty()) "未联系任何域名" else hosts.joinToString(" → ")
            Text("${purposeLabel(entry.purpose)} · $chain · HTTP ${entry.status ?: "无"} · ${if (entry.succeeded) "成功" else "失败"}")
            val usage = if (entry.promptTokens != null || entry.completionTokens != null) {
                " · token 用量 ${entry.promptTokens ?: 0} 入 / ${entry.completionTokens ?: 0} 出"
            } else {
                ""
            }
            Text("${entry.sentCharacters} 字$usage")
            // 旧记录没有字节数，就少这一行，不补 0。
            entry.receivedBytes?.let {
                Text(stringResource(R.string.privacy_downloaded_bytes, UpdateFormat.mebibytes(it)))
            }
            if (entry.sentBody.isNotEmpty()) {
                Text(entry.sentBody)
            } else if (!entry.purpose.recordsBody) {
                Text("（测试模式：不记正文）")
            }
        }
    }
}

private fun purposeLabel(purpose: GatewayPurpose): String = when (purpose) {
    GatewayPurpose.Update -> "更新"
    GatewayPurpose.FetchModels -> "拉取模型列表"
    GatewayPurpose.RoundtableChat -> "圆桌聊天"
    GatewayPurpose.HearthChat -> "炉边聊天"
    GatewayPurpose.HearthChatTest -> "炉边 · 测试模式"
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

