package com.worldcopy.agentdeck

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.worldcopy.agentdeck.core.notifications.ConnectionService
import com.worldcopy.agentdeck.feature.hosts.HostsScreen
import com.worldcopy.agentdeck.feature.hosts.KeysScreen
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
        setContent { AgentDeckTheme { AgentDeckApp(notificationTarget, { notificationTarget = null }, sharedInput) { sharedInput = null } } }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDeckApp(target: Pair<String, String>? = null, consumeTarget: () -> Unit = {}, shared: SharedInput? = null, consumeShare: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AgentDeckApplication
    val vm = app.hosts
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    fun startBackground() { context.startForegroundService(Intent(context, ConnectionService::class.java)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startBackground() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(target) { target?.let { (host, thread) ->
        workspaceId = host
        val workspace = app.workspace(host)
        if (workspace.connection == "服务未连接") workspace.openOffline(host)
        // Defer opening until the cached host state is loaded.
        while (workspace.busy) kotlinx.coroutines.delay(50)
        workspace.openSession(JSONObject().put("id", thread))
        consumeTarget()
    } }
    Scaffold(topBar = { TopAppBar(title = { Text("掌舵 · AgentDeck") }, actions = {
        TextButton(onClick = {
            if (ConnectionService.active) context.stopService(Intent(context, ConnectionService::class.java))
            else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startBackground()
        }) { Text(if (ConnectionService.active) "后台同步中" else "后台同步") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0; workspaceId = null }, icon = { Text("⌘") }, label = { Text("主机") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1; workspaceId = null }, icon = { Text("⚿") }, label = { Text("密钥") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2; workspaceId = null }, icon = { Text("…") }, label = { Text("待处理") })
        }
    }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
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
            if (selectedWorkspace?.selected == null) Text("请选择主机，再打开或新建会话。")
        }
        if (workspaceId != null) WorkspaceScreen(app.workspace(workspaceId!!)) { workspaceId = null }
        else when (tab) {
            0 -> HostsScreen(vm) { host -> vm.work {
                val workspace = app.workspace(host.id)
                check(!workspace.busy) { "当前请求尚未结束，请稍后再试" }
                if (vm.statuses[host.id] != "SSH 已连接") workspace.openOffline(host.id)
                else {
                    val port = vm.localPort(host.id)
                    val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        vm.store.vault.get("token-${host.id}")?.toString(Charsets.UTF_8)
                    } ?: error("请在主机设置中填写电脑服务令牌")
                    workspace.connect(host.id, port, token) { vm.reconnectService(host.id) }
                }
                workspaceId = host.id
            } }
            1 -> KeysScreen(vm)
            2 -> androidx.compose.foundation.lazy.LazyColumn {
                item { Text("已连接主机的待处理事项", style = MaterialTheme.typography.titleLarge) }
                app.workspaces.forEach { (hostId, workspace) ->
                    workspace.approvals.forEach { approval -> item(key = approval.getString("id")) {
                        val host = vm.hosts.find { it.id == hostId }
                        TextButton(onClick = {
                            workspaceId = hostId
                            val thread = approval.optJSONObject("params")?.optString("threadId")
                            if (!thread.isNullOrEmpty()) workspace.openSession(JSONObject().put("id", thread))
                        }) { Text("${host?.name ?: "主机"} · 需要处理") }
                    } }
                }
                item { TextButton(onClick = {
                    app.workspaces.values.forEach { it.clearCache() }
                }) { Text("清除阅读缓存（保留凭据和草稿）") } }
            }
        }
    } }
}
