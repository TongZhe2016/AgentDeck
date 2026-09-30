package com.worldcopy.agentdeck.core.ssh

import com.worldcopy.agentdeck.core.model.AuthMethod
import com.worldcopy.agentdeck.core.model.Host
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HostKeyConfirmation(val hostKey: String, val fingerprint: String, val changed: Boolean) :
    Exception(if (changed) "主机身份已变化，请与电脑核对" else "首次连接，请确认主机身份")

class SshConnection private constructor(private val client: SSHClient) : Closeable {
    private var listener: ServerSocket? = null
    val connected: Boolean get() = client.isConnected && client.isAuthenticated

    fun forward(servicePort: Int): Int {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        listener = socket
        val forwarder = client.newLocalPortForwarder(Parameters("127.0.0.1", socket.localPort, "127.0.0.1", servicePort), socket)
        thread(name = "agentdeck-tunnel", isDaemon = true) {
            try { forwarder.listen() } catch (_: java.io.IOException) { socket.close() }
        }
        return socket.localPort
    }

    fun readServiceToken(directory: String = "~/.agentdeck"): String {
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        val path = directory.ifBlank { "~/.agentdeck" }.trimEnd('/') + "/token"
        val argument = when {
            path.startsWith("~/") -> "\"\$HOME\"/" + quote(path.removePrefix("~/"))
            path.startsWith("/") -> quote(path)
            else -> "\"\$HOME\"/" + quote(path)
        }
        return client.startSession().use { session ->
            val command = session.exec("cat $argument")
            val token = command.inputStream.bufferedReader().use { it.readText().trim() }
            command.join(15, TimeUnit.SECONDS)
            check(command.exitStatus == 0) { "无法读取电脑服务令牌。请先在此 SSH 账号下启动 AgentDeck；自定义安装请核对高级设置中的服务目录。" }
            check(token.isNotEmpty()) { "电脑服务令牌文件为空，请检查电脑上的 AgentDeck 服务。" }
            token
        }
    }

    override fun close() {
        listener?.close()
        client.close()
    }

    companion object {
        fun connect(host: Host, password: String?, keyPair: KeyPair?): SshConnection {
            IdentityCrypto.initialize()
            val ssh = SSHClient()
            ssh.connectTimeout = 10_000
            ssh.timeout = 15_000
            var confirmation: HostKeyConfirmation? = null
            ssh.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val bytes = Buffer.PlainBuffer().putPublicKey(key).compactData
                    val encoded = "${KeyType.fromKey(key)} ${Base64.getEncoder().encodeToString(bytes)}"
                    if (encoded == host.trustedHostKey) return true
                    // SSH's standard fingerprint is used for the user's trust decision.
                    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
                    confirmation = HostKeyConfirmation(encoded, fingerprint, host.trustedHostKey != null)
                    return false
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                    host.trustedHostKey?.substringBefore(' ')?.let { listOf(it) } ?: emptyList()
            })
            try {
                ssh.connect(host.address, host.port)
                when (host.authMethod) {
                    AuthMethod.PASSWORD -> ssh.authPassword(host.username, password ?: error("请填写密码"))
                    AuthMethod.KEY -> ssh.authPublickey(host.username, KeyPairWrapper(keyPair ?: error("请选择登录密钥")))
                }
                ssh.connection.keepAlive.keepAliveInterval = 20
                return SshConnection(ssh)
            } catch (e: Exception) {
                runCatching { ssh.close() }
                throw confirmation ?: e
            }
        }
    }
}
