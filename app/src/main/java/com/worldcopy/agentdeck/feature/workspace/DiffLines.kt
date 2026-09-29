package com.worldcopy.agentdeck.feature.workspace

data class DiffLine(val text: String, val old: Int?, val new: Int?)
fun diffLines(text: String, preview: Boolean = false): List<DiffLine> {
    var old = 0; var new = 0; var inHunk = false
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
    return text.lines().mapIndexed { index, line ->
        if (preview) DiffLine(line, null, index + 1)
        else {
            val match = hunk.matchEntire(line)
            if (match != null) { old = match.groupValues[1].toInt(); new = match.groupValues[2].toInt(); inHunk = true; DiffLine(line, null, null) }
            else if (!inHunk || line.startsWith("\\")) DiffLine(line, null, null)
            else when (line.firstOrNull()) {
                '+' -> DiffLine(line, null, new++)
                '-' -> DiffLine(line, old++, null)
                ' ' -> DiffLine(line, old++, new++)
                else -> { inHunk = false; DiffLine(line, null, null) }
            }
        }
    }
}
