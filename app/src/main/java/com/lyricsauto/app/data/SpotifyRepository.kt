package com.lyricsauto.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class SpotifyTrack(
    val id: String,
    val name: String,
    val artist: String,
    val albumName: String,
    val albumArtUrl: String,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long
)

class SpotifyRepository(private val context: Context) {

    companion object {
        // TODO: Register your app at https://developer.spotify.com/dashboard and replace this value.
        private const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
        private const val REDIRECT_URI = "spotify-lyrics-auto://callback"
        private const val SCOPES =
            "user-read-currently-playing user-read-playback-state user-modify-playback-state"
        private const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
        private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
        private const val API_BASE = "https://api.spotify.com/v1"

        private const val PREF_NAME = "spotify_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CODE_VERIFIER = "code_verifier"

        private const val TAG = "SpotifyRepository"
    }

    private val httpClient = OkHttpClient()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ---- PKCE helpers ----

    fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAuthUrl(): String {
        check(CLIENT_ID != "YOUR_SPOTIFY_CLIENT_ID") {
            "CLIENT_ID is not configured. Please register your app at " +
                "https://developer.spotify.com/dashboard and update CLIENT_ID in SpotifyRepository.kt"
        }
        val verifier = generateCodeVerifier()
        prefs.edit().putString(KEY_CODE_VERIFIER, verifier).apply()
        val challenge = generateCodeChallenge(verifier)
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
            .toString()
    }

    // ---- Token management ----

    suspend fun exchangeCodeForTokens(code: String): Boolean = withContext(Dispatchers.IO) {
        val verifier = prefs.getString(KEY_CODE_VERIFIER, null) ?: return@withContext false
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext false
            if (!response.isSuccessful) {
                Log.e(TAG, "Token exchange failed: $responseBody")
                return@withContext false
            }
            val json = JSONObject(responseBody)
            saveTokens(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange exception", e)
            false
        }
    }

    private suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext false
            if (!response.isSuccessful) {
                Log.e(TAG, "Token refresh failed: $responseBody")
                return@withContext false
            }
            val json = JSONObject(responseBody)
            saveTokens(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh exception", e)
            false
        }
    }

    private fun saveTokens(json: JSONObject) {
        val accessToken = json.getString("access_token")
        val expiresIn = json.optLong("expires_in", 3600)
        val refreshToken = json.optString("refresh_token", prefs.getString(KEY_REFRESH_TOKEN, ""))
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
            .apply()
    }

    fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).remove(KEY_EXPIRES_AT).apply()
    }

    fun isConnected(): Boolean = prefs.getString(KEY_ACCESS_TOKEN, null) != null

    private suspend fun getValidAccessToken(): String? {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (System.currentTimeMillis() > expiresAt - 60_000) {
            if (!refreshAccessToken()) return null
        }
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    // ---- Spotify API ----

    suspend fun getCurrentlyPlaying(): SpotifyTrack? = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: return@withContext null
        val request = Request.Builder()
            .url("$API_BASE/me/player/currently-playing")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.code == 204) return@withContext null // Nothing playing
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) {
                Log.e(TAG, "getCurrentlyPlaying failed (${response.code}): $body")
                return@withContext null
            }
            parseCurrentlyPlaying(body)
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentlyPlaying exception", e)
            null
        }
    }

    private fun parseCurrentlyPlaying(json: String): SpotifyTrack? {
        return try {
            val obj = JSONObject(json)
            val isPlaying = obj.optBoolean("is_playing", false)
            val progressMs = obj.optLong("progress_ms", 0)
            val item = obj.optJSONObject("item") ?: return null
            val id = item.getString("id")
            val name = item.getString("name")
            val durationMs = item.getLong("duration_ms")
            val artists = item.getJSONArray("artists")
            val artist = artists.getJSONObject(0).getString("name")
            val album = item.getJSONObject("album")
            val albumName = album.getString("name")
            val images = album.getJSONArray("images")
            val albumArtUrl = if (images.length() > 0) images.getJSONObject(0).getString("url") else ""
            SpotifyTrack(id, name, artist, albumName, albumArtUrl, isPlaying, progressMs, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "parseCurrentlyPlaying exception", e)
            null
        }
    }

    suspend fun skipToNext(): Boolean = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: return@withContext false
        val emptyBody = ByteArray(0).toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("$API_BASE/me/player/next")
            .header("Authorization", "Bearer $token")
            .post(emptyBody)
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            response.isSuccessful || response.code == 204
        } catch (e: Exception) {
            Log.e(TAG, "skipToNext exception", e)
            false
        }
    }

    suspend fun skipToPrevious(): Boolean = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: return@withContext false
        val emptyBody = ByteArray(0).toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("$API_BASE/me/player/previous")
            .header("Authorization", "Bearer $token")
            .post(emptyBody)
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            response.isSuccessful || response.code == 204
        } catch (e: Exception) {
            Log.e(TAG, "skipToPrevious exception", e)
            false
        }
    }
}
