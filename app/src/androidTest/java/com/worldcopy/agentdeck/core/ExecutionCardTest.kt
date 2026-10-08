package com.worldcopy.agentdeck.core

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
        compose.setContent { AgentDeckTheme { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("我先查看实现，再修改界面。")
            ExecutionCard("turn", steps, "running")
        } } }
        compose.onNodeWithText("我先查看实现，再修改界面。").assertIsDisplayed()
        compose.onNodeWithText("执行中").assertIsDisplayed()
        compose.onNodeWithText("读取 README.md").assertDoesNotExist()
        compose.onNodeWithTag("step-detail:read").assertDoesNotExist()
        capture("execution-collapsed")
        compose.onNodeWithText("展开").performClick()
        capture("execution-steps")
        compose.onNodeWithTag("step:read").assertIsDisplayed().performClick()
        compose.onNodeWithText("cat README.md\n完整输出内容").assertIsDisplayed()
        compose.onNodeWithTag("step-detail:search").assertDoesNotExist()
        capture("execution-details")
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithTag("step-detail:read").assertDoesNotExist()
    }
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        output.mkdirs()
        File(output, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
