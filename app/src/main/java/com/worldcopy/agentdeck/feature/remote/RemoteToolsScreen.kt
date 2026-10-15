package com.worldcopy.agentdeck.feature.remote

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.worldcopy.agentdeck.core.model.Host
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteToolsScreen(vm: RemoteToolsViewModel, target: Host, openWorkspace: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var installDialog by remember { mutableStateOf(false) }
    var leaveDialog by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<RemoteEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        download?.let { entry -> if (uri != null) vm.download(entry, uri) }; download = null
    }
    fun leave() { if (vm.installing) leaveDialog = true else { vm.close(); back() } }
    BackHandler { leave() }
    LaunchedEffect(target.id) { vm.connect(target) }
    // Keep the SSH session through rotation; leaving this screen closes it.
    DisposableEffect(vm) { onDispose { if ((context as? android.app.Activity)?.isChangingConfigurations != true) vm.close() } }
    if (leaveDialog) AlertDialog(onDismissRequest = { leaveDialog = false }, title = { Text("安装正在进行") },
        text = { Text("离开会断开安装连接。电脑可能已经完成部分安装，请先查看安装结果。") },
        confirmButton = { TextButton(onClick = { leaveDialog = false }) { Text("继续查看") } },
        dismissButton = { TextButton(onClick = { vm.close(); back() }) { Text("断开并离开") } })
    vm.confirmation?.let { key ->
        AlertDialog(onDismissRequest = vm::dismissConfirmation, title = { Text(if (key.changed) "主机身份发生变化" else "确认主机身份") },
            text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) { Text("${target.username}@${target.address}:${target.port}"); Text(key.fingerprint); Text("请与电脑上的 SSH 主机公钥核对后再信任。") } } },
            confirmButton = { TextButton(onClick = vm::trust) { Text("已核对，信任并连接") } },
            dismissButton = { TextButton(onClick = vm::dismissConfirmation) { Text("取消") } })
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Text("${target.name} · SSH 工具", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = { leave() }) { Text("返回") } },
            actions = { if (vm.connected) TextButton(onClick = vm::reconnect, enabled = !vm.installing && vm.transfer == null) { Text("重连") } })
        if (!vm.connected) {
            Column(Modifier.padding(16.dp)) {
                Text("直接连接 SSH，无需电脑预装 AgentDeck 服务。")
                Button(onClick = { vm.connect(target) }, enabled = !vm.connecting) { Text(if (vm.connecting) "正在连接…" else "连接 SSH") }
            }
        }
        vm.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::dismissError) { Text("关闭") }
        } }
        vm.notice?.let { notice -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(notice, Modifier.weight(1f))
            TextButton(onClick = vm::dismissNotice) { Text("关闭") }
        } }
        if (vm.connecting || vm.listing || vm.transfer != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.transfer?.let { label -> Row(Modifier.padding(horizontal = 16.dp)) {
            Text(label, Modifier.weight(1f))
            if (!vm.installing) TextButton(onClick = vm::cancelDownload) { Text("取消下载") }
        } }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("终端", "SFTP 文件", "安装服务").forEachIndexed { index, label -> FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) }) }
        }
        if (vm.connected) when (tab) {
            0 -> key(vm.connectionGeneration) { TerminalPane(vm, Modifier.weight(1f)) }
            1 -> {
                var requested by remember(vm.path) { mutableStateOf(vm.path) }
                OutlinedTextField(requested, { requested = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("remote-path"),
                    label = { Text("远程目录") }, singleLine = true)
                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { vm.browse(requested) }, enabled = !vm.listing && vm.transfer == null) { Text("前往") }
                    TextButton(onClick = { vm.browse("..") }, enabled = !vm.listing && vm.transfer == null) { Text("上一级") }
                    TextButton(onClick = { vm.browse("~") }, enabled = !vm.listing && vm.transfer == null) { Text("主目录") }
                    TextButton(onClick = { vm.browse(vm.path) }, enabled = !vm.listing && vm.transfer == null) { Text("刷新") }
                }
                LazyColumn(Modifier.weight(1f).testTag("remote-files"), contentPadding = PaddingValues(16.dp)) {
                    if (vm.entries.isEmpty() && !vm.listing) item { Text("目录为空") }
                    items(vm.entries, key = { it.path }) { entry ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = vm.transfer == null && !vm.listing && (entry.directory || entry.downloadable)) {
                            if (entry.directory) vm.browse(entry.path) else { download = entry; picker.launch(entry.name) }
                        }.padding(vertical = 12.dp)) {
                            Text((if (entry.directory) "文件夹 · " else "文件 · ") + entry.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (entry.directory) "点击打开" else if (!entry.downloadable) "特殊文件或失效链接" else "${entry.size} 字节 · ${DateFormat.getDateTimeInstance().format(Date(entry.modified * 1000))} · 点击下载",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
            2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("安装 AgentDeck 电脑服务", style = MaterialTheme.typography.titleLarge)
                Text("将手机内置安装包通过 SFTP 上传到此 SSH 账号，在用户目录安装并启动服务。支持 macOS 和 Ubuntu/systemd Linux（x64、ARM64）。")
                Text("缺少 Node.js 24 或 Codex CLI 时，将联网下载到 ~/.local/share/agentdeck；不需要 sudo。macOS 需要该账号已登录桌面，Ubuntu 需要可用的 systemd 用户服务。")
                Text("目标：${target.username}@${target.address}\n服务端口：${target.servicePort}\n数据目录：${target.serviceDirectory}")
                Button(onClick = { installDialog = true }, enabled = !vm.installing && vm.transfer == null) { Text("上传并安装") }
                if (vm.installed) {
                    Text("服务安装及健康检查完成。请按安装日志中的命令在终端确认 Codex 登录，然后连接工作台。")
                    Button(onClick = openWorkspace) { Text("连接工作台") }
                }
                if (vm.installLog.isNotBlank()) SelectionContainer { Text(vm.installLog, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("install-log")) }
            }
        }
    }
    if (installDialog) AlertDialog(onDismissRequest = { installDialog = false }, title = { Text("安装到 ${target.name}？") },
        text = { Text("会上传当前 APK 携带的电脑服务，并创建此 SSH 用户的自启动服务。缺少的 Node 和 Codex CLI 会联网安装到用户目录。已有 AgentDeck 安装会停止本次安装。Codex 认证和自定义模型配置需在电脑端完成。", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { installDialog = false; vm.install() }) { Text("开始安装") } },
        dismissButton = { TextButton(onClick = { installDialog = false }) { Text("取消") } })
}

