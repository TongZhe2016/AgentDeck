package com.worldcopy.agentdeck.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@Composable
fun WorkspaceScreen(vm: WorkspaceViewModel, fromProjects: Boolean = false, projectScope: String? = null, back: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab, vm.online) {
        if (tab != 0 && vm.online && vm.project.isNotBlank()) {
            val watchedProject = vm.project
            if (tab == 2) vm.loadGraph()
            while (vm.project == watchedProject) {
                if (!vm.busy) vm.refreshGit()
                kotlinx.coroutines.delay(15_000)
            }
        }
    }
    BackHandler { when { vm.diff != null -> vm.clearDiff(); vm.detail != null -> vm.clearDetail(); fromProjects -> { vm.clearSession(); back() }; vm.selected != null -> vm.clearSession(); else -> back() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (fromProjects) { vm.clearSession(); back() } else if (vm.selected != null) vm.clearSession() else back() }) { Text(if (fromProjects) "‹ 项目" else if (vm.selected == null) "‹ 主机" else "‹ 会话") }
            Text(vm.connection, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary)
        }
        Text(vm.hostName, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TabRow(selectedTabIndex = tab) {
            listOf("会话", "Changes", "Graph").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        when (tab) { 0 -> if (vm.selected == null) SessionList(vm, projectScope) else Chat(vm); 1 -> Changes(vm); 2 -> Graph(vm) }
    }
    vm.error?.let { message -> AlertDialog(onDismissRequest = vm::dismissError, title = { Text("操作未完成") }, text = { SelectionContainer { Text(message) } },
        confirmButton = { TextButton(onClick = vm::dismissError) { Text("知道了") } }) }
    vm.detail?.let { commit ->
        val parent = commit.optInt("parentIndex")
        AlertDialog(onDismissRequest = vm::clearDetail, title = { Text("提交 ${commit.getString("oid").take(8)}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(commit.getString("message")) }
                Text("${commit.string("author")} · ${commit.string("date")}")
                val parents = commit.optJSONArray("parents")
                if ((parents?.length() ?: 0) > 1) Row { (0 until parents!!.length()).forEach { index ->
                    FilterChip(selected = parent == index, onClick = { vm.loadCommit(commit.getString("oid"), index) }, enabled = !vm.busy, label = { Text("父提交 ${index + 1}") })
                } }
                Text("变更文件（相对父提交 ${parent + 1}）")
                val paths = commit.getJSONArray("paths")
                (0 until paths.length()).forEach { index ->
                    TextButton(onClick = { vm.loadDiff(paths.getString(index), "commit", commit.getString("oid"), parent) }) { Text(paths.getString(index)) }
                }
            } }, confirmButton = { TextButton(onClick = vm::clearDetail) { Text("关闭") } },
            dismissButton = { TextButton(onClick = {
                vm.updateDraft(vm.draft + "\n项目：${vm.project}\n提交：${commit.getString("oid")}\n${commit.getString("message")}")
                vm.clearDetail(); tab = 0
            }, enabled = vm.selected != null && !vm.hasUnconfirmedSubmission) { Text("引用到对话") } })
    }
    vm.diff?.let { result ->
        val context = LocalContext.current
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let {
                runCatching { context.contentResolver.openOutputStream(it)?.use { stream -> stream.write(result.optString("text").toByteArray()) } }
                    .onFailure { vm.reportError(it.message ?: "无法导出 Diff") }
            }
        }
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
            } }, confirmButton = { TextButton(onClick = vm::clearDiff) { Text("关闭") } },
            dismissButton = { Row {
                TextButton(onClick = { export.launch("agentdeck-diff.patch") }) { Text("导出") }
                TextButton(onClick = {
                    vm.updateDraft(vm.draft + "\n项目：${vm.project}\n文件：${result.getString("path")}\n${result.optString("text").take(8000)}")
                    vm.clearDiff(); tab = 0
                }, enabled = vm.selected != null && !vm.hasUnconfirmedSubmission) { Text("引用到对话") }
            } })
    }
}

