package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.feature.workspace.diffLines
import com.worldcopy.agentdeck.feature.workspace.graphRows
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
    @Test fun mergeBranchesConvergeOnTheSameParentLane() {
        val rows = graphRows(listOf("M" to listOf("A", "B"), "A" to listOf("R"), "B" to listOf("R"), "R" to emptyList()))
        assertEquals(listOf("A", "B"), rows[0].after)
        assertEquals(listOf("R", "B"), rows[1].after)
        assertEquals(1, rows[2].node)
        assertEquals(listOf("R"), rows[2].after)
        assertTrue(rows[3].after.isEmpty())
    }
}
