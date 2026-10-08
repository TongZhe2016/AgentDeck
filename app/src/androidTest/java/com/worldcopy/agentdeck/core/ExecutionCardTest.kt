package com.worldcopy.agentdeck.core

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.worldcopy.agentdeck.feature.workspace.ChatItem
import com.worldcopy.agentdeck.feature.workspace.ExecutionCard
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Rule
import org.junit.Test

class ExecutionCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailsRequireTwoClicksAndStayHiddenAsOutputArrives() {
        val steps = listOf(
            ChatItem("read", "命令", "cat README.md\n完整输出内容", "turn", summary = "读取 README.md"),
            ChatItem("search", "命令", "rg needle app\n第二条输出", "turn", summary = "搜索代码"),
        )
        compose.setContent { AgentDeckTheme { Column {
            Text("我先查看实现，再修改界面。")
            ExecutionCard("turn", steps, "running")
        } } }
        compose.onNodeWithText("我先查看实现，再修改界面。").assertIsDisplayed()
        compose.onNodeWithText("执行中").assertIsDisplayed()
        compose.onNodeWithText("读取 README.md").assertDoesNotExist()
        compose.onNodeWithTag("step-detail:read").assertDoesNotExist()
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithTag("step:read").assertIsDisplayed().performClick()
        compose.onNodeWithText("cat README.md\n完整输出内容").assertIsDisplayed()
        compose.onNodeWithTag("step-detail:search").assertDoesNotExist()
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithTag("step-detail:read").assertDoesNotExist()
    }
}
