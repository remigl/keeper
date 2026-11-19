package io.github.remigl.keeper

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.remigl.keeper.log.LogEntry
import io.github.remigl.keeper.log.LogParser
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Reads today's or the previous log file and lists its entries, with filters by app and by time
 * of day, newest or oldest first. Read-only: it never writes to the logs.
 */
class ViewerActivity : Activity() {

    private val store by lazy { Keeper.store(this) }
    private lateinit var icons: AppIcons

    private lateinit var days: LinearLayout
    private lateinit var apps: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private val adapter = EntryAdapter()

    private var files: List<File> = emptyList() // [current, previous?]
    private var dayIndex = 0
    private var entries: List<LogEntry> = emptyList()
    private var fileDate: LocalDate? = null
    private var selectedPackage: String? = null
    private var newestFirst = true
    private var from: LocalTime? = null
    private var to: LocalTime? = null
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        setContentView(R.layout.activity_viewer)
        applySystemBarInsets(findViewById(R.id.root))
        icons = AppIcons(this)

        days = findViewById(R.id.days)
        apps = findViewById(R.id.apps)
        controls = findViewById(R.id.controls)
        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ -> adapter.toggle(position) }

        if (savedInstanceState != null) {
            dayIndex = savedInstanceState.getInt(KEY_DAY)
            selectedPackage = savedInstanceState.getString(KEY_PACKAGE)
            newestFirst = savedInstanceState.getBoolean(KEY_NEWEST, true)
            from = savedInstanceState.getString(KEY_FROM)?.let(LocalTime::parse)
            to = savedInstanceState.getString(KEY_TO)?.let(LocalTime::parse)
        }
        buildControls()
        loadFiles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DAY, dayIndex)
        outState.putString(KEY_PACKAGE, selectedPackage)
        outState.putBoolean(KEY_NEWEST, newestFirst)
        outState.putString(KEY_FROM, from?.toString())
        outState.putString(KEY_TO, to?.toString())
    }

    // ---- loading (off the main thread) ----

    private fun loadFiles() {
        Thread {
            val status = store.status()
            val list = listOfNotNull(status.current, status.previous)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                files = list
                if (dayIndex >= files.size) dayIndex = 0
                buildDays()
                loadDay()
            }
        }.start()
    }

    private fun loadDay() {
        val file = files.getOrNull(dayIndex)
        val generation = ++loadGeneration
        entries = emptyList()
        adapter.show(emptyList())
        empty.setText(if (file == null) R.string.no_log else R.string.loading)
        summary.text = ""
        apps.removeAllViews()
        if (file == null) return
        Thread {
            val parsed = try { LogParser.parse(file) } catch (e: Exception) { null }
            // Resolve labels and icons here too: PackageManager calls stay off the main thread.
            parsed?.map { it.packageName }?.distinct()?.forEach { icons.get(it) }
            runOnUiThread {
                if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                if (parsed == null) {
                    empty.setText(R.string.read_error)
                    return@runOnUiThread
                }
                entries = parsed
                fileDate = dateOf(file)
                if (selectedPackage != null && parsed.none { it.packageName == selectedPackage }) selectedPackage = null
                buildApps()
                applyFilters()
            }
        }.start()
    }

    // ---- filters ----

    private fun applyFilters() {
        val pkg = selectedPackage
        val start = from
        val end = to
        val filtered = entries.filter { e ->
            (pkg == null || e.packageName == pkg) && inRange(e.time.toLocalTime(), start, end)
        }
        val sorted = if (newestFirst) filtered.sortedByDescending { it.time.toInstant() } else filtered.sortedBy { it.time.toInstant() }
        adapter.show(sorted)
        summary.text = getString(R.string.summary, sorted.size, entries.size)
        empty.setText(if (entries.isEmpty()) R.string.empty_day else R.string.empty_filter)
    }

    /** Inclusive time-of-day range; a range like 22:00 – 06:00 wraps over midnight. */
    private fun inRange(t: LocalTime, start: LocalTime?, end: LocalTime?): Boolean = when {
        start == null || end == null -> true
        !start.isAfter(end) -> !t.isBefore(start) && !t.isAfter(end)
        else -> !t.isBefore(start) || !t.isAfter(end)
    }

    // ---- chips ----

    private fun buildDays() {
        days.removeAllViews()
        val today = LocalDate.now()
        files.forEachIndexed { index, file ->
            val date = dateOf(file)
            val label = when {
                date == null -> file.name
                date == today -> getString(R.string.today)
                date == today.minusDays(1) -> getString(R.string.yesterday)
                else -> DATE_SHORT.format(date)
            }
            days.addView(chip(label, null, index == dayIndex) {
                if (dayIndex != index) {
                    dayIndex = index
                    buildDays()
                    loadDay()
                }
            })
        }
    }

    private fun buildApps() {
        apps.removeAllViews()
        if (entries.isEmpty()) return
        apps.addView(chip(getString(R.string.all_apps, entries.size), null, selectedPackage == null) { selectApp(null) })
        entries.groupingBy { it.packageName }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { icons.get(it.key).label.lowercase() })
            .forEach { (pkg, count) ->
                val app = icons.get(pkg)
                apps.addView(chip(getString(R.string.app_chip, app.label, count), app, selectedPackage == pkg) { selectApp(pkg) })
            }
    }

    private fun selectApp(pkg: String?) {
        if (selectedPackage == pkg) return
        selectedPackage = pkg
        buildApps()
        applyFilters()
    }

    private fun buildControls() {
        controls.removeAllViews()
        controls.addView(chip(getString(if (newestFirst) R.string.sort_newest else R.string.sort_oldest), null, false) {
            newestFirst = !newestFirst
            buildControls()
            applyFilters()
        })
        val start = from
        val end = to
        val timeLabel = if (start != null && end != null) getString(R.string.time_range, fmt(start), fmt(end)) else getString(R.string.time_any)
        controls.addView(chip(timeLabel, null, start != null) { pickTimeRange() })
        if (start != null) {
            controls.addView(chip(getString(R.string.time_clear), null, false) {
                from = null; to = null
                buildControls()
                applyFilters()
            })
        }
        controls.addView(chip(getString(R.string.raw_file), null, false) { openRawFile() })
    }

    private fun pickTimeRange() {
        val is24 = DateFormat.is24HourFormat(this)
        val initialFrom = from ?: LocalTime.of(0, 0)
        TimePickerDialog(this, { _, h1, m1 ->
            val initialTo = to ?: LocalTime.of(23, 59)
            TimePickerDialog(this, { _, h2, m2 ->
                from = LocalTime.of(h1, m1)
                to = LocalTime.of(h2, m2, 59) // inclusive minute
                buildControls()
                applyFilters()
            }, initialTo.hour, initialTo.minute, is24).apply { setTitle(R.string.pick_to) }.show()
        }, initialFrom.hour, initialFrom.minute, is24).apply { setTitle(R.string.pick_from) }.show()
    }

    private fun chip(label: String, app: AppIcons.App?, selected: Boolean, onClick: () -> Unit): View {
        val pad = dp(12)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(if (app != null) dp(6) else pad, dp(6), pad, dp(6))
            isSelected = selected
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            if (app != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(app.icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
                })
            }
            addView(TextView(context).apply {
                text = label
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                maxLines = 1
            })
        }
    }

    private fun openRawFile() {
        val file = files.getOrNull(dayIndex) ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(LogProvider.uriFor(packageName, file), "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!tryStart(view)) Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
    }

    private fun dateOf(file: File): LocalDate? = try {
        LocalDate.parse(file.name.removeSuffix(".log"))
    } catch (e: Exception) {
        null
    }

    private fun fmt(t: LocalTime): String = TIME_SHORT.format(t)

    // ---- list ----

    private inner class EntryAdapter : BaseAdapter() {
        private var items: List<LogEntry> = emptyList()
        private val expanded = HashSet<Int>()

        fun show(list: List<LogEntry>) {
            items = list
            expanded.clear()
            notifyDataSetChanged()
        }

        fun toggle(position: Int) {
            if (!expanded.add(position)) expanded.remove(position)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_notification, parent, false).apply {
                tag = Holder(this)
            }
            val h = view.tag as Holder
            val e = items[position]
            val app = icons.get(e.packageName)
            h.icon.setImageBitmap(app.icon)
            h.app.text = app.label
            val date = e.time.toLocalDate()
            h.time.text = if (date == fileDate) TIME_FULL.format(e.time) else DATE_TIME.format(e.time)
            h.title.text = e.title
            h.title.visibility = if (e.title.isEmpty()) View.GONE else View.VISIBLE
            h.text.text = e.text
            h.text.visibility = if (e.text.isEmpty()) View.GONE else View.VISIBLE
            h.text.maxLines = if (position in expanded) Int.MAX_VALUE else COLLAPSED_LINES
            return view
        }
    }

    private class Holder(view: View) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val app: TextView = view.findViewById(R.id.app)
        val time: TextView = view.findViewById(R.id.time)
        val title: TextView = view.findViewById(R.id.title)
        val text: TextView = view.findViewById(R.id.text)
    }

    private companion object {
        const val COLLAPSED_LINES = 3
        const val KEY_DAY = "day"
        const val KEY_PACKAGE = "package"
        const val KEY_NEWEST = "newest"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        val TIME_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val TIME_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
        val DATE_SHORT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    }
}
