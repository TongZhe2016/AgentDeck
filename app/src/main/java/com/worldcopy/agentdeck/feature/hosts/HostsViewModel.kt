package com.worldcopy.agentdeck.feature.hosts

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.model.SshIdentity
import com.worldcopy.agentdeck.core.ssh.HostKeyConfirmation
import com.worldcopy.agentdeck.core.ssh.SshConnection
import com.worldcopy.agentdeck.core.storage.HostStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    var confirmation by mutableStateOf<Pair<Host, HostKeyConfirmation>?>(null); private set
    private val connections = mutableMapOf<String, SshConnection>()
    private val ports = mutableMapOf<String, Int>()

    private fun refresh() { hosts = store.hosts(); identities = store.identities() }
    fun dismissMessage() { message = null }
    fun dismissConfirmation() { confirmation = null }

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

    fun save(host: Host, password: String?, token: String?, done: () -> Unit) = work {
        stopWorkspace(host.id)
        withContext(Dispatchers.IO) {
            closeConnection(host.id)
            store.saveHost(host, password, token)
        }
        statuses = statuses - host.id
        done()
    }

    fun createKey(name: String) = work { withContext(Dispatchers.IO) { store.createIdentity(name) } }
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
        confirmation = null
        work {
            val trusted = host.copy(trustedHostKey = key.hostKey)
            withContext(Dispatchers.IO) { store.saveHost(trusted) }
            connectHost(trusted)
        }
    }

    fun connect(host: Host) = work { connectHost(host) }

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
            confirmation = host to e
            statuses = statuses + (host.id to "等待核对主机身份")
        } catch (e: Exception) {
            statuses = statuses + (host.id to "连接失败")
            if (e is UserAuthException) error("认证失败，请检查用户名、密码或电脑端的公钥授权")
            throw e
        }
    }

    fun localPort(hostId: String): Int {
        check(connections[hostId]?.connected == true) { "请先连接此主机" }
        return ports[hostId] ?: error("SSH 隧道未建立")
    }

    suspend fun reconnectService(id: String): com.worldcopy.agentdeck.core.network.HostApi {
        val host = hosts.firstOrNull { it.id == id } ?: error("主机已删除")
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
            val token = withContext(Dispatchers.IO) { store.vault.get("token-$id")?.toString(Charsets.UTF_8) }
                ?: error("请填写电脑服务令牌")
            return com.worldcopy.agentdeck.core.network.HostApi(port, token)
        } catch (e: HostKeyConfirmation) {
            confirmation = host to e
            statuses = statuses + (id to "等待核对主机身份")
            throw e
        }
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
