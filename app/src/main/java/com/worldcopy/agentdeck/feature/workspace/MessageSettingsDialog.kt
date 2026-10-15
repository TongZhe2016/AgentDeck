package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.core.storage.FollowupMode
import com.worldcopy.agentdeck.core.storage.MessageSettings

@Composable
fun MessageSettingsDialog(settings: MessageSettings, dismiss: () -> Unit) {
    AlertDialog(modifier = Modifier.testTag("message-settings-dialog"), onDismissRequest = dismiss, title = { Text("发送模式") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅影响执行中发送的新消息。空闲时开始下一轮，已发送消息不变。")
            FollowupMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("followup-mode-${mode.value}")
                    .selectable(selected = settings.followupMode == mode, role = Role.RadioButton, onClick = { settings.choose(mode) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = settings.followupMode == mode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.titleMedium)
                        Text(mode.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}
