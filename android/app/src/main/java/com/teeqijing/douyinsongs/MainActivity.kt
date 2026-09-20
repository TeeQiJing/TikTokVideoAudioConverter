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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        setContentView(buildUi())
        refreshFolderLabel()

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
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbWatcher) }
        Usb.close()
    }

    // ------------------------------------------------------------------ UI

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
        val songs = Capture.songs(folderId)
        if (songs.isEmpty()) {
            status.text = getString(R.string.no_songs_found)
            return
        }
        setBusy(true)
        status.text = getString(R.string.downloading, 0, songs.size)

        lifecycleScope.launch {
            var saved = 0
            var skipped = 0
            var failed = 0
            try {
                withContext(Dispatchers.IO) {
                    val dir = Usb.openSongsDir(this@MainActivity)
                    val existing = Usb.existingNames(dir)
                    songs.forEachIndexed { index, song ->
                        val name = song.fileName()
                        withContext(Dispatchers.Main) {
                            status.text = getString(
                                R.string.downloading, index + 1, songs.size)
                            progress.max = songs.size
                            progress.progress = index
                        }
                        if (name in existing) {
                            skipped++
                            log("- 已有：$name")
                            return@forEachIndexed
                        }
                        try {
                            Downloader.fetch(song, { Usb.newFile(dir, name) }) { _, _ -> }
                            saved++
                            log("✓ $name")
                        } catch (e: Exception) {
                            failed++
                            log("✗ $name  (${e.message})")
                        }
                    }
                }
                status.text = getString(R.string.done, saved, skipped, failed)
            } catch (e: Usb.NeedsPermissionException) {
                status.text = getString(R.string.usb_needs_permission)
                Usb.requestPermission(this@MainActivity)
            } catch (e: Usb.UnsupportedFormatException) {
                status.text = getString(R.string.usb_not_fat32)
            } catch (e: Exception) {
                status.text = getString(R.string.failed, e.message ?: "")
            } finally {
                Usb.close()
                setBusy(false)
                progress.progress = progress.max
                refreshUsb()
            }
        }
    }
}
