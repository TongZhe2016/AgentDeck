package com.worldcopy.agentdeck.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.worldcopy.agentdeck.feature.hosts.ConfirmDialog
import com.worldcopy.agentdeck.feature.hosts.HostEditor
import com.worldcopy.agentdeck.feature.hosts.HostsViewModel
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel
import com.worldcopy.agentdeck.feature.workspace.string
import com.worldcopy.agentdeck.ui.components.*
import org.json.JSONObject

data class ProjectHostStatus(val loading: Boolean = false, val problem: String? = null, val label: String = "未同步")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: HostsViewModel, workspaces: Map<String, WorkspaceViewModel>, openWorkspace: (Host) -> Unit,
                   open: (ProjectGroup, JSONObject?) -> Unit) {
    val hosts = vm.hosts
    val connecting = vm.busy
    val connectingHosts = vm.connectingHosts
    val sshStatuses = vm.statuses
    var menuHost by remember { mutableStateOf<Host?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Host?>(null) }
    var cloning by remember { mutableStateOf<Host?>(null) }
    var deleting by remember { mutableStateOf<Host?>(null) }
    val groups = groupProjects(hosts, workspaces.mapValues { it.value.projectSessions })
    val statuses = hosts.associate { host ->
        val workspace = workspaces[host.id]
        val loading = host.id in connectingHosts || workspace?.busy == true
        val sshStatus = sshStatuses[host.id]
        val problem = workspace?.projectSyncError ?: when {
            workspace?.online == true -> null
            sshStatus == "等待核对主机身份" || sshStatus == "连接失败" -> sshStatus
            workspace?.connection == "连接中断，等待恢复" || workspace?.connection?.contains("已暂停重连") == true -> workspace.connection
            else -> null
        }
        host.id to ProjectHostStatus(loading, problem, when {
            loading -> if (host.id in connectingHosts) "正在连接与同步…" else "正在读取项目…"
            problem != null -> if (sshStatus == "等待核对主机身份") sshStatus else "连接或同步失败"
            workspace?.online == true -> "在线"
            else -> workspace?.connection ?: sshStatus ?: "未同步"
        })
    }
    ProjectList(groups, hosts, statuses, busyHosts = workspaces.filterValues { it.busy }.keys +
        if (connecting) hosts.map { it.id }.toSet() else emptySet(), open = open,
        manageHost = { menuHost = it },
        header = {
            PageHeading("项目", "${groups.size} 个项目 · ${hosts.size} 台主机") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { adding = true }, enabled = !connecting) {
                        DeckGlyph(DeckIcon.Add); Spacer(Modifier.width(8.dp)); Text("添加主机")
                    }
                    FilledTonalButton(onClick = { vm.syncProjects() }, enabled = !connecting && workspaces.values.none { it.busy } && hosts.isNotEmpty()) {
                        DeckGlyph(DeckIcon.Sync); Spacer(Modifier.width(8.dp)); Text("同步项目")
                    }
                }
            }
            if (hosts.isNotEmpty()) Text("长按主机管理连接与配置", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (groups.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                EmptyState(DeckIcon.Folder, if (hosts.isEmpty()) "从第一台主机开始" else "发现你的项目",
                    if (hosts.isEmpty()) "添加电脑后，在这里查看各个文件夹下的对话。" else "同步主机读取已有项目，或长按主机进入工作台新建会话。")
            }
        }, hostDetails = { host ->
            val workspace = workspaces[host.id]
            val status = statuses.getValue(host.id)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!status.loading) status.problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!workspace?.snapshotTime.isNullOrBlank()) Text("最近同步：${workspace?.snapshotTime}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (workspace?.busy == true) TextButton(onClick = workspace::cancelWork) { Text("取消同步") }
                else TextButton(onClick = { vm.syncProjects(host) }, enabled = !connecting,
                    modifier = Modifier.testTag("project-host-sync:${host.id}")) {
                    DeckGlyph(DeckIcon.Sync); Spacer(Modifier.width(8.dp))
                    Text(if (status.problem != null) "重试连接" else "同步此主机")
                }
            }
        })
    menuHost?.let { host ->
        val workspace = workspaces[host.id]
        val enabled = !connecting && workspace?.busy != true
        ModalBottomSheet(onDismissRequest = { menuHost = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(host.name, style = MaterialTheme.typography.titleLarge)
                    Text("${host.username}@${host.address}:${host.port}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { menuHost = null; openWorkspace(host) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("进入工作台") }
                TextButton(onClick = { menuHost = null; vm.syncProjects(host) }, enabled = enabled && workspace?.online != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("连接") }
                TextButton(onClick = { menuHost = null; vm.reconnect(host) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重连") }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                TextButton(onClick = { menuHost = null; editing = host }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("编辑") }
                TextButton(onClick = { menuHost = null; cloning = host }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("克隆") }
                TextButton(onClick = { menuHost = null; deleting = host }, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("删除") }
            }
        }
    }
    fun dismissEditor() { adding = false; editing = null; cloning = null }
    if (adding || editing != null || cloning != null) HostEditor(editing ?: cloning, vm.identities, vm.busy,
        cloning = cloning != null, dismiss = ::dismissEditor,
        save = { host, password ->
            val source = cloning
            if (source != null) vm.cloneHost(source, host, password, ::dismissEditor)
            else vm.save(host, password, ::dismissEditor)
        })
    deleting?.let { host -> ConfirmDialog("删除 ${host.name}？", "删除手机上的主机配置和凭据。", { deleting = null }) {
        vm.deleteHost(host.id); deleting = null
    } }
}

@Composable
fun ProjectList(groups: List<ProjectGroup>, hosts: List<Host>,
                statuses: Map<String, ProjectHostStatus> = emptyMap(), busyHosts: Set<String> = emptySet(),
                open: (ProjectGroup, JSONObject?) -> Unit,
                manageHost: (Host) -> Unit = {},
                header: @Composable () -> Unit = {},
                hostDetails: @Composable (Host) -> Unit = {}) {
    // Each visit starts with both levels collapsed.
    val expandedHosts = remember { mutableStateMapOf<String, Boolean>() }
    val expandedProjects = remember { mutableStateMapOf<ProjectKey, Boolean>() }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { header(); Spacer(Modifier.height(6.dp)) }
        val projectsByHost = groups.groupBy { it.key.hostId }
        hosts.forEach { host ->
            val hostId = host.id
            val projects = projectsByHost[hostId].orEmpty()
            val status = statuses[hostId] ?: ProjectHostStatus()
            val hostExpanded = expandedHosts[hostId] == true
            item(key = "project-host:$hostId") {
                QuietCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("project-host:$hostId")
                        .combinedClickable(role = Role.Button, onClickLabel = if (hostExpanded) "收起项目" else "展开项目",
                            onLongClickLabel = "${host.name}的主机操作", onLongClick = { manageHost(host) }, onClick = {
                            expandedHosts[hostId] = !hostExpanded
                            if (hostExpanded) projects.forEach { expandedProjects.remove(it.key) }
                        })
                        .semantics { stateDescription = "${status.label}，" + if (hostExpanded) "已展开" else "已折叠" }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(DeckIcon.Computer)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(host.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${projects.size} 个项目 · ${projects.sumOf { it.sessions.size }} 条对话",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (status.loading || status.problem != null) Text(status.label, style = MaterialTheme.typography.bodySmall,
                                color = if (status.loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            when {
                                status.loading -> CircularProgressIndicator(Modifier.fillMaxSize().testTag("project-host-loading:$hostId"), strokeWidth = 2.dp)
                                status.problem != null -> DeckGlyph(DeckIcon.Warning,
                                    modifier = Modifier.testTag("project-host-warning:$hostId"), tint = MaterialTheme.colorScheme.error)
                                else -> DeckChevron(hostExpanded)
                            }
                        }
                    }
                    if (hostExpanded) hostDetails(host)
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
    }
}
