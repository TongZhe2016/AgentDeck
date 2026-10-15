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

class ModelCatalogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun model(id: String, name: String, efforts: List<String>) = JSONObject().put("id", id).put("model", id)
        .put("displayName", name).put("defaultReasoningEffort", efforts.firstOrNull() ?: "none")
        .put("supportedReasoningEfforts", JSONArray(efforts.map { JSONObject().put("reasoningEffort", it) }))

    private fun fixture(block: (WorkspaceViewModel, WorkspaceFixtureService) -> Unit) {
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val app = compose.activity.application as AgentDeckApplication
        val hostId = "model-catalog-${UUID.randomUUID()}"
        val vm = app.workspace(hostId)
        WorkspaceFixtureService().use { service ->
            service.modelCatalog = JSONArray(listOf(model("deepseek-fixture", "DeepSeek Fixture", emptyList()), model("future-vendor", "Future Vendor", listOf("high"))))
            try {
                compose.setContent { AgentDeckTheme { Surface { WorkspaceScreen(vm) {} } } }
                compose.runOnUiThread { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnUiThread { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.isNotEmpty() }
                compose.onNodeWithTag("execution-settings-bar").performClick()
                compose.waitUntil(10_000) { !vm.optionsLoading && vm.models.size == 2 }
                block(vm, service)
            } finally {
                compose.runOnUiThread { vm.disconnect(); app.workspaces.remove(hostId) }
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }

    @Test fun customModelUsesHostDefaultWithoutReasoningAndRefreshDiscoversNewModel() = fixture { vm, service ->
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("DeepSeek Fixture"))
        compose.onNodeWithText("DeepSeek Fixture").performClick()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("此模型未提供可调思考档位"))
        compose.onNodeWithTag("effort-slider").assertDoesNotExist()
        val output = File(compose.activity.getExternalFilesDir(null), "model-catalog.png")
        compose.onNodeWithTag("execution-settings-options").captureToImage().asAndroidBitmap().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("close-execution-settings").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 1 }
        val settings = service.writes.single().second
        assertEquals("deepseek-fixture", settings.getString("model"))
        assertEquals("none", settings.getString("effort"))
        assertEquals("none", vm.selected!!.getJSONObject("executionSettings").getString("effort"))
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        compose.onNodeWithTag("execution-settings-bar").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading && service.modelReads.get() == 2 }
        service.modelCatalog = JSONArray(listOf(model("fresh-model", "New Host Model", listOf("high"))))
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("refresh-models"))
        compose.onNodeWithTag("refresh-models").performClick()
        compose.waitUntil(10_000) { !vm.optionsLoading && vm.models.singleOrNull()?.optString("model") == "fresh-model" }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("New Host Model"))
        compose.onNodeWithText("New Host Model").performClick()
        compose.onNodeWithTag("close-execution-settings").performClick()
        compose.waitUntil(10_000) { !vm.busy && service.writes.size == 2 }
        assertEquals("fresh-model", service.writes.last().second.getString("model"))
        assertEquals("high", service.writes.last().second.getString("effort"))
        assertEquals(3, service.modelReads.get())
    }

    @Test fun refreshedCatalogRemovingEditedSelectionOffersDiscard() = fixture { _, service ->
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("Future Vendor"))
        compose.onNodeWithText("Future Vendor").performClick()
        service.modelCatalog = JSONArray(listOf(model("another-model", "Another Model", listOf("low"))))
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("refresh-models"))
        compose.onNodeWithTag("refresh-models").performClick()
        compose.waitUntil(10_000) { service.modelReads.get() == 2 }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("放弃更改"))
        compose.onNodeWithText("放弃更改").performClick()
        compose.onNodeWithText("执行设置").assertDoesNotExist()
        assertTrue(service.writes.isEmpty())
    }
}
