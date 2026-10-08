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
) : Closeable {
    private val server = ServerSocket(0)
    val port = server.localPort
    val lists = AtomicInteger()
    val histories = AtomicInteger()
    val streams = AtomicInteger()
    val events = LinkedBlockingQueue<String>()
    private val sockets = CopyOnWriteArrayList<Socket>()
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
        val input = socket.getInputStream().bufferedReader()
        val path = input.readLine().split(' ')[1]
        var length = 0
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        val body = CharArray(length)
        var offset = 0
        while (offset < length) offset += input.read(body, offset, length - offset)
        val output = socket.getOutputStream()
        if (path.startsWith("/v1/events")) {
            streams.incrementAndGet()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
            while (!server.isClosed) {
                val event = events.poll(1, TimeUnit.SECONDS)
                output.write((if (event == null) ": keepalive\n\n" else "data: $event\n\n").toByteArray()); output.flush()
            }
            return
        }
        val response = when {
            path == "/v1/health" -> JSONObject().put("protocol", 1)
            path == "/v1/snapshot" -> JSONObject().put("runs", JSONArray()).put("approvals", JSONArray()).put("cursor", 0)
            path == "/v1/sessions/thread/settings" -> JSONObject().put("executionSettings", JSONObject(String(body)))
            path.startsWith("/v1/sessions/thread") -> {
                histories.incrementAndGet()
                JSONObject().put("id", "thread").put("cwd", "/fixture").put("managed", true).put("turns", JSONArray(listOf(
                    JSONObject().put("id", "turn").put("items", JSONArray(historyItems)))))
            }
            path.startsWith("/v1/sessions") -> {
                lists.incrementAndGet()
                beforeList()
                val more = path.contains("cursor=")
                JSONObject().put("data", JSONArray(listOf(JSONObject().put("id", if (more) "older" else "thread").put("cwd", "/fixture"))))
                    .put("nextCursor", if (more) JSONObject.NULL else "next")
            }
            else -> error("Unexpected request $path")
        }.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(response); output.flush()
    }
    override fun close() { server.close(); sockets.forEach { it.close() } }
}
