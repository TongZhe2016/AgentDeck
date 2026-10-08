package com.worldcopy.agentdeck.core

import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.network.HostApi
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HostConcurrencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentDeckApplication
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            var done = false
            onMain { done = condition() }
            if (done) return
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Host sync did not finish" }
            Thread.sleep(20)
        }
    }
    private fun save(hosts: List<Host>) {
        hosts.forEach { host ->
            await { !app.hosts.busy }
            onMain { app.hosts.save(host, null) {} }
            await { !app.hosts.busy && app.hosts.hosts.any { it.id == host.id } }
        }
    }
    private fun cleanup(hosts: List<Host>) {
        await { !app.hosts.busy }
        hosts.forEach { host ->
            onMain { app.hosts.deleteHost(host.id) }
            await { !app.hosts.busy }
            File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(host.id + "-") }?.forEach { it.delete() }
        }
    }

    @Test fun startupStartsTenSshConnectionsAndReusesFreedSlots() {
        StalledSshServer().use { service ->
            val hosts = List(12) { Host(name = "Parallel SSH $it", address = "127.0.0.1", port = service.port,
                username = "fixture", authMethod = AuthMethod.PASSWORD) }
            try {
                save(hosts)
                onMain { app.hosts.syncProjects(refreshConnected = false) }
                repeat(10) { assertNotNull(service.accepted.poll(5, TimeUnit.SECONDS)) }
                assertNull("Only ten SSH handshakes may run at once", service.accepted.poll(300, TimeUnit.MILLISECONDS))
                onMain { assertEquals(10, app.hosts.connectingHosts.size) }
                service.sockets[0].close()
                assertNotNull("A failed host must free a slot immediately", service.accepted.poll(5, TimeUnit.SECONDS))
                service.sockets[1].close()
                assertNotNull("The final queued host should start before the others finish", service.accepted.poll(5, TimeUnit.SECONDS))
                service.release()
                await { !app.hosts.busy }
                onMain {
                    assertTrue(app.hosts.connectingHosts.isEmpty())
                    hosts.forEach { assertEquals("连接失败", app.hosts.statuses[it.id]) }
                }
            } finally { service.release(); cleanup(hosts) }
        }
    }

    @Test fun sessionLoadingHoldsSlotsUntilFinishedAndIsolatesFailure() {
        val blockLists = AtomicBoolean(false)
        val entered = LinkedBlockingQueue<Int>()
        val gates = List(12) { CountDownLatch(1) }
        val services = List(12) { index -> WorkspaceFixtureService(beforeList = {
            if (blockLists.get()) {
                entered.put(index)
                check(gates[index].await(10, TimeUnit.SECONDS)) { "Fixture request was not released" }
                if (index == 0) error("Simulated host list failure")
            }
        }) }
        val hosts = List(12) { Host(name = "Parallel sessions $it", address = "127.0.0.1", port = 1,
            username = "fixture", authMethod = AuthMethod.PASSWORD) }
        try {
            save(hosts)
            onMain { hosts.forEachIndexed { index, host ->
                val service = services[index]
                app.workspace(host.id).connect(host.id, service.port, "fixture") { HostApi(service.port, "fixture") }
            } }
            await { hosts.all { app.workspace(it.id).let { workspace -> workspace.online && !workspace.busy } } }
            blockLists.set(true)
            onMain { app.hosts.syncProjects() }
            val active = List(10) { entered.poll(5, TimeUnit.SECONDS).also { assertNotNull(it) }!! }
            assertNull("The limit includes in-flight session requests", entered.poll(300, TimeUnit.MILLISECONDS))
            onMain { assertEquals(10, app.hosts.connectingHosts.size) }
            gates[active[0]].countDown()
            assertNotNull(entered.poll(5, TimeUnit.SECONDS))
            gates[active[1]].countDown()
            assertNotNull(entered.poll(5, TimeUnit.SECONDS))
            gates.forEach { it.countDown() }
            await { !app.hosts.busy && hosts.all { !app.workspace(it.id).busy } }
            onMain {
                assertTrue(app.hosts.connectingHosts.isEmpty())
                assertNotNull(app.workspace(hosts[0].id).projectSyncError)
                hosts.drop(1).forEach { assertNull(app.workspace(it.id).projectSyncError) }
            }
            services.drop(1).forEach { assertEquals(2, it.lists.get()) }
        } finally {
            gates.forEach { it.countDown() }
            cleanup(hosts)
            services.forEach { it.close() }
        }
    }
}

private class StalledSshServer : Closeable {
    private val server = ServerSocket(0)
    val port = server.localPort
    val sockets = CopyOnWriteArrayList<Socket>()
    val accepted = LinkedBlockingQueue<Socket>()
    private val released = AtomicBoolean(false)
    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                accepted.put(socket)
                if (released.get()) socket.close()
            }
        }
    }
    fun release() { released.set(true); sockets.forEach { it.close() } }
    override fun close() { server.close(); release() }
}
