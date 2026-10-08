package com.worldcopy.agentdeck.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EventCursorExpired : IOException("事件游标已失效")
class HostApiAuthException : IOException("电脑服务认证失败，请重新连接；若仍失败，请核对高级设置中的服务目录和端口")

class HostApi(private val port: Int, private val token: String) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private fun request(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = "http://127.0.0.1:$port/v1/$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return Request.Builder().url(url.build()).header("Authorization", "Bearer $token")
    }
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        execute(request(path, query).build())
    }
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        execute(request(path).post(body.toString().toRequestBody("application/json".toMediaType())).build())
    }
    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code == 401) throw HostApiAuthException()
                        val body = JSONObject(it.body?.string() ?: error("服务返回空响应"))
                        check(it.isSuccessful) { body.optString("error", "请求失败 (${it.code})") }
                        body
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun upload(id: String, threadId: String, file: java.io.File, mime: String): JSONObject = withContext(Dispatchers.IO) {
        require(file.length() <= 10 * 1024 * 1024) { "单个附件上限 10 MiB" }
        execute(request("attachments/$id", mapOf("threadId" to threadId)).post(file.readBytes().toRequestBody(mime.toMediaType())).build())
    }
    suspend fun downloadFile(path: String, cwd: String, output: java.io.OutputStream): Long = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request("files", mapOf("path" to path, "cwd" to cwd)).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (it.code == 401) throw HostApiAuthException()
                        if (it.code == 404) error("此电脑服务尚未支持文件下载，请更新电脑服务")
                        if (!it.isSuccessful) error(JSONObject(it.body?.string().orEmpty()).optString("error", "下载失败 (${it.code})"))
                        val body = it.body ?: error("文件响应为空")
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun removeAttachment(id: String) = withContext(Dispatchers.IO) { execute(request("attachments/$id").delete().build()) }
    suspend fun transcribe(id: String, threadId: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("attachmentId", id).put("threadId", threadId)
        client.newBuilder().readTimeout(135, TimeUnit.SECONDS).build().newCall(request("transcriptions")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            val result = JSONObject(it.body?.string() ?: error("服务返回空响应"))
            check(it.isSuccessful) { result.optString("error", "转写失败") }
            result.getString("text")
        }
    }
    fun events(after: Long, onConnected: () -> Unit = {}) = callbackFlow {
        val call = client.newCall(request("events", mapOf("after" to after.toString())).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code == 400) throw EventCursorExpired()
                        if (it.code == 401) throw HostApiAuthException()
                        if (!it.isSuccessful) error("事件流不可用 (${it.code})")
                        val source = it.body?.source() ?: error("事件流为空")
                        onConnected()
                        while (!call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ") && !trySend(JSONObject(line.removePrefix("data: "))).isSuccess) {
                                error("事件消费落后，将从游标恢复")
                            }
                        }
                    }
                    close(IOException("事件流已断开"))
                } catch (e: Exception) { close(e) }
            }
        })
        awaitClose { call.cancel() }
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
