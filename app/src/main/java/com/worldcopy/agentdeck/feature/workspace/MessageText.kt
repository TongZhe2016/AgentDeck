package com.worldcopy.agentdeck.feature.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MessageText(text: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.split("```").forEachIndexed { index, block ->
            if (index % 2 == 1) {
                val language = block.substringBefore('\n')
                val code = block.substringAfter('\n', block).trimEnd()
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(language, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("复制代码") }
                        }
                        Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else block.lines().forEach { line ->
                val heading = line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ")
                val body = if (heading) line.trimStart('#', ' ') else line
                Text(inlineText(body), style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun inlineText(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`").findAll(text).forEach { match ->
        append(text.substring(start, match.range.first))
        if (match.value.startsWith("**")) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
        start = match.range.last + 1
    }
    append(text.substring(start))
}
