package io.github.remigl.keeper.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LogStoreTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val root: File = Files.createTempDirectory("keeper").toFile()
    private val dir = File(root, "NotificationLogger")
    private var clock = at(2026, 11, 16, 10, 0)
    private val store = LogStore(dir) { clock }

    @After fun cleanup() { root.deleteRecursively() }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = paris) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    private fun names() = store.logFiles().map { it.name }
    private fun entry(text: String = "x") = LogFormat.entry(clock, "com.example.app", "Titre", text)
    private fun count(name: String) = store.countEntries(File(dir, name))

    @Test fun firstLaunchCreatesFolderAndTodayFile() {
        store.prepare()
        assertTrue(dir.isDirectory)
        assertEquals(listOf("2026-11-16.log"), names())
    }

    @Test fun dailyRotationKeepsTwoFiles() {
        store.append(entry()); store.append(entry())
        assertEquals(2, count("2026-11-16.log"))

        clock = at(2026, 11, 17, 0, 0)
        store.append(entry("after midnight"))
        assertEquals(listOf("2026-11-16.log", "2026-11-17.log"), names())
        assertEquals(1, count("2026-11-17.log"))

        clock = at(2026, 11, 18, 9, 0)
        store.append(entry())
        assertEquals(listOf("2026-11-17.log", "2026-11-18.log"), names())
    }

    @Test fun phoneOffSeveralDaysCreatesNoHistory() {
        store.append(entry())
        clock = at(2026, 11, 17, 9, 0); store.append(entry())
        clock = at(2026, 11, 22, 8, 0); store.append(entry())
        assertEquals(listOf("2026-11-17.log", "2026-11-22.log"), names())
    }

    @Test fun strayOldLogsRemovedOtherFilesUntouched() {
        store.prepare()
        File(dir, "2026-01-01.log").writeText("x")
        File(dir, "2026-11-15.log").writeText("x")
        File(dir, "notes.txt").writeText("keep")
        clock = at(2026, 11, 17, 8, 0)
        store.append(entry())
        assertEquals(listOf("2026-11-16.log", "2026-11-17.log"), names())
        assertTrue(File(dir, "notes.txt").exists())
    }

    @Test fun timeZoneChangeUsesLocalDate() {
        store.append(entry())
        clock = at(2026, 11, 16, 23, 30).withZoneSameInstant(ZoneId.of("Asia/Tokyo")) // already the 17th there
        store.append(entry())
        assertEquals(listOf("2026-11-16.log", "2026-11-17.log"), names())
    }

    @Test fun clockMovedBackwardsNeverExceedsTwoFiles() {
        store.append(entry())
        clock = at(2026, 11, 17, 9, 0); store.append(entry())
        clock = at(2026, 11, 10, 9, 0); store.append(entry())
        assertEquals(listOf("2026-11-10.log", "2026-11-17.log"), names())
        clock = at(2026, 11, 18, 9, 0); store.append(entry())
        assertEquals(listOf("2026-11-17.log", "2026-11-18.log"), names())
    }

    @Test fun dstOverlapIsUnambiguous() {
        val first = at(2026, 10, 25, 2, 30).withEarlierOffsetAtOverlap()
        val second = first.withLaterOffsetAtOverlap()
        assertEquals("[2026-10-25 02:30:00 +02:00]", LogFormat.entry(first, "p", "", "").lines()[0])
        assertEquals("[2026-10-25 02:30:00 +01:00]", LogFormat.entry(second, "p", "", "").lines()[0])
    }

    @Test fun formatHandlesEmptyUnicodeMultilineAndControlChars() {
        val e = LogFormat.entry(clock, "com.x", null, "Ligne 1\r\nLigne 2\u0007\rLigne 3 😀 日本語  \n\n")
        assertEquals(
            "[2026-11-16 10:00:00 +01:00]\nPackage: com.x\nTitle: \nText: Ligne 1\n  Ligne 2\n  Ligne 3 😀 日本語\n" +
                LogFormat.SEPARATOR + "\n",
            e,
        )
    }

    @Test fun contentCannotForgeStructure() {
        val e = LogFormat.entry(clock, "p", "t", "x\n${LogFormat.SEPARATOR}\n[2026-01-01 00:00:00 +00:00]")
        assertEquals(1, e.lines().count { it == LogFormat.SEPARATOR })
        assertEquals(1, e.lines().count { it.startsWith("[") })
    }

    @Test fun longTextTruncatedWithoutBreakingSurrogates() {
        val cleaned = LogFormat.clean("😀".repeat(LogFormat.MAX_FIELD_CHARS))
        val kept = cleaned.substringBefore(" […")
        assertTrue(cleaned.contains("tronqués"))
        assertFalse(Character.isHighSurrogate(kept.last()))
        assertTrue(kept.length <= LogFormat.MAX_FIELD_CHARS)
    }

    @Test fun concurrentEntriesAreSerializedAndIntact() {
        val n = 500
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(n)
        repeat(n) { i ->
            pool.execute { store.appendAsync(LogFormat.entry(clock, "p$i", "T$i", "x".repeat(i % 300))); latch.countDown() }
        }
        latch.await(); pool.shutdown()
        var tries = 0
        while (count("2026-11-16.log") < n && tries++ < 200) Thread.sleep(25)
        val blocks = File(dir, "2026-11-16.log").readText().split(LogFormat.SEPARATOR + "\n").filter { it.isNotEmpty() }
        assertEquals(n, blocks.size)
        assertTrue(blocks.all { it.startsWith("[") && it.contains("\nPackage: p") })
    }

    @Test fun unavailableStorageKeepsEntriesPendingThenFlushes() {
        dir.parentFile!!.mkdirs()
        dir.writeText("not a directory")
        assertFalse(store.append(entry("1")))
        assertEquals(1, store.status().pending)
        dir.delete()
        assertTrue(store.append(entry("2")))
        assertEquals(2, count("2026-11-16.log"))
        assertEquals(0, store.status().pending)
    }
}
