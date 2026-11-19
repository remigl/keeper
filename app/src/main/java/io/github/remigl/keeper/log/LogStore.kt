package io.github.remigl.keeper.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Owns the log directory: daily files, rotation and serialized, durable appends.
 * Pure Kotlin/JVM (no Android dependency) so the logic can be tested anywhere.
 *
 * Retention rule: the file of the current day plus the most recent other day are kept,
 * every other `yyyy-MM-dd.log` file is deleted. In normal use that is "today + yesterday";
 * after several days without any notification (phone off) the last recorded day is kept
 * rather than an empty, invented "yesterday" — never more than two files either way.
 */
class LogStore(
    val directory: File,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    data class Status(
        val current: File?,
        val previous: File?,
        val entriesToday: Int,
        val lastWriteMillis: Long,
        val pending: Int,
        val lastErrorMillis: Long,
        val lastErrorType: String?,
    )

    private val lock = Any()

    /** Day for which the directory was last checked/pruned; null forces a check. Guarded by [lock]. */
    private var preparedFor: LocalDate? = null

    /** Entries that could not be written (storage unavailable); retried on the next append. Guarded by [lock]. */
    private val pending = ArrayDeque<ByteArray>()

    @Volatile var lastErrorMillis: Long = 0L
        private set
    @Volatile var lastErrorType: String? = null
        private set

    /**
     * One writer thread at most, created on demand and terminated after 10 s idle:
     * appends are serialized, and nothing stays alive while no notification arrives.
     */
    private val writer = ThreadPoolExecutor(
        1, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "keeper-writer") }.apply { allowCoreThreadTimeOut(true) }

    fun appendAsync(entry: String) {
        writer.execute { append(entry) }
    }

    /** Appends one entry (plus any pending ones) to today's file. Returns false if it was kept pending. */
    fun append(entry: String): Boolean {
        val bytes = entry.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            return try {
                val file = currentFileLocked()
                val payload = if (pending.isEmpty()) bytes else concat(pending, bytes)
                // One write() of the whole entry, then fsync: an entry is either fully on disk or absent,
                // except in the rare case of a power loss in the middle of the write itself.
                FileOutputStream(file, true).use { out ->
                    out.write(payload)
                    out.flush()
                    out.fd.sync()
                }
                pending.clear()
                true
            } catch (e: Exception) { // IOException, SecurityException…: never the entry content
                preparedFor = null
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(bytes)
                lastErrorMillis = System.currentTimeMillis()
                lastErrorType = e.javaClass.simpleName
                false
            }
        }
    }

    /** Creates the directory and today's file if needed and applies the retention rule. */
    fun prepare(): File = synchronized(lock) { currentFileLocked() }

    fun status(): Status {
        val current = try { prepare() } catch (e: Exception) { null }
        val logs = logFiles()
        val previous = pickPrevious(logs, current?.name)
        return Status(
            current = current,
            previous = previous,
            entriesToday = current?.let { countEntries(it) } ?: 0,
            lastWriteMillis = logs.filter { it.length() > 0 }.maxOfOrNull { it.lastModified() } ?: 0L,
            pending = synchronized(lock) { pending.size },
            lastErrorMillis = lastErrorMillis,
            lastErrorType = lastErrorType,
        )
    }

    /**
     * Modification time of the newest log file (0 if none): the last successful append, or the
     * creation of today's file. Used as the catch-up boundary when the listener (re)connects.
     */
    fun lastWriteMillis(): Long = logFiles().maxOfOrNull { it.lastModified() } ?: 0L

    fun logFiles(): List<File> =
        directory.listFiles()?.filter { it.isFile && isLogName(it.name) }?.sortedBy { it.name } ?: emptyList()

    fun countEntries(file: File): Int = try {
        file.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.count { it == LogFormat.SEPARATOR } }
    } catch (e: IOException) {
        0
    }

    private fun currentFileLocked(): File {
        val today = now().toLocalDate()
        val file = File(directory, fileName(today))
        // Fast path: same day and file still there → a single stat(), no directory listing.
        if (today == preparedFor && file.isFile) return file

        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("log directory unavailable")
        }
        if (!file.isFile && !file.createNewFile() && !file.isFile) {
            throw IOException("log file unavailable")
        }
        val logs = logFiles()
        val keep = pickPrevious(logs, file.name)
        for (f in logs) {
            if (f.name != file.name && f != keep) f.delete()
        }
        preparedFor = today
        return file
    }

    companion object {
        const val MAX_PENDING = 200
        private val LOG_NAME = Regex("""\d{4}-\d{2}-\d{2}\.log""")

        fun isLogName(name: String): Boolean = LOG_NAME.matches(name)

        fun fileName(date: LocalDate): String = "$date.log"

        /** Most recent log older than today; if none (clock moved backwards), the most recent other one. */
        internal fun pickPrevious(logs: List<File>, todayName: String?): File? {
            val others = logs.filter { it.name != todayName }
            if (todayName == null) return others.maxByOrNull { it.name }
            return others.filter { it.name < todayName }.maxByOrNull { it.name }
                ?: others.maxByOrNull { it.name }
        }

        private fun concat(parts: Collection<ByteArray>, last: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size } + last.size)
            var pos = 0
            for (p in parts) {
                System.arraycopy(p, 0, out, pos, p.size)
                pos += p.size
            }
            System.arraycopy(last, 0, out, pos, last.size)
            return out
        }
    }
}
