package com.worldcopy.agentdeck.feature.workspace

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.worldcopy.agentdeck.ui.components.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.feature.hosts.Field
import org.json.JSONObject
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

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
    BackHandler { when { vm.diff != null -> vm.clearDiff(); fromProjects -> { vm.clearSession(); back() }; vm.selected != null -> vm.clearSession(); else -> back() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (fromProjects) { vm.clearSession(); back() } else if (vm.selected != null) vm.clearSession() else back() }) {
                DeckGlyph(DeckIcon.Back, if (fromProjects) "返回项目" else if (vm.selected == null) "返回主机" else "返回会话")
            }
            Column(Modifier.weight(1f)) {
                Text(vm.hostName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(vm.connection, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (vm.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(3f), divider = {}) {
                listOf("会话", "Changes", "Graph").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }) {
                        Text(title, Modifier.padding(vertical = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (tab) { 0 -> if (vm.selected == null) SessionList(vm, projectScope) else key(vm, vm.selected?.string("id")) { Chat(vm) }; 1 -> Changes(vm); 2 -> Graph(vm) { tab = 0 } }
    }
    vm.error?.let { message -> AlertDialog(onDismissRequest = vm::dismissError, title = { Text("操作未完成") }, text = { SelectionContainer { Text(message) } },
        confirmButton = { TextButton(onClick = vm::dismissError) { Text("知道了") } }) }
    vm.diff?.let { result ->
        DiffViewer(result, vm::clearDiff,
            quote = if (vm.selected != null && !vm.hasUnconfirmedSubmission) ({
                vm.updateDraft(vm.draft + "\n项目：${vm.project}\n文件：${result.getString("path")}\n${result.optString("text").take(8000)}")
                vm.clearDiff(); tab = 0
            }) else null,
            error = vm::reportError)

    }
}

@Composable
private fun SessionList(vm: WorkspaceViewModel, projectScope: String?) {
    var search by remember { mutableStateOf("") }
    var currentProject by remember { mutableStateOf(projectScope != null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (projectScope == null) Field(vm.project, { vm.project = it }, "电脑上的项目绝对路径", enabled = !vm.busy)
            else Text(projectScope, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = vm::newSession, enabled = vm.online && !vm.busy && vm.project.isNotBlank()) { Text("新建 Codex 会话") }
            Field(search, { search = it; vm.clearSearch() }, "搜索标题或正文", enabled = !vm.busy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(session.string("name").ifBlank { session.string("preview").ifBlank { "新会话" } }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(session.string("cwd"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    StatusLabel(if (session.optBoolean("managed")) "受管理会话" else "已有会话 · 可继续", positive = session.optBoolean("managed"))
                }
            }
        }
        if (vm.searchResults == null && vm.hasMoreSessions) item { TextButton(onClick = { vm.refreshSessions(search, true) }, enabled = !vm.busy) { Text("加载更多") } }
    }
}

@Composable
private fun Chat(vm: WorkspaceViewModel) {
    val thread = vm.selected ?: return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(vm.hasExternalWriter) {
        if (vm.hasExternalWriter) { focus.clearFocus(); keyboard?.hide() }
    }
    val onMessageLink = rememberMessageLinkHandler(vm)
    val listState = rememberLazyListState()
    var positioned by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestMessage = vm.messages.lastOrNull()
    // Capture the position before new content is measured and stable item keys shift its index.
    val followLatest = remember(latestMessage) {
        !positioned || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
    }
    LaunchedEffect(latestMessage) {
        if (latestMessage != null && followLatest) {
            listState.scrollToItem(0)
            positioned = true
        }
    }
    val timeline = remember(vm.messages) { executionTimeline(vm.messages) }
    val runs = vm.runs.filter { it.optString("threadId") == thread.getString("id") }
    val active = runs.firstOrNull { it.optString("state") in listOf("queued", "running", "waiting_approval", "waiting_input", "unknown") }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val composerMaxHeight = maxHeight * 0.6f
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).testTag("chat-messages"), state = listState, reverseLayout = true,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active == null && runs.isNotEmpty()) item(key = "last-run") {
                    Column {
                        Text("上一轮：${stateName(runs.first().optString("state"))}")
                        runs.first().string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (active != null) item(key = "active-run") {
                    Column {
                        if (vm.messages.none { it.execution && it.turnId == active.string("turnId") }) {
                            ExecutionCard(active.string("turnId").ifBlank { active.getString("id") }, emptyList(), active.string("state"))
                        }
                        active.string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (active.optString("state") == "unknown") TextButton(onClick = { vm.reconcile(active) }, enabled = !vm.busy) { Text("核实执行结果") }
                        else TextButton(onClick = { vm.cancel(active) }, enabled = !vm.busy && active.string("turnId").isNotBlank()) { Text("取消本轮执行") }
                    }
                }
                items(vm.approvals.filter { a -> runs.any { it.optString("id") == a.optString("runId") } }.asReversed(), key = { it.getString("id") }) { approval ->
                    ApprovalCard(vm, approval)
                }
                items(timeline.asReversed(), key = { it.key }) { entry ->
                    val item = entry.message
                    if (item == null) {
                        val run = runs.firstOrNull { it.string("turnId") == entry.turnId }
                        val latest = timeline.lastOrNull { it.message == null && it.turnId == entry.turnId }?.key == entry.key
                        ExecutionCard(entry.key, entry.steps, if (latest) run?.string("state") ?: "completed" else "completed")
                    } else Surface(color = if (item.role == "你") MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(start = if (item.role == "你") 24.dp else 0.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            SelectionContainer { MessageText(item.text, onMessageLink) }
                        }
                    }
                }
                if (vm.hasMoreHistory) item { TextButton(onClick = vm::olderHistory, enabled = !vm.busy) { Text("加载更早的消息") } }
                item(key = "thread-header") {
                    Column {
                        Text(thread.string("name").ifBlank { thread.string("preview").ifBlank { "Codex" } }, style = MaterialTheme.typography.titleLarge)
                        Text(thread.string("cwd"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (vm.messages.isNotEmpty() && listState.canScrollBackward) TextButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                Text("查看最新消息")
            }
            Surface(modifier = Modifier.heightIn(max = composerMaxHeight), color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 1.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (vm.hasExternalWriter) {
                        vm.writerNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = vm::takeOverSession, enabled = vm.online && !vm.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("takeover-session")) {
                            Text(if (vm.busy) "正在接管…" else if (vm.writerNotice != null) "重试接管" else "接管会话")
                        }
                    } else {
                        ExecutionSettingsBar(vm, enabled = vm.online && !vm.busy && active == null && !vm.hasUnconfirmedSubmission)
                        if (vm.attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.attachments.forEachIndexed { index, attachment ->
                                if (attachment.mime.startsWith("image/")) com.worldcopy.agentdeck.feature.media.ImageThumbnail(attachment.path)
                                if (attachment.mime.startsWith("audio/")) TextButton(onClick = { vm.transcribe(attachment) }, enabled = !vm.busy && !vm.hasUnconfirmedSubmission) { Text("重试转写录音 ${index + 1}") }
                                InputChip(selected = false, onClick = { vm.removeAttachment(attachment) }, enabled = !vm.busy && !vm.hasUnconfirmedSubmission,
                                    label = { Text("${if (attachment.mime.startsWith("image")) "图片" else "录音"} ${index + 1} · ${if (attachment.uploaded) "已上传" else "本地"}") },
                                    trailingIcon = { DeckGlyph(DeckIcon.Close, modifier = Modifier.size(16.dp)) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            OutlinedTextField(vm.draft, vm::updateDraft, Modifier.weight(1f), label = { Text("输入消息") }, maxLines = 4,
                                shape = MaterialTheme.shapes.medium)
                            Button(onClick = vm::send, enabled = vm.online && !vm.busy && (active == null || vm.hasUnconfirmedSubmission),
                                contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 56.dp)) {
                                Text(if (vm.hasUnconfirmedSubmission) "确认送达" else if (!thread.optBoolean("managed")) "继续对话" else "发送")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            com.worldcopy.agentdeck.feature.media.MediaInput(vm)
                            Text("草稿保存在此设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                    if (vm.showFork) OutlinedButton(onClick = vm::forkSession, enabled = vm.online && !vm.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fork-session")) { Text("Fork") }
                }
            }

        }
    }
}

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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.approve(approval, true) }, enabled = !vm.busy) { Text("允许本次") }
                TextButton(onClick = { vm.approve(approval, false) }, enabled = !vm.busy) { Text("拒绝") }
            }
        }
    } }
}

@Composable
private fun ProjectHeader(vm: WorkspaceViewModel, refresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { Field(vm.project, { vm.project = it }, "电脑上的仓库路径", enabled = !vm.busy) }
        IconButton(onClick = refresh, enabled = !vm.busy && vm.project.isNotBlank()) { DeckGlyph(DeckIcon.Sync, "刷新") }
    }
}

