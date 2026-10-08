package com.worldcopy.agentdeck.core

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

class ContinueHistoryTest {
    @get:Rule val compose = createComposeRule()

    private fun history(block: (WorkspaceViewModel, WorkspaceFixtureService) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentDeckApplication
        val hostId = "continue-${UUID.randomUUID()}"
        val vm = app.workspace(hostId)
        WorkspaceFixtureService(initiallyManaged = false).use { service ->
            try {
                compose.setContent { AgentDeckTheme { Surface { WorkspaceScreen(vm) {} } } }
                compose.runOnIdle { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnIdle { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.isNotEmpty() }
                assertTrue(service.writes.isEmpty()) // Reading history must not resume it.
                block(vm, service)
            } finally {
                compose.runOnIdle { vm.disconnect(); app.workspaces.remove(hostId) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }

    @Test fun historySettingsAreClickableInlineAndApplyBeforeContinuing() = history { vm, service ->
        compose.onNodeWithText("设置").assertDoesNotExist()
        val bar = compose.onNodeWithTag("execution-settings-bar")
        bar.assertIsEnabled().assertIsDisplayed()
        val labels = listOf("gpt-6.1-sol", "high", "请求批准").map {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertEquals(labels[0].center.y, labels[1].center.y, 1f)
        assertEquals(labels[1].center.y, labels[2].center.y, 1f)
        assertTrue(labels[0].right < labels[1].left && labels[1].right < labels[2].left)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "continue-history.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("high").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading && vm.models.isNotEmpty() }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
        compose.onNodeWithTag("effort-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("permission-slider"))
        compose.onNodeWithTag("permission-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(4f) }
        compose.onNodeWithText("保存设置").assertDoesNotExist()
        compose.onNodeWithTag("close-execution-settings").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        assertEquals(listOf("/v1/sessions/thread/resume", "/v1/sessions/thread/settings"), service.writes.map { it.first })
        assertTrue(vm.selected!!.getBoolean("managed"))
        assertEquals("low", vm.selected!!.getJSONObject("executionSettings").getString("effort"))
        assertEquals("full-access", vm.selected!!.getJSONObject("executionSettings").getString("permissionMode"))
        compose.onNodeWithText("完全访问", useUnmergedTree = true).assertIsDisplayed()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output.parentFile, "full-access-inline.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue(vm.projectSessions.first().getBoolean("managed"))
        assertEquals("初始回复", vm.messages.single().text)
        compose.runOnIdle { vm.updateDraft("Continue with selected settings") }
        compose.onNodeWithText("发送").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.runs.isNotEmpty() }
        assertEquals(1, service.writes.count { it.first.endsWith("/resume") })
        assertEquals("/v1/runs", service.writes.last().first)
        assertEquals("thread", service.writes.last().second.getString("threadId"))
        assertEquals("", vm.draft)
    }

    @Test fun backAppliesChangesAndUnchangedCloseDoesNotWrite() = history { vm, service ->
        compose.onNodeWithTag("execution-settings-bar").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading && vm.models.isNotEmpty() }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        assertTrue(service.writes.isEmpty())
        compose.onNodeWithTag("execution-settings-bar").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
        compose.onNodeWithTag("effort-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        assertEquals("low", service.settings.getString("effort"))
        compose.onNodeWithTag("execution-settings-bar").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading }
        compose.onNodeWithTag("close-execution-settings").performClick()
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        assertEquals(2, service.writes.size)
    }

    @Test fun swipeDismissKeepsChoicesOnSaveFailureAndRetriesOnClose() = history { vm, service ->
        compose.onNodeWithTag("execution-settings-bar").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading && vm.models.isNotEmpty() }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
        compose.onNodeWithTag("effort-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        service.refuseSettings = true
        compose.onNodeWithText("执行设置").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 600f), durationMillis = 400)
        }
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        compose.onNodeWithText("设置暂时无法保存").assertIsDisplayed()
        compose.onNodeWithText("执行设置").assertIsDisplayed()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-value"))
        compose.onNodeWithTag("effort-value").assertTextEquals("low")
        assertEquals("high", vm.selected!!.getJSONObject("executionSettings").getString("effort"))
        service.refuseSettings = false
        compose.onNodeWithTag("close-execution-settings").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.settings.getString("effort") == "low" }
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        assertEquals(1, service.writes.count { it.first.endsWith("/resume") })
        assertEquals(2, service.writes.count { it.first.endsWith("/settings") })
        service.refuseSettings = true
        compose.onNodeWithTag("execution-settings-bar").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
        compose.onNodeWithTag("effort-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("close-execution-settings").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 4 }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("放弃更改"))
        compose.onNodeWithText("放弃更改").performClick()
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        assertEquals("low", service.settings.getString("effort"))
    }

    @Test fun continueResumesOriginalThreadAndKeepsDraftIfOtherClientIsRunning() = history { vm, service ->
        service.refuseResume = true
        compose.runOnIdle { vm.updateDraft("Continue original conversation") }
        compose.onNodeWithText("继续对话").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.error != null }
        assertEquals("Continue original conversation", vm.draft)
        assertFalse(vm.hasUnconfirmedSubmission)
        assertFalse(vm.selected!!.getBoolean("managed"))
        assertFalse(service.writes.any { it.first == "/v1/runs" })
        compose.onNodeWithText("知道了").performClick()
        service.refuseResume = false
        compose.onNodeWithText("继续对话").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy && vm.runs.isNotEmpty() }
        assertEquals("/v1/runs", service.writes.last().first)
        assertEquals("thread", service.writes.last().second.getString("threadId"))
        assertEquals("Continue original conversation", service.writes.last().second.getString("text"))
        assertTrue(vm.selected!!.getBoolean("managed"))
    }
}
