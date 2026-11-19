package io.github.remigl.keeper

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import io.github.remigl.keeper.log.LogStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
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

        folder.text = getString(R.string.folder, store.directory.absolutePath)

        findViewById<Button>(R.id.btnListenerSettings).setOnClickListener { openListenerSettings() }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        btnRebind.setOnClickListener {
            NotificationListenerService.requestRebind(listener)
            btnRebind.postDelayed({ if (!isDestroyed) refresh() }, 1500)
        }
        findViewById<Button>(R.id.btnView).setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
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

    private fun startSafely(intent: Intent) {
        if (!tryStart(intent)) Toast.makeText(this, R.string.cannot_open_settings, Toast.LENGTH_SHORT).show()
    }
}
