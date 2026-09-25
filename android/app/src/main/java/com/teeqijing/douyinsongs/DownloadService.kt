package com.teeqijing.douyinsongs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.Collections
import me.jahnen.libaums.core.fs.UsbFile

/**
 * Runs the download on its own, so it survives the screen turning off and
 * the user switching to another app.
 *
 * Downloading a large 收藏夹 takes minutes. Doing that from the activity
 * meant Android suspended the transfer the moment the screen locked, which
 * is exactly when someone would put the phone down and wait. A foreground
 * service plus a wake lock is the supported way to keep it going.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.teeqijing.douyinsongs.START"
        const val ACTION_CANCEL = "com.teeqijing.douyinsongs.CANCEL"
        const val EXTRA_FOLDER_ID = "folder_id"

        /** Reconnect attempts before a song is given up on. */
        private const val USB_RETRIES = 3
        /** Time for the drive to settle before reopening it. */
        private const val USB_SETTLE_MS = 2000L
        /**
         * How long to keep trying to pick the drive back up. A drop makes it
         * re-enumerate as a different device, which takes a few seconds, so
         * a couple of quick retries would give up while it is still coming
         * back.
         */
        private const val RECONNECT_WINDOW_MS = 25_000L
        /** A breather between songs; back-to-back writes seem to upset some drives. */
        private const val BETWEEN_SONGS_MS = 250L

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, folderId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FOLDER_ID, folderId)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java)
                .apply { action = ACTION_CANCEL })
        }
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Progress.cancelled = true
            return START_NOT_STICKY
        }
        val folderId = intent?.getStringExtra(EXTRA_FOLDER_ID)
        if (folderId == null || worker != null) return START_NOT_STICKY

        startForegroundCompat(notification(getString(R.string.notif_preparing), 0, 0))
        acquireWakeLock()

        worker = Thread { runDownload(folderId) }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    // ---------------------------------------------------------------- work

    private fun runDownload(folderId: String) {
        val songs = Capture.songs(folderId)
        Progress.begin(songs.size)
        try {
            var dir = openWithRetry()
            val existing = Usb.existingNames(dir)
            for ((index, song) in songs.withIndex()) {
                if (Progress.cancelled) break
                Progress.index = index + 1
                val name = song.fileName()
                update(getString(R.string.notif_downloading,
                    index + 1, songs.size), index, songs.size)

                if (name in existing) {
                    Progress.skipped++
                    Progress.log("- 已有：$name")
                    continue
                }
                Thread.sleep(BETWEEN_SONGS_MS)

                var attempt = 0
                while (true) {
                    try {
                        Downloader.fetch(song, { Usb.newFile(dir, name) }) { _, _ -> }
                        Progress.saved++
                        Progress.log("✓ $name")
                        break
                    } catch (e: Exception) {
                        // Never leave a partial file behind: it would be big
                        // enough to count as "already downloaded" next run.
                        runCatching { Usb.discard(dir, name) }
                        // A dropped drive kills the handle the whole run
                        // shares, so without reconnecting here every
                        // remaining song fails for the same reason.
                        if (looksLikeUsbDrop(e) && attempt < USB_RETRIES) {
                            attempt++
                            Progress.log("… U 盘连接中断，正在重新连接（$attempt）")
                            val reopened = reopenDrive()
                            if (reopened == null) {
                                Progress.error = getString(R.string.usb_lost)
                                return
                            }
                            dir = reopened
                            continue
                        }
                        Progress.failed++
                        Progress.log("✗ $name  (${e.message})")
                        break
                    }
                }
            }
        } catch (e: Usb.NeedsPermissionException) {
            Progress.error = getString(R.string.usb_needs_permission)
        } catch (e: Usb.CannotOpenException) {
            Progress.error = getString(R.string.usb_cannot_open)
        } catch (e: Exception) {
            Progress.error = e.message ?: e.javaClass.simpleName
        } finally {
            Usb.close()
            Progress.finish()
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** Opens the drive, allowing for it still settling after a plug-in. */
    private fun openWithRetry(): UsbFile {
        var last: Exception? = null
        repeat(USB_RETRIES) { attempt ->
            try {
                return Usb.openSongsDir(this)
            } catch (e: Usb.CannotOpenException) {
                last = e
                Usb.close()
                Thread.sleep(USB_SETTLE_MS)
            }
        }
        throw last ?: Usb.CannotOpenException(IllegalStateException("unknown"))
    }

    /** Does this look like the drive falling off the bus, not a bad download? */
    private fun looksLikeUsbDrop(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val text = cause.message.orEmpty().lowercase()
            if (text.contains("recovery") || text.contains("reattach") ||
                text.contains("transfer command") || text.contains("endpoint")) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * Waits for the drive to come back and reopens it.
     *
     * After a drop it reappears as a new USB device, so this waits for it to
     * be present and permitted again rather than failing on the first try.
     */
    private fun reopenDrive(): UsbFile? {
        runCatching { Usb.close() }
        val deadline = System.currentTimeMillis() + RECONNECT_WINDOW_MS
        while (System.currentTimeMillis() < deadline && !Progress.cancelled) {
            Thread.sleep(USB_SETTLE_MS)
            if (!Usb.isAttached(this) || !Usb.hasPermission(this)) continue
            val dir = runCatching { Usb.openSongsDir(this) }.getOrNull()
            if (dir != null) return dir
            runCatching { Usb.close() }
        }
        return null
    }

    // -------------------------------------------------------- housekeeping

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "DouyinSongs:download").apply {
            setReferenceCounted(false)
            // Long enough for a big collection, but bounded so a crash can
            // never leave the CPU held awake.
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Already released.
        }
        wakeLock = null
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun update(text: String, done: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text, done, total))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 &&
            manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) }
            .addAction(Notification.Action.Builder(
                null, getString(R.string.notif_stop), stop).build())
            .build()
    }
}

/**
 * Shared state between the service doing the work and the screen showing it.
 * Deliberately plain: the activity polls this a few times a second, which is
 * all a progress line needs and avoids tying the work to any UI lifecycle.
 */
object Progress {

    @Volatile var running = false
    @Volatile var cancelled = false
    @Volatile var index = 0
    @Volatile var total = 0
    @Volatile var saved = 0
    @Volatile var skipped = 0
    @Volatile var failed = 0
    @Volatile var error: String? = null

    /** Bumped when a run ends, so the screen can react exactly once. */
    @Volatile var completedRuns = 0

    private val lines = Collections.synchronizedList(ArrayList<String>())

    fun begin(count: Int) {
        running = true
        cancelled = false
        index = 0
        total = count
        saved = 0
        skipped = 0
        failed = 0
        error = null
        lines.clear()
    }

    fun finish() {
        running = false
        completedRuns++
    }

    fun log(line: String) {
        lines.add(line)
    }

    fun lines(): List<String> = ArrayList(lines)
}
