package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
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

    @Test fun homeCollapsesProjectsAndOpensConversationOnItsOwnHost() {
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
        val project = "project:${b.id}:/work/AgentDeck"
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
        compose.onNodeWithTag(project).performScrollTo().assertExists()
        compose.onNodeWithTag(project).performClick()
        compose.onNodeWithText("打开项目 / 新建对话").performScrollTo().performClick()
        compose.onNodeWithText("新建 Codex 会话").assertIsNotEnabled()
        assertEquals("/work/AgentDeck", app.workspaces[b.id]!!.project)
        assertNull(app.workspaces[b.id]!!.selected)
        compose.onNodeWithText("归档对话").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回项目").performClick()
    }
}
