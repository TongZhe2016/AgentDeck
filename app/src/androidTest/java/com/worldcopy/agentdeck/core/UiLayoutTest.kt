package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as AgentDeckApplication
    private val fixtures = mutableListOf<String>()
    private val project = "/workspace/AgentDeck"
    private val threadId = "ui-conversation"
    private fun ready() = compose.waitUntil(15000) { !app.hosts.busy && app.workspaces.values.none { it.busy } }

    @After fun cleanup() {
        ready()
        fixtures.forEach { id ->
            compose.runOnUiThread { app.hosts.deleteHost(id) }
            ready()
            File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
        }
    }

    @Test fun navigationProjectChatAndDiffRemainReachable() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val hosts = listOf(
            Host(name = "MacBook Pro", address = "mac.local", username = "demo", authMethod = AuthMethod.PASSWORD),
            Host(name = "Ubuntu · 开发服务器", address = "ubuntu.local", username = "demo", authMethod = AuthMethod.PASSWORD),
        )
        for (host in hosts) {
            fixtures += host.id
            val cache = File(app.noBackupFilesDir, "workspace").apply { mkdirs() }
            val session = JSONObject().put("id", threadId).put("cwd", project).put("name", "优化项目首页与移动端体验").put("managed", false)
            val sessions = listOf(session, JSONObject().put("id", "history").put("cwd", "/workspace/website").put("name", "检查网站构建和 Git 状态"))
            File(cache, "${host.id}-sessions.json").writeText(JSONObject().put("data", JSONArray(sessions)).toString())
            File(cache, "${host.id}-history-$threadId.json").writeText(JSONObject().put("messages", JSONArray(listOf(
                JSONObject().put("id", "user").put("role", "你").put("text", "帮我优化项目首页，让主机和文件夹更容易辨认。"),
                JSONObject().put("id", "agent").put("role", "Agent").put("text", "## 首页已更新\n项目按主机与文件夹分组，默认收起对话。\n\n点击项目即可展开，继续上一次工作。"),
            ))).toString())
            File(cache, "${host.id}-git.json").writeText(JSONObject().put("root", project).put("branch", "main").put("changedFiles", 2)
                .put("commitCount", 18).put("upstream", "origin/main").put("changes", JSONArray(listOf(
                    JSONObject().put("path", "app/src/main/ProjectsScreen.kt").put("status", "M").put("group", "unstaged").put("additions", 24).put("deletions", 8),
                    JSONObject().put("path", "docs/design.md").put("status", "A").put("group", "untracked").put("additions", 12).put("deletions", 0),
                ))).toString())
            val query = JSONObject(mapOf("cwd" to project, "path" to "app/src/main/ProjectsScreen.kt", "group" to "unstaged", "parent" to "0"))
            val diff = JSONObject().put("path", "app/src/main/ProjectsScreen.kt")
                .put("text", "@@ -1,2 +1,3 @@\n fun ProjectScreen() {\n-    Text(\"项目\")\n+    ProjectCard(project)\n+    ConversationList(project)\n }")
            File(cache, "${host.id}-diff.json").writeText(JSONObject().put("query", query.toString()).put("result", diff).toString())
            ready(); compose.runOnUiThread { app.hosts.save(host, null) {} }; ready()
            compose.waitUntil(10000) { app.workspaces[host.id]?.projectSessions?.size == 2 }
        }
        val first = hosts.first()
        val projectTag = "project:${first.id}:$project"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("project-host:${first.id}"))
        compose.onNodeWithTag("project-host:${first.id}").assertIsDisplayed()
        capture("projects")
        compose.onNodeWithTag("project-host:${first.id}").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(projectTag))
        compose.onNodeWithTag(projectTag).assertIsDisplayed()
        capture("host-expanded")
        compose.onNodeWithTag(projectTag).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("thread:${first.id}:$threadId"))
        capture("project-expanded")
        compose.onNodeWithTag("thread:${first.id}:$threadId").assertIsDisplayed()
        compose.onNodeWithTag("thread:${first.id}:$threadId").performClick()
        compose.waitUntil(10000) { app.workspaces[first.id]?.messages?.size == 2 }
        capture("chat")
        compose.onNodeWithText("输入消息").performTextInput("草稿仍可继续编辑")
        compose.onNodeWithText("发送").assertIsDisplayed()
        compose.onNodeWithText("附件").performClick()
        compose.onNodeWithText("图片").assertIsDisplayed()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        compose.onNodeWithText("语音转文字").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Changes").performClick()
        compose.onNodeWithTag("git-changes").performScrollToNode(hasText("ProjectsScreen.kt"))
        compose.onNodeWithText("ProjectsScreen.kt").performScrollTo().performClick()
        compose.waitUntil(10000) { app.workspaces[first.id]?.diff != null }
        capture("diff")
        compose.onNodeWithContentDescription("关闭差异").performClick()
        compose.onNodeWithContentDescription("返回项目").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("project-host:${first.id}"))
        compose.onNodeWithTag("project-host:${first.id}").performTouchInput { longClick() }
        listOf("进入工作台", "连接", "重连", "编辑", "克隆", "删除").forEach {
            compose.onNodeWithText(it, substring = false).performScrollTo().assertIsDisplayed()
        }
        capture("host-menu")
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithText("主机名称").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("更多设置").performClick()
        compose.onNodeWithText("密钥", substring = false).performClick()
        capture("keys")
        compose.onNodeWithText("导入已有密钥").performScrollTo().performClick()
        compose.onNodeWithTag("import-private").performScrollTo().assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回项目").performClick()
        compose.onNodeWithContentDescription("待处理").performClick()
        compose.onNodeWithText("暂无待处理事项").assertExists()
        capture("pending")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val variant = InstrumentationRegistry.getArguments().getString("uiVariant") ?: "portrait"
        val surface = if (name == "diff" || name == "host-menu") compose.onNode(isDialog()) else compose.onRoot()
        surface.captureToImage().asAndroidBitmap().let { bitmap ->
            File(app.cacheDir, "ui-$variant-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
