package com.worldcopy.agentdeck.core

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.MainActivity
import com.worldcopy.agentdeck.core.model.Host
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class StartupSyncTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentDeckApplication
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
        while (true) {
            var done = false
            onMain { done = condition() }
            if (done) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Startup sync timed out" }
            Thread.sleep(50)
        }
    }

    // Requires a temporary SSH identity authorized on a real AgentDeck Mac service.
    @Test fun launchAndForegroundSyncWithoutManualConnection() {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("startupHost")
        assumeTrue("Requires explicit startup SSH fixture", name != null)
        val project = args.getString("startupProject")!!
        var original: Host? = null
        onMain { original = app.hosts.hosts.first { it.name == name } }
        val host = original!!
        val failed = host.copy(id = UUID.randomUUID().toString(), name = "Startup offline fixture", port = 1)
        val first = host.copy(id = UUID.randomUUID().toString(), name = "Startup confirmation A", trustedHostKey = null)
        val second = host.copy(id = UUID.randomUUID().toString(), name = "Startup confirmation B", trustedHostKey = null)
        val fixtures = listOf(failed, first, second, host)
        val cache = File(app.noBackupFilesDir, "workspace").apply { mkdirs() }
        val cached = JSONObject().put("id", "offline-fixture").put("cwd", "/offline/cache").put("name", "Cached conversation")
        File(cache, "${failed.id}-sessions.json").writeText(JSONObject().put("data", JSONArray(listOf(cached))).toString())
        try {
            // Put failure and identity prompts before the usable host in the saved order.
            for (fixture in fixtures) {
                await { !app.hosts.busy }
                onMain { app.hosts.save(fixture, null) {} }
                await { !app.hosts.busy && app.hosts.hosts.any { it.id == fixture.id } }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var confirmed: Host
                lateinit var remaining: Host
                await {
                    !app.hosts.busy && app.workspaces[host.id]?.let { workspace ->
                        workspace.online && !workspace.busy && workspace.projectSessions.any { it.optString("cwd") == project }
                    } == true
                }
                onMain {
                    assertEquals("连接失败", app.hosts.statuses[failed.id])
                    assertNotNull(app.workspace(failed.id).error)
                    assertEquals("offline-fixture", app.workspace(failed.id).projectSessions.single().getString("id"))
                    confirmed = app.hosts.confirmation!!.first
                    assertTrue(confirmed.id in listOf(first.id, second.id))
                    remaining = if (confirmed.id == first.id) second else first
                    assertEquals("等待核对主机身份", app.hosts.statuses[remaining.id])
                    assertNotNull(app.hosts.store.vault.get("token-${host.id}"))
                    app.hosts.trustHost()
                }
                await { !app.hosts.busy && app.workspaces[confirmed.id]?.let { it.online && !it.busy } == true }
                onMain {
                    assertEquals(remaining.id, app.hosts.confirmation!!.first.id)
                    assertNotNull(app.hosts.hosts.first { it.id == confirmed.id }.trustedHostKey)
                    app.hosts.dismissConfirmation()
                }
                val workspace = app.workspace(host.id)
                var thread = ""
                var port = 0
                var syncedAt = ""
                onMain {
                    thread = workspace.projectSessions.first { it.optString("cwd") == project }.getString("id")
                    port = app.hosts.localPort(host.id)
                    workspace.openSession(JSONObject().put("id", thread).put("cwd", project))
                }
                await { !workspace.busy && workspace.selected?.optString("id") == thread }
                onMain { workspace.updateDraft("Startup reconnect draft"); syncedAt = workspace.snapshotTime }
                scenario.recreate()
                await { !app.hosts.busy && !workspace.busy }
                onMain {
                    assertEquals(syncedAt, workspace.snapshotTime)
                    assertEquals(port, app.hosts.localPort(host.id))
                    assertEquals(thread, workspace.selected!!.getString("id"))
                    assertEquals("Startup reconnect draft", workspace.draft)
                    app.hosts.dismissConfirmation()
                    syncedAt = workspace.snapshotTime
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { !app.hosts.busy && !workspace.busy }
                onMain {
                    assertEquals(syncedAt, workspace.snapshotTime)
                    assertEquals(port, app.hosts.localPort(host.id))
                    assertEquals(thread, workspace.selected!!.getString("id"))
                    assertEquals("Startup reconnect draft", workspace.draft)
                    app.hosts.dismissConfirmation()
                }
            }
        } finally {
            await { !app.hosts.busy }
            for (fixture in fixtures) {
                onMain { app.hosts.deleteHost(fixture.id) }
                await { !app.hosts.busy && app.hosts.hosts.none { it.id == fixture.id } }
                cache.listFiles()?.filter { it.name.startsWith("${fixture.id}-") }?.forEach { it.delete() }
            }
        }
    }
}
