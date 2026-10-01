package com.worldcopy.agentdeck.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel
import com.worldcopy.agentdeck.feature.workspace.string
import com.worldcopy.agentdeck.ui.components.*
import org.json.JSONObject

@Composable
fun ProjectsScreen(hosts: List<Host>, workspaces: Map<String, WorkspaceViewModel>, connecting: Boolean,
                   sync: (Host?) -> Unit, manageHosts: () -> Unit,
                   open: (ProjectGroup, JSONObject?) -> Unit) {
    val groups = groupProjects(hosts, workspaces.mapValues { it.value.projectSessions })
    var showSync by remember { mutableStateOf(false) }
    ProjectList(groups, busyHosts = workspaces.filterValues { it.busy }.keys +
        if (connecting) hosts.map { it.id }.toSet() else emptySet(), open = open,
        header = {
            PageHeading("项目", "${groups.size} 个项目 · ${hosts.size} 台主机") {
                FilledTonalButton(onClick = { sync(null) }, enabled = !connecting && workspaces.values.none { it.busy } && hosts.isNotEmpty()) {
                    DeckGlyph(DeckIcon.Sync); Spacer(Modifier.width(8.dp)); Text("同步项目")
                }
            }
            if (groups.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                EmptyState(DeckIcon.Folder, if (hosts.isEmpty()) "从第一台主机开始" else "发现你的项目",
                    if (hosts.isEmpty()) "连接电脑后，在这里查看各个文件夹下的对话。" else "同步主机读取已有项目，或前往主机工作台新建会话。") {
                    OutlinedButton(onClick = manageHosts) { Text("管理主机") }
                }
            }
        }, footer = {
            if (hosts.isNotEmpty()) item {
                TextButton(onClick = { showSync = !showSync }, modifier = Modifier.fillMaxWidth()) {
                    DeckGlyph(DeckIcon.Computer); Spacer(Modifier.width(8.dp))
                    Text("主机同步", Modifier.weight(1f)); DeckChevron(showSync)
                }
            }
            if (showSync) items(hosts, key = { "sync-${it.id}" }) { host ->
                val workspace = workspaces[host.id]
                QuietCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (workspace?.busy == true) "正在读取项目…" else workspace?.connection ?: "未同步",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (workspace?.busy == true) TextButton(onClick = workspace::cancelWork) { Text("取消") }
                            else TextButton(onClick = { sync(host) }, enabled = !connecting) { Text("同步") }
                        }
                        workspace?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (!workspace?.snapshotTime.isNullOrBlank()) Text("最近同步：${workspace?.snapshotTime}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!showSync) items(hosts.filter { !workspaces[it.id]?.error.isNullOrBlank() }, key = { "error-${it.id}" }) { host ->
                Text("${host.name}：${workspaces[host.id]?.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        })
}

@Composable
fun ProjectList(groups: List<ProjectGroup>, busyHosts: Set<String> = emptySet(),
                open: (ProjectGroup, JSONObject?) -> Unit,
                header: @Composable () -> Unit = {},
                footer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    // Each visit starts with both levels collapsed.
    val expandedHosts = remember { mutableStateMapOf<String, Boolean>() }
    val expandedProjects = remember { mutableStateMapOf<ProjectKey, Boolean>() }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { header(); Spacer(Modifier.height(6.dp)) }
        groups.groupBy { it.key.hostId }.forEach { (hostId, projects) ->
            val hostExpanded = expandedHosts[hostId] == true
            item(key = "project-host:$hostId") {
                QuietCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("project-host:$hostId")
                        .clickable(role = Role.Button, onClickLabel = if (hostExpanded) "收起项目" else "展开项目") {
                            expandedHosts[hostId] = !hostExpanded
                            if (hostExpanded) projects.forEach { expandedProjects.remove(it.key) }
                        }
                        .semantics { stateDescription = if (hostExpanded) "已展开" else "已折叠" }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(DeckIcon.Computer)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(projects.first().hostName, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${projects.size} 个项目 · ${projects.sumOf { it.sessions.size }} 条对话",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DeckChevron(hostExpanded)
                    }
                }
            }
            if (hostExpanded) projects.forEach { group ->
                val isExpanded = expandedProjects[group.key] == true
                item(key = "project:${group.key.hostId}:${group.key.path}") {
                    QuietCard(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("project:${group.key.hostId}:${group.key.path}")
                            .clickable(role = Role.Button, onClickLabel = if (isExpanded) "收起对话" else "展开对话") { expandedProjects[group.key] = !isExpanded }
                            .semantics { stateDescription = if (isExpanded) "已展开" else "已折叠" }.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeckGlyph(DeckIcon.Folder, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${group.sessions.size} 条对话", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (group.key.path.isNotBlank()) Text(group.key.path, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DeckChevron(isExpanded)
                        }
                    }
                }
                if (isExpanded) {
                    items(group.sessions, key = { "thread:${group.key.hostId}:${it.getString("id")}" }) { session ->
                        Surface(Modifier.fillMaxWidth().padding(start = 32.dp).testTag("thread:${group.key.hostId}:${session.getString("id")}")
                            .clickable(enabled = group.key.hostId !in busyHosts, role = Role.Button) { open(group, session) },
                            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                DeckGlyph(DeckIcon.Chat, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(session.string("name").ifBlank { session.string("preview").ifBlank { "新会话" } },
                                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (group.key.path.isNotBlank()) item(key = "new:${group.key.hostId}:${group.key.path}") {
                        TextButton(onClick = { open(group, null) }, enabled = group.key.hostId !in busyHosts, modifier = Modifier.padding(start = 32.dp)) {
                            DeckGlyph(DeckIcon.Add); Spacer(Modifier.width(8.dp)); Text("打开项目 / 新建对话")
                        }
                    }
                }
            }
        }
        footer()
    }
}