private class TerminalBridge(val vm: RemoteToolsViewModel, val view: WebView, val readyCallback: () -> Unit) {
    @JavascriptInterface fun fontSize() = (14 * view.resources.configuration.fontScale).toInt()
    @JavascriptInterface fun input(data: String) { vm.send(data) }
    @JavascriptInterface fun resize(cols: Int, rows: Int) { vm.resize(cols, rows) }
    @JavascriptInterface fun consumed(id: Int) { view.post { vm.terminalConsumed(id) } }
    @JavascriptInterface fun ready(cols: Int, rows: Int) { view.post {
        val history = vm.attachTerminal(cols, rows)
        view.evaluateJavascript("receive(${JSONObject.quote(history)},0)", null)
        readyCallback()
    } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalPane(vm: RemoteToolsViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val view = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            // xterm measures its own cells. WebView text zoom would enlarge glyphs without resizing those cells.
            settings.textZoom = 100
            settings.allowFileAccess = false; settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    // The terminal renders only bundled assets; remote output cannot load external documents.
                    if (request.url.scheme != "file") return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                    return null
                }
            }
            addJavascriptInterface(TerminalBridge(vm, this) { ready = true }, "Native")
            loadUrl("file:///android_asset/terminal/index.html")
        }
    }
    LaunchedEffect(ready) { if (ready) for (chunk in vm.output) {
        view.evaluateJavascript("receive(${JSONObject.quote(chunk.encoded)},${chunk.id})", null)
        chunk.consumed.await()
    } }
    DisposableEffect(view) { onDispose { vm.detachTerminal(); view.removeJavascriptInterface("Native"); view.destroy() } }
    Column(modifier) {
        Row(Modifier.padding(horizontal = 12.dp)) {
            Text(vm.terminalStatus, Modifier.weight(1f))
            if (vm.terminalStatus == "终端已退出" || vm.terminalStatus == "终端已断开") TextButton(onClick = vm::restartTerminal) { Text("重新打开") }
        }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("ssh-terminal"))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Ctrl-C" to "\u0003", "Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Enter" to "\r").forEach { (label, data) ->
                TextButton(onClick = { vm.send(data) }) { Text(label) }
            }
        }
    }
}