@Composable
private fun Changes(vm: WorkspaceViewModel) {
    var filter by remember { mutableStateOf("") }
    LazyColumn(Modifier.testTag("git-changes"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    QuietCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.loadDiff(change.getString("path"), group) }) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeckGlyph(DeckIcon.Code, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val path = change.getString("path")
                                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if ('/' in path) Text(path.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!change.isNull("additions")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("+${change.optInt("additions")}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    Text("−${change.optInt("deletions")}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                } else if (change.optBoolean("binary")) Text("二进制", style = MaterialTheme.typography.bodySmall)
                                change.string("oldPath").takeIf { it.isNotBlank() }?.let { Text("原路径：$it", style = MaterialTheme.typography.bodySmall) }
                                change.string("submodule").takeIf { it.startsWith("S") }?.let { Text("子模块：$it", style = MaterialTheme.typography.bodySmall) }
                            }
                            StatusLabel(change.getString("status"))
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
private fun Graph(vm: WorkspaceViewModel, showChat: () -> Unit) {
    var expandedOid by remember(vm.project, vm.graphScope) { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(java.time.Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000); now = java.time.Instant.now() }
    }
    BackHandler(enabled = expandedOid != null && vm.diff == null) { expandedOid = null; vm.clearDetail() }
    val commits = vm.commits
    val entries = remember(commits) {
        val rows = graphRows(commits.map { c -> c.getString("oid") to c.getJSONArray("parents").let { a -> (0 until a.length()).map { a.getString(it) } } })
        commits.zip(rows)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            ProjectHeader(vm) { vm.loadGraph() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.graphScope == "head", onClick = { vm.loadGraph(all = false) }, label = { Text("HEAD 历史") })
                FilterChip(selected = vm.graphScope == "all", onClick = { vm.loadGraph(all = true) }, label = { Text("全部已知引用") })
            }
        }
        items(entries, key = { it.first.getString("oid") }) { (commit, row) ->
            val oid = commit.getString("oid")
            val detail = vm.detail?.takeIf { it.string("oid") == oid }
            CommitGraphRow(commit, row, expandedOid == oid, detail, vm.busy, now,
                toggle = {
                    if (expandedOid == oid) { expandedOid = null; vm.clearDetail() }
                    else { expandedOid = oid; vm.clearDetail(); vm.loadCommit(oid) }
                },
                loadParent = { vm.loadCommit(oid, it) },
                openDiff = { path, parent -> vm.loadDiff(path, "commit", oid, parent) },
                quote = if (vm.selected != null && !vm.hasUnconfirmedSubmission && detail != null) ({
                    vm.updateDraft(vm.draft + "\n项目：${vm.project}\n提交：$oid\n${detail.string("message")}")
                    expandedOid = null; vm.clearDetail(); showChat()
                }) else null)
        }

        if (vm.hasMoreCommits) item { TextButton(onClick = { vm.loadGraph(more = true) }, enabled = !vm.busy) { Text("加载后续父节点") } }
    }
}
