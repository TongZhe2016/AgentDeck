package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.ui.components.DeckChevron

@Composable
fun ExecutionCard(id: String, steps: List<ChatItem>, state: String) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val summary = steps.lastOrNull()?.let(::stepSummary)
    val label = when {
        summary == null -> stateName(state)
        state == "completed" -> summary
        else -> "${stateName(state)} · $summary"
    }
    Column(Modifier.fillMaxWidth().testTag("execution:$id")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("execution-toggle:$id")
            .clickable(enabled = steps.isNotEmpty(), role = Role.Button,
                onClickLabel = if (expanded) "收起执行过程" else "展开执行过程") { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (steps.isNotEmpty()) DeckChevron(expanded)
        }
        if (expanded) steps.forEach { step ->
            var detail by rememberSaveable(id, step.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("step:${step.id}")
                .clickable(role = Role.Button, onClickLabel = if (detail) "收起详情" else "查看详情") { detail = !detail }
                .semantics { stateDescription = if (detail) "已展开" else "已折叠" }.padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stepSummary(step), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (step.status == "failed" || step.status == "declined") Text("失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                DeckChevron(detail)
            }
            if (detail) SelectionContainer(Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp).testTag("step-detail:${step.id}")) {
                Text(step.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

internal fun stateName(state: String) = when (state) {
    "queued" -> "已排队"
    "running", "inProgress" -> "执行中"
    "waiting_approval" -> "等待审批"
    "waiting_input" -> "等待回答"
    "completed" -> "执行完成"
    "failed" -> "执行失败"
    "interrupted" -> "已中断"
    "unknown" -> "待核实"
    else -> "执行过程"
}
