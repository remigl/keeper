package io.github.remigl.keeper

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.remigl.keeper.log.LogStore
import java.io.File
import java.util.Date

/**
 * Minimal status / setup screen. Not needed for logging: once access is granted, the service
 * works without this screen ever being opened again.
 */
class MainActivity : Activity() {

    private val listener by lazy { ComponentName(this, NotificationLoggerService::class.java) }
    private val store by lazy { Keeper.store(this) }

    private lateinit var access: TextView
    private lateinit var service: TextView
    private lateinit var currentFile: TextView
    private lateinit var previousFile: TextView
    private lateinit var entries: TextView
    private lateinit var lastWrite: TextView
    private lateinit var error: TextView
    private lateinit var folder: TextView
    private lateinit var btnRebind: Button
    private lateinit var btnOpenCurrent: Button
    private lateinit var btnOpenPrevious: Button

    private var current: File? = null
    private var previous: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Log content may be displayed here: keep it out of screenshots and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))

        access = findViewById(R.id.access)
        service = findViewById(R.id.service)
        currentFile = findViewById(R.id.currentFile)
        previousFile = findViewById(R.id.previousFile)
        entries = findViewById(R.id.entries)
        lastWrite = findViewById(R.id.lastWrite)
        error = findViewById(R.id.error)
        folder = findViewById(R.id.folder)
        btnRebind = findViewById(R.id.btnRebind)
        btnOpenCurrent = findViewById(R.id.btnOpenCurrent)
        btnOpenPrevious = findViewById(R.id.btnOpenPrevious)

        folder.text = getString(R.string.folder, store.directory.absolutePath)

        findViewById<Button>(R.id.btnListenerSettings).setOnClickListener { openListenerSettings() }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        btnRebind.setOnClickListener {
            NotificationListenerService.requestRebind(listener)
            btnRebind.postDelayed({ if (!isDestroyed) refresh() }, 1500)
        }
        btnOpenCurrent.setOnClickListener { current?.let(::openLog) }
        btnOpenPrevious.setOnClickListener { previous?.let(::openLog) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
        val connected = NotificationLoggerService.connected

        access.setText(if (granted) R.string.access_on else R.string.access_off)
        access.setTextColor(getColor(if (granted) R.color.ok else R.color.warn))
        service.setText(
            when {
                !granted -> R.string.service_off
                connected -> R.string.service_connected
                else -> R.string.service_waiting
            },
        )
        btnRebind.visibility = if (granted && !connected) View.VISIBLE else View.GONE

        // First launch creates the folder and today's file; disk work stays off the main thread.
        Thread {
            val status = store.status()
            runOnUiThread { if (!isDestroyed) show(status) }
        }.start()
    }

    private fun show(status: LogStore.Status) {
        current = status.current
        previous = status.previous
        currentFile.text = getString(R.string.current_file, status.current?.name ?: getString(R.string.no_file))
        previousFile.text = getString(R.string.previous_file, status.previous?.name ?: getString(R.string.no_file))
        entries.text = getString(R.string.entries_today, status.entriesToday)
        lastWrite.text = getString(
            R.string.last_write,
            if (status.lastWriteMillis > 0) formatTime(status.lastWriteMillis) else getString(R.string.never),
        )
        if (status.lastErrorType != null) {
            error.visibility = View.VISIBLE
            error.text = getString(R.string.write_error, formatTime(status.lastErrorMillis), status.lastErrorType, status.pending)
        } else {
            error.visibility = View.GONE
        }
        btnOpenCurrent.isEnabled = status.current != null
        btnOpenPrevious.isEnabled = status.previous != null
    }

    private fun formatTime(millis: Long): String {
        val date = Date(millis)
        return "${DateFormat.getDateFormat(this).format(date)} ${DateFormat.getTimeFormat(this).format(date)}"
    }

    private fun openListenerSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
            if (tryStart(detail)) return
        }
        startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Opens the log in a text viewer chosen by the user; falls back to an in-app read-only view. */
    private fun openLog(file: File) {
        if (!file.isFile) {
            Toast.makeText(this, R.string.file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(LogProvider.uriFor(packageName, file), "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!tryStart(view)) showInApp(file)
    }

    private fun showInApp(file: File) {
        Thread {
            val body = try {
                val (tail, truncated) = store.readTail(file, VIEWER_MAX_BYTES)
                if (truncated) getString(R.string.viewer_truncated) + tail else tail
            } catch (e: Exception) {
                getString(R.string.file_missing)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val pad = (16 * resources.displayMetrics.density).toInt()
                val content = TextView(this).apply {
                    setText(body)
                    setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(pad, pad, pad, pad)
                }
                val scroll = ScrollView(this).apply { addView(content) }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setView(scroll)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }.start()
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun startSafely(intent: Intent) {
        if (!tryStart(intent)) Toast.makeText(this, R.string.cannot_open_settings, Toast.LENGTH_SHORT).show()
    }

    /** Android 15 draws apps edge-to-edge: keep the content clear of the status and navigation bars. */
    private fun applySystemBarInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        // Before Android 15 the window is not edge-to-edge: no inset to apply, keep the default.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) root.setOnApplyWindowInsetsListener(null)
    }

    private companion object {
        const val VIEWER_MAX_BYTES = 256 * 1024
    }
}
