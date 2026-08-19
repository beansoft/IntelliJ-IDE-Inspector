package github.intellij.support.ide.inspector.logviewer

/**
 * Parse idea.log content into entries.
 *
 * Header line format:
 *   2026-05-19 07:16:55,139 [ 301011]   WARN - com.foo.Bar - message
 *
 * Everything up to the next header line (or EOF) belongs to the entry body,
 * including stack traces / "Caused by:" chains / "... N more" markers.
 */
object IdeaLogParser {

    private val HEADER_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3})\s+\[\s*(\d+)]\s+(\w+)\s+-\s+(\S+)\s*-\s?(.*)$"""
    )

    private val EXCEPTION_HEAD_REGEX = Regex(
        """^(?:Caused by:\s+)?([A-Za-z_$][\w$.]*(?:\.[A-Z][\w$]*)+(?:Exception|Error|Throwable))(?::\s?(.*))?$"""
    )

    fun parse(text: String): List<IdeaLogEntry> {
        if (text.isEmpty()) return emptyList()

        val entries = ArrayList<IdeaLogEntry>()
        val lineStarts = ArrayList<Int>().apply { add(0) }
        for (i in text.indices) {
            if (text[i] == '\n' && i + 1 < text.length) lineStarts.add(i + 1)
        }
        lineStarts.add(text.length + 1)

        val headers = ArrayList<Triple<Int, IntRange, MatchResult>>()
        for (i in 0 until lineStarts.size - 1) {
            val start = lineStarts[i]
            val end = (lineStarts[i + 1] - 1).coerceAtMost(text.length)
            if (start >= end) continue
            val line = text.substring(start, end).trimEnd('\r')
            val m = HEADER_REGEX.matchEntire(line) ?: continue
            headers.add(Triple(i, start until end, m))
        }

        for ((idx, headerInfo) in headers.withIndex()) {
            val (_, headerRange, m) = headerInfo
            val nextStart = if (idx + 1 < headers.size) headers[idx + 1].second.first else text.length
            val entryStart = headerRange.first
            val bodyStart = (headerRange.last + 1).coerceAtMost(text.length)
            val body = if (bodyStart < nextStart) text.substring(bodyStart, nextStart).trimEnd('\r', '\n') else ""

            val exceptions = parseExceptions(body)
            entries.add(
                IdeaLogEntry(
                    index = idx,
                    timestamp = m.groupValues[1],
                    elapsedMs = m.groupValues[2].toLongOrNull() ?: 0L,
                    level = m.groupValues[3],
                    logger = m.groupValues[4],
                    message = m.groupValues[5],
                    body = body,
                    startOffset = entryStart,
                    endOffset = nextStart,
                    exceptions = exceptions,
                )
            )
        }
        return entries
    }

    private fun parseExceptions(body: String): List<IdeaLogException> {
        if (body.isBlank()) return emptyList()
        val result = ArrayList<IdeaLogException>()
        val lines = body.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd('\r')
            val trimmed = line.trimStart()
            val m = EXCEPTION_HEAD_REGEX.matchEntire(trimmed)
            if (m != null) {
                val causedBy = trimmed.startsWith("Caused by:")
                val stack = ArrayList<String>()
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trimEnd('\r')
                    val nextTrim = next.trimStart()
                    if (EXCEPTION_HEAD_REGEX.matchEntire(nextTrim) != null) break
                    if (nextTrim.startsWith("at ") || nextTrim.startsWith("... ") ||
                        nextTrim.startsWith("Suppressed:") || next.isBlank()) {
                        stack.add(next)
                        j++
                    } else {
                        break
                    }
                }
                result.add(
                    IdeaLogException(
                        type = m.groupValues[1],
                        message = m.groupValues.getOrNull(2).orEmpty(),
                        stackLines = stack,
                        causedBy = causedBy,
                    )
                )
                i = j
            } else {
                i++
            }
        }
        return result
    }
}
