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
import androidx.compose.ui.Modifier
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
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Host?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(20.dp)) {
        item {
            Text("你的电脑，随时掌舵", style = MaterialTheme.typography.headlineSmall)
            Text("通过 SSH 连接主机，继续项目中的工作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { adding = true }, enabled = !vm.busy) { Text("添加主机") }
        }
        if (vm.hosts.isEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                Text("连接第一台电脑", style = MaterialTheme.typography.titleMedium)
                Text("可以使用账号密码，或在「密钥」中创建、导入 SSH 密钥后登录。")
            } }
        }
        items(vm.hosts, key = { it.id }) { host ->
            ElevatedCard(Modifier.fillMaxWidth().testTag("host-${host.id}")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(host.name, style = MaterialTheme.typography.titleLarge)
                Text("${host.username}@${host.address}:${host.port}")
                Text(if (host.authMethod == AuthMethod.KEY) "密钥 · ${vm.identities.find { it.id == host.identityId }?.name ?: "请选择密钥"}" else "密码登录")
                Text(vm.statuses[host.id] ?: "未连接", color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.connect(host) }, enabled = !vm.busy) { Text("连接 / 重连") }
                    OutlinedButton(onClick = { openWorkspace(host) }, enabled = !vm.busy) { Text("工作台") }
                    TextButton(onClick = { editing = host }, enabled = !vm.busy) { Text("编辑") }
                    TextButton(onClick = { deleting = host }, enabled = !vm.busy) { Text("删除") }
                }
            } }
        }
    }
    if (adding || editing != null) HostEditor(editing, vm.identities, vm.busy,
        dismiss = { adding = false; editing = null },
        save = { host, password -> vm.save(host, password) { adding = false; editing = null } })
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
private fun HostEditor(original: Host?, identities: List<SshIdentity>, busy: Boolean, dismiss: () -> Unit,
                       save: (Host, String?) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
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
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (original == null) "添加主机" else "编辑主机") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(name, { name = it }, "主机名称")
            Field(address, { address = it }, "SSH 地址")
            Field(port, { port = it }, "SSH 端口", number = true)
            Field(username, { username = it }, "用户名")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = method == AuthMethod.PASSWORD, onClick = { method = AuthMethod.PASSWORD }, label = { Text("密码") })
                FilterChip(selected = method == AuthMethod.KEY, onClick = { method = AuthMethod.KEY }, label = { Text("密钥") })
            }
            if (method == AuthMethod.PASSWORD) {
                OutlinedTextField(password, { password = it }, label = { Text(if (original == null) "密码" else "新密码（留空保留）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else {
                if (identities.isEmpty()) Text("请先在密钥页创建或导入密钥。")
                identities.forEach { key -> FilterChip(selected = identity == key.id, onClick = { identity = key.id }, label = { Text(key.name) }) }
            }
            HorizontalDivider()
            Text("通过 SSH 自动配置电脑服务。请先在电脑上安装并启动 AgentDeck。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级服务设置" else "高级服务设置") }
            if (advanced) {
                Field(servicePort, { servicePort = it }, "服务端口", number = true)
                Field(serviceDirectory, { serviceDirectory = it }, "服务数据目录")
                Text("默认 ~/.agentdeck；自定义安装时填写 AGENTDECK_DATA_DIR 对应目录。", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
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
            Text("登录密钥", style = MaterialTheme.typography.headlineSmall)
            Text("私钥加密保存在此设备。将公钥配置到电脑后，即可选择密钥登录。")
            Button(onClick = { creating = true }, enabled = !vm.busy) { Text("创建 Ed25519 密钥") }
            OutlinedButton(onClick = { importing = true }, enabled = !vm.busy) { Text("导入已有密钥") }
        }
        items(vm.identities, key = { it.id }) { key ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(key.name, style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(key.publicKey, style = MaterialTheme.typography.bodySmall) }
                FlowRow {
                    TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("SSH 公钥", key.publicKey)) }) { Text("复制") }
                    TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKey), "分享 SSH 公钥")) }) { Text("分享") }
                    TextButton(onClick = { exportKey = key; export.launch("agentdeck-${key.id.take(8)}.pub") }) { Text("导出 .pub") }
                    TextButton(onClick = { naming = key }, enabled = !vm.busy) { Text("重命名") }
                    TextButton(onClick = { deleting = key }, enabled = !vm.busy) { Text("删除") }
                }
            } }
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
    OutlinedTextField(value, change, enabled = enabled, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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
