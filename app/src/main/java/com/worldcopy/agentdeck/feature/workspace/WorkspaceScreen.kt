package com.worldcopy.agentdeck.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.feature.hosts.ConfirmDialog
import com.worldcopy.agentdeck.feature.hosts.Field
import org.json.JSONObject

@Composable
fun WorkspaceScreen(vm: WorkspaceViewModel, back: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    BackHandler { when { vm.diff != null -> vm.clearDiff(); vm.detail != null -> vm.clearDetail(); vm.selected != null -> vm.clearSession(); else -> back() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (vm.selected != null) vm.clearSession() else back() }) { Text(if (vm.selected == null) "‹ 主机" else "‹ 会话") }
            Text(vm.connection, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary)
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TabRow(selectedTabIndex = tab) {
            listOf("会话", "Changes", "Graph").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        when (tab) { 0 -> if (vm.selected == null) SessionList(vm) else Chat(vm); 1 -> Changes(vm); 2 -> Graph(vm) }
    }
    vm.error?.let { message -> AlertDialog(onDismissRequest = vm::dismissError, title = { Text("操作未完成") }, text = { SelectionContainer { Text(message) } },
        confirmButton = { TextButton(onClick = vm::dismissError) { Text("知道了") } }) }
    vm.detail?.let { commit ->
        var parent by remember(commit) { mutableIntStateOf(0) }
        AlertDialog(onDismissRequest = vm::clearDetail, title = { Text("提交 ${commit.getString("oid").take(8)}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(commit.getString("message")) }
                val parents = commit.optJSONArray("parents")
                if ((parents?.length() ?: 0) > 1) Row { (0 until parents!!.length()).forEach { index ->
                    FilterChip(selected = parent == index, onClick = { parent = index }, label = { Text("父提交 ${index + 1}") })
                } }
                Text("变更文件（相对第一父提交）")
                val paths = commit.getJSONArray("paths")
                (0 until paths.length()).forEach { index ->
                    TextButton(onClick = { vm.loadDiff(paths.getString(index), "commit", commit.getString("oid"), parent) }) { Text(paths.getString(index)) }
                }
            } }, confirmButton = { TextButton(onClick = vm::clearDetail) { Text("关闭") } })
    }
    vm.diff?.let { result ->
        AlertDialog(onDismissRequest = vm::clearDiff, title = { Text(result.getString("path"), maxLines = 2) },
            text = { Column {
                if (result.optBoolean("truncated")) Text("内容已截断（上限 512 KiB）", color = MaterialTheme.colorScheme.error)
                if (result.optBoolean("preview")) Text("未跟踪文件预览")
                SelectionContainer { LazyColumn(Modifier.heightIn(max = 500.dp)) {
                    items(diffLines(result.optString("text"), result.optBoolean("preview"))) { row ->
                        val line = row.text
                        val color = when { line.startsWith('+') -> Color(0xFF1C7A49); line.startsWith('-') -> Color(0xFFB3261E); else -> MaterialTheme.colorScheme.onSurface }
                        Text("${row.old?.toString()?.padStart(4) ?: "    "} ${row.new?.toString()?.padStart(4) ?: "    "}  $line", color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                } }
            } }, confirmButton = { TextButton(onClick = vm::clearDiff) { Text("关闭") } })
    }
}

@Composable
private fun SessionList(vm: WorkspaceViewModel) {
    var search by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Field(vm.project, { vm.project = it }, "电脑上的项目绝对路径", enabled = !vm.busy)
            Button(onClick = vm::newSession, enabled = !vm.busy && vm.project.isNotBlank()) { Text("新建 Codex 会话") }
            Field(search, { search = it }, "搜索会话标题")
            TextButton(onClick = { vm.refreshSessions(search) }, enabled = !vm.busy) { Text("搜索 / 刷新历史") }
            if (vm.snapshotTime.isNotBlank()) Text("最近同步：${vm.snapshotTime}", style = MaterialTheme.typography.bodySmall)
        }
        items(vm.sessions, key = { it.getString("id") }) { session ->
            OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.openSession(session) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(session.string("name").ifBlank { session.string("preview").ifBlank { "新会话" } }, maxLines = 3, style = MaterialTheme.typography.titleMedium)
                    Text(session.string("cwd"), style = MaterialTheme.typography.bodySmall)
                    Text(if (session.optBoolean("managed")) "受管理会话" else "已有历史 · 只读", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (vm.hasMoreSessions) item { TextButton(onClick = { vm.refreshSessions(search, true) }, enabled = !vm.busy) { Text("加载更多") } }
    }
}

@Composable
private fun Chat(vm: WorkspaceViewModel) {
    val thread = vm.selected ?: return
    var resume by remember { mutableStateOf(false) }
    val runs = vm.runs.filter { it.optString("threadId") == thread.getString("id") }
    val active = runs.firstOrNull { it.optString("state") in listOf("queued", "running", "waiting_approval", "waiting_input", "unknown") }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(thread.string("name").ifBlank { thread.string("preview").ifBlank { "Codex" } }, style = MaterialTheme.typography.titleLarge)
                Text(thread.string("cwd"), style = MaterialTheme.typography.bodySmall)
                if (!thread.optBoolean("managed")) Button(onClick = { resume = true }, enabled = !vm.busy) { Text("恢复此会话") }
            }
            if (vm.hasMoreHistory) item { TextButton(onClick = vm::olderHistory, enabled = !vm.busy) { Text("加载更早的消息") } }
            items(vm.messages, key = { it.id }) { item ->
                var expanded by remember(item.id) { mutableStateOf(false) }
                val tool = item.role.startsWith("命令") || item.role.startsWith("工具")
                Surface(color = if (item.role == "你") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.role, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(if (tool && !expanded) item.text.take(300) else item.text,
                            fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default) }
                        if (tool && item.text.length > 300) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开输出") }
                    }
                }
            }
            items(vm.approvals.filter { a -> runs.any { it.optString("id") == a.optString("runId") } }, key = { it.getString("id") }) { approval ->
                ApprovalCard(vm, approval)
            }
            if (active != null) item {
                Text("执行状态：${stateName(active.optString("state"))}")
                active.string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (active.optString("state") == "unknown") TextButton(onClick = { vm.reconcile(active) }, enabled = !vm.busy) { Text("核实执行结果") }
                else TextButton(onClick = { vm.cancel(active) }, enabled = !vm.busy && active.string("turnId").isNotBlank()) { Text("取消本轮执行") }
            }
            if (active == null && runs.isNotEmpty()) item {
                Text("上一轮：${stateName(runs.first().optString("state"))}")
                runs.first().string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        com.worldcopy.agentdeck.feature.media.MediaInput(vm)
        FlowRow(Modifier.padding(horizontal = 12.dp)) {
            vm.attachments.forEachIndexed { index, attachment ->
                InputChip(selected = false, onClick = { vm.removeAttachment(attachment) }, enabled = !vm.busy && !vm.hasUnconfirmedSubmission,
                    label = { Text("${if (attachment.mime.startsWith("image")) "图片" else "录音"} ${index + 1} · ${if (attachment.uploaded) "已上传" else "待发送"} ×") })
            }
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.draft, vm::updateDraft, Modifier.weight(1f), label = { Text("输入消息 · 离线时保留草稿") }, maxLines = 5)
            Button(onClick = vm::send, enabled = !vm.busy && thread.optBoolean("managed") && (active == null || vm.hasUnconfirmedSubmission)) {
                Text(if (vm.hasUnconfirmedSubmission) "确认送达" else "发送")
            }
        }
    }
    if (resume) ConfirmDialog("恢复原有会话", "请确认电脑上的原会话已停止。恢复后将由 AgentDeck 服务继续执行。", { resume = false }) { resume = false; vm.resume() }
}

