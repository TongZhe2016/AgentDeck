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

    @Test fun codexChatThroughSsh() {
        val project = InstrumentationRegistry.getArguments().getString("smokeProject")
        assumeTrue("Requires an explicitly configured SSH host", project != null)
        val hostName = InstrumentationRegistry.getArguments().getString("smokeHost")
        val hostId = hostName?.let { name ->
            com.worldcopy.agentdeck.core.storage.HostStore(compose.activity).hosts().first { it.name == name }.id
        }
        fun hostButton(label: String) = compose.onNode(hasText(label) and
            (hostId?.let { hasAnyAncestor(hasTestTag("host-$it")) } ?: SemanticsMatcher("any host") { true }))
        hostButton("连接 / 重连").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("SSH 已连接").fetchSemanticsNodes().isNotEmpty() }
        hostButton("工作台").performClick()
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

    @Test fun voiceBecomesDraftAndSharedImageReachesCodex() {
        val project = InstrumentationRegistry.getArguments().getString("smokeProject")
        assumeTrue("Requires local synthetic media fixtures and configured Mac", project != null)
        val app = compose.activity.application as AgentDeckApplication
        assumeTrue(java.io.File(app.cacheDir, "voice-test.m4a").exists())
        compose.onNodeWithText("连接 / 重连").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("SSH 已连接").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("工作台").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(project!!)
        compose.onNodeWithText("新建 Codex 会话").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("发送").fetchSemanticsNodes().isNotEmpty() }
        val workspace = app.workspaces.values.first()
        compose.runOnUiThread { workspace.attachAudio(java.io.File(app.cacheDir, "voice-test.m4a")) }
        compose.waitUntil(135_000) { !workspace.busy && workspace.draft.isNotBlank() }
        assertNull(workspace.error)
        assertTrue(workspace.attachments.isEmpty())
        assertTrue(workspace.runs.none { it.optString("threadId") == workspace.selected!!.getString("id") })
        compose.runOnUiThread {
            workspace.updateDraft("What color is the image? Answer briefly in English. Do not use tools.")
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", java.io.File(app.cacheDir, "image-test.png"))
            compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("将分享内容加入当前会话").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("将分享内容加入当前会话").performClick()
        compose.waitUntil(10_000) { !workspace.busy && workspace.attachments.size == 1 }
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(90_000) { workspace.messages.any { it.role == "Agent" && it.text.contains("red", ignoreCase = true) } }
    }

    @Test fun gitProjectEditingAndViews() {
        val project = InstrumentationRegistry.getArguments().getString("smokeProject")
        assumeTrue("Requires configured Mac and an existing Git repository", project != null)
        val app = compose.activity.application as AgentDeckApplication
        compose.onNodeWithText("连接 / 重连").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("SSH 已连接").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("工作台").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size >= 2 }
        compose.onNodeWithText("Graph").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("/temporary")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsEnabled().performTextReplacement(project!!)
        val workspace = app.workspaces.values.first()
        assertNull(workspace.error)
        compose.onNodeWithText("刷新").performClick()
        compose.waitUntil(20_000) { !workspace.busy && workspace.commits.isNotEmpty() }
        fun capture(name: String) {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                java.io.File(app.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        capture("graph.png")
        compose.onNodeWithText("Changes").performClick()
        compose.waitUntil(20_000) { !workspace.busy && workspace.gitState != null }
        assertEquals(project, workspace.gitState!!.getString("root"))
        capture("changes.png")
    }
}
