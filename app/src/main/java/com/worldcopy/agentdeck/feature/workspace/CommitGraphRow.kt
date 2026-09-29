package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun CommitGraphRow(commit: JSONObject, row: GraphRow, expanded: Boolean, detail: JSONObject?, busy: Boolean,
                            now: Instant, toggle: () -> Unit, loadParent: (Int) -> Unit,
                            openDiff: (String, Int) -> Unit, quote: (() -> Unit)?) {
    val oid = commit.getString("oid")
    val refs = commit.string("refs").split(", ").filter { it.isNotBlank() }
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width((maxOf(row.before.size, row.after.size, row.node + 1) * 14 + 12).dp).fillMaxHeight()) {
            fun x(lane: Int) = (lane * 14 + 10).dp.toPx()
            val center = 24.dp.toPx()
            row.before.forEachIndexed { lane, parent ->
                if (parent == row.oid) drawLine(primary, Offset(x(lane), 0f), Offset(x(row.node), center), 2.dp.toPx())
                else drawLine(primary.copy(alpha = .5f), Offset(x(lane), 0f), Offset(x(row.after.indexOf(parent)), size.height), 2.dp.toPx())
            }
            row.parents.forEach { parent -> drawLine(primary, Offset(x(row.node), center), Offset(x(row.after.indexOf(parent)), size.height), 2.dp.toPx()) }
            drawCircle(primary, 4.dp.toPx(), Offset(x(row.node), center))
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("commit-row-$oid")
                .clickable(enabled = expanded || !busy, role = Role.Button, onClickLabel = if (expanded) "收起提交详情" else "展开提交详情", onClick = toggle)
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(commit.string("subject"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                if (refs.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(refs.first().removePrefix("HEAD -> ") + if (refs.size > 1) " +${refs.size - 1}" else "", Modifier.widthIn(max = 88.dp).padding(horizontal = 5.dp, vertical = 2.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
                Text(commitRelativeTime(commit.string("date"), now), maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("commit-detail-$oid"),
                color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text(detail?.string("message") ?: commit.string("subject"), style = MaterialTheme.typography.bodyMedium) }
                    Text(commit.string("author"), style = MaterialTheme.typography.labelLarge)
                    Text(runCatching { OffsetDateTime.parse(commit.string("date")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")) }.getOrDefault(commit.string("date")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(oid.take(8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    if (refs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        refs.forEach { ref -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(ref, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                        } }
                    }
                    if (detail == null && busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (detail != null) {
                        val parents = detail.getJSONArray("parents")
                        val parent = detail.optInt("parentIndex")
                        if (parents.length() > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0 until parents.length()).forEach { index ->
                                FilterChip(selected = parent == index, onClick = { loadParent(index) }, enabled = !busy, label = { Text("父提交 ${index + 1}") })
                            }
                        }
                        val paths = detail.getJSONArray("paths")
                        Text("${paths.length()} 个文件变更${if (parents.length() > 1) " · 相对父提交 ${parent + 1}" else ""}", style = MaterialTheme.typography.labelMedium)
                        (0 until paths.length()).forEach { index ->
                            TextButton(onClick = { openDiff(paths.getString(index), parent) }, enabled = !busy,
                                contentPadding = PaddingValues(0.dp)) { Text(paths.getString(index), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        if (quote != null) TextButton(onClick = quote, enabled = !busy) { Text("引用到对话") }
                    }
                }
            }
        }
    }
}
