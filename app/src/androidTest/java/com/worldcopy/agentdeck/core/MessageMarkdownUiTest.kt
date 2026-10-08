package com.worldcopy.agentdeck.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.feature.workspace.MessageText
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MessageMarkdownUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tablesScrollAndFileLinksRemainClickableWithFormattedMarkdown() {
        var opened: String? = null
        val markdown = """
            ## Markdown 显示

            **粗体**、*斜体*、~~删除线~~和 `inline code`

            | 文件 | 状态 | 大小 | 说明 |
            | :--- | :---: | ---: | --- |
            | report.pdf | 完成 | 2048 | 最后一列 |
            | data.csv | 完成 | 1024 | 第二行 |

            > 引用段落

            3. 有序列表
               - 嵌套列表

            - [x] 已完成
            - [ ] 待完成

            [下载报告](</tmp/My Report.pdf:12>)

            ~~~kotlin
            val line = "A long code line that should scroll horizontally without wrapping or losing indentation"
            ~~~
        """.trimIndent()
        val wide = InstrumentationRegistry.getArguments().getString("wide") == "true"
        compose.setContent { AgentDeckTheme { Surface {
            Column(Modifier.width(if (wide) 750.dp else 375.dp).fillMaxHeight().padding(16.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { MessageText(markdown) { opened = it } }
            }
        } } }
        compose.onNodeWithText("Markdown 显示").assertIsDisplayed()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "markdown.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val table = compose.onNodeWithTag("markdown-table")
        table.performTouchInput { swipeLeft() }
        compose.onNodeWithText("最后一列").assertIsDisplayed()
        compose.onNodeWithText("☑").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("☐").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载报告").performScrollTo().performClick()
        assertEquals("/tmp/My Report.pdf:12", opened)
        compose.onNodeWithText("复制代码").performScrollTo().performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.ClipboardManager::class.java)
        compose.runOnIdle { assertTrue(clipboard.primaryClip!!.getItemAt(0).text.startsWith("val line =")) }
        compose.onNodeWithText("Markdown 显示").performScrollTo()

    }
}
