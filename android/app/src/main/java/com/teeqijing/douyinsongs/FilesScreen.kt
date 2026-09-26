package com.teeqijing.douyinsongs

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors
import me.jahnen.libaums.core.fs.UsbFile

/**
 * Browsing and tidying the pendrive, plus a preview player.
 *
 * Built for someone who wants to check what is on the stick before driving
 * off with it: big rows, one song open at a time, and no operation that
 * cannot be undone without being asked first.
 *
 * All drive access happens on a single background thread, because the drive
 * is serial and because listing a folder over USB is far too slow for the
 * main thread.
 */
class FilesScreen(private val activity: Activity) {

    companion object {
        private const val ROW_TEXT_SP = 17f
        private const val SKIP_MS = 10_000
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val player = SongPlayer()

    private lateinit var root: LinearLayout
    private lateinit var header: TextView
    private lateinit var upButton: Button
    private lateinit var listHolder: LinearLayout
    private lateinit var message: TextView

    private var driveRoot: UsbFile? = null
    private var current: UsbFile? = null
    private var openRow: PlayerRow? = null

    /** What the list is ordered by, remembered between visits. */
    private enum class SortBy { NAME, TIME, DURATION, SIZE }
    private var sortBy = SortBy.NAME
    private var ascending = true
    /** Song lengths once read, keyed by file name; also used for sorting. */
    private val durations = mutableMapOf<String, Int>()
    private var entriesHere: List<UsbFile> = emptyList()
    private var freeHere = -1L
    private lateinit var sortBar: LinearLayout
    private var visible = false
    /** Every expandable panel in the current listing, so all can be closed. */
    private val panels = mutableListOf<LinearLayout>()

    /** The expanded player attached to one song row. */
    private inner class PlayerRow(
        val file: UsbFile,
        val panel: LinearLayout,
        val bar: SeekBar,
        val elapsed: TextView,
        val total: TextView,
        val playPause: Button,
    )

    // ------------------------------------------------------------------ view

    fun view(): View {
        root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 8)
        }

        header = TextView(activity).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(header)

