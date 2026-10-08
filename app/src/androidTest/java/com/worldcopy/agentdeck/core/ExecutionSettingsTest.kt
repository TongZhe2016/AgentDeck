package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import com.worldcopy.agentdeck.feature.workspace.ExecutionSettingsSheet
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExecutionSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectingModelUpdatesEffortsAndSavesAllThreeChoices() {
        fun model(id: String, name: String, efforts: List<String>) = JSONObject().put("id", id).put("model", id).put("displayName", name)
            .put("defaultReasoningEffort", "medium").put("supportedReasoningEfforts", JSONArray(efforts.map { JSONObject().put("reasoningEffort", it) }))
        var saved: List<String>? = null
        compose.setContent { AgentDeckTheme {
            ExecutionSettingsSheet(listOf(model("sol", "GPT-6.1 Sol", listOf("low", "medium")), model("astra", "GPT-6 Astra", listOf("medium", "high", "max"))),
                false, null, "sol", "low", "on-request", false, {}, {}) { name, effort, mode -> saved = listOf(name, effort, mode) }
        } }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("GPT-6 Astra"))
        compose.onNodeWithText("GPT-6 Astra").performClick()
        compose.onNodeWithText("低").assertDoesNotExist()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("最大"))
        compose.onNodeWithText("最大").performClick()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("完全访问"))
        compose.onNodeWithText("完全访问").performClick()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("保存设置"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: instrumentation.targetContext.cacheDir
        output.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output, "execution-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("保存设置").performClick()
        assertEquals(listOf("astra", "max", "full-access"), saved)
    }
}
