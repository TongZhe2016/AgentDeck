package com.worldcopy.agentdeck.core

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.network.HostApi
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ActiveWriterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun fixture(block: (WorkspaceViewModel, WorkspaceFixtureService) -> Unit) {
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val app = compose.activity.application as AgentDeckApplication
        val id = "writer-${UUID.randomUUID()}"
        val vm = app.workspace(id)
        WorkspaceFixtureService(initiallyManaged = false).use { service ->
            service.externalWriter = true
            try {
                compose.setContent { AgentDeckTheme { Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { WorkspaceScreen(vm) {} } } }
                compose.runOnUiThread { vm.connect(id, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.isNotEmpty() }
                compose.runOnUiThread { vm.updateDraft("保留的草稿") }
                compose.onNodeWithTag("takeover-session").assertIsDisplayed()
                compose.onNodeWithTag("execution-settings-bar").assertDoesNotExist()
                compose.onNodeWithText("输入消息").assertDoesNotExist()
                compose.onNodeWithText("附件").assertDoesNotExist()
                compose.onNodeWithTag("fork-session").assertDoesNotExist()
                assertTrue(service.writes.isEmpty())
                screenshot("writer-initial.png")
                block(vm, service)
            } finally {
                compose.runOnUiThread { vm.disconnect(); app.workspaces.remove(id) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(id) }?.forEach { it.delete() }
            }
        }
    }

    @Test fun successfulTakeoverRestoresComposerPreservesDraftAndKeepsFork() = fixture { vm, service ->
        compose.onNodeWithTag("takeover-session").performClick()
        compose.waitUntil(10_000) { !vm.busy && !vm.hasExternalWriter }
        compose.onNodeWithTag("execution-settings-bar").assertIsDisplayed()
        compose.onNodeWithText("保留的草稿").assertIsDisplayed()
        compose.onNodeWithText("附件").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fork-session").performScrollTo().assertIsDisplayed()
        assertEquals("thread", vm.selected!!.getString("id"))
        assertEquals("初始回复", vm.messages.single().text)
        assertEquals(listOf("/v1/sessions/thread/takeover"), service.writes.map { it.first })
        screenshot("writer-acquired.png")
    }

    @Test fun writerConflictOnSendKeepsDraftAndReturnsToTakeoverButton() = fixture { vm, service ->
        compose.onNodeWithTag("takeover-session").performClick()
        compose.waitUntil(10_000) { !vm.busy && !vm.hasExternalWriter }
        service.runWriterConflict = true
        compose.onNodeWithText("发送").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.hasExternalWriter }
        assertEquals("保留的草稿", vm.draft)
        assertFalse(vm.hasUnconfirmedSubmission)
        compose.onNodeWithTag("takeover-session").assertIsDisplayed()
        compose.onNodeWithText("输入消息").assertDoesNotExist()
    }

    @Test fun blockedTakeoverShowsMessageAndForkCreatesIndependentConversation() = fixture { vm, service ->
        service.refuseTakeover = true
        compose.onNodeWithTag("takeover-session").performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.showFork }
        compose.onNodeWithText("有其他用户正在使用").assertIsDisplayed()
        compose.onNodeWithTag("execution-settings-bar").assertDoesNotExist()
        compose.onNodeWithText("输入消息").assertDoesNotExist()
        compose.onNodeWithText("附件").assertDoesNotExist()
        compose.onNodeWithTag("fork-session").assertIsDisplayed()
        assertEquals("保留的草稿", vm.draft)
        screenshot("writer-blocked.png")
        compose.onNodeWithTag("fork-session").performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.selected?.optString("id") == "forked" && vm.messages.isNotEmpty() }
        compose.onNodeWithTag("execution-settings-bar").assertIsDisplayed()
        assertEquals("初始回复", vm.messages.single().text)
        assertEquals(listOf("/v1/sessions/thread/takeover", "/v1/sessions/thread/fork"), service.writes.map { it.first })
        assertTrue(vm.projectSessions.any { it.optString("id") == "thread" })
        assertTrue(vm.projectSessions.any { it.optString("id") == "forked" })
        compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
        compose.waitUntil(10_000) { !vm.busy && vm.hasExternalWriter }
        assertEquals("保留的草稿", vm.draft)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
