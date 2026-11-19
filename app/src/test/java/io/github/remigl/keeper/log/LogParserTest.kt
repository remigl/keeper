package io.github.remigl.keeper.log

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogParserTest {
    private val t = ZonedDateTime.of(2026, 11, 19, 14, 32, 8, 0, ZoneId.of("Europe/Paris"))

    @Test fun roundTripWithMultilineEmptyAndUnicode() {
        val raw = LogFormat.entry(t, "com.a", "Titre 😀", "L1\nL2\n\nL4") +
            LogFormat.entry(t.plusMinutes(1), "com.b", null, null) +
            LogFormat.entry(t.plusMinutes(2), "com.c", "x", "y\n${LogFormat.SEPARATOR}\n[2026-01-01 00:00:00 +00:00]")
        val e = LogParser.parse(raw.lineSequence())
        assertEquals(3, e.size)
        assertEquals(LogEntry(t.toOffsetDateTime(), "com.a", "Titre 😀", "L1\nL2\n\nL4"), e[0])
        assertEquals("", e[1].title)
        assertEquals("", e[1].text)
        assertEquals("y\n${LogFormat.SEPARATOR}\n[2026-01-01 00:00:00 +00:00]", e[2].text)
        assertEquals("+01:00", e[0].time.offset.id)
    }

    @Test fun incompleteLastEntryAndGarbageAreTolerated() {
        val raw = "garbage\n[not a date]\nPackage: z\n" +
            LogFormat.entry(t, "com.a", "t", "x") +
            "[2026-11-19 15:00:00 +01:00]\nPackage: com.late\nTitle: partial\n"
        val e = LogParser.parse(raw.lineSequence())
        assertEquals(listOf("com.a", "com.late"), e.map { it.packageName })
        assertEquals("partial", e[1].title)
    }
}
