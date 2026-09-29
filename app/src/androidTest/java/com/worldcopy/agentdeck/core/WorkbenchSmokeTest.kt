package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

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
}
