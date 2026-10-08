package com.worldcopy.agentdeck.core

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.network.HostApi
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class StreamingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fragmentsAppearBeforeCompletionAndStoredReplyRepairsMissingEvents() {
        val app = compose.activity.application as AgentDeckApplication
        val host = "stream-${UUID.randomUUID()}"
        val vm = app.workspace(host)
        WorkspaceFixtureService().use { service ->
            try {
                compose.setContent { AgentDeckTheme { Surface { WorkspaceScreen(vm) {} } } }
                compose.runOnUiThread { vm.connect(host, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.isNotEmpty() }
                compose.runOnUiThread { vm.updateDraft("不丢草稿") }
                var seq = 0
                fun event(method: String, params: JSONObject) {
                    service.events.offer(JSONObject().put("seq", ++seq).put("type", "agent.event").put("data", JSONObject()
                        .put("method", method).put("params", params.put("threadId", "thread").put("turnId", "new-turn"))).toString())
                }
                fun delta(text: String) = event("item/agentMessage/delta", JSONObject().put("itemId", "answer").put("delta", text))
                delta("正在")
                compose.waitUntil(5_000) { vm.messages.any { it.text == "正在" } }
                compose.onNodeWithText("正在").assertIsDisplayed()
                repeat(150) { delta("生成") }
                compose.waitUntil(10_000) { vm.messages.any { it.text == "正在" + "生成".repeat(150) } }
                assertEquals(1, service.streams.get())
                service.dropEventConnections()
                compose.waitUntil(5_000) { !vm.online }
                val finalText = "完整回答：包括刚才没有收到的末尾。"
                // No item/completed or turn/completed events: persisted content must repair the answer.
                service.liveTurns = JSONArray(listOf(JSONObject().put("id", "new-turn").put("items", JSONArray(listOf(
                    JSONObject().put("id", "answer").put("type", "agentMessage").put("text", finalText))))))
                compose.waitUntil(10_000) { service.streams.get() >= 2 && vm.online && vm.messages.any { it.text == finalText } }
                delta("迟到的旧片段")
                event("item/started", JSONObject().put("item", JSONObject().put("id", "answer").put("type", "agentMessage").put("text", "")))
                event("item/agentMessage/delta", JSONObject().put("itemId", "sentinel").put("delta", "后续消息"))
                compose.waitUntil(5_000) { vm.messages.any { it.id == "sentinel" } }
                assertEquals(finalText, vm.messages.single { it.id == "answer" }.text)
                assertEquals(1, vm.messages.count { it.id == "answer" })
                assertEquals("不丢草稿", vm.draft)
                assertEquals(1, service.histories.get())
                assertEquals(1, service.lists.get())
                assertEquals("初始回复", vm.messages.first().text)
                // A stored message from another writer also follows without claiming the writer.
                service.liveTurns = JSONArray(listOf(JSONObject().put("id", "external-turn").put("items", JSONArray(listOf(
                    JSONObject().put("id", "external-answer").put("type", "agentMessage").put("text", "电脑端的新回复"))))))
                compose.waitUntil(10_000) { vm.messages.any { it.id == "external-answer" } }
                assertTrue(service.writes.isEmpty())
                compose.runOnUiThread { vm.clearSession() }
                val reads = service.updates.get()
                Thread.sleep(1500)
                assertEquals(reads, service.updates.get())
            } finally {
                compose.runOnUiThread { vm.disconnect(); app.workspaces.remove(host) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(host) }?.forEach { it.delete() }
            }
        }
    }
}
