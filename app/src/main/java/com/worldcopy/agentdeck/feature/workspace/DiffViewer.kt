package com.worldcopy.agentdeck.feature.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.worldcopy.agentdeck.ui.components.*
import org.json.JSONObject

@Composable
internal fun DiffViewer(result: JSONObject, close: () -> Unit, quote: (() -> Unit)?, error: (String) -> Unit) {
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(result.optString("text").toByteArray()) }
                ?: error("无法打开导出文件")
        }.onFailure { error(it.message ?: "无法导出 Diff") }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { DeckGlyph(DeckIcon.Back, "关闭差异") }
                    Column(Modifier.weight(1f)) {
                        Text("文件差异", style = MaterialTheme.typography.titleMedium)
                        Text(result.getString("path"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { export.launch("agentdeck-diff.patch") }) { Text("导出") }
                    if (quote != null) FilledTonalButton(onClick = quote) { Text("引用到对话") }
                }
                if (result.optBoolean("truncated")) Text("内容已截断（上限 512 KiB）", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                if (result.optBoolean("preview")) Text("未跟踪文件预览", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
                        items(diffLines(result.optString("text"), result.optBoolean("preview"))) { row ->
                            val color = when { row.text.startsWith('+') -> MaterialTheme.colorScheme.primary; row.text.startsWith('-') -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${row.old?.toString() ?: ""}\n${row.new?.toString() ?: ""}", Modifier.widthIn(min = 32.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                                Text(row.text, Modifier.weight(1f), color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
