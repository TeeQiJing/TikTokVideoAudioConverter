package com.teeqijing.douyinsongs

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * The whole app is one screen with two buttons: choose a 收藏夹 once, then
 * press the big button whenever new songs should go onto the pendrive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbStatus: TextView
    private lateinit var folderStatus: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var chooseBtn: Button
    private lateinit var goBtn: Button

    private val prefs by lazy { getSharedPreferences("douyinsongs", Context.MODE_PRIVATE) }
    private var busy = false
    private val ui = Handler(Looper.getMainLooper())
    private var lastSeenRun = 0
    private var shownLines = -1
    // Kept separate from Progress.running: the service takes a moment to
    // start, and polling must survive that gap or the screen shows nothing.
    private var watching = false
    private lateinit var content: android.widget.FrameLayout
    private lateinit var downloadTab: Button
    private lateinit var filesTab: Button
    private lateinit var files: FilesScreen
    private var onFilesTab = false

    private val usbWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshUsb()
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { showFolderPicker() }

    private val readSongs = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { startDownload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = FilesScreen(this)
        setContentView(buildShell())
        refreshFolderLabel()
        lastSeenRun = Progress.completedRuns
        askForNotifications()

        val filter = IntentFilter().apply {
            addAction(Usb.ACTION_PERMISSION)
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        }
        ContextCompat_registerReceiver(filter)
    }

    private fun ContextCompat_registerReceiver(filter: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbWatcher, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbWatcher, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsb()
        // A download may have been started earlier and kept going while this
        // screen was closed, so pick its progress back up.
        if (Progress.running || watching) {
            watching = true
            watch()
        } else {
            renderProgress()
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(watcher)
        // Let go of the drive so a download is never blocked by the browser.
        if (onFilesTab) files.onHide()
    }

    /** Without this the progress notification is silently hidden on 13+. */
    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbWatcher) }
        ui.removeCallbacks(watcher)
        files.onDestroy()
        // The service owns the drive while it is working; closing it here
        // would pull the pendrive out from under a running download.
        if (!Progress.running) Usb.close()
    }

    // ------------------------------------------------------------------ UI

    /** Two big tabs: getting songs, and tidying what is already on the stick. */
    private fun buildShell(): android.view.View {
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content = android.widget.FrameLayout(this)
        content.addView(buildUi())
        content.addView(files.view().apply { visibility = android.view.View.GONE })
        shell.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }
        downloadTab = tabButton(getString(R.string.tab_download)) { showTab(false) }
        filesTab = tabButton(getString(R.string.tab_files)) { showTab(true) }
        bar.addView(downloadTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(filesTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        shell.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        showTab(false)
        return shell
    }

    private fun tabButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setPadding(0, 28, 0, 28)
        setOnClickListener { onClick() }
    }

    private fun showTab(filesSelected: Boolean) {
        onFilesTab = filesSelected
        content.getChildAt(0).visibility =
            if (filesSelected) android.view.View.GONE else android.view.View.VISIBLE
        content.getChildAt(1).visibility =
            if (filesSelected) android.view.View.VISIBLE else android.view.View.GONE
        downloadTab.setBackgroundColor(
            if (filesSelected) Color.parseColor("#E4E4E4") else Color.parseColor("#FE2C55"))
        downloadTab.setTextColor(if (filesSelected) Color.DKGRAY else Color.WHITE)
        filesTab.setBackgroundColor(
            if (filesSelected) Color.parseColor("#FE2C55") else Color.parseColor("#E4E4E4"))
        filesTab.setTextColor(if (filesSelected) Color.WHITE else Color.DKGRAY)
        if (filesSelected) files.onShow() else files.onHide()
    }

    override fun onBackPressed() {
        if (onFilesTab && files.onBackPressed()) return
        if (onFilesTab) {
            showTab(false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun buildUi(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 28)
        })

        usbStatus = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 10) }
        root.addView(usbStatus)

        folderStatus = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 24) }
        root.addView(folderStatus)

        chooseBtn = Button(this).apply {
            text = getString(R.string.choose_folder)
            textSize = 17f
            setOnClickListener { chooseFolder() }
        }
        root.addView(chooseBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        goBtn = Button(this).apply {
            text = getString(R.string.go)
            textSize = 22f
            setPadding(0, 40, 0, 40)
            setBackgroundColor(Color.parseColor("#FE2C55"))
            setTextColor(Color.WHITE)
            setOnClickListener { go() }
        }
        root.addView(goBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 28
        })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = android.view.View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 20
        })

        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
        }
        root.addView(status)

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
        }
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun log(line: String) = runOnUiThread {
        logView.append(line + "\n")
    }

    private fun setBusy(value: Boolean) {
        busy = value
        chooseBtn.isEnabled = !value
        goBtn.isEnabled = !value
        progress.visibility = if (value) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    // ------------------------------------------------------------- state

    private fun savedFolderId() = prefs.getString("folder_id", null)
    private fun savedFolderName() = prefs.getString("folder_name", null)

    private fun refreshFolderLabel() {
        val name = savedFolderName()
        folderStatus.text = if (name != null) getString(R.string.folder_is, name)
        else getString(R.string.no_folder)
    }

    private fun refreshUsb() {
        usbStatus.text = when {
            !Usb.isAttached(this) -> getString(R.string.usb_missing)
            !Usb.hasPermission(this) -> getString(R.string.usb_needs_permission)
            else -> getString(R.string.usb_ready)
        }
    }

    // ----------------------------------------------------------- actions

    private fun chooseFolder() {
        Capture.reset()
        status.text = getString(R.string.opening_douyin)
        pickFolder.launch(Intent(this, DouyinWebActivity::class.java).apply {
            putExtra(DouyinWebActivity.EXTRA_MODE, DouyinWebActivity.MODE_FOLDERS)
        })
    }

    private fun showFolderPicker() {
        val folders = Capture.folders()
        if (folders.isEmpty()) {
            status.text = getString(R.string.no_folders_found)
            return
        }
        val labels = folders.map { "${it.name}  (${it.count})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_title)
            .setItems(labels) { _, which ->
                val f = folders[which]
                prefs.edit().putString("folder_id", f.id)
                    .putString("folder_name", f.name).apply()
                refreshFolderLabel()
                status.text = getString(R.string.folder_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun go() {
        if (busy) return
        val folderId = savedFolderId()
        if (folderId == null) {
            status.text = getString(R.string.need_folder)
            return
        }
        if (!Usb.isAttached(this)) {
            status.text = getString(R.string.usb_missing)
            return
        }
        if (!Usb.hasPermission(this)) {
            status.text = getString(R.string.usb_needs_permission)
            Usb.requestPermission(this)
            return
        }
        Capture.reset()
        logView.text = ""
        status.text = getString(R.string.opening_douyin)
        readSongs.launch(Intent(this, DouyinWebActivity::class.java).apply {
            putExtra(DouyinWebActivity.EXTRA_MODE, DouyinWebActivity.MODE_SONGS)
            putExtra(DouyinWebActivity.EXTRA_FOLDER_ID, folderId)
            putExtra(DouyinWebActivity.EXTRA_FOLDER_NAME, savedFolderName())
        })
    }

    private fun startDownload() {
        val folderId = savedFolderId() ?: return
        if (Capture.songs(folderId).isEmpty()) {
            status.text = getString(R.string.no_songs_found)
            return
        }
        files.onHide()
        lastSeenRun = Progress.completedRuns
        setBusy(true)
        logView.text = ""
        shownLines = -1
        status.text = getString(R.string.keep_running)
        watching = true
        DownloadService.start(this, folderId)
        watch()
    }

    /**
     * Polls the shared progress a few times a second. The work belongs to
     * the service, so the screen can be closed and reopened mid-download and
     * still pick the progress back up.
     */
    private fun watch() {
        ui.removeCallbacks(watcher)
        ui.post(watcher)
    }

    private val watcher = object : Runnable {
        override fun run() {
            renderProgress()
            if (watching) ui.postDelayed(this, 400)
        }
    }

    private fun renderProgress() {
        val shown = Progress.lines()
        if (shown.size != shownLines) {
            shownLines = shown.size
            logView.text = shown.joinToString("\n")
        }
        if (Progress.running) {
            setBusy(true)
            progress.max = Progress.total
            progress.progress = Progress.index
            status.text = getString(R.string.downloading, Progress.index, Progress.total) +
                "\n" + getString(R.string.keep_running)
            return
        }
        if (Progress.completedRuns == lastSeenRun) return
        lastSeenRun = Progress.completedRuns
        watching = false
        setBusy(false)
        val failure = Progress.error
        status.text = if (failure != null) {
            getString(R.string.failed, failure)
        } else {
            getString(R.string.done, Progress.saved, Progress.skipped, Progress.failed)
        }
        refreshUsb()
    }
}
