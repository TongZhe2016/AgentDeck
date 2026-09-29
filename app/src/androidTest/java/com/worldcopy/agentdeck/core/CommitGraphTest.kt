package com.worldcopy.agentdeck.core

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.feature.workspace.CommitGraphRow
import com.worldcopy.agentdeck.feature.workspace.graphRows
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CommitGraphTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactTitleExpandsDetailsAndKeepsParentDiffActions() {
        val oid = "1234567890abcdef1234567890abcdef12345678"
        val subject = "fix: resolve workspace host names and preserve project context across connected computers"
        val body = "$subject\n\nKeep each conversation attached to its own host and project."
        val commit = JSONObject().put("oid", oid).put("subject", subject).put("author", "Test Author")
            .put("date", "2026-09-30T11:58:00+08:00").put("refs", "HEAD -> main, origin/main")
        val row = graphRows(listOf(oid to listOf("parent-a", "parent-b"))).first()
        var chosenDiff: Pair<String, Int>? = null
        var quoted = false
        compose.setContent {
            var expanded by remember { mutableStateOf(false) }
            var parent by remember { mutableIntStateOf(0) }
            val detail = JSONObject().put("message", body).put("parents", JSONArray(listOf("parent-a", "parent-b")))
                .put("parentIndex", parent).put("paths", JSONArray(listOf(if (parent == 0) "app/Hosts.kt" else "app/Projects.kt")))
            AgentDeckTheme {
                LazyColumn(Modifier.width(360.dp)) {
                    item { CommitGraphRow(commit, row, expanded, detail, false, Instant.parse("2026-09-30T04:00:00Z"),
                        toggle = { expanded = !expanded }, loadParent = { parent = it },
                        openDiff = { path, index -> chosenDiff = path to index }, quote = { quoted = true }) }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(subject, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        compose.onNodeWithText("Test Author").assertDoesNotExist()
        compose.onNodeWithText(oid.take(8)).assertDoesNotExist()
        compose.onNodeWithText("2分钟前", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("commit-row-$oid").assertHeightIsEqualTo(48.dp)
        capture("graph-compact.png")
        compose.onNodeWithTag("commit-row-$oid").performClick()
        compose.onNodeWithText(body).assertExists()
        compose.onNodeWithText("Test Author").assertExists()
        compose.onNodeWithText(oid.take(8)).assertExists()
        compose.onNodeWithText("父提交 2").performScrollTo().performClick()
        compose.onNodeWithText("app/Projects.kt").performScrollTo().performClick()
        assertEquals("app/Projects.kt" to 1, chosenDiff)
        compose.onNodeWithText("引用到对话").performScrollTo().performClick()
        assertTrue(quoted)
        capture("graph-expanded.png")
        compose.onNodeWithTag("commit-row-$oid").performScrollTo().performClick()
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithText(oid.take(8)).assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
