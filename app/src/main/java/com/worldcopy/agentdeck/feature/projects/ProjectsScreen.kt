package com.worldcopy.agentdeck.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import org.json.JSONObject

@Composable
fun ProjectsScreen(hosts: List<Host>, workspaces: Map<String, WorkspaceViewModel>, connecting: Boolean,
                   sync: (Host?) -> Unit, manageHosts: () -> Unit,
                   open: (ProjectGroup, JSONObject?) -> Unit) {
    val groups = groupProjects(hosts, workspaces.mapValues { it.value.projectSessions })
    ProjectList(groups, busyHosts = workspaces.filterValues { it.busy }.keys, open = open,
        header = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("项目", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { sync(null) }, enabled = !connecting && workspaces.values.none { it.busy } && hosts.isNotEmpty()) { Text("同步项目") }
            }
            Text("${groups.size} 个项目 · ${hosts.size} 台主机", style = MaterialTheme.typography.bodyMedium)
            if (groups.isEmpty()) {
                Text(if (hosts.isEmpty()) "添加主机后，在这里查看各项目的对话。" else "同步主机以发现已有对话的项目；也可以到主机工作台新建项目会话。", Modifier.padding(vertical = 12.dp))
                TextButton(onClick = manageHosts) { Text("管理主机") }
            }
        }, footer = {
            item {
                Text("主机同步", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            }
            items(hosts, key = { "sync-${it.id}" }) { host ->
                val workspace = workspaces[host.id]
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(host.name, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (workspace?.busy == true) TextButton(onClick = workspace::cancelWork) { Text("取消") }
                        else TextButton(onClick = { sync(host) }, enabled = !connecting) { Text("同步") }
                    }
                    Text(if (workspace?.busy == true) "正在读取项目…" else workspace?.connection ?: "未同步", style = MaterialTheme.typography.bodySmall)
                    workspace?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (!workspace?.snapshotTime.isNullOrBlank()) Text("最近同步：${workspace?.snapshotTime}", style = MaterialTheme.typography.bodySmall)
                }
            }
        })
}

@Composable
fun ProjectList(groups: List<ProjectGroup>, busyHosts: Set<String> = emptySet(),
                open: (ProjectGroup, JSONObject?) -> Unit,
                header: @Composable () -> Unit = {},
                footer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}) {
    // Each visit starts collapsed so all projects remain easy to scan.
    val expanded = remember { mutableStateMapOf<ProjectKey, Boolean>() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { header() }
        groups.forEach { group ->
            val isExpanded = expanded[group.key] == true
            item(key = "project:${group.key.hostId}:${group.key.path}") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().testTag("project:${group.key.hostId}:${group.key.path}").clickable(role = Role.Button, onClickLabel = if (isExpanded) "收起对话" else "展开对话") {
                        expanded[group.key] = !isExpanded
                    }.semantics { stateDescription = if (isExpanded) "已展开" else "已折叠" }.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.sessions.size} 条对话 ${if (isExpanded) "▾" else "▸"}", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(group.hostName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (group.key.path.isNotBlank()) Text(group.key.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (isExpanded) {
                items(group.sessions, key = { "thread:${group.key.hostId}:${it.getString("id")}" }) { session ->
                    Surface(Modifier.fillMaxWidth().padding(start = 12.dp).testTag("thread:${group.key.hostId}:${session.getString("id")}")
                        .clickable(enabled = group.key.hostId !in busyHosts) { open(group, session) }, shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(session.string("name").ifBlank { session.string("preview").ifBlank { "新会话" } },
                            Modifier.padding(14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (group.key.path.isNotBlank()) item(key = "new:${group.key.hostId}:${group.key.path}") {
                    TextButton(onClick = { open(group, null) }, enabled = group.key.hostId !in busyHosts, modifier = Modifier.padding(start = 12.dp)) { Text("打开项目 / 新建对话") }
                }
            }
        }
        footer()
    }
}
