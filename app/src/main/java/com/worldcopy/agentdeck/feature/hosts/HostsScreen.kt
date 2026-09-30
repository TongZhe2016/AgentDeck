package com.worldcopy.agentdeck.feature.hosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.worldcopy.agentdeck.ui.components.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.model.SshIdentity

@Composable
fun HostsScreen(vm: HostsViewModel, openWorkspace: (Host) -> Unit) {
    var editing by remember { mutableStateOf<Host?>(null) }
    var cloning by remember { mutableStateOf<Host?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Host?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(20.dp)) {
        item {
            PageHeading("我的主机", "${vm.hosts.size} 台电脑 · 通过 SSH 连接") {
                Button(onClick = { adding = true }, enabled = !vm.busy) {
                    DeckGlyph(DeckIcon.Add); Spacer(Modifier.width(8.dp)); Text("添加主机")
                }
            }
        }
        if (vm.hosts.isEmpty()) item {
            EmptyState(DeckIcon.Computer, "连接第一台电脑", "使用账号密码，或在密钥页创建、导入 SSH 密钥。电脑上的项目和对话会集中显示在首页。")
        }
        items(vm.hosts, key = { it.id }) { host ->
            var menu by remember { mutableStateOf(false) }
            QuietCard(Modifier.fillMaxWidth().testTag("host-${host.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(DeckIcon.Computer)
                        Column(Modifier.weight(1f)) {
                            Text(host.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${host.username}@${host.address}:${host.port}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !vm.busy) { DeckGlyph(DeckIcon.More, "${host.name}的主机操作") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; editing = host })
                                DropdownMenuItem(text = { Text("克隆") }, onClick = { menu = false; cloning = host })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = host })
                            }
                        }
                    }
                    val status = vm.statuses[host.id] ?: "未连接"
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLabel(status, positive = status == "SSH 已连接")
                        Text(if (host.authMethod == AuthMethod.KEY) "密钥 · ${vm.identities.find { it.id == host.identityId }?.name ?: "请选择密钥"}" else "密码登录",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(onClick = { openWorkspace(host) }, enabled = !vm.busy) { DeckGlyph(DeckIcon.Code); Spacer(Modifier.width(8.dp)); Text("工作台") }
                        OutlinedButton(onClick = { vm.connect(host) }, enabled = !vm.busy) { Text("连接 / 重连") }
                    }
                }
            }
        }
    }
    fun dismissEditor() { adding = false; editing = null; cloning = null }
    if (adding || editing != null || cloning != null) HostEditor(editing ?: cloning, vm.identities, vm.busy,
        cloning = cloning != null, dismiss = ::dismissEditor,
        save = { host, password ->
            val source = cloning
            if (source != null) vm.cloneHost(source, host, password, ::dismissEditor)
            else vm.save(host, password, ::dismissEditor)
        })
    deleting?.let { host -> ConfirmDialog("删除 ${host.name}？", "删除手机上的主机配置和凭据。", { deleting = null }) {
        vm.deleteHost(host.id); deleting = null
    } }
}

@Composable
fun HostIdentityDialog(vm: HostsViewModel) {
    vm.confirmation?.let { (host, key) ->
        AlertDialog(onDismissRequest = vm::dismissConfirmation,
            title = { Text(if (key.changed) "主机身份发生变化" else "确认主机身份") },
            text = { SelectionContainer { Column {
                Text("${host.address}:${host.port}\n${key.hostKey.substringBefore(' ')}")
                Text(key.fingerprint)
                Text("请与电脑上的 SSH 主机公钥核对后再信任。")
            } } },
            confirmButton = { TextButton(onClick = vm::trustHost) { Text("已核对，信任并连接") } },
            dismissButton = { TextButton(onClick = vm::dismissConfirmation) { Text("取消") } })
    }
}

