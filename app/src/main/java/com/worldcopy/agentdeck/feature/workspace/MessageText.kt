package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser

internal val messageParser: Parser = Parser.builder().extensions(listOf(
    TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create(), TaskListItemsExtension.create(),
)).build()

internal fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()

@Composable
fun MessageText(text: String, onLink: (String) -> Unit = {}) {
    val document = remember(text) { messageParser.parse(text) }
    MarkdownBlocks(document.children(), onLink)
}

@Composable
private fun MarkdownBlocks(nodes: List<Node>, onLink: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEach { node ->
            when (node) {
                is Paragraph -> Text(markdownInline(node, onLink), style = MaterialTheme.typography.bodyLarge)
                is Heading -> Text(markdownInline(node, onLink), style = when (node.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }, fontWeight = FontWeight.Bold)
                is FencedCodeBlock -> CodeBlock(node.literal, node.info.orEmpty().substringBefore(' '))
                is IndentedCodeBlock -> CodeBlock(node.literal, "")
                is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Box(Modifier.weight(1f).padding(start = 12.dp)) { MarkdownBlocks(node.children(), onLink) }
                }
                is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children().forEachIndexed { index, item ->
                        val marker = item.firstChild as? TaskListItemMarker
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when {
                                marker != null -> if (marker.isChecked) "☑" else "☐"
                                node is OrderedList -> "${(node.markerStartNumber ?: 1) + index}."
                                else -> "•"
                            }, style = MaterialTheme.typography.bodyLarge)
                            Box(Modifier.weight(1f)) { MarkdownBlocks(item.children().filterNot { it is TaskListItemMarker }, onLink) }
                        }
                    }
                }
                is TableBlock -> MarkdownTable(node, onLink)
                is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyMedium)
                else -> if (node.firstChild != null) MarkdownBlocks(node.children(), onLink)
            }
        }
    }
}

@Composable
private fun CodeBlock(source: String, language: String) {
    val clipboard = LocalClipboardManager.current
    val code = source.removeSuffix("\n")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(language, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 16.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("复制代码") }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("markdown-code")) {
                Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, softWrap = false)
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock, onLink: (String) -> Unit) {
    val rows = table.children().flatMap { it.children() }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("markdown-table")) {
        rows.forEach { row ->
            val header = row.parent is TableHead
            Row(Modifier.height(IntrinsicSize.Min).background(if (header) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface)) {
                row.children().filterIsInstance<TableCell>().forEach { cell ->
                    Box(Modifier.width(160.dp).fillMaxHeight().padding(10.dp)) {
                        Text(markdownInline(cell, onLink), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.Right
                                else -> TextAlign.Start
                            })
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun markdownInline(node: Node, onLink: (String) -> Unit): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        fun appendNode(child: Node) {
            fun children() = child.children().forEach { appendNode(it) }
            when (child) {
                is MarkdownText -> append(child.literal)
                is SoftLineBreak -> append("\n")
                is HardLineBreak -> append("\n")
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHighest)) { append(child.literal) }
                is Link -> withLink(LinkAnnotation.Clickable(child.destination,
                    TextLinkStyles(style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) { onLink(child.destination) }) { children() }
                is Image -> withLink(LinkAnnotation.Clickable(child.destination,
                    TextLinkStyles(style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) { onLink(child.destination) }) {
                    append("图片："); children()
                }
                is HtmlInline -> if (child.literal.matches(Regex("(?i)<br\\s*/?>"))) append("\n") else append(child.literal)
                else -> children()
            }
        }
        node.children().forEach { appendNode(it) }
    }
}
