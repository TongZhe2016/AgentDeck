package com.worldcopy.agentdeck.feature.media

import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.worldcopy.agentdeck.feature.workspace.WorkspaceViewModel
import java.io.File
import java.util.UUID
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun MediaInput(vm: WorkspaceViewModel) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var photoFile by remember { mutableStateOf<File?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::attachImage) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        photoFile?.let { file -> if (success) vm.attachImage(android.net.Uri.fromFile(file)) else file.delete() }
    }
    fun finishRecording() {
        val current = recorder ?: return
        recorder = null
        try { current.stop(); recordingFile?.let(vm::attachAudio) }
        catch (_: Exception) { recordingFile?.delete(); vm.reportError("录音未完成，请按下录音后再尝试") }
        finally { current.release(); recordingFile = null }
    }
    fun startRecording() {
        try {
            val file = File(context.noBackupFilesDir, "workspace/${UUID.randomUUID()}.m4a")
            file.parentFile?.mkdirs()
            val current = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = current; recordingFile = file
            current.setAudioSource(MediaRecorder.AudioSource.MIC)
            current.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            current.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            current.setAudioEncodingBitRate(64000)
            current.setAudioSamplingRate(16000)
            current.setOutputFile(file.absolutePath)
            current.setMaxDuration(120_000)
            current.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording() }
            current.prepare(); current.start()
        } catch (e: Exception) { recorder?.release(); recorder = null; recordingFile?.delete(); vm.reportError(e.message ?: "无法开始录音") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startRecording() else vm.reportError("允许麦克风权限后才能录音")
    }
    DisposableEffect(Unit) { onDispose { recorder?.run { runCatching { stop() }; release() }; recordingFile?.delete() } }
    FlowRow {
        TextButton(onClick = { pick.launch("image/*") }, enabled = !vm.busy && recorder == null) { Text("图片") }
        TextButton(onClick = {
            photoFile = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
            runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile!!)) }
                .onFailure { vm.reportError("此设备没有可用的相机应用") }
        }, enabled = !vm.busy && recorder == null) { Text("拍照") }
        TextButton(onClick = { if (recorder != null) finishRecording() else permission.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !vm.busy) {
            Text(if (recorder == null) "语音转文字" else "停止并转写（最长 2 分钟）")
        }
    }
}

@Composable
fun ImageThumbnail(path: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    }
    bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "图片附件预览", Modifier.size(64.dp)) }
}
