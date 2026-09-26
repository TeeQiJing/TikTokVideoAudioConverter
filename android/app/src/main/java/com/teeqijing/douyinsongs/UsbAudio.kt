package com.teeqijing.douyinsongs

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import java.nio.ByteBuffer
import me.jahnen.libaums.core.fs.UsbFile

/**
 * Lets the system audio player read a song directly off the pendrive.
 *
 * The drive is handled as raw blocks, so its files have no path and no
 * content URI - MediaPlayer cannot open them the usual way. Copying a song
 * to the phone first would work, but the whole point of this app is that
 * songs never land on the phone. A MediaDataSource closes that gap: the
 * player pulls the bytes it wants, when it wants them, straight from USB.
 */
class UsbAudioSource(private val file: UsbFile) : MediaDataSource() {

    private val total = file.length

    override fun getSize(): Long = total

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        val count = minOf(size.toLong(), total - position).toInt()
        if (count <= 0) return -1
        // The drive is a single serial device; the browser, the player and a
        // download must never be talking to it at the same moment.
        synchronized(Usb.ioLock) {
            file.read(position, ByteBuffer.wrap(buffer, offset, count))
        }
        return count
    }

    override fun close() {
        // The UsbFile belongs to the listing, not to this reader.
    }
}

/**
 * A single-song player for previewing what was just downloaded.
 *
 * Deliberately one song at a time: the drive cannot serve two readers, and
 * one player is all the screen ever shows.
 */
class SongPlayer {

    private var player: MediaPlayer? = null
    var playingPath: String? = null
        private set

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    val durationMs: Int
        get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    val positionMs: Int
        get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** Prepares [file] and starts it. [onReady] fires once the length is known. */
    fun play(file: UsbFile, onReady: () -> Unit, onError: (String) -> Unit) {
        stop()
        playingPath = file.absolutePath
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                setDataSource(UsbAudioSource(file))
                setOnPreparedListener {
                    it.start()
                    onReady()
                }
                setOnErrorListener { _, what, extra ->
                    onError("$what/$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            playingPath = null
            onError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume() {
        runCatching { player?.start() }
    }

    /** Moves by [deltaMs], clamped to the song. */
    fun nudge(deltaMs: Int) {
        runCatching {
            val p = player ?: return
            p.seekTo((p.currentPosition + deltaMs).coerceIn(0, p.duration))
        }
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms) }
    }

    fun stop() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
        playingPath = null
    }
}

/** Reads how long a song is without playing it. Slow-ish: call off the UI thread. */
fun readDurationMs(file: UsbFile): Int {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(UsbAudioSource(file))
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    } finally {
        runCatching { retriever.release() }
    }
}

/** 0:07, 3:45, 1:02:30 - whichever is shortest while staying clear. */
fun formatTime(ms: Int): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
