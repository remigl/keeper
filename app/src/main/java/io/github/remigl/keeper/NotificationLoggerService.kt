package io.github.remigl.keeper

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.remigl.keeper.log.LogFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Passive observer. Android binds it when notification access is granted (and again after every
 * reboot / app update), then calls it only when a notification is posted or removed.
 * It never cancels, snoozes, answers or alters a notification: it only reads and appends to the log.
 * Callbacks arrive on the main thread; the file I/O is handed to the store's single writer thread.
 */
class NotificationLoggerService : NotificationListenerService() {

    /**
     * key → hash of (title, text) of the last entry logged for that notification.
     * Apps re-post the same notification many times (progress, media sessions…) without changing
     * what the user sees; identical re-posts are not logged again. Only a hash is kept, not the content.
     */
    private val lastLogged = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_TRACKED
    }

    override fun onListenerConnected() {
        connected = true
        catchUp()
    }

    override fun onListenerDisconnected() {
        connected = false
        lastLogged.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) record(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Forget it, so that the same notification posted again later is logged again.
        if (sbn != null) lastLogged.remove(sbn.key)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    /**
     * Notifications posted while the listener was not bound (between boot and first unlock,
     * during an app update, after a process kill) and still displayed now are logged once,
     * using the time of the last successful write as the boundary.
     */
    private fun catchUp() {
        val since = Keeper.store(this).lastWriteMillis()
        val active = try {
            activeNotifications
        } catch (e: Exception) { // SecurityException if access was revoked meanwhile
            Log.w(TAG, "activeNotifications failed: ${e.javaClass.simpleName}")
            null
        } ?: return
        active.sortedBy { it.postTime }.forEach { sbn ->
            if (since > 0 && sbn.postTime > since) record(sbn) else remember(sbn)
        }
    }

    private fun record(sbn: StatusBarNotification) {
        val content = extract(sbn)
        if (lastLogged.put(sbn.key, content.hash) == content.hash) return

        val millis = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        val entry = LogFormat.entry(time, sbn.packageName ?: "", content.title, content.text)
        Keeper.store(this).appendAsync(entry)
    }

    /** Marks an already-displayed notification as seen without logging it. */
    private fun remember(sbn: StatusBarNotification) {
        lastLogged[sbn.key] = extract(sbn).hash
    }

    private class Content(val title: CharSequence?, val text: CharSequence?) {
        val hash: Int = 31 * (title?.toString()?.hashCode() ?: 0) + (text?.toString()?.hashCode() ?: 0)
    }

    private fun extract(sbn: StatusBarNotification): Content = try {
        val extras: Bundle = sbn.notification?.extras ?: Bundle.EMPTY
        Content(title(extras), text(extras))
    } catch (e: RuntimeException) {
        // e.g. BadParcelableException from an app's custom extras. Never log the content itself.
        Log.w(TAG, "unreadable notification extras: ${e.javaClass.simpleName}")
        Content(null, UNREADABLE)
    }

    private fun title(extras: Bundle): CharSequence? =
        extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)

    /** Expanded text if present, otherwise the short text; plus the lines of an InboxStyle notification. */
    private fun text(extras: Bundle): CharSequence? {
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val base = if (!big.isNullOrBlank()) big else extras.getCharSequence(Notification.EXTRA_TEXT)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.filter { !it.isNullOrBlank() }
            .orEmpty()
        if (lines.isEmpty()) return base
        val joined = lines.joinToString("\n")
        return if (base.isNullOrBlank()) joined else "$base\n$joined"
    }

    companion object {
        private const val TAG = "Keeper"
        private const val MAX_TRACKED = 256
        private const val UNREADABLE = "[contenu illisible]"

        /** True while Android keeps this listener bound (same process as the UI). */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
