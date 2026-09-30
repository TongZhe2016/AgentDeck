package com.worldcopy.agentdeck

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.ui.components.*
import com.worldcopy.agentdeck.ui.theme.DeckNavy
import androidx.compose.ui.platform.LocalContext
import com.worldcopy.agentdeck.core.notifications.ConnectionService
import com.worldcopy.agentdeck.feature.hosts.HostsScreen
import com.worldcopy.agentdeck.feature.hosts.KeysScreen
import com.worldcopy.agentdeck.feature.hosts.HostIdentityDialog
import com.worldcopy.agentdeck.feature.projects.ProjectsScreen
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONObject

data class SharedInput(val text: String, val images: List<android.net.Uri>)

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<Pair<String, String>?>(null)
    private var sharedInput by mutableStateOf<SharedInput?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readTarget(intent)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var dynamicColor by rememberSaveable { mutableStateOf(preferences.getBoolean("dynamicColor", false)) }
            AgentDeckTheme(dynamicColor = dynamicColor) {
                AgentDeckApp(notificationTarget, { notificationTarget = null }, sharedInput, { sharedInput = null }, dynamicColor) {
                    dynamicColor = it
                    preferences.edit().putBoolean("dynamicColor", it).apply()
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readTarget(intent) }
    private fun readTarget(intent: Intent) {
        val host = intent.getStringExtra("hostId"); val thread = intent.getStringExtra("threadId")
        if (host != null && thread != null) notificationTarget = host to thread
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val images = if (intent.type?.startsWith("image/") == true) {
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
                else listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            } else emptyList()
            sharedInput = SharedInput(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "", images.take(4))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentDeckApp(target: Pair<String, String>? = null, consumeTarget: () -> Unit = {}, shared: SharedInput? = null, consumeShare: () -> Unit = {}, dynamicColor: Boolean = false, changeDynamicColor: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDeckApplication
    val vm = app.hosts
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fromProjects by rememberSaveable { mutableStateOf(false) }
    var projectScope by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    val pendingCount = app.workspaces.values.sumOf { it.approvals.size }
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = workspaceId == null && tab != 0) { tab = 0 }
    fun navigate(index: Int) { tab = index; workspaceId = null }
    fun startBackground() { context.startForegroundService(Intent(context, ConnectionService::class.java)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startBackground() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(vm.hosts.map { it.id }) {
        vm.hosts.forEach { host ->
            val workspace = app.workspace(host.id)
            if (workspace.connection == "服务未连接") workspace.openOffline(host.id)
        }
    }
    HostIdentityDialog(vm)
    LaunchedEffect(target) { target?.let { (host, thread) ->
        workspaceId = host
        fromProjects = true
        projectScope = null
        val workspace = app.workspace(host)
        if (workspace.connection == "服务未连接") workspace.openOffline(host)
        // Defer opening until the cached host state is loaded.
        while (workspace.busy) kotlinx.coroutines.delay(50)
        workspace.openSession(JSONObject().put("id", thread))
        consumeTarget()
    } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Scaffold(topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(36.dp), shape = MaterialTheme.shapes.small, color = DeckNavy) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
                    }
                    Text("掌舵", style = MaterialTheme.typography.titleLarge)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), actions = {
                TextButton(onClick = {
                    if (ConnectionService.active) context.stopService(Intent(context, ConnectionService::class.java))
                    else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startBackground()
                }) {
                    DeckGlyph(if (ConnectionService.active) DeckIcon.Check else DeckIcon.Sync, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (ConnectionService.active) "后台同步中" else "后台同步")
                }
                Box {
                    IconButton(onClick = { menu = true }) { DeckGlyph(DeckIcon.More, "更多设置") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (Build.VERSION.SDK_INT >= 31) DropdownMenuItem(
                            text = { Text(if (dynamicColor) "使用掌舵配色" else "使用系统壁纸配色") },
                            onClick = { changeDynamicColor(!dynamicColor); menu = false })
                        DropdownMenuItem(text = { Text("清除阅读缓存") }, onClick = {
                            app.workspaces.values.forEach { it.clearCache() }; menu = false
                        })
                    }
                }
            })
        }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (!wide && !imeVisible) DeckNavigation(tab, pendingCount, false, ::navigate)
        }) { padding ->
            Row(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                if (wide) DeckNavigation(tab, pendingCount, true, ::navigate)
                Column(Modifier.weight(1f).fillMaxHeight()) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        shared?.let { input ->
            val selectedWorkspace = workspaceId?.let { app.workspaces[it] }
            Row {
                TextButton(onClick = {
                    selectedWorkspace?.importShare(input.text, input.images)
                    consumeShare()
                }, enabled = selectedWorkspace?.selected != null && !selectedWorkspace.busy) { Text("将分享内容加入当前会话") }
                TextButton(onClick = consumeShare) { Text("取消分享") }
            }
            if (selectedWorkspace?.selected == null) Text("请选择项目，再打开或新建会话。")
        }
        if (workspaceId != null) WorkspaceScreen(app.workspace(workspaceId!!), fromProjects, projectScope) { workspaceId = null; if (fromProjects) tab = 0 }
        else when (tab) {
            0 -> ProjectsScreen(vm.hosts, app.workspaces, vm.busy, vm::syncProjects, { tab = 1 }) { group, session ->
                val workspace = app.workspace(group.key.hostId)
                workspace.browseProject(group.key.path)
                if (session != null) workspace.openSession(session)
                projectScope = group.key.path
                fromProjects = true
                workspaceId = group.key.hostId
            }
            1 -> HostsScreen(vm) { host -> vm.work {
                val workspace = app.workspace(host.id)
                check(!workspace.busy) { "当前请求尚未结束，请稍后再试" }
                if (vm.statuses[host.id] != "SSH 已连接") workspace.openOffline(host.id)
                else {
                    val port = vm.localPort(host.id)
                    val token = vm.serviceToken(host.id)
                    workspace.connect(host.id, port, token) { vm.reconnectService(host.id) }
                }
                fromProjects = false
                projectScope = null
                workspaceId = host.id
            } }
            2 -> KeysScreen(vm)
            3 -> androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { PageHeading("待处理事项", "$pendingCount 项需要你的确认") }
                if (pendingCount == 0) item {
                    EmptyState(DeckIcon.Inbox, "暂无待处理事项", "已连接主机的审批与问题会汇总到这里。")
                }
                app.workspaces.forEach { (hostId, workspace) ->
                    workspace.approvals.forEach { approval -> item(key = "$hostId:${approval.getString("id")}") {
                        val host = vm.hosts.find { it.id == hostId }
                        Card(onClick = {
                            fromProjects = true
                            projectScope = null
                            workspaceId = hostId
                            val thread = approval.optJSONObject("params")?.optString("threadId")
                            if (!thread.isNullOrEmpty()) workspace.openSession(JSONObject().put("id", thread))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconTile(DeckIcon.Inbox)
                                Column(Modifier.weight(1f)) {
                                    Text(host?.name ?: "主机", style = MaterialTheme.typography.titleMedium)
                                    Text("需要处理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DeckChevron(false)
                            }
                        }
                    } }
                }
            }
        }
                }
            }
        }
    }
}

@Composable
private fun DeckNavigation(selected: Int, pending: Int, rail: Boolean, navigate: (Int) -> Unit) {
    val destinations = listOf("项目" to DeckIcon.Folder, "主机" to DeckIcon.Computer, "密钥" to DeckIcon.Key, "待处理" to DeckIcon.Inbox)
    @Composable fun glyph(index: Int) {
        BadgedBox(badge = { if (index == 3 && pending > 0) Badge { Text(pending.toString()) } }) { DeckGlyph(destinations[index].second) }
    }
    if (rail) NavigationRail(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
        destinations.forEachIndexed { index, (label, _) ->
            NavigationRailItem(selected = selected == index, onClick = { navigate(index) }, icon = { glyph(index) },
                label = { Text(label) }, modifier = Modifier.testTag("nav-$index"))
        }
    } else NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        destinations.forEachIndexed { index, (label, _) ->
            NavigationBarItem(selected = selected == index, onClick = { navigate(index) }, icon = { glyph(index) },
                label = { Text(label) }, modifier = Modifier.testTag("nav-$index"))
        }
    }
}
