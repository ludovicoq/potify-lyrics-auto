package com.lyricsauto.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

class LyricsRepository {

    companion object {
        private const val LRCLIB_BASE = "https://lrclib.net/api/get"
        private const val TAG = "LyricsRepository"
    }

    private val httpClient = OkHttpClient()

    // In-memory cache: "artist|track" -> List<LyricLine>
    private val cache = HashMap<String, List<LyricLine>>()

    suspend fun getLyrics(artist: String, trackName: String): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val key = "$artist|$trackName"
            cache[key]?.let { return@withContext it }

            val url = buildUrl(artist, trackName)
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    Log.w(TAG, "Lyrics fetch failed (${response.code}) for $key")
                    val fallback = listOf(LyricLine(0L, "No lyrics found"))
                    cache[key] = fallback
                    return@withContext fallback
                }
                val lines = parseLyrics(body)
                cache[key] = lines
                lines
            } catch (e: Exception) {
                Log.e(TAG, "getLyrics exception for $key", e)
                val fallback = listOf(LyricLine(0L, "No lyrics found"))
                cache[key] = fallback
                fallback
            }
        }

    private fun buildUrl(artist: String, trackName: String): String {
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val encodedTrack = URLEncoder.encode(trackName, "UTF-8")
        return "$LRCLIB_BASE?artist_name=$encodedArtist&track_name=$encodedTrack"
    }

    private fun parseLyrics(json: String): List<LyricLine> {
        return try {
            val obj = JSONObject(json)
            // Prefer synced lyrics
            val syncedLyrics = obj.optString("syncedLyrics", "")
            if (syncedLyrics.isNotBlank()) {
                return parseSyncedLyrics(syncedLyrics)
            }
            // Fall back to plain lyrics
            val plainLyrics = obj.optString("plainLyrics", "")
            if (plainLyrics.isNotBlank()) {
                return parsePlainLyrics(plainLyrics)
            }
            listOf(LyricLine(0L, "No lyrics found"))
        } catch (e: Exception) {
            Log.e(TAG, "parseLyrics exception", e)
            listOf(LyricLine(0L, "No lyrics found"))
        }
    }

    /**
     * Parse LRC-format synced lyrics.
     * Each line looks like: [mm:ss.xx] lyric text
     */
    private fun parseSyncedLyrics(lrc: String): List<LyricLine> {
        val timeRegex = Regex("""\[(\d+):(\d+\.\d+)\](.*)""")
        val lines = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val match = timeRegex.find(raw.trim()) ?: continue
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val timeMs = minutes * 60_000 + (seconds * 1000).toLong()
            val text = match.groupValues[3].trim()
            if (text.isNotEmpty()) {
                lines.add(LyricLine(timeMs, text))
            }
        }
        return if (lines.isEmpty()) listOf(LyricLine(0L, "No lyrics found")) else lines
    }

    private fun parsePlainLyrics(plain: String): List<LyricLine> {
        var timeMs = 0L
        return plain.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val lyricLine = LyricLine(timeMs, line.trim())
                timeMs += 3000 // Assign incremental timestamps for plain lyrics
                lyricLine
            }
            .ifEmpty { listOf(LyricLine(0L, "No lyrics found")) }
    }

    fun clearCache() = cache.clear()
}
