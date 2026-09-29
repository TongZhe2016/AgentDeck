package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.feature.workspace.diffLines
import com.worldcopy.agentdeck.feature.workspace.graphRows
import com.worldcopy.agentdeck.feature.workspace.commitRelativeTime
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GitRenderingTest {
    @Test fun unifiedLineNumbersFollowBothSidesOfHunks() {
        val rows = diffLines("@@ -10,2 +10,3 @@\n context\n-old\n+new\n+extra\n\\ No newline at end of file")
        assertEquals(10, rows[1].old); assertEquals(10, rows[1].new)
        assertEquals(11, rows[2].old); assertNull(rows[2].new)
        assertNull(rows[3].old); assertEquals(11, rows[3].new)
        assertEquals(12, rows[4].new); assertNull(rows[5].new)
    }
    @Test fun relativeCommitTimeUsesElapsedTimeAcrossOffsets() {
        val now = Instant.parse("2026-09-30T04:00:00Z")
        assertEquals("刚刚", commitRelativeTime("2026-09-30T11:59:40+08:00", now))
        assertEquals("1分钟前", commitRelativeTime("2026-09-30T11:59:00+08:00", now))
        assertEquals("2小时前", commitRelativeTime("2026-09-30T10:00:00+08:00", now))
        assertEquals("1天前", commitRelativeTime("2026-09-29T12:00:00+08:00", now))
        assertEquals("1个月前", commitRelativeTime("2026-08-30T12:00:00+08:00", now))
        assertEquals("1年前", commitRelativeTime("2025-09-30T12:00:00+08:00", now))
    }
    @Test fun mergeBranchesConvergeOnTheSameParentLane() {
        val rows = graphRows(listOf("M" to listOf("A", "B"), "A" to listOf("R"), "B" to listOf("R"), "R" to emptyList()))
        assertEquals(listOf("A", "B"), rows[0].after)
        assertEquals(listOf("R", "B"), rows[1].after)
        assertEquals(1, rows[2].node)
        assertEquals(listOf("R"), rows[2].after)
        assertTrue(rows[3].after.isEmpty())
    }
}