private fun stateName(state: String) = mapOf("queued" to "已排队", "running" to "执行中", "waiting_approval" to "等待审批", "waiting_input" to "等待回答",
    "completed" to "本轮完成", "failed" to "失败", "interrupted" to "已中断", "unknown" to "待核实")[state] ?: state

@Composable
private fun ApprovalCard(vm: WorkspaceViewModel, approval: JSONObject) {
    val params = approval.getJSONObject("params")
    var answers by remember(approval.getString("id")) { mutableStateOf<Map<String, String>>(emptyMap()) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("需要你的处理", style = MaterialTheme.typography.titleMedium)
        if (approval.getString("method") == "item/tool/requestUserInput") {
            val questions = params.optJSONArray("questions").objects()
            questions.forEach { q ->
                Text(q.optString("question"))
                q.optJSONArray("options").objects().forEach { option ->
                    FilterChip(selected = answers[q.getString("id")] == option.optString("label"), onClick = { answers = answers + (q.getString("id") to option.getString("label")) }, label = { Text(option.optString("label")) })
                }
                Field(answers[q.getString("id")] ?: "", { answers = answers + (q.getString("id") to it) }, "回答")
            }
            Button(onClick = { vm.answer(approval, answers) }, enabled = !vm.busy && questions.all { !answers[it.getString("id")].isNullOrBlank() }) { Text("提交回答") }
        } else {
            SelectionContainer { Text(listOf(params.string("command"), params.string("reason"), params.string("cwd")).filter { it.isNotBlank() }.joinToString("\n").ifBlank { params.toString(2) }) }
            Row {
                Button(onClick = { vm.approve(approval, true) }, enabled = !vm.busy) { Text("允许本次") }
                TextButton(onClick = { vm.approve(approval, false) }, enabled = !vm.busy) { Text("拒绝") }
            }
        }
    } }
}

@Composable
private fun ProjectHeader(vm: WorkspaceViewModel, refresh: () -> Unit) {
    Field(vm.project, { vm.project = it }, "电脑上的仓库路径", enabled = !vm.busy)
    TextButton(onClick = refresh, enabled = !vm.busy && vm.project.isNotBlank()) { Text("刷新") }
}