        upButton = Button(activity).apply {
            text = activity.getString(R.string.files_up)
            textSize = 17f
            visibility = View.GONE
            setOnClickListener { goUp() }
        }
        root.addView(upButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = 12 })

        sortBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        addSortButton(R.string.sort_name, SortBy.NAME)
        addSortButton(R.string.sort_time, SortBy.TIME)
        addSortButton(R.string.sort_duration, SortBy.DURATION)
        addSortButton(R.string.sort_size, SortBy.SIZE)
        root.addView(sortBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        message = TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(0, 24, 0, 8)
        }
        root.addView(message)

        listHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(ScrollView(activity).apply { addView(listHolder) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun addSortButton(labelRes: Int, key: SortBy) {
        val button = Button(activity).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
            tag = key
            setOnClickListener {
                // Tapping the column already in use flips the direction,
                // which is what people expect from a list header.
                if (sortBy == key) ascending = !ascending else {
                    sortBy = key
                    ascending = true
                }
                saveSort()
                markSortButtons()
                applySort()
            }
        }
        button.setText(labelRes)
        sortBar.addView(button, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun markSortButtons() {
        for (i in 0 until sortBar.childCount) {
            val b = sortBar.getChildAt(i) as Button
            val chosen = b.tag == sortBy
            b.setBackgroundColor(
                if (chosen) Color.parseColor("#FE2C55") else Color.parseColor("#E4E4E4"))
            b.setTextColor(if (chosen) Color.WHITE else Color.DKGRAY)
            val base = b.text.toString().removeSuffix(" ↑").removeSuffix(" ↓")
            b.text = if (chosen) base + (if (ascending) " ↑" else " ↓") else base
        }
    }

    private fun saveSort() {
        activity.getSharedPreferences("douyinsongs", 0).edit()
            .putString("sort_by", sortBy.name)
            .putBoolean("sort_asc", ascending)
            .apply()
    }

    private fun loadSort() {
        val prefs = activity.getSharedPreferences("douyinsongs", 0)
        sortBy = runCatching {
            SortBy.valueOf(prefs.getString("sort_by", SortBy.NAME.name)!!)
        }.getOrDefault(SortBy.NAME)
        ascending = prefs.getBoolean("sort_asc", true)
    }

    /** Re-orders what is already listed, without going back to the drive. */
    private fun applySort() {
        val folders = entriesHere.filter { it.isDirectory }
        val files = entriesHere.filter { !it.isDirectory }
        val sortedFiles = when (sortBy) {
            SortBy.NAME -> files.sortedBy { it.name.lowercase() }
            SortBy.TIME -> files.sortedBy { lastChanged(it) }
            SortBy.SIZE -> files.sortedBy { it.length }
            // Songs whose length is not known yet sort last either way,
            // rather than pretending to be zero-length.
            SortBy.DURATION -> files.sortedBy {
                durations[it.name] ?: Int.MAX_VALUE
            }
        }.let { if (ascending) it else it.reversed() }

        val sortedFolders = folders.sortedBy { it.name.lowercase() }
            .let { if (ascending) it else it.reversed() }
        render(sortedFolders + sortedFiles, freeHere)
    }

    private fun lastChanged(file: UsbFile): Long = try {
        val modified = file.lastModified()
        if (modified > 0) modified else file.createdAt()
    } catch (e: Exception) {
        0L
    }

    // ------------------------------------------------------------ lifecycle

    fun onShow() {
        visible = true
        loadSort()
        if (Progress.running) {
            showMessage(activity.getString(R.string.files_busy_downloading))
            listHolder.removeAllViews()
            return
        }
        openDrive()
    }

    fun onHide() {
        visible = false
        closePlayer()
        // Only give the drive back if nothing else is using it: a download
        // owns the same device, and closing it underneath would kill the run.
        if (!Progress.running) {
            io.execute { runCatching { Usb.close() } }
        }
        driveRoot = null
        current = null
    }

    fun onDestroy() {
        closePlayer()
        io.shutdownNow()
    }

    /** True when a back press was consumed by going up a folder. */
    fun onBackPressed(): Boolean {
        val here = current ?: return false
        if (openRow != null) {
            closePlayer()
            return true
        }
        if (here.isRoot) return false
        goUp()
        return true
    }

    // ----------------------------------------------------------- navigation

    private fun openDrive() {
        showMessage(activity.getString(R.string.files_loading))
        listHolder.removeAllViews()
        io.execute {
            try {
                val top = Usb.openRoot(activity)
                val start = Usb.songsDirIn(top)
                driveRoot = top
                ui.post { navigateTo(start) }
            } catch (e: Usb.NeedsPermissionException) {
                ui.post {
                    showMessage(activity.getString(R.string.usb_needs_permission))
                    Usb.requestPermission(activity)
                }
            } catch (e: Exception) {
                ui.post { showMessage(activity.getString(R.string.usb_cannot_open)) }
            }
        }
    }

    private fun goUp() {
        val parent = current?.parent ?: return
        closePlayer()
        navigateTo(parent)
    }

    private fun navigateTo(dir: UsbFile) {
        current = dir
        closePlayer()
        header.text = activity.getString(R.string.files_here,
            if (dir.isRoot) activity.getString(R.string.files_drive_root) else dir.name)
        upButton.visibility = if (dir.isRoot) View.GONE else View.VISIBLE
        showMessage(activity.getString(R.string.files_loading))
        listHolder.removeAllViews()

        io.execute {
            val entries = try {
                synchronized(Usb.ioLock) { dir.listFiles() }.toList()
            } catch (e: Exception) {
                ui.post { showMessage(activity.getString(R.string.files_cannot_read)) }
                return@execute
            }
            val free = Usb.freeBytes()
            ui.post {
                entriesHere = entries
                freeHere = free
                markSortButtons()
                applySort()
                if (visible) loadDurations(entries)
            }
        }
    }

    // -------------------------------------------------------------- listing

    private fun render(entries: List<UsbFile>, freeBytes: Long) {
        listHolder.removeAllViews()
        panels.clear()
        val songs = entries.count { !it.isDirectory && it.name.endsWith(".mp3", true) }
        showMessage(
            if (entries.isEmpty()) activity.getString(R.string.files_empty)
            else activity.getString(R.string.files_summary, songs, gb(freeBytes)))

        for (entry in entries) {
            listHolder.addView(
                if (entry.isDirectory) folderRow(entry) else fileRow(entry))
            listHolder.addView(divider())
        }
    }

    private fun divider() = View(activity).apply {
        setBackgroundColor(Color.parseColor("#22808080"))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
    }

    private fun folderRow(dir: UsbFile): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 160
        setPadding(8, 24, 8, 24)
        isClickable = true
        addView(TextView(activity).apply { text = "📁"; textSize = 22f })
        addView(TextView(activity).apply {
            text = "  ${dir.name}"
            textSize = ROW_TEXT_SP
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = "›"
            textSize = 22f
            setTextColor(Color.GRAY)
        })
        setOnClickListener { navigateTo(dir) }
    }

    private fun fileRow(file: UsbFile): View {
        val isSong = file.name.endsWith(".mp3", ignoreCase = true)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val durationLabel = TextView(activity).apply {
            text = when {
                !isSong -> mb(file.length)
                durations[file.name] != null -> formatTime(durations[file.name]!!)
                else -> ""
            }
            textSize = 15f
            setTextColor(Color.GRAY)
            tag = "dur:${file.name}"
        }

        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 160
            setPadding(8, 24, 8, 24)
            isClickable = true
            addView(TextView(activity).apply {
                text = if (isSong) "🎵" else "📄"
                textSize = 20f
            })
            addView(TextView(activity).apply {
                text = "  ${file.name.removeSuffix(".mp3").removeSuffix(".MP3")}"
                textSize = ROW_TEXT_SP
                maxLines = 3
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(durationLabel)
        }
        wrapper.addView(head)

        val panel = playerPanel(file)
        panels.add(panel)
        wrapper.addView(panel)

        head.setOnClickListener {
            if (panel.visibility == View.VISIBLE) {
                closePlayer()
            } else if (isSong) {
                closePlayer()
                startPlaying(file, panel)
            } else {
                // Not a song: still offer rename and delete.
                closePlayer()
                panel.visibility = View.VISIBLE
            }
        }
        return wrapper
    }

    // --------------------------------------------------------------- player

    private fun playerPanel(file: UsbFile): LinearLayout {
        val isSong = file.name.endsWith(".mp3", ignoreCase = true)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 0, 8, 24)
        }

        lateinit var bar: SeekBar
        lateinit var elapsed: TextView
        lateinit var total: TextView
        lateinit var playPause: Button

        if (isSong) {
            bar = SeekBar(activity)
            val times = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            elapsed = TextView(activity).apply { textSize = 15f }
            total = TextView(activity).apply {
                textSize = 15f
                gravity = Gravity.END
            }
            times.addView(elapsed, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            times.addView(total, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val back = bigButton("⏪ 10秒") { player.nudge(-SKIP_MS) }
            playPause = bigButton("⏸") {
                if (player.isPlaying) {
                    player.pause()
                    it.text = "▶"
                } else {
                    player.resume()
                    it.text = "⏸"
                }
            }
            val forward = bigButton("10秒 ⏩") { player.nudge(SKIP_MS) }
            controls.addView(back, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            controls.addView(playPause, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            controls.addView(forward, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) player.seekTo(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })

            panel.addView(bar)
            panel.addView(times)
            panel.addView(controls)
            panel.tag = PlayerRow(file, panel, bar, elapsed, total, playPause)
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        actions.addView(plainButton(activity.getString(R.string.files_rename)) {
            askRename(file)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(plainButton(activity.getString(R.string.files_delete)) {
            askDelete(file)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panel.addView(actions)
        return panel
    }

    private fun startPlaying(file: UsbFile, panel: LinearLayout) {
        val row = panel.tag as? PlayerRow ?: return
        panel.visibility = View.VISIBLE
        openRow = row
        row.elapsed.text = formatTime(0)
        row.total.text = activity.getString(R.string.files_opening)
        row.playPause.text = "⏸"

        player.play(file, onReady = {
            ui.post {
                if (openRow !== row) return@post
                row.bar.max = player.durationMs
                row.total.text = formatTime(player.durationMs)
                tick()
            }
        }, onError = { reason ->
            ui.post {
                if (openRow !== row) return@post
                row.total.text = activity.getString(R.string.files_play_failed)
                row.playPause.text = "▶"
            }
        })
    }

    private val ticker = object : Runnable {
        override fun run() {
            val row = openRow ?: return
            row.bar.progress = player.positionMs
            row.elapsed.text = formatTime(player.positionMs)
            if (!player.isPlaying && player.positionMs >= player.durationMs - 250) {
                row.playPause.text = "▶"
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun tick() {
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun closePlayer() {
        ui.removeCallbacks(ticker)
        player.stop()
        openRow = null
        panels.forEach { it.visibility = View.GONE }
    }

    // -------------------------------------------------------------- actions

    private fun askRename(file: UsbFile) {
        val isSong = file.name.endsWith(".mp3", ignoreCase = true)
        val input = EditText(activity).apply {
            setText(file.name.removeSuffix(".mp3").removeSuffix(".MP3"))
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.files_rename)
            .setView(input)
            .setPositiveButton(R.string.files_ok) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isEmpty()) return@setPositiveButton
                val safe = typed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val finalName = if (isSong) "$safe.mp3" else safe
                closePlayer()
                io.execute {
                    val ok = runCatching {
                        synchronized(Usb.ioLock) { file.name = finalName }
                    }.isSuccess
                    ui.post {
                        toast(if (ok) R.string.files_renamed else R.string.files_action_failed)
                        current?.let { navigateTo(it) }
                    }
                }
            }
            .setNegativeButton(R.string.files_cancel, null)
            .show()
    }

    private fun askDelete(file: UsbFile) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.files_delete)
            // Deleting from a pendrive has no recycle bin, so make sure the
            // name being removed is actually on screen before confirming.
            .setMessage(activity.getString(R.string.files_delete_confirm, file.name))
            .setPositiveButton(R.string.files_delete) { _, _ ->
                closePlayer()
                io.execute {
                    val ok = runCatching {
                        synchronized(Usb.ioLock) { file.delete() }
                    }.isSuccess
                    ui.post {
                        toast(if (ok) R.string.files_deleted else R.string.files_action_failed)
                        current?.let { navigateTo(it) }
                    }
                }
            }
            .setNegativeButton(R.string.files_cancel, null)
            .show()
    }

    // ------------------------------------------------------------- plumbing

    /** Fills in each song's length in the background; the list stays usable. */
    private fun loadDurations(entries: List<UsbFile>) {
        val songs = entries.filter {
            !it.isDirectory && it.name.endsWith(".mp3", ignoreCase = true)
        }
        for (song in songs) {
            io.execute {
                if (!visible || openRow != null) return@execute
                val ms = readDurationMs(song)
                if (ms <= 0) return@execute
                ui.post {
                    durations[song.name] = ms
                    listHolder.findViewWithTag<TextView>("dur:${song.name}")?.text =
                        formatTime(ms)
                    // Once lengths arrive they change the order, but only
                    // re-shuffle if that is what the list is sorted by.
                    if (sortBy == SortBy.DURATION && openRow == null) applySort()
                }
            }
        }
    }

    private fun bigButton(label: String, onClick: (Button) -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 17f
            setOnClickListener { onClick(this) }
        }

    private fun plainButton(label: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 16f
            setOnClickListener { onClick() }
        }

    private fun showMessage(text: String) {
        message.text = text
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(activity, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun gb(bytes: Long): String =
        if (bytes < 0) "?" else String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun mb(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KB"
        else String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
