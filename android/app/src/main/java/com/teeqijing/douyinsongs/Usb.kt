package com.teeqijing.douyinsongs

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import java.io.OutputStream
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream

/**
 * Talks to a pendrive plugged into the phone's USB-C port.
 *
 * This goes through libaums rather than the system file picker, because it
 * can find the drive by itself the moment it is plugged in - the user never
 * has to hunt for it in a folder dialog. The trade-off is FAT32 only, which
 * is what car stereos want anyway.
 */
object Usb {

    const val SONGS_DIR = "DouyinSongs"

    /** Anything smaller than this is a leftover stub, not a song. */
    private const val MIN_SONG_BYTES = 16 * 1024L
    const val ACTION_PERMISSION = "com.teeqijing.douyinsongs.USB_PERMISSION"

    class NoDriveException : Exception("No pendrive found")
    class NeedsPermissionException : Exception("USB permission not granted")
    class UnsupportedFormatException(cause: Throwable) :
        Exception("Pendrive is not FAT32", cause)

    private var device: UsbMassStorageDevice? = null

    /** A pendrive is physically attached (permission may still be missing). */
    fun isAttached(context: Context): Boolean =
        UsbMassStorageDevice.getMassStorageDevices(context).isNotEmpty()

    fun hasPermission(context: Context): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbMassStorageDevice.getMassStorageDevices(context)
            .any { manager.hasPermission(it.usbDevice) }
    }

    fun requestPermission(context: Context) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (devices.isEmpty()) return
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        manager.requestPermission(devices.first().usbDevice, intent)
    }

    /** Opens the drive and returns the folder songs are written into. */
    @Synchronized
    fun openSongsDir(context: Context): UsbFile {
        close()
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull()
            ?: throw NoDriveException()
        if (!manager.hasPermission(dev.usbDevice)) throw NeedsPermissionException()

        try {
            dev.init()
        } catch (e: Exception) {
            throw UnsupportedFormatException(e)
        }
        device = dev

        val partition = dev.partitions.firstOrNull()
            ?: throw UnsupportedFormatException(IllegalStateException("no partition"))
        val root = partition.fileSystem.rootDirectory
        return root.search(SONGS_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(SONGS_DIR)
    }

    /** Free space in bytes, or -1 when it cannot be read. */
    fun freeBytes(): Long = try {
        device?.partitions?.firstOrNull()?.fileSystem?.freeSpace ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /**
     * Names already on the drive, ignoring anything too small to be a real
     * song. A download that died part-way leaves a stub behind, and treating
     * that as "already have it" would hide the failure forever.
     */
    fun existingNames(dir: UsbFile): Set<String> = try {
        dir.listFiles()
            .filter { !it.isDirectory && it.length >= MIN_SONG_BYTES }
            .map { it.name }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Replaces any existing file of the same name. */
    fun newFile(dir: UsbFile, name: String): OutputStream {
        dir.search(name)?.delete()
        return UsbFileOutputStream(dir.createFile(name))
    }

    @Synchronized
    fun close() {
        try {
            device?.close()
        } catch (e: Exception) {
            // Nothing useful to do if the drive was already pulled out.
        }
        device = null
    }
}
