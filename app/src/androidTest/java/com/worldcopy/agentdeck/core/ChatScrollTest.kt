package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.network.HostApi
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ChatScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun assertLastLineVisible(text: String) {
        val target = compose.onNodeWithText(text, substring = true)
        val layout = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        val node = target.fetchSemanticsNode()
        val last = layout.single().getBoundingBox(layout.single().layoutInput.text.text.lastIndex)
            .translate(node.positionInRoot)
        val viewport = compose.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Last line is above viewport", last.top >= viewport.top)
        org.junit.Assert.assertTrue("Last line is below viewport", last.bottom <= viewport.bottom)
    }

    @Test fun opensAtLatestFollowsStreamingAndPreservesHistoryReading() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentDeckApplication
        val hostId = "scroll-${UUID.randomUUID()}"
        val vm = app.workspace(hostId)
        val history = (1..50).map { index ->
            JSONObject().put("id", "message-$index").put("type", "agentMessage").put("text", "历史消息 $index")
        } + JSONObject().put("id", "latest").put("type", "agentMessage")
            .put("text", (1..60).joinToString("\n") { "最新长回复第 $it 行" } + "\n最新消息末尾")
        WorkspaceFixtureService(history).use { service ->
            try {
                compose.setContent { AgentDeckTheme { WorkspaceScreen(vm) {} } }
                compose.runOnIdle { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnIdle { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.size == 51 }
                assertLastLineVisible("最新消息末尾")
                compose.onNodeWithText("历史消息 1").assertDoesNotExist()

                var sequence = 0
                fun event(method: String, params: JSONObject) {
                    service.events.offer(JSONObject().put("seq", ++sequence).put("type", "agent.event")
                        .put("data", JSONObject().put("method", method).put("params", params
                            .put("threadId", "thread").put("turnId", "turn"))).toString())
                }
                event("item/agentMessage/delta", JSONObject().put("itemId", "latest").put("delta", "\n流式回复末尾"))
                compose.waitUntil(10_000) { vm.messages.last().text.endsWith("流式回复末尾") }
                assertLastLineVisible("流式回复末尾")
                event("item/completed", JSONObject().put("item", JSONObject().put("id", "new")
                    .put("type", "agentMessage").put("text", "新到的消息")))
                compose.waitUntil(10_000) { vm.messages.last().id == "new" }
                compose.onNodeWithText("新到的消息").assertIsDisplayed()

                compose.onNodeWithTag("chat-messages").performScrollToNode(hasText("历史消息 25"))
                compose.onNodeWithText("历史消息 25").assertIsDisplayed()
                event("item/completed", JSONObject().put("item", JSONObject().put("id", "while-reading")
                    .put("type", "agentMessage").put("text", "阅读历史时收到的消息")))
                compose.waitUntil(10_000) { vm.messages.last().id == "while-reading" }
                compose.onNodeWithText("历史消息 25").assertIsDisplayed()
                compose.onNodeWithText("查看最新消息").performClick()
                compose.onNodeWithText("阅读历史时收到的消息").assertIsDisplayed()

                // Cached conversations also open at their end, including switching without leaving Chat.
                val cache = File(app.noBackupFilesDir, "workspace")
                event("turn/completed", JSONObject())
                compose.waitUntil(10_000) { File(cache, "$hostId-history-thread.json").readText().contains("while-reading") }
                compose.runOnIdle { vm.disconnect() }
                File(cache, "$hostId-history-second.json").writeText(JSONObject().put("messages", JSONArray(
                    (1..40).map { JSONObject().put("id", "second-$it").put("role", "Agent").put("text", "另一个会话 $it") }
                )).toString())
                compose.onNodeWithTag("chat-messages").performScrollToNode(hasText("历史消息 25"))
                compose.runOnIdle { vm.openSession(JSONObject().put("id", "second").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.lastOrNull()?.id == "second-40" }
                compose.onNodeWithText("另一个会话 40").assertIsDisplayed()
                compose.runOnIdle { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.lastOrNull()?.id == "while-reading" }
                compose.onNodeWithText("阅读历史时收到的消息").assertIsDisplayed()
            } finally {
                compose.runOnIdle { vm.disconnect(); app.workspaces.remove(hostId) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }
}
