package com.teeqijing.douyinsongs

import android.util.Log
import org.json.JSONObject

/**
 * Holds the collection JSON captured from Douyin's own web pages.
 *
 * Douyin's web API refuses requests that its JavaScript has not signed, and a
 * 收藏夹 has no shareable link at all, so the listing cannot be fetched
 * directly. Instead the app opens douyin.com in a WebView the user has logged
 * into and records the responses the page fetches for itself. Everything
 * needed is in those responses - including, for each saved video, a direct
 * URL to the song as an MP3.
 */
object Capture {

    const val FOLDERS_EP = "collects/list"
    const val VIDEOS_EP = "collects/video/list"

    data class Folder(val id: String, val name: String, val count: Int)

    data class Song(
        val awemeId: String,
        val title: String,
        val artist: String,
        // Douyin lists the same track on two CDN hosts; one of them can be
        // unresolvable from a given network, so keep both and fall back.
        val musicUrls: List<String>,
    ) {
        /** A filename a car stereo will not choke on. */
        fun fileName(): String {
            val safe = buildString {
                for (c in "$title - $artist") {
                    append(if (c.isLetterOrDigit() || c in " -_()（）【】") c else '_')
                }
            }.trim().replace(Regex("_{2,}"), "_").trim('_', ' ').take(60)
            return (if (safe.isBlank()) awemeId else safe) + ".mp3"
        }
    }

    private val folderById = linkedMapOf<String, Folder>()
    private val songsByFolder = linkedMapOf<String, LinkedHashMap<String, Song>>()

    private val moreByFolder = linkedMapOf<String, Boolean>()

    @Synchronized
    fun reset() {
        folderById.clear()
        songsByFolder.clear()
        moreByFolder.clear()
    }

    @Synchronized
    fun folders(): List<Folder> = folderById.values.toList()

    @Synchronized
    fun songs(folderId: String): List<Song> =
        songsByFolder[folderId]?.values?.toList() ?: emptyList()

    @Synchronized
    fun songCount(folderId: String): Int = songsByFolder[folderId]?.size ?: 0

    /** Whether Douyin says this folder has more pages still to load. */
    @Synchronized
    fun hasMore(folderId: String): Boolean = moreByFolder[folderId] ?: true

    /**
     * Feed one captured response in. [url] decides how it is read; anything
     * unrecognised or malformed is ignored, because the page fetches plenty
     * of other things we do not care about.
     */
    @Synchronized
    fun accept(url: String, body: String) {
        try {
            when {
                url.contains(FOLDERS_EP) -> readFolders(body)
                url.contains(VIDEOS_EP) -> readVideos(url, body)
            }
        } catch (e: Exception) {
            Log.w("Capture", "could not read ${url.take(60)}", e)
        }
    }

    private fun readFolders(body: String) {
        val list = JSONObject(body).optJSONArray("collects_list") ?: return
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("collects_id_str").ifBlank { c.optString("collects_id") }
            if (id.isBlank()) continue
            folderById[id] = Folder(
                id = id,
                name = c.optString("collects_name").ifBlank { "(未命名)" },
                // Counts saved slots, so it can exceed what Douyin still serves.
                count = c.optInt("total_number", 0),
            )
        }
    }

    private fun readVideos(url: String, body: String) {
        val root = JSONObject(body)
        val folderId = Regex("collects_id=(\\d+)").find(url)?.groupValues?.get(1) ?: return
        moreByFolder[folderId] = root.optInt("has_more", 0) == 1

        val items = root.optJSONArray("aweme_list") ?: return
        val bucket = songsByFolder.getOrPut(folderId) { linkedMapOf() }
        for (i in 0 until items.length()) {
            val a = items.optJSONObject(i) ?: continue
            val awemeId = a.optString("aweme_id")
            if (awemeId.isBlank()) continue
            val music = a.optJSONObject("music")
            val urls = music?.optJSONObject("play_url")?.optJSONArray("url_list")
            val musicUrls = buildList {
                for (u in 0 until (urls?.length() ?: 0)) {
                    urls?.optString(u)?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            if (musicUrls.isEmpty()) continue  // nothing downloadable for this one

            val rawTitle = a.optString("desc").ifBlank { music?.optString("title").orEmpty() }
            bucket[awemeId] = Song(
                awemeId = awemeId,
                title = cleanTitle(rawTitle).ifBlank { awemeId },
                artist = music?.optString("author").orEmpty()
                    .ifBlank { a.optJSONObject("author")?.optString("nickname").orEmpty() },
                musicUrls = musicUrls,
            )
        }
    }

    /** Douyin descriptions are mostly hashtags; keep the human part. */
    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("#\\S+"), " ")
            .replace(Regex("@\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(45)
}
