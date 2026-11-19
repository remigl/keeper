package io.github.remigl.keeper.log

import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One parsed log entry. [time] keeps the UTC offset that was in force when it was logged. */
data class LogEntry(
    val time: OffsetDateTime,
    val packageName: String,
    val title: String,
    val text: String,
)

/**
 * Reads files written by [LogFormat]. Tolerant: an incomplete last entry (no separator yet)
 * is still returned, unknown lines are ignored, an unparsable timestamp skips that entry only.
 */
object LogParser {
    private val HEADER = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{2}:\d{2})]$""")
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)

    fun parse(file: File): List<LogEntry> =
        file.bufferedReader(Charsets.UTF_8).useLines { parse(it) }

    fun parse(lines: Sequence<String>): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        var time: OffsetDateTime? = null
        var pkg = ""
        val title = StringBuilder()
        val text = StringBuilder()
        var field: StringBuilder? = null

        fun flush() {
            val t = time
            if (t != null && pkg.isNotEmpty()) out.add(LogEntry(t, pkg, title.toString(), text.toString()))
            time = null; pkg = ""; title.setLength(0); text.setLength(0); field = null
        }

        for (line in lines) {
            when {
                line == LogFormat.SEPARATOR -> flush()
                line.startsWith("  ") -> field?.append('\n')?.append(line, 2, line.length)
                line.startsWith("[") -> {
                    flush()
                    val m = HEADER.matchEntire(line) ?: continue
                    time = try { OffsetDateTime.parse(m.groupValues[1], TIMESTAMP) } catch (e: DateTimeParseException) { null }
                }
                time == null -> Unit
                line.startsWith("Package: ") -> { pkg = line.substring(9); field = null }
                line.startsWith("Title: ") -> { title.append(line, 7, line.length); field = title }
                line.startsWith("Text: ") -> { text.append(line, 6, line.length); field = text }
                else -> field = null
            }
        }
        flush()
        return out
    }
}
