package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.feature.workspace.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionTimelineTest {
    @Test fun agentSpeechStaysVisibleAndSeparatesToolGroups() {
        val messages = listOf(
            ChatItem("speech", "Agent", "先读代码", "turn"),
            ChatItem("read", "命令", "cat README.md\nprivate output", "turn", summary = "读取 README.md"),
            ChatItem("search", "命令", "rg query", "turn", summary = "搜索代码"),
            ChatItem("update", "Agent", "已经找到原因", "turn"),
            ChatItem("edit", "文件变更", "patch", "turn"),
            ChatItem("answer", "Agent", "完成了", "turn"),
        )
        val entries = executionTimeline(messages)
        assertEquals(listOf("先读代码", "已经找到原因", "完成了"), entries.mapNotNull { it.message?.text })
        assertEquals(listOf(2, 1), entries.filter { it.message == null }.map { it.steps.size })
        assertEquals("搜索代码", stepSummary(entries[1].steps.last()))
        assertEquals(entries[1].key, executionTimeline(messages.take(2))[1].key)
    }
    @Test fun separateTurnsAndCachedCommandsKeepTheirBoundaries() {
        val entries = executionTimeline(listOf(ChatItem("u1", "你", "first"), ChatItem("c1", "命令 · completed", "output"),
            ChatItem("u2", "你", "second"), ChatItem("c2", "命令 · completed", "output")))
        assertEquals(listOf("u1", "u2"), entries.filter { it.message == null }.map { it.turnId })
        assertEquals("执行命令", stepSummary(entries[1].steps.single()))
    }
    @Test fun summaryIsShortAndContainsNoMultilineOutput() {
        assertEquals("读取文件", oneLineSummary("\n读取文件\nlarge output"))
        assertEquals(80, oneLineSummary("a".repeat(200)).length)
    }
}