@Composable
private fun HostEditor(original: Host?, identities: List<SshIdentity>, busy: Boolean, cloning: Boolean = false, dismiss: () -> Unit,
                       save: (Host, String?) -> Unit) {
    var name by remember { mutableStateOf(original?.name?.let { if (cloning) "$it（克隆）" else it } ?: "") }
    var address by remember { mutableStateOf(original?.address ?: "") }
    var port by remember { mutableStateOf(original?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(original?.username ?: "") }
    var method by remember { mutableStateOf(original?.authMethod ?: AuthMethod.PASSWORD) }
    var identity by remember { mutableStateOf(original?.identityId ?: identities.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var serviceDirectory by remember { mutableStateOf(original?.serviceDirectory ?: "~/.agentdeck") }
    var servicePort by remember { mutableStateOf(original?.servicePort?.toString() ?: "4317") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (cloning) "克隆主机" else if (original == null) "添加主机" else "编辑主机") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("连接信息")
            Field(name, { name = it }, "主机名称")
            Field(address, { address = it }, "SSH 地址")
            Field(port, { port = it }, "SSH 端口", number = true)
            Field(username, { username = it }, "用户名")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = method == AuthMethod.PASSWORD, onClick = { method = AuthMethod.PASSWORD }, label = { Text("密码") })
                FilterChip(selected = method == AuthMethod.KEY, onClick = { method = AuthMethod.KEY }, label = { Text("密钥") })
            }
            if (method == AuthMethod.PASSWORD) {
                OutlinedTextField(password, { password = it }, label = { Text(
                    if (cloning && original?.authMethod == AuthMethod.PASSWORD) "新密码（留空沿用）"
                    else if (original?.authMethod != AuthMethod.PASSWORD) "密码" else "新密码（留空保留）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else {
                if (identities.isEmpty()) Text("请先在密钥页创建或导入密钥。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    identities.forEach { key -> FilterChip(selected = identity == key.id, onClick = { identity = key.id }, label = { Text(key.name) }) }
                }
            }
            HorizontalDivider()
            Text("通过 SSH 自动配置电脑服务。请先在电脑上安装并启动 AgentDeck。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级服务设置" else "高级服务设置"); Spacer(Modifier.width(8.dp)); DeckChevron(advanced) }
            if (advanced) {
                Field(servicePort, { servicePort = it }, "服务端口", number = true)
                Field(serviceDirectory, { serviceDirectory = it }, "服务数据目录")
                Text("默认 ~/.agentdeck；自定义安装时填写 AGENTDECK_DATA_DIR 对应目录。", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(enabled = !busy, onClick = {
            try {
                val host = Host(id = original?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), address = address.trim(),
                    port = port.toIntOrNull() ?: 0, username = username.trim(), authMethod = method,
                    identityId = if (method == AuthMethod.KEY) identity else null,
                    trustedHostKey = original?.takeIf { it.address == address.trim() && it.port.toString() == port }?.trustedHostKey,
                    servicePort = servicePort.toIntOrNull() ?: 0, serviceDirectory = serviceDirectory.trim().ifBlank { "~/.agentdeck" })
                host.validate()
                require(method != AuthMethod.PASSWORD || password.isNotEmpty() || original?.authMethod == AuthMethod.PASSWORD) { "请填写密码" }
                save(host, password.takeIf { it.isNotEmpty() })
            } catch (e: Exception) { error = e.message }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun KeysScreen(vm: HostsViewModel) {
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<SshIdentity?>(null) }
    var deleting by remember { mutableStateOf<SshIdentity?>(null) }
    var exportKey by remember { mutableStateOf<SshIdentity?>(null) }
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val key = exportKey
        if (uri != null && key != null) vm.work {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write((key.publicKey + "\n").toByteArray()) }
                    ?: error("无法写入公钥文件")
            }
        }
        exportKey = null
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeading("登录密钥", "在此设备加密保存，连接时选择使用") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { creating = true }, enabled = !vm.busy) { DeckGlyph(DeckIcon.Add); Spacer(Modifier.width(8.dp)); Text("创建 Ed25519 密钥") }
                    OutlinedButton(onClick = { importing = true }, enabled = !vm.busy) { DeckGlyph(DeckIcon.Upload); Spacer(Modifier.width(8.dp)); Text("导入已有密钥") }
                }
            }
        }
        if (vm.identities.isEmpty()) item {
            EmptyState(DeckIcon.Key, "让登录更方便", "生成手机专用密钥，或导入电脑已有密钥。将对应公钥授权到电脑后，即可使用密钥登录。")
        }
        items(vm.identities, key = { it.id }) { key ->
            var expanded by remember { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            QuietCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(DeckIcon.Key)
                        Column(Modifier.weight(1f)) {
                            Text(key.name, style = MaterialTheme.typography.titleLarge)
                            Text("${key.publicKey.substringBefore(' ').removePrefix("ssh-")} · ${vm.hosts.count { it.identityId == key.id }} 台主机使用",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !vm.busy) { DeckGlyph(DeckIcon.More, "${key.name}的密钥操作") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; naming = key })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = key })
                            }
                        }
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起公钥" else "查看公钥"); Spacer(Modifier.width(8.dp)); DeckChevron(expanded)
                    }
                    if (expanded) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                            SelectionContainer { Text(key.publicKey, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("SSH 公钥", key.publicKey)) }) { Text("复制") }
                            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKey), "分享 SSH 公钥")) }) { Text("分享") }
                            TextButton(onClick = { exportKey = key; export.launch("agentdeck-${key.id.take(8)}.pub") }) { Text("导出 .pub") }
                        }
                    }
                }
            }
        }
    }
    if (importing) KeyImportDialog(vm) { importing = false }
    if (creating || naming != null) NameDialog(naming?.name ?: "", { creating = false; naming = null }) { name ->
        if (creating) vm.createKey(name) else naming?.let { vm.renameKey(it.id, name) }
        creating = false; naming = null
    }
    deleting?.let { key ->
        val names = vm.hosts.filter { it.identityId == key.id }.joinToString { it.name }.ifEmpty { "无" }
        ConfirmDialog("删除 ${key.name}？", "关联主机：$names\n将关闭对应 SSH 连接并解除关联。电脑上的公钥授权需自行撤销。", { deleting = null }) {
            vm.deleteKey(key); deleting = null
        }
    }
}

@Composable
fun Field(value: String, change: (String) -> Unit, label: String, number: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, enabled = enabled, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
}

@Composable
private fun NameDialog(initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("密钥名称") }, text = { Field(name, { name = it }, "例如：我的手机") },
        confirmButton = { TextButton(onClick = { save(name) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
fun ConfirmDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
