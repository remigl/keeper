package io.github.remigl.keeper.log

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text format of one log entry (pure Kotlin, no Android dependency):
 *
 * ```
 * [2026-11-17 14:32:08 +01:00]
 * Package: com.example.app
 * Title: Nouveau message
 * Text: Bonjour, tu as reçu un nouveau message.
 *   (continuation lines of a multi-line field are indented by two spaces)
 * ----------------------------------------
 * ```
 *
 * Parsing rule for other programs: a line starting at column 0 is always structural
 * (timestamp, `Key: `, separator); field content never starts at column 0 after its first line.
 * The UTC offset makes timestamps unambiguous across time zone and DST changes.
 */
object LogFormat {
    const val SEPARATOR = "----------------------------------------"

    /** Per-field cap: a pathological notification cannot bloat the file. */
    const val MAX_FIELD_CHARS = 10_000
    private const val MAX_PACKAGE_CHARS = 255
    private const val CONTINUATION = "  "

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)

    fun entry(time: ZonedDateTime, packageName: String, title: CharSequence?, text: CharSequence?): String {
        val sb = StringBuilder(128)
        sb.append('[').append(TIMESTAMP.format(time)).append("]\n")
        sb.append("Package: ").append(clean(packageName, MAX_PACKAGE_CHARS)).append('\n')
        sb.append("Title: ").append(clean(title)).append('\n')
        sb.append("Text: ").append(clean(text)).append('\n')
        sb.append(SEPARATOR).append('\n')
        return sb.toString()
    }

    /**
     * Normalises line breaks, drops control characters (except tab), trims trailing blanks,
     * indents continuation lines and caps the length without splitting a surrogate pair.
     */
    fun clean(value: CharSequence?, max: Int = MAX_FIELD_CHARS): String {
        if (value == null) return ""
        var s = value.toString()
        var dropped = 0
        if (s.length > max) {
            var cut = max
            if (Character.isHighSurrogate(s[cut - 1])) cut--
            dropped = s.length - cut
            s = s.substring(0, cut)
        }

        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < s.length && s[i + 1] == '\n') i++
                }
                c == '\n' || c == '\u0085' || c == ' ' || c == ' ' -> sb.append('\n')
                c == '\t' -> sb.append('\t')
                Character.isISOControl(c) -> Unit
                else -> sb.append(c)
            }
            i++
        }
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        sb.setLength(end)

        var out = sb.toString().replace("\n", "\n$CONTINUATION")
        if (dropped > 0) out += " […$dropped caractères tronqués]"
        return out
    }
}
