package com.worldcopy.agentdeck.core

import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.ssh.SshConnection
import com.worldcopy.agentdeck.feature.remote.RemoteToolsScreen
import com.worldcopy.agentdeck.feature.remote.RemoteToolsViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class RemoteToolsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = rule.activity.application as AgentDeckApplication
    private fun target() = app.hosts.store.hosts().first { it.name == args.getString("sshName") }
    private fun required() { assumeTrue("Requires explicitly configured disposable SSH fixture", args.getString("remoteRoot") != null) }
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun webView(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) } else null
    private fun screen(): String {
        rule.waitForIdle()
        val result = AtomicReference<String?>(null)
        rule.runOnUiThread {
            val terminal = webView(rule.activity.window.decorView)
            if (terminal == null) result.set("") else terminal.evaluateJavascript("JSON.stringify(Array.from({length:term.buffer.active.length},(_,i)=>term.buffer.active.getLine(i).translateToString()).join('\\n'))") { result.set(it) }
        }
        rule.waitUntil(5000) { result.get() != null }
        return result.get()!!
    }
    @Test fun nativePtySftpAndUploadedPackage() {
        required()
        val host = target(); val root = args.getString("remoteRoot")!!
        val store = app.hosts.store
        SshConnection.connect(host, null, store.keyPair(host.identityId!!)).use { ssh ->
            ssh.sftp().use { sftp ->
                val data = ByteArray(131072) { (it % 251).toByte() }
                val file = "$root/二进制 ' sample.bin"
                sftp.open(file, setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC)).use { remote -> remote.RemoteFileOutputStream().use { it.write(data) } }
                sftp.open(file).use { remote -> remote.RemoteFileInputStream().use { assertArrayEquals(data,it.readBytes()) } }
                sftp.open("$root/package.tgz", setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC)).use { remote ->
                    remote.RemoteFileOutputStream().use { out -> app.assets.open("agentdeck-host.tgz").use { it.copyTo(out) } }
                }
            }
            val output = StringBuilder()
            assertEquals(0, ssh.execute("cd ${quote(root)} && mkdir -p payload && tar -xzf package.tgz -C payload && test -f payload/src/main.js && sh -n payload/bootstrap.sh && echo PAYLOAD_OK") { output.append(it) })
            assertTrue(output.toString().contains("PAYLOAD_OK"))
            val stderr = StringBuilder()
            assertEquals(7, ssh.execute("echo STDERR_OK >&2; exit 7") { stderr.append(it) }); assertTrue(stderr.contains("STDERR_OK"))
            ssh.shell(80,24).use { shell ->
                shell.changeWindowDimensions(92,31,0,0)
                shell.outputStream.write("stty size; printf '终端_PTY_OK\\n'; exit\r".toByteArray()); shell.outputStream.flush()
                val text = shell.inputStream.bufferedReader().readText()
                assertTrue(text.contains("31 92")); assertTrue(text.contains("终端_PTY_OK"))
            }
        }
    }
    @Test fun terminalBrowserDownloadAndReattach() {
        required()
        val host = target(); val root = args.getString("remoteRoot")!!
        SshConnection.connect(host, null, app.hosts.store.keyPair(host.identityId!!)).use { ssh ->
            assertEquals(0, ssh.execute("cd ${quote(root)} && mkdir -p folder && ln -sfn folder linked-folder && ln -sfn missing broken-link") {})
            ssh.sftp().use { sftp ->
                sftp.open("$root/二进制 ' sample.bin", setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC)).use { remote ->
                    remote.RemoteFileOutputStream().use { it.write(ByteArray(131072) { index -> (index % 251).toByte() }) }
                }
            }
        }
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[RemoteToolsViewModel::class.java]
        val file = File(app.cacheDir,"sftp-download.bin")
        try {
            rule.setContent { com.worldcopy.agentdeck.ui.theme.AgentDeckTheme { androidx.compose.material3.Surface { RemoteToolsScreen(vm,host,{}, {}) } } }
            rule.waitUntil(20000) { vm.terminalStatus == "SSH 终端" }
            rule.runOnIdle { vm.send("printf 'REMOTE_%s\\n' UI_OK\r") }
            rule.waitUntil(10000) { screen().contains("REMOTE_UI_OK") }
            screenshot("terminal")
            rule.onNodeWithText("SFTP 文件").performClick()
            rule.runOnIdle { vm.browse(root) }
            rule.waitUntil(10000) { vm.path == root && !vm.listing }
            assertTrue(vm.entries.any { it.name == "linked-folder" && it.directory })
            assertTrue(vm.entries.any { it.name == "broken-link" && !it.downloadable })
            screenshot("sftp")
            rule.onNodeWithTag("remote-files").performScrollToNode(hasText("文件 · 二进制 ' sample.bin"))
            rule.onNodeWithText("文件 · 二进制 ' sample.bin").assertIsDisplayed()
            val entry = vm.entries.first { it.name == "二进制 ' sample.bin" }
            rule.runOnIdle { vm.download(entry,android.net.Uri.fromFile(file)) }
            rule.waitUntil(10000) { vm.transfer == null }
            assertNull(vm.error); assertArrayEquals(ByteArray(131072) { (it % 251).toByte() }, file.readBytes())
            rule.onNodeWithText("终端", useUnmergedTree = true).performClick()
            rule.waitUntil(10000) { screen().contains("REMOTE_UI_OK") }
            rule.runOnIdle { vm.send("printf 'REATTACHED_%s\\n' OK\r") }
            rule.waitUntil(10000) { screen().contains("REATTACHED_OK") }
            rule.onNodeWithText("安装服务").performClick()
            rule.onNodeWithText("上传并安装").performScrollTo()
            rule.onNodeWithText("上传并安装").assertIsDisplayed()
            rule.onNodeWithText("上传并安装").performClick()
            rule.onNodeWithText("开始安装").assertIsDisplayed()
            rule.onNodeWithText("取消", useUnmergedTree = true).performClick()
            rule.onNodeWithTag("install-log").assertDoesNotExist()
            rule.onNodeWithText("终端", useUnmergedTree = true).performClick()
            rule.runOnIdle { vm.reconnect() }
            rule.waitUntil(20000) { vm.connected && vm.terminalStatus == "SSH 终端" }
            rule.runOnIdle { vm.send("printf 'RECONNECTED_%s\\n' OK\r") }
            rule.waitUntil(10000) { screen().contains("RECONNECTED_OK") }
        } finally { rule.runOnIdle { vm.close() }; file.delete() }
    }
    @Test fun existingInstallationReportsFailureAndCleansUpload() {
        required(); assumeTrue(args.getString("remoteExisting") == "true")
        val host = target()
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[RemoteToolsViewModel::class.java]
        val store = app.hosts.store
        fun staging() = SshConnection.connect(host,null,store.keyPair(host.identityId!!)).use { ssh ->
            ssh.sftp().use { sftp -> sftp.ls(".").filter { it.name.startsWith(".agentdeck-install-") }.map { it.name }.toSet() }
        }
        val before = staging()
        try {
            rule.runOnUiThread { vm.connect(host) }
            rule.waitUntil(20000) { vm.connected }
            rule.runOnUiThread { vm.install() }
            rule.waitUntil(60000) { !vm.installing }
            assertFalse(vm.installed); assertTrue(vm.installLog,vm.installLog.contains("已存在 AgentDeck 安装")); assertNotNull(vm.error)
            assertEquals(before,staging())
        } finally { rule.runOnUiThread { vm.close() } }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val done = java.util.concurrent.CountDownLatch(1)
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            // Wait two display frames for the WebView compositor after xterm's parser callback.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.view.PixelCopy.request(rule.activity.window, bitmap, { result ->
                if (result == android.view.PixelCopy.SUCCESS) File(app.getExternalFilesDir(null), "remote-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle(); done.countDown()
            }, android.os.Handler(android.os.Looper.getMainLooper())) }, 100)
        }
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS))
    }
}
