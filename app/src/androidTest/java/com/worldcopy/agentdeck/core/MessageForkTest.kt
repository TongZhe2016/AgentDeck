package com.worldcopy.agentdeck.core

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.network.HostApi
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class MessageForkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun answer(id: String, text: String) = JSONObject().put("id", id).put("type", "agentMessage").put("text", text)
    private fun turn(id: String, status: String, vararg items: JSONObject) =
        JSONObject().put("id", id).put("status", status).put("items", JSONArray(items.toList()))

    private fun fixture(block: (WorkspaceViewModel, WorkspaceFixtureService) -> Unit) {
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val app = compose.activity.application as AgentDeckApplication
        val hostId = "message-fork-${UUID.randomUUID()}"
        val vm = app.workspace(hostId)
        WorkspaceFixtureService(initiallyManaged = false).use { service ->
            val first = turn("earlier", "completed", answer("commentary", "执行前的说明"), answer("reply", "第一轮回答"))
            service.historyTurns = JSONArray(listOf(first, turn("later", "inProgress", answer("streaming", "正在生成"))))
            service.forkedTurns = JSONArray(listOf(first))
            try {
                compose.setContent { AgentDeckTheme { Surface { WorkspaceScreen(vm) {} } } }
                compose.runOnUiThread { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.size == 3 }
                compose.runOnUiThread { vm.updateDraft("原会话草稿") }
                block(vm, service)
            } finally {
                compose.runOnUiThread { vm.disconnect(); app.workspaces.remove(hostId) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }

    @Test fun forkAtEarlierAnswerOpensIndependentHistoryAndPreservesOriginalDraft() = fixture { vm, service ->
        compose.onNodeWithTag("fork-message-commentary").assertDoesNotExist()
        compose.onNodeWithTag("fork-message-streaming").assertDoesNotExist()
        compose.onNodeWithTag("fork-message-reply").performScrollTo().assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "message-fork.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        service.events.offer(JSONObject().put("seq", 1).put("type", "agent.event").put("data", JSONObject()
            .put("method", "item/completed").put("params", JSONObject().put("threadId", "thread").put("turnId", "earlier")
                .put("item", answer("reply", "第一轮回答（事件重放）")))).toString())
        compose.waitUntil(10_000) { vm.messages.any { it.text == "第一轮回答（事件重放）" } }
        compose.onNodeWithTag("fork-message-reply").assertIsDisplayed()
        compose.onNodeWithTag("fork-message-reply").performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.selected?.optString("id") == "forked" && vm.messages.size == 2 }
        assertEquals("earlier", service.writes.single().second.getString("lastTurnId"))
        assertEquals("/v1/sessions/thread/fork", service.writes.single().first)
        assertEquals(listOf("执行前的说明", "第一轮回答"), vm.messages.map { it.text })
        assertEquals("", vm.draft)
        compose.onNodeWithTag("execution-settings-bar").assertIsDisplayed()
        assertTrue(vm.projectSessions.any { it.optString("id") == "forked" })
        compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
        compose.waitUntil(10_000) { !vm.busy && vm.messages.size == 3 }
        assertEquals("原会话草稿", vm.draft)
        assertFalse(vm.selected!!.optBoolean("managed"))
    }

    @Test fun failedForkKeepsOriginalConversationAndCanRetry() = fixture { vm, service ->
        service.refuseFork = true
        compose.onNodeWithTag("fork-message-reply").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.error == "分叉暂时失败" }
        assertEquals("thread", vm.selected!!.getString("id"))
        assertEquals("原会话草稿", vm.draft)
        assertEquals(3, vm.messages.size)
        service.refuseFork = false
        compose.onNodeWithTag("fork-message-reply").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.selected?.optString("id") == "forked" && vm.messages.size == 2 }
        assertEquals(2, service.writes.size)
    }
}
