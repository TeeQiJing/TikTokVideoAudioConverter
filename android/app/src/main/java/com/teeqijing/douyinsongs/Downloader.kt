package com.teeqijing.douyinsongs

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams a song from Douyin's music CDN straight onto the pendrive.
 *
 * Douyin hands out the track as a finished MP3 (44.1 kHz stereo, 128 kbps
 * CBR), so there is nothing to transcode: the bytes go from the network to
 * the USB drive without ever being stored on the phone. The only rewriting
 * is the ID3 tag, so the car stereo shows a title instead of a filename.
 */
object Downloader {

    private const val TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024

    class HttpException(val code: Int) : Exception("HTTP $code")

    /**
     * Copies one song, trying each CDN host Douyin offered until one answers.
     *
     * [openOut] is only called once a connection is live, so a host that
     * cannot be reached never leaves a half-written file on the pendrive.
     */
    fun fetch(
        song: Capture.Song,
        openOut: () -> OutputStream,
        onBytes: (Long, Long) -> Unit,
    ) {
        var last: Exception? = null
        for (url in song.musicUrls) {
            try {
                fetchOne(url, song, openOut, onBytes)
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("no download URL")
    }

    private fun fetchOne(
        url: String,
        song: Capture.Song,
        openOut: () -> OutputStream,
        onBytes: (Long, Long) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://www.douyin.com/")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code)

            val total = conn.contentLengthLong
            conn.inputStream.use { raw ->
                val body = skipExistingTag(raw)
                // Only now, with bytes actually flowing, touch the pendrive.
                openOut().use { out ->
                    out.write(Id3.tagFor(song.title, song.artist))
                    val buf = ByteArray(BUFFER)
                    var done = 0L
                    while (true) {
                        val n = body.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes(done, total)
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Douyin's files usually carry their own ID3v2 tag. Skipping it keeps the
     * output to a single, correct tag rather than two stacked ones.
     */
    private fun skipExistingTag(input: InputStream): InputStream {
        val head = ByteArray(10)
        var read = 0
        while (read < 10) {
            val n = input.read(head, read, 10 - read)
            if (n <= 0) break
            read += n
        }
        if (read < 10) return PrefixStream(head.copyOf(read), input)

        val isId3 = head[0] == 'I'.code.toByte() &&
            head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        if (!isId3) return PrefixStream(head, input)

        // Size is a 28-bit "syncsafe" integer spread over four bytes.
        val size = ((head[6].toInt() and 0x7F) shl 21) or
            ((head[7].toInt() and 0x7F) shl 14) or
            ((head[8].toInt() and 0x7F) shl 7) or
            (head[9].toInt() and 0x7F)
        var toSkip = size.toLong()
        while (toSkip > 0) {
            val skipped = input.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        return input
    }

    /** Lets us "unread" the bytes we peeked at. */
    private class PrefixStream(
        private val prefix: ByteArray,
        private val rest: InputStream,
    ) : InputStream() {
        private var at = 0
        override fun read(): Int =
            if (at < prefix.size) prefix[at++].toInt() and 0xFF else rest.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at < prefix.size) {
                val n = minOf(len, prefix.size - at)
                System.arraycopy(prefix, at, b, off, n)
                at += n
                return n
            }
            return rest.read(b, off, len)
        }
    }

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
}

/** Minimal ID3v2.3 writer - just enough for a car stereo's display. */
object Id3 {

    fun tagFor(title: String, artist: String): ByteArray {
        val frames = ArrayList<Byte>()
        frames.addAll(frame("TIT2", title).toList())
        if (artist.isNotBlank()) frames.addAll(frame("TPE1", artist).toList())

        val body = frames.toByteArray()
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3  // v2.3
        header[4] = 0
        header[5] = 0  // no flags
        syncsafe(body.size).copyInto(header, 6)
        return header + body
    }

    /** UTF-16LE with a BOM: the encoding ID3v2.3 players handle most widely. */
    private fun frame(id: String, text: String): ByteArray {
        val payload = byteArrayOf(0x01) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE) +
            byteArrayOf(0, 0)
        val header = ByteArray(10)
        id.forEachIndexed { i, c -> header[i] = c.code.toByte() }
        val size = payload.size
        header[4] = (size ushr 24).toByte()
        header[5] = (size ushr 16).toByte()
        header[6] = (size ushr 8).toByte()
        header[7] = size.toByte()
        return header + payload
    }

    private fun syncsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
