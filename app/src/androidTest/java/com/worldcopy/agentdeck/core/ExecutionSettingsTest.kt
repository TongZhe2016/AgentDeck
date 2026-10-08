package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.worldcopy.agentdeck.feature.workspace.ExecutionSettingsSheet
import com.worldcopy.agentdeck.feature.workspace.FullAccessTrack
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.MotionDurationScale
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExecutionSettingsTest {
    // Compose tests replace the Android animation clock, so pass its duration scale explicitly.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(object : MotionDurationScale {
        override val scaleFactor = if (InstrumentationRegistry.getArguments().getString("animationsDisabled") == "true") 0f else 1f
    })
    @Test fun fullAccessWarningStripesMove() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AgentDeckTheme { FullAccessTrack(steps = 3) } }
        compose.mainClock.advanceTimeBy(32)
        val before = compose.onNodeWithTag("full-access-track").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(450)
        val after = compose.onNodeWithTag("full-access-track").captureToImage().toPixelMap()
        val changed = (0 until before.width).any { x -> before[x, before.height / 2] != after[x, after.height / 2] }
        val animationsDisabled = InstrumentationRegistry.getArguments().getString("animationsDisabled") == "true"
        assertEquals("Warning stripes follow the animation duration scale", !animationsDisabled, changed)
    }

    @Test fun effortSliderOrdersLevelsHighlightsUltraAndAdaptsToModel() {
        val landscape = InstrumentationRegistry.getArguments().getString("landscape") == "true"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        fun model(id: String, efforts: List<String>, default: String) = JSONObject().put("id", id).put("model", id).put("displayName", id)
            .put("defaultReasoningEffort", default).put("supportedReasoningEfforts", JSONArray(efforts.map { JSONObject().put("reasoningEffort", it) }))
        val levels = listOf("low", "medium", "high", "xhigh", "max", "ultra")
        val labels = listOf("low", "medium", "high", "extreme high", "max", "ultra")
        var saved: List<String>? = null
        compose.setContent { AgentDeckTheme {
            ExecutionSettingsSheet(listOf(model("All levels", listOf("ultra", "max", "medium", "xhigh", "low", "high"), "medium"),
                model("Single level", listOf("high"), "high")), false, null, "All levels", "medium", "on-request", false, {}, {}) {
                model, effort, mode -> saved = listOf(model, effort, mode)
            }
        } }
        var normalColor: androidx.compose.ui.graphics.Color? = null
        levels.forEachIndexed { index, level ->
            compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
            val slider = compose.onNodeWithTag("effort-slider")
            when (index) {
                0 -> slider.performTouchInput { swipe(center, centerLeft, durationMillis = 400) }
                5 -> slider.performTouchInput { swipe(Offset(width * .8f, centerY), centerRight, durationMillis = 400) }
                else -> slider.performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
            }
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, labels[index]))
            compose.onNodeWithTag("effort-value").assertTextEquals(labels[index])
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("effort-value").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            val color = layout.single().layoutInput.style.color
            if (index == 0) normalColor = color
            if (level == "ultra") {
                assertNotEquals(normalColor, color)
                compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("思考强度"))
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val output = File(instrumentation.targetContext.getExternalFilesDir(null), "effort-ultra.png")
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            val previous = saved
            compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("保存设置"))
            assertEquals(previous, saved)
            compose.onNodeWithText("保存设置").performClick()
            assertEquals(listOf("All levels", level, "on-request"), saved)
        }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("Single level"))
        compose.onNodeWithText("Single level").performClick()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
        compose.onNodeWithTag("effort-slider").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "high"))
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("保存设置"))
        compose.onNodeWithText("保存设置").performClick()
        assertEquals(listOf("Single level", "high", "on-request"), saved)
    }

    @Test fun selectingModelAndSlidingPermissionSavesAllThreeChoices() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        fun model(id: String, name: String, efforts: List<String>) = JSONObject().put("id", id).put("model", id).put("displayName", name)
            .put("defaultReasoningEffort", "medium").put("supportedReasoningEfforts", JSONArray(efforts.map { JSONObject().put("reasoningEffort", it) }))
        var saved: List<String>? = null
        compose.setContent { AgentDeckTheme {
            ExecutionSettingsSheet(listOf(model("sol", "GPT-6.1 Sol", listOf("low", "medium")), model("astra", "GPT-6 Astra", listOf("medium", "high", "max"))),
                false, null, "sol", "low", "on-request", false, {}, {}) { name, effort, mode -> saved = listOf(name, effort, mode) }
        } }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("GPT-6 Astra"))
        compose.onNodeWithText("GPT-6 Astra").performClick()
        compose.onNodeWithText("low").assertDoesNotExist()
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("effort-slider"))
        compose.onNodeWithTag("effort-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        val levels = listOf("read-only" to "只读", "untrusted" to "未信任", "on-request" to "请求批准", "never" to "不请求批准", "full-access" to "完全访问")
        levels.forEachIndexed { index, (mode, label) ->
            compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasTestTag("permission-slider"))
            val slider = compose.onNodeWithTag("permission-slider")
            val previouslySaved = saved
            when (index) {
                0 -> slider.performTouchInput { swipe(center, centerLeft, durationMillis = 500) }
                4 -> slider.performTouchInput { swipe(Offset(width * .75f, centerY), centerRight, durationMillis = 500) }
                else -> slider.performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
            }
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
            if (mode == "full-access") compose.onNodeWithTag("full-access-track", useUnmergedTree = true).assertIsDisplayed()
            else compose.onNodeWithTag("full-access-track", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(previouslySaved, saved)
            compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("保存设置"))
            compose.onNodeWithText("保存设置").performClick()
            assertEquals(listOf("astra", "max", mode), saved)
        }
        compose.onNodeWithTag("execution-settings-options").performScrollToNode(hasText("保存设置"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: instrumentation.targetContext.getExternalFilesDir(null)!!
        output.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output, "execution-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("保存设置").performClick()
        assertEquals(listOf("astra", "max", "full-access"), saved)
    }
}
