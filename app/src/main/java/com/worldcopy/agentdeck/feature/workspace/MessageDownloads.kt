package com.worldcopy.agentdeck.feature.workspace

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberMessageLinkHandler(vm: WorkspaceViewModel): (String) -> Unit {
    val context = LocalContext.current
    var filePath by rememberSaveable { mutableStateOf<String?>(null) }
    var fileProject by rememberSaveable { mutableStateOf("") }
    var choosingLocation by rememberSaveable { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = filePath
        if (uri != null && path != null) vm.downloadFile(path, fileProject, uri)
        filePath = null; choosingLocation = false
    }
    if (filePath != null && !choosingLocation) AlertDialog(
        onDismissRequest = { filePath = null },
        title = { Text(filePath!!.substringAfterLast('/')) },
        text = { SelectionContainer { Text(filePath!!) } },
        confirmButton = { TextButton(onClick = {
            choosingLocation = true
            save.launch(filePath!!.substringAfterLast('/'))
        }, enabled = vm.online && !vm.downloading) { Text("下载文件") } },
        dismissButton = { TextButton(onClick = { filePath = null }) { Text("取消") } },
    )
    if (vm.downloading) AlertDialog(onDismissRequest = {}, title = { Text("正在下载文件") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在保存到所选位置")
        } }, confirmButton = { TextButton(onClick = vm::cancelDownload) { Text("取消下载") } })
    vm.downloadedName?.let { name ->
        AlertDialog(onDismissRequest = vm::dismissDownload, title = { Text("文件已保存") }, text = { Text(name) },
            confirmButton = { TextButton(onClick = vm::dismissDownload) { Text("完成") } })
    }
    return { url ->
        when (val link = resolveMessageLink(url)) {
            is MessageLink.File -> {
                if (!vm.online) vm.reportError("连接电脑后可下载文件")
                else { filePath = link.path; fileProject = vm.project }
            }
            is MessageLink.Web -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                .onFailure { vm.reportError("没有可打开此链接的应用") }.let { }
            is MessageLink.Unsupported -> vm.reportError(link.reason)
        }
    }
}
