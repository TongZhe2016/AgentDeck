package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal val permissionLabels = linkedMapOf("read-only" to "只读", "on-request" to "请求批准", "untrusted" to "未信任", "never" to "不请求批准", "full-access" to "完全访问")
internal fun effortLabel(value: String) = when (value) {
    "none" -> "无"; "minimal" -> "最低"; "low" -> "低"; "medium" -> "中"; "high" -> "高"
    "xhigh" -> "极高"; "max" -> "最大"; "ultra" -> "超高"; "" -> "模型默认"; else -> value
}

@Composable
fun ExecutionSettingsBar(vm: WorkspaceViewModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val thread = vm.selected ?: return
    val current = thread.optJSONObject("executionSettings")
    val model = current?.string("model")?.ifBlank { null } ?: thread.string("model").ifBlank { "电脑默认模型" }
    val effort = current?.string("effort") ?: thread.string("reasoningEffort")
    val permission = current?.string("permissionMode") ?: "on-request"
    val permissionLabel = if (thread.optBoolean("managed")) permissionLabels[permission] ?: permission else "只读历史"
    TextButton(onClick = { open = true; vm.loadExecutionOptions() }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text("思考：${effortLabel(effort)} · ${permissionLabel}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium)
        }
        Text("设置", style = MaterialTheme.typography.labelLarge)
    }
    if (open) ExecutionSettingsSheet(vm.models, vm.optionsLoading, vm.optionsError,
        model, effort, permission, vm.busy, dismiss = { open = false }, retry = vm::loadExecutionOptions,
        save = { chosenModel, chosenEffort, mode -> vm.saveExecutionSettings(chosenModel, chosenEffort, mode) { open = false } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionSettingsSheet(models: List<JSONObject>, loading: Boolean, error: String?, currentModel: String,
                           currentEffort: String, currentPermission: String, saving: Boolean,
                           dismiss: () -> Unit, retry: () -> Unit, save: (String, String, String) -> Unit) {
    var model by remember { mutableStateOf(currentModel) }
    var effort by remember { mutableStateOf(currentEffort) }
    var permission by remember { mutableStateOf(currentPermission) }
    val selected = models.firstOrNull { it.string("model") == model }
    val efforts = selected?.optJSONArray("supportedReasoningEfforts").objects().map { it.string("reasoningEffort") }
    LaunchedEffect(models, model) {
        if (selected != null && effort !in efforts) effort = selected.string("defaultReasoningEffort")
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("execution-settings-options"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("执行设置", style = MaterialTheme.typography.headlineSmall)
                Text("保存后用于下一轮执行", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = retry, enabled = !loading) { Text("重试读取模型") }
            }
            item { Text("模型", style = MaterialTheme.typography.titleMedium) }
            items(models, key = { it.string("id") }) { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = model == option.string("model"), enabled = !saving, role = Role.RadioButton) { model = option.string("model") },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model == option.string("model"), onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(option.string("displayName").ifBlank { option.string("model") }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Text("思考强度", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    efforts.forEach { value -> FilterChip(selected = effort == value, onClick = { effort = value }, enabled = !saving, label = { Text(effortLabel(value)) }) }
                }
            }
            item {
                Text("权限", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    permissionLabels.forEach { (value, label) -> FilterChip(selected = permission == value, onClick = { permission = value }, enabled = !saving, label = { Text(label) }) }
                }
                Text(when (permission) {
                    "read-only" -> "只读取文件，不允许修改。"
                    "untrusted" -> "除可信读取操作外，执行前请求批准。"
                    "never" -> "允许修改工作区，不弹出批准请求；超出权限的操作会失败。"
                    "full-access" -> "允许访问此电脑的文件与网络，执行时不请求批准。"
                    else -> "允许修改工作区，超出权限时请求批准。"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Button(onClick = { save(model, effort, permission) }, enabled = !saving && !loading && error == null && selected != null && effort in efforts,
                modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在保存…" else "保存设置") } }
        }
    }
}
