package com.worldcopy.agentdeck.feature.workspace

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.worldcopy.agentdeck.core.network.HostApi
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
fun JSONObject.string(name: String): String = if (isNull(name)) "" else optString(name)

data class DraftAttachment(val id: String, val path: String, val mime: String, val uploaded: Boolean = false)

data class ChatItem(val id: String, val role: String, val text: String)

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var projectSyncError by mutableStateOf<String?>(null); private set
    var connection by mutableStateOf("服务未连接"); private set
    var sessions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var projectSessions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var searchResults by mutableStateOf<List<JSONObject>?>(null); private set
    private var searchCursor by mutableStateOf<String?>(null)
    val hasMoreSearch get() = searchCursor != null
    var selected by mutableStateOf<JSONObject?>(null); private set
    var messages by mutableStateOf<List<ChatItem>>(emptyList()); private set
    var runs by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var approvals by mutableStateOf<List<JSONObject>>(emptyList()); private set
    private var projectValue by mutableStateOf("")
    var project: String
        get() = projectValue
        set(value) {
            if (value != projectValue) { gitState = null; commits = emptyList(); graphCursor = null; diff = null; detail = null }
            projectValue = value
        }
    var draft by mutableStateOf(""); private set
    var attachments by mutableStateOf<List<DraftAttachment>>(emptyList()); private set
    private var pendingAttachments: List<String>? = null
    var gitState by mutableStateOf<JSONObject?>(null); private set
    var commits by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var diff by mutableStateOf<JSONObject?>(null); private set
    var detail by mutableStateOf<JSONObject?>(null); private set
    var graphScope by mutableStateOf("head"); private set
    var snapshotTime by mutableStateOf(""); private set
    private var sessionCursor: String? = null
    private var historyCursor by mutableStateOf<String?>(null)
    val hasMoreHistory get() = !historyCursor.isNullOrBlank()
    private var graphCursor: String? = null
    val hasMoreSessions get() = !sessionCursor.isNullOrBlank()
    val hasMoreCommits get() = !graphCursor.isNullOrBlank()
    private var api: HostApi? = null
    val online get() = connection == "在线"
    private var eventsJob: Job? = null
    private var workJob: Job? = null
    private var reopen: (suspend () -> HostApi)? = null
    private var hostId = ""
    val hostName get() = (getApplication<Application>() as com.worldcopy.agentdeck.AgentDeckApplication).hosts.hosts.firstOrNull { it.id == hostId }?.name ?: "电脑"
    private var requestId: String? = null
    private var pendingText: String? = null
    private var cursor = 0L
    private val notifications = com.worldcopy.agentdeck.core.notifications.RunNotifications(application)
    private val cacheDir = File(application.noBackupFilesDir, "workspace").apply { mkdirs() }

    fun disconnect() {
        eventsJob?.cancel(); workJob?.cancel(); api?.close(); api = null; connection = "已断开 · 电脑任务继续"
    }
    fun clearCache() = work {
        withContext(Dispatchers.IO) {
            cacheDir.listFiles()?.filter { it.name.startsWith(hostId + "-") && !it.name.contains("-draft-") }?.forEach { it.delete() }
        }
        projectSessions = emptyList()
        if (api == null) { sessions = emptyList(); messages = emptyList(); gitState = null; commits = emptyList() }
    }
    fun dismissError() { error = null; projectSyncError = null }
    fun clearDiff() { diff = null }
    fun clearDetail() { detail = null }
    fun clearSession() { saveDraft(); selected = null; messages = emptyList() }
    fun reportError(message: String) { error = message }
    fun reportProjectSyncError(message: String) { projectSyncError = message; reportError(message) }
    fun attachImage(uri: android.net.Uri) = work { addImage(uri) }
    fun importShare(text: String, images: List<android.net.Uri>) = work {
        check(requestId == null) { "请先确认上次消息送达" }
        if (text.isNotBlank()) updateDraft(listOf(draft, text).filter { it.isNotBlank() }.joinToString("\n"))
        images.forEach { addImage(it) }
    }
    private suspend fun addImage(uri: android.net.Uri) {
        require(attachments.size < 4 && requestId == null) { "每条消息最多 4 个附件；请先确认上次消息送达" }
        val file = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0) { "无法读取此图片" }
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            val bitmap = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) } ?: error("无法解码图片")
            val orientation = runCatching { resolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } }.getOrNull()
            val matrix = android.graphics.Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> { setRotate(180f); postScale(-1f, 1f) }
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            val oriented = if (matrix.isIdentity) bitmap else android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val target = File(cacheDir, "${UUID.randomUUID()}.jpg")
            try { target.outputStream().use { check(oriented.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)) } }
            finally { if (oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
            target
        }
        attachments = attachments + DraftAttachment(UUID.randomUUID().toString(), file.absolutePath, "image/jpeg")
        saveDraft()
    }
    fun attachAudio(file: File) {
        if (attachments.size >= 4 || requestId != null) { file.delete(); error = "每条消息最多 4 个附件；请先确认上次消息送达"; return }
        attachments = attachments + DraftAttachment(UUID.randomUUID().toString(), file.absolutePath, "audio/mp4")
        saveDraft()
        transcribe(attachments.last())
    }
    fun transcribe(attachment: DraftAttachment) = work {
        check(requestId == null) { "请先确认上次消息送达" }
        val service = api ?: error("连接电脑后可重试转写；录音已保留")
        val threadId = selected?.getString("id") ?: error("请先选择会话")
        if (!attachment.uploaded) {
            service.upload(attachment.id, threadId, File(attachment.path), attachment.mime)
            attachments = attachments.map { if (it.id == attachment.id) it.copy(uploaded = true) else it }; saveDraft()
        }
        val text = service.transcribe(attachment.id, threadId)
        require(text.isNotBlank()) { "未识别到语音；可以重试或删除录音" }
        draft = listOf(draft, text).filter { it.isNotBlank() }.joinToString("\n")
        attachments = attachments.filter { it.id != attachment.id }; saveDraft()
        File(attachment.path).delete()
        service.removeAttachment(attachment.id)
    }
    fun removeAttachment(attachment: DraftAttachment) = work {
        check(requestId == null) { "请先确认上次消息送达" }
        if (attachment.uploaded) api!!.removeAttachment(attachment.id)
        File(attachment.path).delete()
        attachments = attachments.filter { it.id != attachment.id }; saveDraft()
    }
    fun updateDraft(text: String) { draft = text; saveDraft() }
    private fun file(name: String) = File(cacheDir, "$hostId-$name.json")
    private fun readCache(name: String): JSONObject? = file(name).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
    private fun writeCache(name: String, content: String) {
        val target = android.util.AtomicFile(file(name))
        val stream = target.startWrite()
        try { stream.write(content.toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    private fun saveDraft() {
        val id = selected?.optString("id") ?: return
        writeCache("draft-$id", JSONObject().put("draft", draft).put("requestId", requestId).put("pendingText", pendingText).put("pendingAttachments", pendingAttachments?.let { JSONArray(it) }).put("attachments", JSONArray(attachments.map { JSONObject().put("id", it.id).put("path", it.path).put("mime", it.mime).put("uploaded", it.uploaded) })).toString())
    }
    private fun work(onFailure: (String) -> Unit = {}, action: suspend () -> Unit) {
        if (busy) return
        workJob = viewModelScope.launch {
            busy = true
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { val message = e.message ?: "请求失败"; error = message; onFailure(message) }
            finally { busy = false }
        }
    }
    fun cancelWork() { workJob?.cancel() }
    fun searchHistory(query: String, more: Boolean = false, currentProject: Boolean = false) = work {
        val service = api ?: error("正文搜索需要连接电脑")
        val body = JSONObject().put("query", query).put("project", if (currentProject) project else "")
        if (more) body.put("cursor", searchCursor)
        val result = service.post("search", body)
        searchResults = (if (more) searchResults.orEmpty() else emptyList()) + result.optJSONArray("matches").objects()
        searchCursor = result.string("nextCursor").ifBlank { null }
    }
    fun clearSearch() { searchResults = null; searchCursor = null }
    fun browseProject(path: String) {
        clearSession(); clearSearch()
        sessions = projectSessions; sessionCursor = null
        project = path
    }
    fun openOffline(id: String) {
        eventsJob?.cancel(); api?.close(); api = null; saveDraft()
        hostId = id; selected = null; messages = emptyList(); approvals = emptyList(); runs = emptyList()
        connection = "离线 · 已缓存内容"
        work {
            sessions = emptyList()
            file("sessions").takeIf { it.exists() }?.let {
                val cached = JSONObject(it.readText()); sessions = cached.optJSONArray("data").objects(); projectSessions = sessions; snapshotTime = cached.optString("syncedAt")
            }
            val cachedGit = file("git").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            project = cachedGit?.string("root") ?: ""
            gitState = cachedGit
            readCache("graph")?.takeIf { it.string("project") == project }?.let {
                commits = it.optJSONArray("commits").objects(); graphScope = it.string("scope"); graphCursor = null
            }
        }
    }
    fun connect(id: String, port: Int, token: String, reconnect: suspend () -> HostApi) {
        reopen = reconnect
        if (hostId == id && api != null && connection == "在线") return
        eventsJob?.cancel(); api?.close(); saveDraft()
        hostId = id; selected = null; messages = emptyList(); gitState = null; commits = emptyList(); runs = emptyList(); approvals = emptyList()
        api = HostApi(port, token)
        work(onFailure = { projectSyncError = it }) {
            projectSyncError = null
            file("sessions").takeIf { it.exists() }?.let {
                val cached = JSONObject(it.readText()); sessions = cached.optJSONArray("data").objects(); projectSessions = sessions; snapshotTime = cached.optString("syncedAt")
            }
            val health = api!!.get("health")
            check(health.getInt("protocol") == 1) { "电脑服务协议不兼容" }
            refreshSnapshot()
            loadSessions()
            startEvents()
        }
    }
    private suspend fun refreshSnapshot() {
        val result = api!!.get("snapshot")
        runs = result.optJSONArray("runs").objects(); approvals = result.optJSONArray("approvals").objects()
        cursor = result.getLong("cursor"); connection = "在线"
    }
    private fun startEvents() {
        eventsJob = viewModelScope.launch {
            var backoff = 1000L
            while (isActive) {
                try {
                    api!!.events(cursor).collect { event ->
                        processEvent(event); cursor = event.getLong("seq"); connection = "在线"; backoff = 1000
                    }
                } catch (e: CancellationException) { throw e }
                catch (failure: Exception) {
                    if (failure is com.worldcopy.agentdeck.core.network.HostApiAuthException) {
                        try {
                            api?.close(); api = reopen!!.invoke()
                            api!!.get("health") // Verify the freshly read token before resuming the existing event cursor.
                            connection = "在线"
                            continue
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            connection = "服务认证失败，已暂停重连"; error = e.message; break
                        }
                    }
                    connection = "连接中断，等待恢复"
                    delay(backoff + kotlin.random.Random.nextLong(300)); backoff = (backoff * 2).coerceAtMost(30_000)
                    try {
                        api?.close(); api = reopen!!.invoke()
                        // A normal reconnect replays from the last applied event. Replacing the
                        // cursor with the newest snapshot would discard offline completions.
                        if (failure is com.worldcopy.agentdeck.core.network.EventCursorExpired) {
                          refreshSnapshot()
                          selected?.let { thread ->
                            val result = api!!.get("sessions/${thread.getString("id")}")
                            selected = result
                            historyCursor = result.string("nextCursor").ifBlank { null }
                            messages = result.optJSONArray("turns").objects().flatMap { it.optJSONArray("items").objects().mapNotNull(::parseItem) }
                            cacheMessages()
                          }
                        }
                    } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
                        connection = "认证失败，已暂停重连"; error = "请检查此主机的登录凭据"; break
                    } catch (e: com.worldcopy.agentdeck.core.ssh.HostKeyConfirmation) {
                        connection = "主机身份需核对，已暂停重连"; error = "请返回主机页核对 SSH 主机身份"; break
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { connection = "连接中断，等待恢复" }
                }
            }
        }
    }
    private suspend fun processEvent(event: JSONObject) {
        val data = event.getJSONObject("data")
        when (event.getString("type")) {
            "run.updated" -> {
                runs = listOf(data) + runs.filter { it.optString("id") != data.optString("id") }
                val state = data.optString("state")
                if (state in listOf("completed", "failed", "waiting_approval", "waiting_input")) {
                    val hostName = (getApplication<Application>() as com.worldcopy.agentdeck.AgentDeckApplication).hosts.hosts.firstOrNull { it.id == hostId }?.name ?: "电脑"
                    notifications.show(hostId, data.getString("threadId"), event.getLong("seq"), hostName + " · " + when (state) {
                        "completed" -> "本轮完成"; "failed" -> "执行失败"; "waiting_approval" -> "等待审批"; else -> "等待回答"
                    })
                }
            }
            "approval.requested" -> approvals = approvals.filter { it.optString("id") != data.optString("id") } + data
            "approval.resolved" -> approvals = approvals.filter { it.optString("id") != data.optString("id") }
            "agent.event" -> {
                val params = data.optJSONObject("params") ?: return
                if (params.optString("threadId") != selected?.optString("id")) return
                when (data.optString("method")) {
                    "item/started", "item/completed" -> params.optJSONObject("item")?.let { item ->
                        parseItem(item)?.let { parsed -> messages = if (messages.any { it.id == parsed.id }) messages.map { if (it.id == parsed.id) parsed else it } else messages + parsed }
                    }
                    "item/agentMessage/delta" -> {
                        val id = params.getString("itemId"); val old = messages.find { it.id == id }
                        val updated = ChatItem(id, "Agent", boundedText((old?.text ?: "") + params.optString("delta"), 64_000))
                        messages = if (old == null) messages + updated else messages.map { if (it.id == id) updated else it }
                    }
                    "turn/completed" -> cacheMessages()
                }
            }
        }
    }
    fun refreshSessions(search: String = "", more: Boolean = false) = work(onFailure = { projectSyncError = it }) {
        projectSyncError = null
        loadSessions(search, more)
    }
    private suspend fun loadSessions(search: String = "", more: Boolean = false) {
        val service = api ?: error("请先连接主机以同步项目")
        if (search.isBlank() && !more) {
            val all = readAllSessions { next -> service.get("sessions", next?.let { mapOf("cursor" to it) } ?: emptyMap()) }
            sessions = all
            projectSessions = all
            sessionCursor = null
            snapshotTime = java.time.Instant.now().toString()
            cacheSessions()
            return
        }
        val query = mutableMapOf("search" to search)
        if (more) sessionCursor?.let { query["cursor"] = it }
        val result = service.get("sessions", query)
        sessions = ((if (more) sessions else emptyList()) + result.optJSONArray("data").objects()).distinctBy { it.getString("id") }
        sessionCursor = result.string("nextCursor").ifBlank { null }
    }
    private fun cacheSessions() {
        writeCache("sessions", JSONObject().put("data", JSONArray(projectSessions)).put("syncedAt", snapshotTime).toString())
    }
    fun newSession() = work {
        require(project.isNotBlank()) { "请输入电脑上的项目绝对路径" }
        val result = api!!.post("sessions", JSONObject().put("cwd", project))
        showSession(result)
        sessions = listOf(result) + sessions.filter { it.string("id") != result.string("id") }
        projectSessions = listOf(result) + projectSessions.filter { it.string("id") != result.string("id") }
        cacheSessions()
    }
    fun openSession(session: JSONObject) = work {
        showSession(session)
        file("history-${session.getString("id")}").takeIf { it.exists() }?.let { cached ->
            messages = JSONObject(cached.readText()).optJSONArray("messages").objects().map { ChatItem(it.getString("id"), it.getString("role"), it.getString("text")) }
        }
        if (api == null) return@work
        val result = api!!.get("sessions/${session.getString("id")}")
        selected = result; historyCursor = result.string("nextCursor").ifBlank { null }
        val summary = JSONObject(result.toString()).apply { remove("turns") }
        projectSessions = projectSessions.map { if (it.string("id") == result.string("id")) summary else it }
        cacheSessions()
        messages = result.optJSONArray("turns").objects().flatMap { it.optJSONArray("items").objects().mapNotNull(::parseItem) }
        cacheMessages()
    }
    fun olderHistory() = work {
        val id = selected?.getString("id") ?: return@work
        val result = api!!.get("sessions/$id", mapOf("cursor" to (historyCursor ?: return@work)))
        val older = result.optJSONArray("turns").objects().flatMap { it.optJSONArray("items").objects().mapNotNull(::parseItem) }
        messages = (older + messages).distinctBy { it.id }
        historyCursor = result.string("nextCursor").ifBlank { null }
        cacheMessages()
    }
    private fun showSession(session: JSONObject) {
        saveDraft(); selected = session; project = session.optString("cwd"); messages = emptyList(); historyCursor = null
        val cached = file("draft-${session.getString("id")}").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        draft = cached?.optString("draft") ?: ""; requestId = cached?.string("requestId")?.ifBlank { null }; pendingText = cached?.string("pendingText")?.ifBlank { null }
        attachments = cached?.optJSONArray("attachments").objects().map { DraftAttachment(it.getString("id"), it.getString("path"), it.getString("mime"), it.optBoolean("uploaded")) }
        pendingAttachments = cached?.optJSONArray("pendingAttachments")?.let { a -> (0 until a.length()).map { a.getString(it) } }
    }
    fun resume() = work {
        val id = selected!!.getString("id")
        selected = api!!.post("sessions/$id/resume", JSONObject().put("confirmStopped", true))
    }
    fun send() = work {
        val session = selected ?: return@work
        check(session.optBoolean("managed")) { "请先恢复此会话" }
        require(draft.isNotBlank() || pendingText != null || attachments.isNotEmpty()) { "请输入消息" }
        require(attachments.none { it.mime.startsWith("audio/") }) { "请先转写录音，确认文字后再发送" }
        for (attachment in attachments.filter { !it.uploaded }) {
            api!!.upload(attachment.id, session.getString("id"), File(attachment.path), attachment.mime)
            attachments = attachments.map { if (it.id == attachment.id) it.copy(uploaded = true) else it }
            saveDraft()
        }
        val id = requestId ?: UUID.randomUUID().toString().also {
            requestId = it; pendingText = draft.ifBlank { "请查看附件。" }; pendingAttachments = attachments.map { a -> a.id }
        }
        saveDraft()
        val result = api!!.post("runs", JSONObject().put("threadId", session.getString("id")).put("clientRequestId", id).put("text", pendingText).put("attachments", JSONArray(pendingAttachments ?: emptyList<String>())))
        runs = listOf(result) + runs.filter { it.optString("id") != result.optString("id") }
        if (draft == pendingText) draft = ""
        requestId = null; pendingText = null; pendingAttachments = null
        attachments.forEach { File(it.path).delete() }; attachments = emptyList(); saveDraft()
    }
    val hasUnconfirmedSubmission get() = requestId != null
    fun cancel(run: JSONObject) = work { api!!.post("runs/${run.getString("id")}/cancel") }
    fun reconcile(run: JSONObject) = work {
        val result = api!!.post("runs/${run.getString("id")}/reconcile")
        runs = runs.map { if (it.getString("id") == result.getString("id")) result else it }
    }
    fun approve(approval: JSONObject, accept: Boolean) = work {
        api!!.post("approvals/${approval.getString("id")}", JSONObject().put("decision", if (accept) "accept" else "decline"))
        approvals = approvals.filter { it.getString("id") != approval.getString("id") }
    }
    fun answer(approval: JSONObject, answers: Map<String, String>) = work {
        val values = JSONObject(); answers.forEach { (id, answer) -> values.put(id, JSONObject().put("answers", JSONArray(listOf(answer)))) }
        api!!.post("approvals/${approval.getString("id")}", JSONObject().put("answers", values))
        approvals = approvals.filter { it.getString("id") != approval.getString("id") }
    }
    fun refreshGit() = work {
        gitState = api!!.get("git/status", mapOf("cwd" to project))
        writeCache("git", gitState.toString())
    }
    fun loadGraph(more: Boolean = false, all: Boolean = graphScope == "all") = work {
        check(api != null) { "当前显示已缓存的 Graph，连接电脑后可刷新" }
        graphScope = if (all) "all" else "head"
        val query = mutableMapOf("cwd" to project, "scope" to graphScope)
        if (more) graphCursor?.let { query["cursor"] = it }
        val result = api!!.get("git/graph", query)
        commits = ((if (more) commits else emptyList()) + result.optJSONArray("commits").objects()).distinctBy { it.getString("oid") }
        graphCursor = result.string("nextCursor").ifBlank { null }
        writeCache("graph", JSONObject().put("project", project).put("scope", graphScope).put("commits", JSONArray(commits)).toString())
    }
    fun loadDiff(path: String, group: String, commit: String? = null, parent: Int = 0) = work {
        val query = mutableMapOf("cwd" to project, "path" to path, "group" to group, "parent" to parent.toString())
        commit?.let { query["commit"] = it }
        if (api == null) {
            val cached = readCache("diff") ?: error("此 Diff 尚未缓存，请连接电脑")
            check(cached.string("query") == JSONObject(query).toString()) { "此 Diff 尚未缓存，请连接电脑" }
            diff = cached.getJSONObject("result"); return@work
        }
        diff = api!!.get("git/diff", query).put("path", path)
        writeCache("diff", JSONObject().put("query", JSONObject(query).toString()).put("result", diff).toString())
    }
    fun loadCommit(oid: String, parent: Int = 0) = work {
        if (api == null) {
            val cached = readCache("commit") ?: error("此提交详情尚未缓存，请连接电脑")
            check(cached.string("project") == project && cached.getJSONObject("result").string("oid") == oid && cached.getJSONObject("result").optInt("parentIndex") == parent) { "此提交详情尚未缓存，请连接电脑" }
            detail = cached.getJSONObject("result"); return@work
        }
        detail = api!!.get("git/commit", mapOf("cwd" to project, "oid" to oid, "parent" to parent.toString()))
        writeCache("commit", JSONObject().put("project", project).put("result", detail).toString())
    }
    private fun cacheMessages() {
        val id = selected?.optString("id") ?: return
        writeCache("history-$id", JSONObject().put("messages", JSONArray(messages.takeLast(500).map {
            JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
        })).toString())
    }
    private fun parseItem(item: JSONObject): ChatItem? {
        val type = item.optString("type"); val id = item.optString("id")
        return when (type) {
            "userMessage" -> ChatItem(id, "你", item.optJSONArray("content").objects().joinToString("\n") { if (it.optString("type").contains("Audio", true)) "[录音附件]" else if (it.optString("type").contains("Image", true)) "[图片附件]" else it.optString("text", "[附件]") })
            "agentMessage", "plan" -> ChatItem(id, if (type == "plan") "计划" else "Agent", boundedText(item.optString("text"), 64_000))
            "commandExecution" -> ChatItem(id, "命令 · ${item.optString("status")}", item.optString("command") + "\n" + boundedText(item.string("aggregatedOutput"), 16_000))
            "fileChange" -> ChatItem(id, "文件变更", item.optJSONArray("changes").objects().joinToString("\n") { it.optString("path") })
            "mcpToolCall" -> ChatItem(id, "工具 · ${item.optString("status")}", "${item.optString("server")}/${item.optString("tool")}")
            else -> null
        }
    }
    private fun boundedText(text: String, limit: Int) = if (text.length > limit) "[前部内容已省略，完整记录保留在电脑]\n" + text.takeLast(limit) else text
    override fun onCleared() { eventsJob?.cancel(); api?.close() }
}
