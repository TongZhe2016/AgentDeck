package com.worldcopy.agentdeck.core

import android.net.Uri
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.material3.Surface
import com.worldcopy.agentdeck.feature.workspace.WorkspaceScreen
import com.worldcopy.agentdeck.ui.theme.AgentDeckTheme
import org.json.JSONObject
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.worldcopy.agentdeck.AgentDeckApplication
import com.worldcopy.agentdeck.core.network.HostApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class FileDownloadTest {
    @get:Rule val compose = createComposeRule()
    @Test fun clickingCitationLaunchesSavePickerAndDownloadsToReturnedDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AgentDeckApplication
        val hostId = "citation-${UUID.randomUUID()}"
        val vm = app.workspace(hostId)
        val destination = File(app.cacheDir, "$hostId.bin")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", destination)
        var pickerName: String? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                pickerName = intent.getStringExtra(Intent.EXTRA_TITLE)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        val item = JSONObject().put("id", "citation").put("type", "agentMessage")
            .put("text", "[下载报告](<reports/My Report.bin:12>)")
        WorkspaceFixtureService(listOf(item)).use { service ->
            try {
                compose.setContent { AgentDeckTheme { Surface { WorkspaceScreen(vm) {} } } }
                compose.runOnIdle { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnIdle { vm.openSession(JSONObject().put("id", "thread").put("cwd", "/fixture")) }
                compose.waitUntil(10_000) { !vm.busy && vm.messages.isNotEmpty() }
                compose.onNodeWithText("下载报告").performClick()
                compose.onNodeWithText("下载文件").performClick()
                compose.waitUntil(10_000) { !vm.downloading && vm.downloadedName != null }
                compose.onNodeWithText("文件已保存").assertIsDisplayed()
                assertEquals("My Report.bin", pickerName)
                assertEquals("reports/My Report.bin", service.requestedFile!!.getQueryParameter("path"))
                assertEquals("/fixture", service.requestedFile!!.getQueryParameter("cwd"))
                assertArrayEquals(service.fileBytes, destination.readBytes())
            } finally {
                instrumentation.removeMonitor(monitor)
                compose.runOnIdle { vm.disconnect(); app.workspaces.remove(hostId) }
                destination.delete()
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }

    @Test fun streamsToChosenLocationAndReportsMissingFiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentDeckApplication
        val hostId = "download-${UUID.randomUUID()}"
        val vm = app.workspace(hostId)
        val destination = File(app.cacheDir, "$hostId.bin")
        WorkspaceFixtureService().use { service ->
            try {
                compose.runOnIdle { vm.connect(hostId, service.port, "fixture") { HostApi(service.port, "fixture") } }
                compose.waitUntil(10_000) { vm.online && !vm.busy }
                compose.runOnIdle { vm.downloadFile("reports/报告 + data.bin", "/project", Uri.fromFile(destination)) }
                compose.waitUntil(10_000) { !vm.downloading && vm.downloadedName != null }
                assertArrayEquals(service.fileBytes, destination.readBytes())
                assertEquals("reports/报告 + data.bin", service.requestedFile!!.getQueryParameter("path"))
                assertEquals("/project", service.requestedFile!!.getQueryParameter("cwd"))
                val client = HostApi(service.port, "fixture")
                try {
                    val output = ByteArrayOutputStream()
                    val failure = runCatching { runBlocking { client.downloadFile("missing", "/project", output) } }.exceptionOrNull()
                    assertTrue(failure!!.message!!.contains("文件不存在"))
                    assertEquals(0, output.size())
                } finally { client.close() }
            } finally {
                compose.runOnIdle { vm.disconnect(); app.workspaces.remove(hostId) }
                destination.delete()
                File(app.noBackupFilesDir, "workspace").listFiles()?.filter { it.name.startsWith(hostId) }?.forEach { it.delete() }
            }
        }
    }
}
