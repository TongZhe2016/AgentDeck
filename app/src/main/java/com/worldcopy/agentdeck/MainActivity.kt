package com.worldcopy.agentdeck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.ui.components.*
import com.worldcopy.agentdeck.ui.theme.DeckNavy
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
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
    override fun onStart() {
        super.onStart()
        (application as AgentDeckApplication).let { app ->
            app.ensureConnectionService()
            app.hosts.syncProjects(refreshConnected = false)
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
    var showBackgroundSettings by rememberSaveable { mutableStateOf(false) }
    val connectionPreferences = remember { context.getSharedPreferences("connection", Context.MODE_PRIVATE) }
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    fun offerBatterySetup() {
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName) &&
            !connectionPreferences.getBoolean("batteryOptimizationAsked", false)) {
            connectionPreferences.edit().putBoolean("batteryOptimizationAsked", true).apply()
            showBackgroundSettings = true
        }
    }
    val pendingCount = app.workspaces.values.sumOf { it.approvals.size }
    BackHandler(enabled = workspaceId == null && tab != 0) { tab = 0 }
    fun navigate(index: Int) { tab = index; workspaceId = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { offerBatterySetup() }
    val batteryPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(vm.hosts.isNotEmpty()) {
        if (vm.hosts.isNotEmpty()) (context as ComponentActivity).lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (Build.VERSION.SDK_INT >= 33 && !connectionPreferences.getBoolean("notificationAsked", false)) {
                connectionPreferences.edit().putBoolean("notificationAsked", true).apply()
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else offerBatterySetup()
            awaitCancellation()
        }
    }
    if (showBackgroundSettings) {
        val unrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        AlertDialog(onDismissRequest = { showBackgroundSettings = false },
            title = { Text("后台持续连接") },
            text = { Text(if (unrestricted)
                "已允许锁屏后保持电脑连接。部分手机还需在应用系统设置中允许后台运行。常驻连接会增加耗电；从最近任务划掉应用即可停止。"
                else "AgentDeck 会自动在后台接收电脑回复和审批。请一次性允许忽略电池优化，以便锁屏后继续连接。常驻连接会增加耗电；从最近任务划掉应用即可停止。") },
            confirmButton = { TextButton(onClick = {
                showBackgroundSettings = false
                val action = if (unrestricted) Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                batteryPermission.launch(Intent(action, Uri.parse("package:${context.packageName}")))
            }) { Text(if (unrestricted) "应用系统设置" else "允许后台运行") } },
            dismissButton = { TextButton(onClick = { showBackgroundSettings = false }) { Text(if (unrestricted) "关闭" else "稍后") } })
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(vm.hosts.map { it.id }) {
        if ((context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true) {
            app.ensureConnectionService()
        }
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
        // Restore the service and cached state before selecting the notification's conversation.
        while (vm.busy || workspace.busy) kotlinx.coroutines.delay(50)
        workspace.openSession(JSONObject().put("id", thread))
        consumeTarget()
    } }
    Box(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            if (workspaceId == null) TopAppBar(title = {
                if (tab != 0) Text(if (tab == 1) "密钥" else "待处理")
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(36.dp), shape = MaterialTheme.shapes.small, color = DeckNavy) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
                    }
                    Text("掌舵", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { navigate(2) }) {
                        BadgedBox(badge = { if (pendingCount > 0) Badge { Text(pendingCount.toString()) } }) {
                            DeckGlyph(DeckIcon.Bell, "待处理")
                        }
                    }
                }
            }, navigationIcon = {
                if (tab != 0) IconButton(onClick = { navigate(0) }) { DeckGlyph(DeckIcon.Back, "返回项目") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), actions = {
                Box {
                    IconButton(onClick = { menu = true }) { DeckGlyph(DeckIcon.More, "更多设置") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("密钥") }, leadingIcon = { DeckGlyph(DeckIcon.Key) },
                            onClick = { navigate(1); menu = false })
                        DropdownMenuItem(text = { Text("后台运行设置") },
                            onClick = { showBackgroundSettings = true; menu = false })
                        if (Build.VERSION.SDK_INT >= 31) DropdownMenuItem(
                            text = { Text(if (dynamicColor) "使用掌舵配色" else "使用系统壁纸配色") },
                            onClick = { changeDynamicColor(!dynamicColor); menu = false })
                        DropdownMenuItem(text = { Text("清除阅读缓存") }, onClick = {
                            app.workspaces.values.forEach { it.clearCache() }; menu = false
                        })
                    }
                }
            })
        }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
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
            0 -> ProjectsScreen(vm, app.workspaces, openWorkspace = { host ->
                val workspace = app.workspace(host.id)
                workspace.browseProject("")
                projectScope = null
                fromProjects = true
                workspaceId = host.id
            }) { group, session ->
                val workspace = app.workspace(group.key.hostId)
                workspace.browseProject(group.key.path)
                if (session != null) workspace.openSession(session)
                projectScope = group.key.path
                fromProjects = true
                workspaceId = group.key.hostId
            }
            1 -> KeysScreen(vm)
            2 -> androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
