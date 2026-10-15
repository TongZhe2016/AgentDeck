package com.worldcopy.agentdeck.feature.remote

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.model.Host
import com.worldcopy.agentdeck.core.ssh.HostKeyConfirmation
import com.worldcopy.agentdeck.core.ssh.SshConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.OpenMode
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
data class RemoteEntry(val name: String, val path: String, val directory: Boolean, val size: Long, val modified: Long, val downloadable: Boolean = true)
data class TerminalChunk(val id: Int, val encoded: String, val consumed: CompletableDeferred<Unit> = CompletableDeferred())

class RemoteToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val hosts = (application as AgentDeckApplication).hosts
    private val store get() = hosts.store
    var host by mutableStateOf<Host?>(null); private set
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var confirmation by mutableStateOf<HostKeyConfirmation?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var path by mutableStateOf(""); private set
    var entries by mutableStateOf<List<RemoteEntry>>(emptyList()); private set
    var listing by mutableStateOf(false); private set
    var transfer by mutableStateOf<String?>(null); private set
    var installLog by mutableStateOf(""); private set
    var installed by mutableStateOf(false); private set
    var installing by mutableStateOf(false); private set
    var terminalStatus by mutableStateOf("尚未打开终端"); private set
    var connectionGeneration by mutableIntStateOf(0); private set
    private var ssh: SshConnection? = null
    private var shell: Session.Shell? = null
    private var terminalJob: Job? = null
    private var operation: Job? = null
    private var connectionJob: Job? = null
    private var listingJob: Job? = null
    private val input = Channel<String>(Channel.UNLIMITED)
    val output = Channel<TerminalChunk>(16)
    private val history = ArrayDeque<ByteArray>()
    private var historySize = 0
    private var terminalAttached = false
    private var pendingChunk: TerminalChunk? = null
    private var nextChunkId = 1

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (text in input) try { shell?.outputStream?.let { it.write(text.toByteArray()); it.flush() } }
            catch (e: Exception) { withContext(Dispatchers.Main) { error = e.message } }
        }
    }
    fun connect(target: Host) {
        if (connecting || connected) return
        host = store.hosts().find { it.id == target.id } ?: target
        connecting = true; error = null; confirmation = null
        connectionJob = viewModelScope.launch {
            var opening: SshConnection? = null
            try {
                withContext(Dispatchers.IO) {
                    val opened = SshConnection.connect(host!!, store.vault.get("password-${target.id}")?.toString(Charsets.UTF_8), host!!.identityId?.let(store::keyPair))
                    opening = opened
                    if (!currentCoroutineContext().isActive) { opened.close(); currentCoroutineContext().ensureActive() }
                    ssh = opened
                }
                connected = true
                browse(".")
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { opening?.close(); if (ssh === opening) ssh = null }
                throw e
            }
            catch (e: HostKeyConfirmation) { confirmation = e }
            catch (e: Exception) { error = e.message ?: "SSH 连接失败" }
            finally { connecting = false }
        }
    }
    fun trust() {
        val target = host ?: return; val key = confirmation ?: return
        hosts.trustForTools(target, key)
        confirmation = null; connect(target.copy(trustedHostKey = key.hostKey))
    }
    fun dismissConfirmation() { confirmation = null }
    fun dismissError() { error = null }
    fun dismissNotice() { notice = null }
    fun reconnect() { val target = host ?: return; close(); connect(target) }
    fun browse(requested: String) {
        val connection = ssh ?: return
        if (listing || transfer != null) return
        listing = true; error = null
        listingJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    connection.sftp().use { sftp ->
                        val home = sftp.canonicalize(".")
                        val target = when { requested == "~" -> home; requested.startsWith("~/") -> "$home/${requested.drop(2)}"; requested.startsWith("/") -> requested; else -> "${path.ifBlank { home }}/$requested" }
                        val actual = sftp.canonicalize(target)
                        actual to sftp.ls(actual).filter { it.name != "." && it.name != ".." }.map {
                            // Follow symlinks: browsing common linked folders should work like the SSH shell.
                            val attrs = if (it.attributes.type == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) {
                                try { sftp.statExistence(it.path) ?: it.attributes }
                                catch (_: net.schmizz.sshj.sftp.SFTPException) { it.attributes }
                            } else it.attributes
                            RemoteEntry(it.name, it.path, attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY, attrs.size, attrs.mtime,
                                attrs.type == net.schmizz.sshj.sftp.FileMode.Type.REGULAR)
                        }.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
                    }
                }
                path = result.first; entries = result.second
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "目录读取失败" }
            finally { listing = false }
        }
    }
    private suspend fun copy(input: InputStream, output: OutputStream, label: String, size: Long) {
        val bytes = ByteArray(64 * 1024); var done = 0L; var lastUpdate = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes); if (count < 0) break
            output.write(bytes, 0, count); done += count
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 150) { lastUpdate = now; withContext(Dispatchers.Main) { transfer = "$label · $done / $size 字节" } }
        }
    }
    fun download(entry: RemoteEntry, uri: Uri) {
        val connection = ssh ?: return
        if (transfer != null) return
        transfer = "下载 ${entry.name}"; error = null; notice = null
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    connection.sftp().use { sftp -> sftp.open(entry.path).use { remote ->
                        remote.RemoteFileInputStream().use { source ->
                            getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use { target ->
                                checkNotNull(target) { "无法写入所选文件" }; copy(source, target, "下载 ${entry.name}", entry.size)
                            }
                        }
                    } }
                }
                notice = "下载完成：${entry.name}"
            } catch (e: CancellationException) { error = "下载已取消，目标文件可能不完整"; throw e }
            catch (e: Exception) { error = "下载未完成：${e.message}（目标文件可能不完整）" }
            finally { transfer = null }
        }
    }
    fun cancelDownload() { if (!installing) operation?.cancel() }
    fun install() {
        val connection = ssh ?: return; val target = host ?: return
        if (transfer != null || installing) return
        installing = true; transfer = "上传电脑服务安装包"; installLog = ""; error = null; installed = false
        operation = viewModelScope.launch {
            var stage: String? = null
            try {
                withContext(Dispatchers.IO) {
                    connection.sftp().use { sftp ->
                        val home = sftp.canonicalize(".")
                        stage = "$home/.agentdeck-install-${UUID.randomUUID()}"
                        sftp.mkdir(stage)
                        val assets = getApplication<Application>().assets
                        assets.open("agentdeck-host.tgz").use { source ->
                            sftp.open("$stage/package.tgz", setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                                file.RemoteFileOutputStream().use { out -> copy(source, out, "上传安装包", assets.openFd("agentdeck-host.tgz").use { it.length }) }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { transfer = "正在电脑上安装…" }
                    val command = "cd ${shellQuote(stage!!)} && tar -xzf package.tgz && sh bootstrap.sh ${shellQuote(target.serviceDirectory)} ${target.servicePort}"
                    val exit = connection.execute(command) { chunk ->
                        runBlocking(Dispatchers.Main) { installLog = (installLog + chunk).takeLast(100_000) }
                    }
                    check(exit == 0) { "安装命令退出码 $exit，请查看安装日志" }
                }
                installed = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "安装未完成" }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    stage?.let { dir -> runCatching { connection.execute("rm -rf ${shellQuote(dir)}") {} } }
                }
                installing = false; transfer = null
            }
        }
    }
    fun send(text: String) { input.trySend(text) }
    fun resize(cols: Int, rows: Int) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { shell?.changeWindowDimensions(cols, rows, 0, 0) } }
    }
    fun attachTerminal(cols: Int, rows: Int): String {
        terminalAttached = true
        startTerminal(cols, rows)
        resize(cols, rows)
        return Base64.getEncoder().encodeToString(history.fold(java.io.ByteArrayOutputStream()) { all, part -> all.apply { write(part) } }.toByteArray())
    }
    fun detachTerminal() {
        terminalAttached = false; pendingChunk?.consumed?.complete(Unit)
        while (true) { val old = output.tryReceive().getOrNull() ?: break; old.consumed.complete(Unit) }
    }
    fun terminalConsumed(id: Int) { pendingChunk?.takeIf { it.id == id }?.consumed?.complete(Unit) }
    private fun startTerminal(cols: Int, rows: Int) {
        val connection = ssh ?: return
        if (terminalJob?.isActive == true) return
        terminalStatus = "正在打开终端…"
        terminalJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val active = connection.shell(cols, rows); shell = active
                    withContext(Dispatchers.Main) { terminalStatus = "SSH 终端" }
                    active.use {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = active.inputStream.read(buffer); if (count < 0) break
                            val bytes = buffer.copyOf(count)
                            val chunk = TerminalChunk(nextChunkId++, Base64.getEncoder().encodeToString(bytes))
                            val attached = withContext(Dispatchers.Main) {
                                history.addLast(bytes); historySize += count
                                while (historySize > 1024 * 1024) historySize -= history.removeFirst().size
                                if (terminalAttached) { pendingChunk = chunk; output.trySend(chunk) }
                                terminalAttached
                            }
                            if (attached) {
                                chunk.consumed.await(); pendingChunk = null
                            }
                        }
                    }
                }
                terminalStatus = "终端已退出"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { terminalStatus = "终端已断开"; error = e.message }
            finally { shell = null }
        }
    }
    fun restartTerminal() {
        if (terminalJob?.isActive == true) return
        history.clear(); historySize = 0; startTerminal(80, 24)
    }
    fun close() {
        connectionGeneration++
        connectionJob?.cancel(); listingJob?.cancel(); terminalJob?.cancel(); operation?.cancel(); detachTerminal()
        history.clear(); historySize = 0; path = ""; entries = emptyList()
        terminalStatus = "尚未打开终端"
        // Closing the SSH socket releases pending reads as well as all SFTP/PTY channels.
        val old = ssh; ssh = null; connected = false
        connecting = false
        CoroutineScope(Dispatchers.IO).launch { runCatching { old?.close() } }
    }
    override fun onCleared() { close(); input.close(); output.close() }
}
