package com.worldcopy.agentdeck.core

import com.worldcopy.agentdeck.feature.workspace.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class MessageMarkdownTest {
    @Test fun fileReferencesResolveWithoutLineSuffixAndWebLinksStayExternal() {
        val cases = mapOf(
            "/tmp/report.pdf" to "/tmp/report.pdf",
            "/tmp/My%20Report.pdf:12" to "/tmp/My Report.pdf",
            "src/app.kt#L12-L18" to "src/app.kt",
            "app.kt:12:4" to "app.kt",
            "file:///tmp/%E6%8A%A5%E5%91%8A+2026.pdf#L3" to "/tmp/报告+2026.pdf",
            "file:///tmp/My Report.pdf" to "/tmp/My Report.pdf",
            "sandbox:/mnt/data/result.csv" to "/mnt/data/result.csv",
            "../results/data.csv" to "../results/data.csv",
        )
        cases.forEach { (url, path) -> assertEquals(MessageLink.File(path), resolveMessageLink(url)) }
        assertEquals(MessageLink.Web("https://example.com/report.pdf"), resolveMessageLink("https://example.com/report.pdf"))
        assertTrue(resolveMessageLink("javascript:alert(1)") is MessageLink.Unsupported)
    }

    @Test fun parsesTablesNestedFormattingReferencesAndUnfinishedStreamingCode() {
        val doc = messageParser.parse("""
            | Name | Result |
            | :--- | ---: |
            | **A** | ~~old~~ |

            3. First
               - Nested *emphasis*

            > Quote with [report][file]

            [file]: </tmp/My Report.pdf:12>

            ~~~kotlin
            val fence = "```"
        """.trimIndent())
        val blocks = doc.children()
        assertTrue(blocks[0] is TableBlock)
        assertEquals(3, (blocks[1] as OrderedList).markerStartNumber)
        val link = blocks[2].firstChild.children().filterIsInstance<Link>().single()
        assertEquals("/tmp/My Report.pdf:12", link.destination)
        assertEquals("val fence = \"```\"\n", blocks.filterIsInstance<FencedCodeBlock>().single().literal)
        fun descendants(node: Node): List<Node> = node.children().flatMap { listOf(it) + descendants(it) }
        assertTrue(descendants(doc).any { it is Strikethrough })
        assertTrue(descendants(doc).any { it is Emphasis })
        assertTrue(descendants(doc).any { it is BulletList })
    }
}
