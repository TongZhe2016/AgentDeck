package com.worldcopy.agentdeck.core

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.storage.HostStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HostCloneTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as AgentDeckApplication
    private val prefix = "Clone fixture"
    private fun ready() = compose.waitUntil(10000) { !app.hosts.busy }

    @After fun cleanup() {
        ready()
        app.hosts.hosts.filter { it.name.startsWith(prefix) }.forEach { host ->
            compose.runOnUiThread { app.hosts.deleteHost(host.id) }
            ready()
            File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith("${host.id}-") }?.forEach { it.delete() }
        }
        app.hosts.identities.filter { it.name.startsWith(prefix) }.forEach { key ->
            compose.runOnUiThread { app.hosts.deleteKey(key) }
            ready()
        }
    }

    private fun add(host: Host, password: String? = null) {
        ready()
        compose.runOnUiThread { app.hosts.save(host, password) {} }
        compose.waitUntil(10000) { !app.hosts.busy && app.hosts.hosts.any { it.id == host.id } }
    }

    private fun openClone(host: Host) {
        compose.onNodeWithTag("project-host:${host.id}").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("克隆", substring = false).performClick()
        compose.onNodeWithText("克隆主机").assertIsDisplayed()
    }

    @Test fun passwordCloneCanBeCancelledThenEditedWithoutChangingSource() {
        val source = Host(name = "$prefix Mac", address = "192.0.2.10", port = 2222, username = "demo",
            authMethod = AuthMethod.PASSWORD, trustedHostKey = "fixture-host-key", servicePort = 5001, serviceDirectory = "~/deck")
        add(source, "fixture-password")
        app.hosts.store.vault.put("token-${source.id}", "fixture-token".toByteArray())
        val count = app.hosts.hosts.size
        openClone(source)
        compose.onNodeWithText("主机名称").assertTextContains("${source.name}（克隆）")
        compose.onNodeWithText("SSH 地址").assertTextContains(source.address)
        compose.onNodeWithText("SSH 端口").assertTextContains("2222")
        compose.onNodeWithText("取消").performClick()
        assertEquals(count, app.hosts.hosts.size)

        openClone(source)
        compose.onNodeWithText("主机名称").performTextReplacement("$prefix Ubuntu")
        compose.onNodeWithText("SSH 地址").performTextReplacement("192.0.2.11")
        compose.onNodeWithText("SSH 端口").performTextReplacement("2223")
        compose.onNodeWithText("用户名").performTextReplacement("developer")
        compose.onNodeWithText("高级服务设置").performScrollTo().performClick()
        compose.onNodeWithText("服务端口").performScrollTo().assertTextContains("5001")
        compose.onNodeWithText("服务数据目录").performScrollTo().assertTextContains("~/deck")
        compose.onNodeWithText("服务端口").performScrollTo().performTextReplacement("5002")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { !app.hosts.busy && app.hosts.hosts.size == count + 1 }

        val store = HostStore(app)
        val cloned = store.hosts().single { it.name == "$prefix Ubuntu" }
        assertNotEquals(source.id, cloned.id)
        assertEquals("192.0.2.11", cloned.address)
        assertEquals(2223, cloned.port)
        assertEquals("developer", cloned.username)
        assertEquals(5002, cloned.servicePort)
        assertEquals("~/deck", cloned.serviceDirectory)
        assertEquals(AuthMethod.PASSWORD, cloned.authMethod)
        assertNull(cloned.trustedHostKey)
        assertNull(store.vault.get("token-${cloned.id}"))
        assertEquals("fixture-password", store.vault.get("password-${cloned.id}")?.toString(Charsets.UTF_8))
        assertEquals(source, store.hosts().single { it.id == source.id })
        assertEquals("fixture-token", store.vault.get("token-${source.id}")?.toString(Charsets.UTF_8))
    }

    @Test fun keyCloneKeepsIdentityAndCanSwitchToPasswordAuthentication() {
        compose.runOnUiThread { app.hosts.createKey("$prefix key") }
        ready()
        val identity = app.hosts.identities.single { it.name == "$prefix key" }
        val source = Host(name = "$prefix key host", address = "192.0.2.20", username = "demo", identityId = identity.id)
        add(source)
        val count = app.hosts.hosts.size
        openClone(source)
        compose.onNodeWithText(identity.name).performScrollTo().assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { !app.hosts.busy && app.hosts.hosts.size == count + 1 }
        val cloned = app.hosts.hosts.single { it.name == "${source.name}（克隆）" }
        assertNotEquals(source.id, cloned.id)
        assertEquals(identity.id, cloned.identityId)
        assertEquals(AuthMethod.KEY, cloned.authMethod)
        assertEquals(source.address, cloned.address)

        openClone(source)
        compose.onNodeWithText("主机名称").performTextReplacement("$prefix password host")
        compose.onNodeWithText("密码", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请填写密码").assertExists()
        assertEquals(count + 1, app.hosts.hosts.size)
        compose.onNode(hasSetTextAction() and hasText("密码")).performScrollTo().performTextInput("replacement-password")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { !app.hosts.busy && app.hosts.hosts.size == count + 2 }
        val passwordHost = app.hosts.hosts.single { it.name == "$prefix password host" }
        assertEquals(AuthMethod.PASSWORD, passwordHost.authMethod)
        assertNull(passwordHost.identityId)
        assertEquals("replacement-password", app.hosts.store.vault.get("password-${passwordHost.id}")?.toString(Charsets.UTF_8))
        assertEquals(source, app.hosts.hosts.single { it.id == source.id })
    }
}
