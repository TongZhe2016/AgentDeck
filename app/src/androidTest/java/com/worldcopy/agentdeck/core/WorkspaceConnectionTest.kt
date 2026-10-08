package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.network.HostApi
import com.worldcopy.agentdeck.core.notifications.ConnectionService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WorkspaceConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun catalogEventsUpdateProjectsAndReplayOfflineChangesWithoutReloadingHistory() {
        val app = compose.activity.application as AgentDeckApplication
        val hostId = "catalog-${java.util.UUID.randomUUID()}"
        var workspace = app.workspace(hostId)
        fun item(id: String, name: String, updated: Long) = JSONObject().put("id", id).put("name", name).put("cwd", "/fixture").put("updatedAt", updated)
        File(app.noBackupFilesDir, "workspace/$hostId-sessions.json").writeText(
            JSONObject().put("data", JSONArray(listOf(item("archived-before-upgrade", "Old cache", 80)))).toString())
        var offline = false
        WorkspaceFixtureService(catalogResponse = { uri ->
            if (offline) {
                assertEquals("2", uri.getQueryParameter("after"))
                JSONObject().put("data", JSONArray(listOf(item("thread", "Original", 100))))
                    .put("catalogCursor", 3).put("changes", JSONObject()
                        .put("upserted", JSONArray(listOf(item("offline", "Created while disconnected", 103))))
                        .put("removed", JSONArray(listOf("external"))))
            } else JSONObject().put("data", JSONArray(listOf(item("thread", "Original", 100), item("older", "Older", 90))))
                .put("catalogCursor", 0)
        }).use { service ->
            try {
                compose.runOnUiThread { workspace.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { workspace.online && !workspace.busy && workspace.projectSessions.size == 2 }
                compose.runOnUiThread { workspace.openSession(item("thread", "Original", 100)) }
                compose.waitUntil(10_000) { !workspace.busy && workspace.messages.isNotEmpty() }
                compose.runOnUiThread { workspace.updateDraft("Keep this draft") }
                fun event(seq: Long, upserted: JSONObject, removed: List<String> = emptyList()) {
                    service.events.offer(JSONObject().put("seq", seq).put("type", "sessions.changed").put("data", JSONObject()
                        .put("upserted", JSONArray(listOf(upserted))).put("removed", JSONArray(removed))).toString())
                }
                event(1, item("external", "New desktop conversation", 101))
                compose.waitUntil(10_000) { workspace.projectSessions.any { it.optString("id") == "external" } }
                event(2, item("external", "Renamed desktop conversation", 102), listOf("older"))
                compose.waitUntil(10_000) { workspace.projectSessions.first().optString("name") == "Renamed desktop conversation" && workspace.projectSessions.size == 2 }
                assertEquals(1, service.lists.get())
                assertEquals(1, service.histories.get())
                assertEquals("thread", workspace.selected!!.getString("id"))
                assertEquals("Keep this draft", workspace.draft)
                assertEquals("初始回复", workspace.messages.single().text)
                val cache = File(app.noBackupFilesDir, "workspace/$hostId-sessions.json")
                compose.waitUntil(10_000) { runCatching { JSONObject(cache.readText()).optLong("catalogCursor") == 2L }.getOrDefault(false) }
                compose.runOnUiThread { workspace.disconnect(); app.workspaces.remove(hostId); workspace = com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel(app) }
                offline = true
                compose.runOnUiThread { workspace.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { !workspace.busy && workspace.projectSessions.any { it.optString("id") == "offline" } }
                assertFalse(workspace.projectSessions.any { it.optString("id") == "external" || it.optString("id") == "older" })
                assertEquals(2, service.lists.get())
                assertEquals(1, service.histories.get())
            } finally {
                compose.runOnUiThread { workspace.disconnect(); app.workspaces.remove(hostId) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }

    @Test fun foregroundReusesStreamAndConversationAndPagesOnlyOnDemand() {
        val app = compose.activity.application as AgentDeckApplication
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val host = Host(name = "Connection fixture", address = "127.0.0.1", port = 1, username = "fixture", authMethod = AuthMethod.PASSWORD)
        val workspace = app.workspace(host.id)
        val originalKeepConnected = app.keepConnected
        WorkspaceFixtureService().use { service ->
            try {
                compose.waitUntil(10_000) { !app.hosts.busy }
                compose.runOnUiThread { app.hosts.save(host, null) {} }
                compose.waitUntil(10_000) { !app.hosts.busy && !workspace.busy && app.hosts.hosts.any { it.id == host.id } }
                compose.runOnUiThread {
                    app.updateKeepConnected(true)
                    workspace.connect(host.id, service.port, "fixture") { HostApi(service.port, "fixture") }
                }
                compose.waitUntil(10_000) { workspace.online && !workspace.busy && service.streams.get() == 1 && ConnectionService.active }
                assertTrue(ConnectionService.active)
                assertEquals(1, service.lists.get())
                assertEquals(0, service.histories.get())
                assertTrue(workspace.hasMoreProjects)
                compose.runOnUiThread { workspace.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !workspace.busy && workspace.messages.isNotEmpty() }
                var settingsSaved = false
                compose.runOnUiThread { workspace.saveExecutionSettings("gpt-6-astra", "max", "full-access") { settingsSaved = true } }
                compose.waitUntil(10_000) { settingsSaved && !workspace.busy }
                assertEquals("full-access", workspace.selected!!.getJSONObject("executionSettings").getString("permissionMode"))
                assertEquals("thread", workspace.selected!!.getString("id"))
                compose.runOnUiThread { workspace.updateDraft("保留的草稿") }
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                service.events.offer(JSONObject().put("seq", 1).put("type", "agent.event").put("data", JSONObject()
                    .put("method", "item/completed").put("params", JSONObject().put("threadId", "thread").put("turnId", "turn")
                        .put("item", JSONObject().put("id", "background").put("type", "agentMessage").put("text", "后台收到回复")))).toString())
                compose.waitUntil(10_000) { workspace.messages.any { it.text == "后台收到回复" } }
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(10_000) { !app.hosts.busy && !workspace.busy }
                assertEquals(1, service.streams.get())
                assertEquals(1, service.lists.get())
                assertEquals(1, service.histories.get())
                assertEquals("thread", workspace.selected!!.getString("id"))
                assertEquals("保留的草稿", workspace.draft)
                compose.runOnUiThread { workspace.loadMoreProjects() }
                compose.waitUntil(10_000) { !workspace.busy && !workspace.hasMoreProjects }
                assertEquals(2, service.lists.get())
                assertEquals(2, workspace.projectSessions.size)
                assertEquals(1, service.histories.get())
            } finally {
                compose.runOnUiThread { workspace.disconnect(); app.updateKeepConnected(originalKeepConnected) }
                compose.waitUntil(10_000) { !app.hosts.busy }
                compose.runOnUiThread { app.hosts.deleteHost(host.id) }
                compose.waitUntil(10_000) { !app.hosts.busy }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(host.id) }?.forEach { it.delete() }
            }
        }
    }
}

internal class WorkspaceFixtureService(
    val historyItems: List<JSONObject> = listOf(JSONObject().put("id", "initial").put("type", "agentMessage").put("text", "初始回复")),
    private val beforeList: () -> Unit = {},
    initiallyManaged: Boolean = true,
    private val catalogResponse: ((android.net.Uri) -> JSONObject)? = null,
) : Closeable {
    private val server = ServerSocket(0)
    val port = server.localPort
    val lists = AtomicInteger()
    val histories = AtomicInteger()
    val updates = AtomicInteger()
    var liveTurns = JSONArray()
    val streams = AtomicInteger()
    var requestedFile: android.net.Uri? = null
    val fileBytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
    val events = LinkedBlockingQueue<String>()
    var managed = initiallyManaged
    var refuseResume = false
    var refuseSettings = false
    var externalWriter = false
    var refuseTakeover = false
    var runWriterConflict = false
    val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    var settings = JSONObject().put("model", "gpt-6.1-sol").put("effort", "high").put("permissionMode", "on-request")
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val eventSockets = CopyOnWriteArrayList<Socket>()
    fun dropEventConnections() { eventSockets.forEach { it.close() } }
    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                thread(isDaemon = true) { runCatching { respond(socket) }; sockets -= socket; socket.close() }
            }
        }
    }
    private fun respond(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun readLine(): String = buildString {
            while (true) {
                val byte = input.read()
                if (byte == -1 || byte == 10) break
                if (byte != 13) append(byte.toChar())
            }
        }
        val path = readLine().split(' ')[1]
        var length = 0
        while (true) {
            val header = readLine()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) offset += input.read(body, offset, length - offset)
        val output = socket.getOutputStream()
        if (path.startsWith("/v1/files?")) {
            requestedFile = android.net.Uri.parse(path)
            val missing = requestedFile!!.getQueryParameter("path") == "missing"
            val data = if (missing) "{\"error\":\"文件不存在\"}".toByteArray() else fileBytes
            output.write("HTTP/1.1 ${if (missing) "400 Bad Request" else "200 OK"}\r\nContent-Type: application/octet-stream\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(data); output.flush(); return
        }
        if (path.startsWith("/v1/events")) {
            streams.incrementAndGet()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
            eventSockets += socket
            try {
                while (!server.isClosed) {
                    val event = events.poll(1, TimeUnit.SECONDS)
                    output.write((if (event == null) ": keepalive\n\n" else "data: $event\n\n").toByteArray()); output.flush()
                }
            } finally { eventSockets -= socket }
            return
        }
        if (length > 0) writes += path to JSONObject(String(body))
        if ((path == "/v1/sessions/thread/resume" && refuseResume) || (path == "/v1/sessions/thread/settings" && refuseSettings)) {
            val error = JSONObject().put("error", if (path.endsWith("/settings")) "设置暂时无法保存" else "此会话仍在运行").toString().toByteArray()
            output.write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${error.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(error); output.flush(); return
        }
        val response = when {
            path == "/v1/health" -> JSONObject().put("protocol", 1)
            path == "/v1/snapshot" -> JSONObject().put("runs", JSONArray()).put("approvals", JSONArray()).put("cursor", 0)
            path.startsWith("/v1/models") -> JSONObject().put("data", JSONArray(listOf(
                JSONObject().put("id", "sol").put("model", "gpt-6.1-sol").put("displayName", "GPT-6.1 Sol")
                    .put("defaultReasoningEffort", "high").put("supportedReasoningEfforts", JSONArray(listOf("low", "high").map {
                        JSONObject().put("reasoningEffort", it)
                    })))))
            path == "/v1/sessions/thread/takeover" -> {
                if (refuseTakeover) JSONObject().put("acquired", false).put("message", "有其他用户正在使用").put("writerState", "external")
                else {
                    externalWriter = false; managed = true
                    JSONObject().put("acquired", true).put("thread", JSONObject().put("id", "thread").put("cwd", "/fixture")
                        .put("managed", true).put("writerState", "owned").put("executionSettings", settings))
                }
            }
            path == "/v1/sessions/thread/fork" -> JSONObject().put("id", "forked").put("forkedFromId", "thread")
                .put("cwd", "/fixture").put("managed", true).put("writerState", "owned").put("executionSettings", settings)
            path.contains("/updates") -> {
                updates.incrementAndGet()
                JSONObject().put("turns", liveTurns).put("liveCursor", "fixture-anchor").put("more", false)
            }
            path.startsWith("/v1/sessions/forked") -> JSONObject().put("id", "forked").put("cwd", "/fixture").put("managed", true)
                .put("writerState", "owned").put("executionSettings", settings).put("turns", JSONArray(listOf(JSONObject().put("id", "turn").put("items", JSONArray(historyItems)))))
            path == "/v1/sessions/thread/settings" -> {
                check(managed)
                settings = JSONObject(String(body))
                JSONObject().put("executionSettings", settings)
            }
            path == "/v1/sessions/thread/resume" -> {
                managed = true
                JSONObject().put("id", "thread").put("cwd", "/fixture").put("managed", true).put("executionSettings", settings)
            }
            path == "/v1/runs" -> {
                check(managed)
                JSONObject().put("id", "run").put("threadId", "thread").put("turnId", "next-turn")
                    .put("state", if (runWriterConflict) "failed" else "running")
                    .put("writerState", if (runWriterConflict) "external" else "owned")
            }
            path.startsWith("/v1/sessions/thread") -> {
                histories.incrementAndGet()
                JSONObject().put("id", "thread").put("cwd", "/fixture").put("managed", managed).put("writerState", if (externalWriter) "external" else "available").put("executionSettings", settings).put("turns", JSONArray(listOf(
                    JSONObject().put("id", "turn").put("items", JSONArray(historyItems)))))
            }
            path.startsWith("/v1/sessions") -> {
                lists.incrementAndGet()
                beforeList()
                val more = path.contains("cursor=")
                catalogResponse?.invoke(android.net.Uri.parse(path)) ?: JSONObject().put("data", JSONArray(listOf(JSONObject().put("id", if (more) "older" else "thread").put("cwd", "/fixture"))))
                    .put("nextCursor", if (more) JSONObject.NULL else "next")
            }
            else -> error("Unexpected request $path")
        }.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(response); output.flush()
    }
    override fun close() { server.close(); sockets.forEach { it.close() } }
}
