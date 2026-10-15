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
import com.worldcopy.agentdeck.core.storage.FollowupMode
import com.worldcopy.agentdeck.core.storage.MessageSettings
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class FollowupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun run(id: String, state: String, mode: String = "start", text: String = "Current task") =
        JSONObject().put("id", id).put("threadId", "thread").put("turnId", if (mode == "queue") JSONObject.NULL else "active-turn")
            .put("state", state).put("mode", mode).put("text", text).put("createdAt", "2026-10-16T00:00:00Z")
    private fun emit(service: WorkspaceFixtureService, sequence: Int, value: JSONObject) {
        service.events.offer(JSONObject().put("seq", sequence).put("type", "run.updated").put("data", value).toString())
    }
    private fun fixture(block: (WorkspaceViewModel, WorkspaceFixtureService) -> Unit) {
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val app = compose.activity.application as AgentDeckApplication
        val previous = app.messageSettings.followupMode
        compose.runOnUiThread { app.messageSettings.choose(FollowupMode.Queue) }
        val hostId = "followup-${UUID.randomUUID()}"; val vm = app.workspace(hostId)
        WorkspaceFixtureService().use { service ->
            try {
                compose.setContent { AgentDeckTheme { Surface { WorkspaceScreen(vm) {} } } }
                compose.runOnUiThread { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.isNotEmpty() }
                emit(service, 1, run("active", "running"))
                compose.waitUntil(10_000) { vm.runs.any { it.optString("id") == "active" } }
                block(vm, service)
            } finally {
                compose.runOnUiThread { vm.disconnect(); app.workspaces.remove(hostId); app.messageSettings.choose(previous) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }
    private fun chooseSteer() {
        compose.onNodeWithTag("followup-settings").performScrollTo().performClick()
        compose.onNodeWithTag("followup-mode-steer").performScrollTo().performClick()
        val file = File(compose.activity.getExternalFilesDir(null), "followup-mode.png")
        compose.onNodeWithTag("message-settings-dialog").captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun sendsWhileRunningWithSelectedModeShowsQueueAndAllowsRemoval() = fixture { vm, service ->
        service.runResponse = { body -> run(if (body.getString("mode") == "queue") "queued" else "steer", if (body.getString("mode") == "queue") "waiting_queue" else "steered", body.getString("mode"), body.getString("text")) }
        compose.runOnUiThread { vm.updateDraft("后续任务") }
        compose.onNodeWithText("排队", substring = false).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.runs.any { it.optString("id") == "queued" } }
        assertEquals("queue", service.writes.first().second.getString("mode"))
        assertEquals("", vm.draft)
        compose.onNodeWithTag("pending-message-queued").assertIsDisplayed()
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasText("后续任务"))
        compose.onNodeWithText("后续任务").assertIsDisplayed()
        chooseSteer()
        assertEquals(FollowupMode.Steer, MessageSettings(compose.activity).followupMode)
        compose.runOnUiThread { vm.updateDraft("立即纠正方向") }
        compose.onNodeWithText("引导", substring = false).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        assertEquals("steer", service.writes.last().second.getString("mode"))
        assertEquals("running", vm.runs.first { it.optString("id") == "active" }.getString("state"))
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasText("移除排队消息"))
        val file = File(compose.activity.getExternalFilesDir(null), "followup-queue.png")
        compose.onNodeWithTag("chat-messages").captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("移除排队消息").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 3 }
        assertEquals("/v1/runs/queued/cancel", service.writes.last().first)
        emit(service, 2, run("queued", "interrupted", "queue"))
        compose.waitUntil(10_000) { vm.runs.first { it.optString("id") == "queued" }.optString("state") == "interrupted" }
        compose.onNodeWithTag("pending-message-queued").assertDoesNotExist()
    }

    @Test fun rejectedSteerKeepsDraftAndRetryUsesNewRequestId() = fixture { vm, service ->
        chooseSteer()
        service.runResponse = { body -> run("rejected", "failed", "steer", body.getString("text")).put("error", "当前轮次已结束") }
        compose.runOnUiThread { vm.updateDraft("纠正内容") }
        compose.onNodeWithText("引导", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.error != null }
        assertEquals("纠正内容", vm.draft); assertFalse(vm.hasUnconfirmedSubmission)
        compose.onNodeWithText("知道了").performClick()
        val firstId = service.writes.single().second.getString("clientRequestId")
        service.runResponse = { body -> run("steered", "steered", "steer", body.getString("text")) }
        compose.onNodeWithText("引导", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        assertNotEquals(firstId, service.writes.last().second.getString("clientRequestId"))
        assertEquals("", vm.draft)
    }

    @Test fun uncertainTransportKeepsRequestModeWhenPreferenceChanges() = fixture { vm, service ->
        service.runResponse = { throw IllegalStateException("Synthetic dropped reply") }
        compose.runOnUiThread { vm.updateDraft("请排队保留") }
        compose.onNodeWithText("排队", substring = false).performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.busy && vm.hasUnconfirmedSubmission }
        val original = service.writes.first().second.getString("clientRequestId")
        compose.onNodeWithText("知道了").performClick()
        chooseSteer()
        service.runResponse = { body -> run("queued", "waiting_queue", "queue", body.getString("text")) }
        compose.onNodeWithText("确认送达").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && !vm.hasUnconfirmedSubmission }
        assertTrue(service.writes.all { it.second.getString("clientRequestId") == original && it.second.getString("mode") == "queue" })
        assertEquals("", vm.draft)
    }

    @Test fun interruptedTurnPreservesQueueAndOffersResume() = fixture { vm, service ->
        service.runResponse = { body -> run("queued", "waiting_queue", "queue", body.getString("text")) }
        compose.runOnUiThread { vm.updateDraft("中断后继续的任务") }
        compose.onNodeWithText("排队", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.runs.any { it.optString("id") == "queued" } }
        emit(service, 2, run("active", "interrupted"))
        compose.waitUntil(10_000) { vm.runs.first { it.optString("id") == "active" }.optString("state") == "interrupted" }
        compose.onNodeWithText("恢复排队").performScrollTo().assertIsDisplayed()
        val file = File(compose.activity.getExternalFilesDir(null), "followup-paused.png")
        compose.onNodeWithTag("chat-messages").captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("恢复排队").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        assertEquals("/v1/sessions/thread/queue/resume", service.writes.last().first)
        emit(service, 3, run("queued", "running", "queue").put("turnId", "queued-turn"))
        compose.waitUntil(10_000) { vm.runs.first { it.optString("id") == "queued" }.optString("state") == "running" }
        compose.onNodeWithTag("pending-message-queued").assertDoesNotExist()
    }
}