@Composable
private fun SessionList(vm: WorkspaceViewModel, projectScope: String?) {
    var search by remember { mutableStateOf("") }
    var currentProject by remember { mutableStateOf(projectScope != null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (projectScope == null) Field(vm.project, { vm.project = it }, "电脑上的项目绝对路径", enabled = !vm.busy)
            else Text(projectScope, style = MaterialTheme.typography.titleMedium)
            Button(onClick = vm::newSession, enabled = vm.online && !vm.busy && vm.project.isNotBlank()) { Text("新建 Codex 会话") }
            Field(search, { search = it; vm.clearSearch() }, "搜索标题或正文", enabled = !vm.busy)
            Row {
                TextButton(onClick = { vm.clearSearch(); vm.refreshSessions(search) }, enabled = !vm.busy) { Text("搜索标题 / 刷新") }
                TextButton(onClick = { vm.searchHistory(search, currentProject = currentProject) }, enabled = !vm.busy && search.isNotBlank()) { Text("搜索正文") }
                if (vm.busy) TextButton(onClick = vm::cancelWork) { Text("取消查询") }
            }
            FilterChip(selected = currentProject, onClick = { currentProject = !currentProject; vm.clearSearch() }, enabled = projectScope == null && !vm.busy && vm.project.isNotBlank(), label = { Text("正文限当前项目") })
            if (vm.snapshotTime.isNotBlank()) Text("最近同步：${vm.snapshotTime}", style = MaterialTheme.typography.bodySmall)
        }
        vm.searchResults?.let { matches ->
            item { Text("本次扫描命中 ${matches.size} 条${if (vm.hasMoreSearch) "，可继续扫描更早历史" else ""}") }
            items(matches) { match ->
                OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.openSession(match.getJSONObject("thread")) }) {
                    Column(Modifier.padding(12.dp)) { Text(match.getString("excerpt")); Text(match.getJSONObject("thread").string("cwd"), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (vm.hasMoreSearch) item { TextButton(onClick = { vm.searchHistory(search, true, currentProject) }, enabled = !vm.busy) { Text("继续搜索更早历史") } }
        }
        items(if (vm.searchResults == null) vm.sessions.filter { projectScope == null || it.string("cwd").trimEnd('/') == projectScope.trimEnd('/') } else emptyList(), key = { it.getString("id") }) { session ->
            OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.openSession(session) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(session.string("name").ifBlank { session.string("preview").ifBlank { "新会话" } }, maxLines = 3, style = MaterialTheme.typography.titleMedium)
                    Text(session.string("cwd"), style = MaterialTheme.typography.bodySmall)
                    Text(if (session.optBoolean("managed")) "受管理会话" else "已有历史 · 只读", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (vm.searchResults == null && vm.hasMoreSessions) item { TextButton(onClick = { vm.refreshSessions(search, true) }, enabled = !vm.busy) { Text("加载更多") } }
    }
}

@Composable
private fun Chat(vm: WorkspaceViewModel) {
    val thread = vm.selected ?: return
    var resume by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.messages.lastOrNull()) {
        val layout = listState.layoutInfo
        if (layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3) {
            listState.scrollToItem(layout.totalItemsCount - 1)
        }
    }
    val runs = vm.runs.filter { it.optString("threadId") == thread.getString("id") }
    val active = runs.firstOrNull { it.optString("state") in listOf("queued", "running", "waiting_approval", "waiting_input", "unknown") }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        SelectionContainer {
                            if (tool) Text(if (!expanded) item.text.take(300) else item.text, fontFamily = FontFamily.Monospace)
                            else MessageText(item.text)
                        }
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
        if (vm.messages.isNotEmpty()) TextButton(onClick = { scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }) { Text("查看最新消息") }
        com.worldcopy.agentdeck.feature.media.MediaInput(vm)
        FlowRow(Modifier.padding(horizontal = 12.dp)) {
            vm.attachments.forEachIndexed { index, attachment ->
                if (attachment.mime.startsWith("image/")) com.worldcopy.agentdeck.feature.media.ImageThumbnail(attachment.path)
                if (attachment.mime.startsWith("audio/")) TextButton(onClick = { vm.transcribe(attachment) }, enabled = !vm.busy && !vm.hasUnconfirmedSubmission) { Text("重试转写录音 ${index + 1}") }
                InputChip(selected = false, onClick = { vm.removeAttachment(attachment) }, enabled = !vm.busy && !vm.hasUnconfirmedSubmission,
                    label = { Text("${if (attachment.mime.startsWith("image")) "图片" else "录音"} ${index + 1} · ${if (attachment.uploaded) "已上传" else "本地"} ×") })
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
    var filter by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ProjectHeader(vm, vm::refreshGit) }
        item { Field(filter, { filter = it }, "按文件名或目录筛选") }
        vm.gitState?.let { state ->
            item {
                Text("${state.string("branch")} · ${state.optInt("changedFiles")} 个变更文件", style = MaterialTheme.typography.titleMedium)
                Text("HEAD 历史 ${state.optInt("commitCount")} 次提交${if (state.optBoolean("shallow")) "（浅克隆，本地范围）" else ""}")
                Text(if (state.string("upstream").isBlank()) "未设置上游" else "${state.string("upstream")} · 领先 ${state.optInt("ahead")} / 落后 ${state.optInt("behind")}")
                Text("${state.string("root")}\n查询时间：${state.string("syncedAt")}", style = MaterialTheme.typography.bodySmall)
            }
            mapOf("staged" to "已暂存", "unstaged" to "未暂存", "untracked" to "未跟踪", "conflict" to "冲突").forEach { (group, label) ->
                val changes = state.optJSONArray("changes").objects().filter { it.getString("group") == group && it.getString("path").contains(filter, ignoreCase = true) }
                if (changes.isNotEmpty()) item { Text("$label (${changes.size})", style = MaterialTheme.typography.titleSmall) }
                items(changes, key = { group + it.getString("path") }) { change ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.loadDiff(change.getString("path"), group) }) {
                        Column(Modifier.padding(12.dp)) { Text(change.getString("path")); Text(change.getString("status") + if (!change.isNull("additions")) "  +${change.optInt("additions")} −${change.optInt("deletions")}" else if (change.optBoolean("binary")) " · 二进制" else "", color = MaterialTheme.colorScheme.primary)
                            change.string("oldPath").takeIf { it.isNotBlank() }?.let { Text("原路径：$it") }
                            change.string("submodule").takeIf { it.startsWith("S") }?.let { Text("子模块：$it") }
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
    val commits = vm.commits
    val entries = remember(commits) {
        val rows = graphRows(commits.map { c -> c.getString("oid") to c.getJSONArray("parents").let { a -> (0 until a.length()).map { a.getString(it) } } })
        commits.zip(rows)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            ProjectHeader(vm) { vm.loadGraph() }
            Row {
                FilterChip(selected = vm.graphScope == "head", onClick = { vm.loadGraph(all = false) }, label = { Text("HEAD 历史") })
                FilterChip(selected = vm.graphScope == "all", onClick = { vm.loadGraph(all = true) }, label = { Text("全部已知引用") })
            }
        }
        items(entries, key = { it.first.getString("oid") }) { (commit, row) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).height(IntrinsicSize.Min).clickable(enabled = !vm.busy) { vm.loadCommit(commit.getString("oid")) }) {
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
                    Text(commit.string("date").take(10), style = MaterialTheme.typography.bodySmall)
                    Text(commit.string("refs"), maxLines = 1, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (vm.hasMoreCommits) item { TextButton(onClick = { vm.loadGraph(more = true) }, enabled = !vm.busy) { Text("加载后续父节点") } }
    }
}