@Composable
private fun Changes(vm: WorkspaceViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ProjectHeader(vm, vm::refreshGit) }
        vm.gitState?.let { state ->
            item {
                Text("${state.string("branch")} · ${state.optInt("changedFiles")} 个变更文件", style = MaterialTheme.typography.titleMedium)
                Text("HEAD 历史 ${state.optInt("commitCount")} 次提交${if (state.optBoolean("shallow")) "（浅克隆，本地范围）" else ""}")
                Text(if (state.string("upstream").isBlank()) "未设置上游" else "${state.string("upstream")} · 领先 ${state.optInt("ahead")} / 落后 ${state.optInt("behind")}")
                Text("${state.string("root")}\n查询时间：${state.string("syncedAt")}", style = MaterialTheme.typography.bodySmall)
            }
            mapOf("staged" to "已暂存", "unstaged" to "未暂存", "untracked" to "未跟踪", "conflict" to "冲突").forEach { (group, label) ->
                val changes = state.optJSONArray("changes").objects().filter { it.getString("group") == group }
                if (changes.isNotEmpty()) item { Text("$label (${changes.size})", style = MaterialTheme.typography.titleSmall) }
                items(changes, key = { group + it.getString("path") }) { change ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.loadDiff(change.getString("path"), group) }) {
                        Column(Modifier.padding(12.dp)) { Text(change.getString("path")); Text(change.getString("status") + if (!change.isNull("additions")) "  +${change.optInt("additions")} −${change.optInt("deletions")}" else if (change.optBoolean("binary")) " · 二进制" else "", color = MaterialTheme.colorScheme.primary)
                            change.string("oldPath").takeIf { it.isNotBlank() }?.let { Text("原路径：$it") }
                        }
                    }
                }
            }
        }
    }
}

data class GraphRow(val node: Int, val before: List<String>, val after: List<String>, val oid: String, val parents: List<String>)
fun graphRows(commits: List<Pair<String, List<String>>>): List<GraphRow> {
    var lanes = emptyList<String>()
    return commits.map { (oid, parents) ->
        val before = lanes
        val at = lanes.indexOf(oid).takeIf { it >= 0 } ?: lanes.size
        val after = lanes.filter { it != oid }.toMutableList()
        var insert = at.coerceAtMost(after.size)
        parents.forEach { parent -> if (parent !in after) { after.add(insert.coerceAtMost(after.size), parent); insert++ } }
        lanes = after
        GraphRow(at, before, after.toList(), oid, parents)
    }
}

@Composable
private fun Graph(vm: WorkspaceViewModel) {
    val rows = remember(vm.commits) { graphRows(vm.commits.map { c -> c.getString("oid") to c.getJSONArray("parents").let { a -> (0 until a.length()).map { a.getString(it) } } }) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            ProjectHeader(vm) { vm.loadGraph() }
            Row {
                FilterChip(selected = vm.graphScope == "head", onClick = { vm.loadGraph(all = false) }, label = { Text("HEAD 历史") })
                FilterChip(selected = vm.graphScope == "all", onClick = { vm.loadGraph(all = true) }, label = { Text("全部已知引用") })
            }
        }
        items(vm.commits.size) { index ->
            val commit = vm.commits[index]; val row = rows[index]
            Row(Modifier.fillMaxWidth().height(90.dp).clickable(enabled = !vm.busy) { vm.loadCommit(commit.getString("oid")) }) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.width((maxOf(row.before.size, row.after.size, row.node + 1) * 14 + 12).dp).fillMaxHeight()) {
                    fun x(lane: Int) = (lane * 14 + 10).dp.toPx()
                    val center = 28.dp.toPx()
                    row.before.forEachIndexed { lane, oid ->
                        if (oid == row.oid) drawLine(color, Offset(x(lane), 0f), Offset(x(row.node), center), 2.dp.toPx())
                        else drawLine(color.copy(alpha = .5f), Offset(x(lane), 0f), Offset(x(row.after.indexOf(oid)), size.height), 2.dp.toPx())
                    }
                    row.parents.forEach { parent -> drawLine(color, Offset(x(row.node), center), Offset(x(row.after.indexOf(parent)), size.height), 2.dp.toPx()) }
                    drawCircle(color, 4.dp.toPx(), Offset(x(row.node), center))
                }
                Column(Modifier.padding(vertical = 8.dp).weight(1f)) {
                    Text(commit.getString("subject"), maxLines = 2, style = MaterialTheme.typography.titleSmall)
                    Text("${commit.getString("oid").take(8)} · ${commit.getString("author")}", style = MaterialTheme.typography.bodySmall)
                    Text(commit.string("refs"), maxLines = 1, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (vm.hasMoreCommits) item { TextButton(onClick = { vm.loadGraph(more = true) }, enabled = !vm.busy) { Text("加载后续父节点") } }
    }
}
