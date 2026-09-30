package com.worldcopy.agentdeck.core

import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.ssh.SshConnection
import com.worldcopy.agentdeck.core.storage.HostStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CredentialIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedIdentityRoundTripAndDeletion() {
        val store = HostStore(context)
        val identity = store.createIdentity("Storage test")
        val host = Host(name = "Storage test", address = "localhost", username = "test", identityId = identity.id)
        try {
            store.saveHost(host)
            val restored = HostStore(context)
            assertEquals(identity.publicKey, restored.identities().first { it.id == identity.id }.publicKey)
            assertNotNull(restored.keyPair(identity.id))
            val encrypted = File(context.noBackupFilesDir, "credentials/key-${identity.id}").readBytes()
            assertFalse(encrypted.contentEquals(restored.keyPair(identity.id).private.encoded))
            restored.deleteIdentity(identity.id)
            assertNull(HostStore(context).hosts().first { it.id == host.id }.identityId)
            assertFalse(File(context.noBackupFilesDir, "credentials/key-${identity.id}").exists())
        } finally {
            store.deleteHost(host.id)
            store.deleteIdentity(identity.id)
        }
    }

    // Run prepare, authorize the exported public key on the test host, then run connect.
    @Test fun sshIntegration() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("sshPhase")
        org.junit.Assume.assumeTrue("Requires explicit SSH integration parameters", phase != null)
        val store = HostStore(context)
        val fixture = args.getString("sshFixture") ?: "integration"
        val name = args.getString("sshName") ?: "本机 Mac · 开发测试"
        if (phase == "prepare" || phase == "prepare-import") {
            val identity = if (phase == "prepare-import") {
                store.importIdentity(name, File(context.cacheDir, "$fixture-private").readText(),
                    passphrase = args.getString("sshKeyPassphrase") ?: "")
            } else store.createIdentity(name)
            File(context.cacheDir, "$fixture.pub").writeText(identity.publicKey + "\n")
            File(context.cacheDir, "$fixture-id").writeText(identity.id)
        } else {
            val id = File(context.cacheDir, "$fixture-id").readText()
            if (phase == "cleanup") {
                store.hosts().filter { it.identityId == id }.forEach { store.deleteHost(it.id) }
                store.deleteIdentity(id)
                File(context.cacheDir, "$fixture-id").delete()
                File(context.cacheDir, "$fixture.pub").delete()
                File(context.cacheDir, "$fixture-private").delete()
                return
            }
            val host = Host(name = name, address = args.getString("sshHost") ?: "10.0.2.2",
                port = (args.getString("sshPort") ?: "22").toInt(), username = args.getString("sshUser")!!,
                identityId = id, trustedHostKey = args.getString("sshHostKey"),
                serviceDirectory = args.getString("serviceDirectory") ?: "~/.agentdeck")
            if (phase == "configure") {
                val existing = store.hosts().firstOrNull { it.name == name }
                store.saveHost(host.copy(id = existing?.id ?: host.id, name = name))
            } else if (phase == "auto-token") {
                val saved = store.hosts().first { it.name == name }
                store.vault.delete("token-${saved.id}")
                val app = context.applicationContext as com.worldcopy.agentdeck.AgentDeckApplication
                kotlinx.coroutines.runBlocking {
                    val api = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { app.hosts.reconnectService(saved.id) }
                    try { assertEquals(1, api.get("health").getInt("protocol")) } finally { api.close() }
                    assertNotNull(store.vault.get("token-${saved.id}"))
                    store.vault.put("token-${saved.id}", "stale-fixture-token".toByteArray())
                    val reconnected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { app.hosts.reconnectService(saved.id) }
                    try { assertEquals(1, reconnected.get("health").getInt("protocol")) } finally { reconnected.close() }
                    assertNotEquals("stale-fixture-token", store.vault.get("token-${saved.id}")?.toString(Charsets.UTF_8))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { app.hosts.disconnectAll() }
                }
                SshConnection.connect(host, null, store.keyPair(id)).use { ssh ->
                    args.getString("tokenFixtureDir")?.let { directory ->
                        assertEquals("fixture-token", ssh.readServiceToken(directory))
                        for (missing in listOf("$directory/missing", "$directory/empty")) {
                            try { ssh.readServiceToken(missing); fail("Missing or empty token must fail") }
                            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("电脑服务令牌")) }
                        }
                    }
                }
            } else {
                SshConnection.connect(host, null, store.keyPair(id)).use { ssh ->
                    assertTrue(ssh.connected)
                    ssh.readServiceToken(host.serviceDirectory).let { token ->
                        val api = com.worldcopy.agentdeck.core.network.HostApi(ssh.forward(4317), token)
                        kotlinx.coroutines.runBlocking {
                            assertEquals(1, api.get("health").getInt("protocol"))
                            api.get("sessions").getJSONArray("data")
                            args.getString("sshProject")?.let { project ->
                                assertEquals("linux", api.get("health").getString("platform"))
                                val state = api.get("git/status", mapOf("cwd" to project))
                                assertEquals("$project/.git", state.getString("commonDir"))
                                assertTrue(api.get("git/graph", mapOf("cwd" to project)).getJSONArray("commits").length() > 0)
                                val session = api.post("sessions", org.json.JSONObject().put("cwd", project))
                                val thread = session.getString("id")
                                val run = api.post("runs", org.json.JSONObject()
                                    .put("clientRequestId", java.util.UUID.randomUUID().toString())
                                    .put("threadId", thread)
                                    .put("text", "Run uname -s using the command tool, then reply exactly AGENTDECK_UBUNTU_OK. Do not modify files."))
                                val runId = run.getString("id")
                                // A real phone tunnel closure must leave the computer task running.
                                api.close()
                                ssh.close()
                                kotlinx.coroutines.delay(12000)
                                SshConnection.connect(host, null, store.keyPair(id)).use { reconnected ->
                                    val resumed = com.worldcopy.agentdeck.core.network.HostApi(reconnected.forward(4317), token)
                                    try {
                                        kotlinx.coroutines.withTimeout(180000) {
                                            var completed = false
                                            while (!completed) {
                                                val runs = resumed.get("snapshot").getJSONArray("runs")
                                                val current = (0 until runs.length()).map { runs.getJSONObject(it) }.first { it.getString("id") == runId }
                                                val status = current.getString("state")
                                                check(status !in listOf("failed", "interrupted", "unknown")) { current.toString() }
                                                completed = status == "completed"
                                                if (!completed) kotlinx.coroutines.delay(2000)
                                            }
                                        }
                                        val turns = resumed.get("sessions/$thread").getJSONArray("turns")
                                        val items = (0 until turns.length()).flatMap { turnIndex ->
                                            val entries = turns.getJSONObject(turnIndex).getJSONArray("items")
                                            (0 until entries.length()).map { entries.getJSONObject(it) }
                                        }
                                        assertTrue(items.any { it.optString("type") == "agentMessage" && it.optString("text").contains("AGENTDECK_UBUNTU_OK") })
                                        assertTrue(items.any { it.optString("type") == "commandExecution" && it.optInt("exitCode", -1) == 0 && it.optString("aggregatedOutput").contains("Linux") })
                                    } finally { resumed.close() }
                                }
                            }
                        }
                        api.close()
                    }
                }
            }
        }
    }
}
