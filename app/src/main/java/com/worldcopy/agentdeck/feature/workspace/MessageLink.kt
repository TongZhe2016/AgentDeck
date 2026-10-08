package com.worldcopy.agentdeck.feature.workspace

import java.net.URI
import java.net.URLDecoder

internal sealed interface MessageLink {
    data class Web(val url: String) : MessageLink
    data class File(val path: String) : MessageLink
    data class Unsupported(val reason: String) : MessageLink
}

internal fun resolveMessageLink(destination: String): MessageLink {
    val value = destination.trim()
    if (value.startsWith("#")) return MessageLink.Unsupported("这是消息内的章节引用")
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(value)?.groupValues?.get(1)?.lowercase()
    if (scheme in listOf("http", "https", "mailto")) return MessageLink.Web(value)
    val path = when {
        scheme == "file" -> runCatching {
            val uri = URI(value.replace(" ", "%20"))
            require(uri.host.isNullOrEmpty() || uri.host == "localhost")
            uri.rawPath
        }.getOrNull() ?: return MessageLink.Unsupported("无法识别此文件链接")
        scheme == "sandbox" -> value.removePrefix("sandbox:")
        scheme != null && !value.matches(Regex(".+:\\d+(:\\d+)?$")) -> return MessageLink.Unsupported("暂不支持此链接类型")
        else -> value
    }.replace(Regex("#L\\d+(?:C\\d+)?(?:-L?\\d+(?:C\\d+)?)?$"), "")
        .replace(Regex(":\\d+(?::\\d+)?$"), "")
    val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
    return if (decoded.isBlank()) MessageLink.Unsupported("文件路径为空") else MessageLink.File(decoded)
}
