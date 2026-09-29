package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.notifications.ConnectionService
import android.content.Intent
import android.app.NotificationManager

class WorkbenchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun macCodexChatThroughSsh() {
        val project = InstrumentationRegistry.getArguments().getString("smokeProject")
        assumeTrue("Requires an explicitly configured development Mac", project != null)
        compose.onNodeWithText("连接 / 重连").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("SSH 已连接").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("工作台").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("新建 Codex 会话").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(project!!)
        compose.onNodeWithText("新建 Codex 会话").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("发送").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("Reply exactly AGENTDECK_UI_OK. Do not call tools or modify files.")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(90_000) { compose.onAllNodesWithText("AGENTDECK_UI_OK").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun reconnectReplaysRunAndBackgroundNotification() {
        val project = InstrumentationRegistry.getArguments().getString("smokeProject")
        assumeTrue("Requires an explicitly configured development Mac", project != null)
        val app = compose.activity.application as AgentDeckApplication
        compose.onNodeWithText("连接 / 重连").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("SSH 已连接").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("工作台").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(project!!)
        compose.onNodeWithText("新建 Codex 会话").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("发送").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("Run sleep 12 then printf AGENTDECK_RECONNECTED. Do not modify files. After the command succeeds reply exactly AGENTDECK_RECONNECTED.")
        compose.onNodeWithText("发送").performClick()
        val workspace = app.workspaces.values.first()
        compose.waitUntil(30_000) { workspace.runs.any { it.optString("state") == "running" && it.optString("threadId") == workspace.selected?.optString("id") } }
        val threadId = workspace.selected!!.getString("id")
        val runId = workspace.runs.first { it.optString("threadId") == threadId }.getString("id")
        compose.runOnUiThread {
            app.startForegroundService(Intent(app, ConnectionService::class.java))
            app.hosts.disconnectAll()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        compose.waitUntil(90_000) { workspace.runs.any { it.optString("id") == runId && it.optString("state") == "completed" } }
        assertEquals(1, workspace.runs.count { it.optString("threadId") == threadId })
        assertTrue(workspace.messages.any { it.text == "AGENTDECK_RECONNECTED" })
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.extras.getString("android.title")?.contains("本轮完成") == true
        })
        compose.runOnUiThread { app.stopService(Intent(app, ConnectionService::class.java)) }
    }
}
