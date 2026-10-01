package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.worldcopy.agentdeck.MainActivity
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.feature.projects.groupProjects
import com.worldcopy.agentdeck.feature.workspace.readAllSessions
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class ProjectHomeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixtureIds = mutableListOf<String>()
    private val app get() = compose.activity.application as AgentDeckApplication
    private fun thread(id: String, path: String, title: String = id) = JSONObject()
        .put("id", id).put("cwd", path).put("name", title).put("updatedAt", 4_000_000_000L)

    @After fun cleanup() {
        fixtureIds.forEach { id ->
            compose.waitUntil(5000) { !app.hosts.busy }
            compose.runOnUiThread { app.hosts.deleteHost(id) }
            compose.waitUntil(5000) { app.hosts.hosts.none { it.id == id } }
            File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
        }
    }

    @Test fun allPagesKeepSameNameFoldersAndHostsSeparate() = runBlocking {
        val pages = mutableListOf<String?>()
        val all = readAllSessions { cursor ->
            pages += cursor
            if (cursor == null) JSONObject().put("data", JSONArray((1..40).map { thread("t$it", "/work/app") })).put("nextCursor", "older")
            else JSONObject().put("data", JSONArray(listOf(thread("t40", "/work/app"), thread("t41", "/archive/app")))).put("nextCursor", JSONObject.NULL)
        }
        assertEquals(listOf(null, "older"), pages)
        assertEquals(41, all.size)
        val a = Host(id = "A", name = "Ubuntu", address = "localhost", username = "fixture")
        val b = a.copy(id = "B", name = "Mac")
        val projects = groupProjects(listOf(a, b), mapOf("A" to all, "B" to listOf(thread("t1", "/work/app"))))
        assertEquals(3, projects.size)
        assertEquals(40, projects.first { it.key.hostId == "A" && it.key.path == "/work/app" }.sessions.size)
        assertEquals(1, projects.first { it.key.hostId == "B" }.sessions.size)
        assertTrue(projects.all { it.name == "app" })
    }

    @Test fun homeCollapsesHostsAndProjectsAndOpensConversationOnItsOwnHost() {
        val a = Host(name = "测试 Ubuntu", address = "localhost", username = "fixture", authMethod = AuthMethod.PASSWORD)
        val b = a.copy(id = java.util.UUID.randomUUID().toString(), name = "测试 Mac")
        val data = mapOf(a to listOf(thread("same-id", "/work/AgentDeck", "Ubuntu 对话"), thread("second", "/archive/AgentDeck", "归档对话")),
            b to listOf(thread("same-id", "/work/AgentDeck", "Mac 对话")))
        for ((host, sessions) in data) {
            fixtureIds += host.id
            val cache = File(app.noBackupFilesDir, "workspace").apply { mkdirs() }
            File(cache, "${host.id}-sessions.json").writeText(JSONObject().put("data", JSONArray(sessions)).put("syncedAt", "fixture").toString())
            File(cache, "${host.id}-history-same-id.json").writeText(JSONObject().put("messages", JSONArray(listOf(
                JSONObject().put("id", "message").put("role", "Agent").put("text", "${host.name} 的缓存内容")
            ))).toString())
            compose.waitUntil(5000) { !app.hosts.busy }
            compose.runOnUiThread { app.hosts.save(host, null) {} }
            compose.waitUntil(5000) { app.workspaces[host.id]?.projectSessions?.size == sessions.size }
        }
        compose.onNodeWithTag("thread:${a.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag("thread:${b.id}:same-id").assertDoesNotExist()
        val ubuntuProject = "project:${a.id}:/work/AgentDeck"
        val archiveProject = "project:${a.id}:/archive/AgentDeck"
        val project = "project:${b.id}:/work/AgentDeck"
        val ubuntuHost = "project-host:${a.id}"
        val macHost = "project-host:${b.id}"
        compose.onNodeWithTag(ubuntuHost).performScrollTo().assertExists()
        compose.onNodeWithTag(ubuntuProject).assertDoesNotExist()
        compose.onNodeWithTag(archiveProject).assertDoesNotExist()
        compose.onNodeWithTag(macHost).performScrollTo().assertExists()
        compose.onNodeWithTag(project).assertDoesNotExist()
        compose.onNodeWithTag(ubuntuHost).performScrollTo().performClick()
        compose.onNodeWithTag(ubuntuProject).performScrollTo().assertExists()
        compose.onNodeWithTag(archiveProject).performScrollTo().assertExists()
        compose.onNodeWithTag("thread:${a.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag("thread:${a.id}:second").assertDoesNotExist()
        compose.onNodeWithTag(ubuntuProject).performScrollTo().performClick()
        compose.onNodeWithTag("thread:${a.id}:same-id").performScrollTo().assertExists()
        compose.onNodeWithTag("thread:${a.id}:second").assertDoesNotExist()
        compose.onNodeWithTag(ubuntuHost).performScrollTo().performClick()
        compose.onNodeWithTag(ubuntuProject).assertDoesNotExist()
        compose.onNodeWithTag("thread:${a.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag(ubuntuHost).performClick()
        compose.onNodeWithTag(ubuntuProject).performScrollTo().assertExists()
        compose.onNodeWithTag("thread:${a.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag(ubuntuHost).performScrollTo().performClick()
        compose.onNodeWithTag(macHost).performScrollTo().performClick()
        compose.onNodeWithTag(project).performScrollTo().performClick()
        compose.onNodeWithTag("thread:${b.id}:same-id").assertExists()
        compose.onNodeWithTag("thread:${a.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag(project).performClick()
        compose.onNodeWithTag("thread:${b.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag(project).performClick()
        compose.onNodeWithTag("thread:${b.id}:same-id").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试 Mac 的缓存内容").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试 Ubuntu 的缓存内容").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回项目").performClick()
        compose.onNodeWithTag("thread:${b.id}:same-id").assertDoesNotExist()
        compose.onNodeWithTag(macHost).performScrollTo().assertExists()
        compose.onNodeWithTag(project).assertDoesNotExist()
        compose.onNodeWithTag(macHost).performClick()
        compose.onNodeWithTag(project).performScrollTo().assertExists()
        compose.onNodeWithTag(project).performClick()
        compose.onNodeWithText("打开项目 / 新建对话").performScrollTo().performClick()
        compose.onNodeWithText("新建 Codex 会话").assertIsNotEnabled()
        assertEquals("/work/AgentDeck", app.workspaces[b.id]!!.project)
        assertNull(app.workspaces[b.id]!!.selected)
        compose.onNodeWithText("归档对话").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回项目").performClick()
    }

    @Test fun connectionProgressFailureAndRetryStayOnTheirHostRow() {
        // Hold the SSH handshake open so the actual connection has an observable loading state.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val empty = Host(name = "待连接主机", address = "127.0.0.1", port = server.localPort,
                username = "fixture", authMethod = AuthMethod.PASSWORD)
            val cached = empty.copy(id = java.util.UUID.randomUUID().toString(), name = "缓存主机")
            for (host in listOf(empty, cached)) {
                fixtureIds += host.id
                if (host == cached) {
                    val cache = File(app.noBackupFilesDir, "workspace").apply { mkdirs() }
                    File(cache, "${host.id}-sessions.json").writeText(JSONObject().put("data",
                        JSONArray(listOf(thread("cached", "/work/project", "离线对话")))).toString())
                }
                compose.waitUntil(5000) { !app.hosts.busy }
                compose.runOnUiThread { app.hosts.save(host, "fixture") {} }
                compose.waitUntil(5000) { app.hosts.hosts.any { it.id == host.id } && app.workspaces[host.id]?.busy == false }
            }
            compose.onNodeWithText("主机同步").assertDoesNotExist()
            compose.onNodeWithTag("project-host:${empty.id}").performScrollTo().performClick()
            val retry = "project-host-sync:${empty.id}"
            compose.onNodeWithTag(retry).performScrollTo().performClick()
            server.accept().use {
                compose.waitUntil(5000) { empty.id in app.hosts.connectingHosts }
                compose.onNodeWithTag("project-host-loading:${empty.id}", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("project-host-warning:${empty.id}", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithTag("project-host:${cached.id}").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("project-host-loading:${cached.id}", useUnmergedTree = true).assertDoesNotExist()
                compose.runOnUiThread { app.workspaces[cached.id]!!.reportError("合成会话操作错误") }
                compose.onNodeWithTag("project-host-warning:${cached.id}", useUnmergedTree = true).assertDoesNotExist()
                captureHostStatus("loading")
            }
            compose.waitUntil(10000) { !app.hosts.busy && app.workspaces[empty.id]?.error != null }
            compose.onNodeWithTag("project-host:${empty.id}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("project-host-warning:${empty.id}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("project-host-loading:${empty.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(retry).performScrollTo().assertIsEnabled()
            captureHostStatus("failure")
            // Retry the same host without navigating to an additional sync section.
            compose.onNodeWithTag(retry).performClick()
            server.accept().use {
                compose.waitUntil(5000) { empty.id in app.hosts.connectingHosts }
                compose.onNodeWithTag("project-host-loading:${empty.id}", useUnmergedTree = true).assertIsDisplayed()
            }
            compose.waitUntil(10000) { !app.hosts.busy }
            compose.onNodeWithTag("project-host:${empty.id}").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("重连", substring = false).performScrollTo().performClick()
            server.accept().use {
                compose.waitUntil(5000) { empty.id in app.hosts.connectingHosts }
                compose.onNodeWithTag("project-host-loading:${empty.id}", useUnmergedTree = true).assertIsDisplayed()
            }
            compose.waitUntil(10000) { !app.hosts.busy }
            // Failure does not hide the other host's offline projects or conversations.
            compose.onNodeWithTag("project-host:${cached.id}").performScrollTo().performClick()
            compose.onNodeWithTag("project:${cached.id}:/work/project").performScrollTo().performClick()
            compose.onNodeWithTag("thread:${cached.id}:cached").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun projectHomeAddsEditsOpensAndDeletesHostThroughLongPress() {
        compose.waitUntil(10000) { !app.hosts.busy }
        val add = compose.onNodeWithText("添加主机", substring = false)
        val sync = compose.onNodeWithText("同步项目", substring = false)
        assertTrue(add.fetchSemanticsNode().boundsInRoot.left < sync.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithTag("nav-3").assertDoesNotExist()
        add.performClick()
        compose.onNodeWithText("主机名称").performTextInput("项目页管理测试")
        compose.onNodeWithText("SSH 地址").performTextInput("127.0.0.1")
        compose.onNodeWithText("SSH 端口").performTextReplacement("1")
        compose.onNodeWithText("用户名").performTextInput("fixture")
        compose.onNode(hasSetTextAction() and hasText("密码")).performScrollTo().performTextInput("fixture")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { !app.hosts.busy && app.hosts.hosts.any { it.name == "项目页管理测试" } }
        val host = app.hosts.hosts.single { it.name == "项目页管理测试" }
        fixtureIds += host.id
        compose.waitUntil(5000) { app.workspaces[host.id]?.busy == false }
        fun menu() = compose.onNodeWithTag("project-host:${host.id}").performScrollTo().performTouchInput { longClick() }
        menu()
        listOf("进入工作台", "连接", "重连", "编辑", "克隆", "删除").forEach {
            compose.onNodeWithText(it, substring = false).performScrollTo().assertIsEnabled()
        }
        compose.onNodeWithText("编辑", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("主机名称").performTextReplacement("项目页已编辑")
        compose.onNodeWithText("SSH 端口").performTextReplacement("2")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { !app.hosts.busy && app.hosts.hosts.any { it.id == host.id && it.name == "项目页已编辑" } }
        assertEquals(2, app.hosts.hosts.single { it.id == host.id }.port)
        menu()
        compose.onNodeWithText("进入工作台").performScrollTo().performClick()
        compose.onNodeWithText("电脑上的项目绝对路径").assertExists()
        compose.onNodeWithContentDescription("返回项目").performClick()
        menu()
        compose.onNodeWithText("连接", substring = false).performScrollTo().performClick()
        compose.waitUntil(10000) { !app.hosts.busy && app.workspaces[host.id]?.projectSyncError != null }
        compose.onNodeWithTag("project-host-warning:${host.id}", useUnmergedTree = true).assertIsDisplayed()
        menu()
        compose.onNodeWithText("删除", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertTrue(app.hosts.hosts.any { it.id == host.id })
        menu()
        compose.onNodeWithText("删除", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("确认", substring = false).performClick()
        compose.waitUntil(5000) { !app.hosts.busy && app.hosts.hosts.none { it.id == host.id } }
        compose.onNodeWithTag("project-host:${host.id}").assertDoesNotExist()
    }

    private fun captureHostStatus(state: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(app.cacheDir, "project-host-$state.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
