package com.worldcopy.agentdeck.feature.workspace

/** Details are retained for inspection; summaries never contain command output. */
data class ChatItem(
    val id: String,
    val role: String,
    val text: String,
    val turnId: String = "",
    val execution: Boolean = role.startsWith("命令") || role.startsWith("工具") || role == "文件变更" || role == "计划",
    val summary: String = "",
    val status: String = "",
)

data class ChatEntry(val key: String, val message: ChatItem? = null, val steps: List<ChatItem> = emptyList(), val turnId: String = "")

fun executionTimeline(messages: List<ChatItem>): List<ChatEntry> {
    val entries = mutableListOf<ChatEntry>()
    var fallbackTurn = "history"
    for (message in messages) {
        if (message.role == "你") fallbackTurn = message.id
        val turn = message.turnId.ifBlank { fallbackTurn }
        if (!message.execution) {
            entries += ChatEntry("message:${message.id}", message = message)
        } else {
            val previous = entries.lastOrNull()
            if (previous?.message == null && previous?.turnId == turn) {
                entries[entries.lastIndex] = previous.copy(steps = previous.steps + message)
            } else entries += ChatEntry("execution:${message.id}", steps = listOf(message), turnId = turn)
        }
    }
    return entries
}

fun oneLineSummary(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    ?.replace(Regex("\\s+"), " ")?.let { if (it.length > 80) it.take(79) + "…" else it } ?: "正在处理"

fun stepSummary(item: ChatItem): String = item.summary.ifBlank {
    when {
        item.role.startsWith("命令") -> "执行命令"
        item.role.startsWith("工具") -> "调用工具"
        item.role == "文件变更" -> "更新文件"
        item.role == "计划" -> "更新计划"
        else -> oneLineSummary(item.text)
    }
}
