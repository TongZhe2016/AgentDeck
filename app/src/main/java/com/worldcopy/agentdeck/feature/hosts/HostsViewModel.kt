package com.worldcopy.agentdeck.feature.hosts

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.SshIdentity
import com.worldcopy.agentdeck.core.ssh.HostKeyConfirmation
import com.worldcopy.agentdeck.core.ssh.SshConnection
import com.worldcopy.agentdeck.core.storage.HostStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.UserAuthException

class HostsViewModel(application: Application) : AndroidViewModel(application) {
    val store = HostStore(application)
    var hosts by mutableStateOf(store.hosts()); private set
    var identities by mutableStateOf(store.identities()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var statuses by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var connectingHosts by mutableStateOf<Set<String>>(emptySet()); private set
    private var confirmations by mutableStateOf<List<Pair<Host, HostKeyConfirmation>>>(emptyList())
    val confirmation get() = confirmations.firstOrNull()
    private val connections = mutableMapOf<String, SshConnection>()
    private val ports = mutableMapOf<String, Int>()

    private fun refresh() { hosts = store.hosts(); identities = store.identities() }
    fun dismissMessage() { message = null }
    fun dismissConfirmation() { confirmations = confirmations.drop(1) }
    private fun requestConfirmation(host: Host, key: HostKeyConfirmation) {
        confirmations = confirmations.filterNot { it.first.id == host.id } + (host to key)
    }

    fun work(action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { action(); refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }

    fun save(host: Host, password: String?, done: () -> Unit) = work {
        stopWorkspace(host.id)
        withContext(Dispatchers.IO) {
            closeConnection(host.id)
            store.saveHost(host, password)
            store.vault.delete("token-${host.id}")
        }
        statuses = statuses - host.id
        done()
    }

    fun cloneHost(source: Host, configuration: Host, password: String?, done: () -> Unit) = work {
        withContext(Dispatchers.IO) {
            val cloned = configuration.copy(id = java.util.UUID.randomUUID().toString(), trustedHostKey = null)
            val clonedPassword = if (cloned.authMethod == AuthMethod.PASSWORD) {
                password ?: source.takeIf { it.authMethod == AuthMethod.PASSWORD }
                    ?.let { store.vault.get("password-${it.id}")?.toString(Charsets.UTF_8) }
                    ?: error("请填写密码")
            } else null
            store.saveHost(cloned, clonedPassword)
        }
        done()
    }

    fun createKey(name: String) = work { withContext(Dispatchers.IO) { store.createIdentity(name) } }
    fun importKey(name: String, privateText: String, publicText: String, passphrase: String, result: (String?) -> Unit) = work {
        try {
            withContext(Dispatchers.IO) { store.importIdentity(name, privateText, publicText, passphrase) }
            result(null)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(e.message ?: "导入失败，请检查密钥文件") }
    }
    fun renameKey(id: String, name: String) = work { withContext(Dispatchers.IO) { store.renameIdentity(id, name) } }
    fun deleteKey(identity: SshIdentity) = work {
        val affected = hosts.filter { it.identityId == identity.id }.map { it.id }.toSet()
        affected.forEach(::stopWorkspace)
        withContext(Dispatchers.IO) {
            affected.forEach { closeConnection(it) }
            store.deleteIdentity(identity.id)
        }
        statuses = statuses - affected
    }
    fun deleteHost(id: String) = work {
        stopWorkspace(id)
        withContext(Dispatchers.IO) { closeConnection(id); store.deleteHost(id) }
        statuses = statuses - id
    }

    fun trustHost() {
        val (host, key) = confirmation ?: return
        if (busy) return
        dismissConfirmation()
        work {
            val trusted = host.copy(trustedHostKey = key.hostKey)
            withContext(Dispatchers.IO) { store.saveHost(trusted) }
            syncHosts(listOf(trusted))
        }
    }

    fun connect(host: Host) = work {
        connectingHosts = connectingHosts + host.id
        try { connectHost(host) }
        finally { connectingHosts = connectingHosts - host.id }
    }

    private suspend fun connectHost(host: Host) {
        statuses = statuses + (host.id to "正在连接…")
        try {
            withContext(Dispatchers.IO) {
                closeConnection(host.id)
                val ssh = SshConnection.connect(host,
                    store.vault.get("password-${host.id}")?.toString(Charsets.UTF_8),
                    host.identityId?.let { store.keyPair(it) })
                connections[host.id] = ssh
                ports[host.id] = ssh.forward(host.servicePort)
            }
            statuses = statuses + (host.id to "SSH 已连接")
        } catch (e: HostKeyConfirmation) {
            requestConfirmation(host, e)
            statuses = statuses + (host.id to "等待核对主机身份")
        } catch (e: Exception) {
            statuses = statuses + (host.id to "连接失败")
            if (e is UserAuthException) error("认证失败，请检查用户名、密码或电脑端的公钥授权")
            throw e
        }
    }

    fun syncProjects(host: Host? = null) = work {
        syncHosts(host?.let { listOf(it) } ?: hosts)
    }

    private suspend fun syncHosts(targets: List<Host>) {
        val app = getApplication<Application>() as com.worldcopy.agentdeck.AgentDeckApplication
        for (target in targets) {
            val workspace = app.workspace(target.id)
            connectingHosts = connectingHosts + target.id
            try {
                if (workspace.connection == "服务未连接") {
                    workspace.openOffline(target.id)
                    while (workspace.busy) delay(50)
                }
                if (workspace.busy) continue
                workspace.dismissError()
                if (workspace.online) { workspace.refreshSessions(); continue }
                if (connections[target.id]?.connected != true) connectHost(target)
                if (connections[target.id]?.connected != true) continue
                val token = serviceToken(target.id)
                workspace.connect(target.id, localPort(target.id), token) { reconnectService(target.id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspace.reportProjectSyncError(e.message ?: "同步失败") }
            finally { connectingHosts = connectingHosts - target.id }
        }
    }

    fun localPort(hostId: String): Int {
        check(connections[hostId]?.connected == true) { "请先连接此主机" }
        return ports[hostId] ?: error("SSH 隧道未建立")
    }

    suspend fun serviceToken(id: String): String = withContext(Dispatchers.IO) {
        val host = hosts.firstOrNull { it.id == id } ?: error("主机已删除")
        val ssh = connections[id]?.takeIf { it.connected } ?: error("请先连接此主机")
        // Read through the authenticated SSH account on every service connection, including reconnects.
        store.vault.delete("token-$id")
        ssh.readServiceToken(host.serviceDirectory).also { store.vault.put("token-$id", it.toByteArray()) }
    }

    suspend fun reconnectService(id: String): com.worldcopy.agentdeck.core.network.HostApi {
        val host = hosts.firstOrNull { it.id == id } ?: error("主机已删除")
        connectingHosts = connectingHosts + id
        try {
            val port = withContext(Dispatchers.IO) {
                closeConnection(id)
                val ssh = SshConnection.connect(host,
                    store.vault.get("password-$id")?.toString(Charsets.UTF_8),
                    host.identityId?.let { store.keyPair(it) })
                connections[id] = ssh
                ssh.forward(host.servicePort).also { ports[id] = it }
            }
            statuses = statuses + (id to "SSH 已连接")
            val token = serviceToken(id)
            return com.worldcopy.agentdeck.core.network.HostApi(port, token)
        } catch (e: HostKeyConfirmation) {
            requestConfirmation(host, e)
            statuses = statuses + (id to "等待核对主机身份")
            throw e
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { statuses = statuses + (id to "连接失败"); throw e }
        finally { connectingHosts = connectingHosts - id }
    }

    fun disconnectAll() = work {
        withContext(Dispatchers.IO) { connections.keys.toList().forEach { closeConnection(it) } }
        statuses = emptyMap()
    }

    private fun stopWorkspace(id: String) {
        (getApplication<Application>() as com.worldcopy.agentdeck.AgentDeckApplication).workspaces[id]?.disconnect()
    }
    private fun closeConnection(id: String) { ports.remove(id); connections.remove(id)?.close() }
    override fun onCleared() {
        connections.values.forEach { runCatching { it.close() } }
    }
}
