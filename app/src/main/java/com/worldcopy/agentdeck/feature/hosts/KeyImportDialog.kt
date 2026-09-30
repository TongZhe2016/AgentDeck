package com.worldcopy.agentdeck.feature.hosts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeyImportDialog(vm: HostsViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Credentials deliberately use remember, not saved instance state.
    var name by remember { mutableStateOf("") }
    var privateText by remember { mutableStateOf("") }
    var publicText by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var publicPicker by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = vm.busy || reading
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            error = null
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArray(65_537)
                        var size = 0
                        while (size < bytes.size) {
                            val count = input.read(bytes, size, bytes.size - size)
                            if (count == -1) break
                            size += count
                        }
                        require(size <= 65_536) { "文件过大，请选择 SSH 密钥文本文件" }
                        String(bytes, 0, size, Charsets.UTF_8)
                    } ?: error("无法打开密钥文件")
                }
                if (publicPicker) publicText = text else privateText = text
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法读取文件，请选择不超过 64 KB 的 SSH 密钥文本文件" }
            finally { reading = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("导入已有密钥") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("支持 OpenSSH／PEM 格式的 Ed25519、RSA、ECDSA 私钥。公钥可自动提取。", style = MaterialTheme.typography.bodySmall)
                Field(name, { name = it }, "密钥名称", enabled = !busy)
                OutlinedButton(enabled = !busy, onClick = { publicPicker = false; picker.launch(arrayOf("*/*")) }) {
                    Text("选择私钥文件")
                }
                OutlinedTextField(privateText, { privateText = it; error = null }, enabled = !busy,
                    label = { Text("私钥内容（可粘贴）") }, minLines = 2, maxLines = 3,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("import-private"))
                OutlinedTextField(passphrase, { passphrase = it; error = null }, enabled = !busy,
                    label = { Text("私钥口令（未加密则留空）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("import-passphrase"))
                Text("口令用于解锁此文件。导入后私钥由手机加密保存，连接时无需再次输入。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { publicPicker = true; picker.launch(arrayOf("*/*")) }) {
                    Text("选择公钥文件（可选）")
                }
                OutlinedTextField(publicText, { publicText = it; error = null }, enabled = !busy,
                    label = { Text("公钥内容（留空自动提取）") }, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("import-public"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && privateText.isNotBlank(), onClick = {
                error = null
                vm.importKey(name, privateText, publicText, passphrase) { failure ->
                    if (failure == null) {
                        privateText = ""; passphrase = ""; dismiss()
                    } else error = failure
                }
            }) { Text(if (busy) "正在导入…" else "导入") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("取消") } },
    )
}
