package com.lyricsauto.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lyricsauto.app.data.SpotifyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles the OAuth 2.0 PKCE redirect callback from Spotify.
 * Launched when the browser redirects to spotify-lyrics-auto://callback.
 */
class SpotifyAuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SpotifyAuthActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data
        if (data == null || data.scheme != "spotify-lyrics-auto") {
            Log.w(TAG, "Unexpected intent data: $data")
            navigateToMain()
            return
        }

        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")

        when {
            error != null -> {
                Log.e(TAG, "OAuth error: $error")
                Toast.makeText(this, "Spotify authorization failed: $error", Toast.LENGTH_LONG).show()
                navigateToMain()
            }
            code != null -> {
                exchangeCode(code)
            }
            else -> {
                Log.w(TAG, "No code or error in redirect URI")
                navigateToMain()
            }
        }
    }

    private fun exchangeCode(code: String) {
        val repo = SpotifyRepository(this)
        CoroutineScope(Dispatchers.Main).launch {
            val success = repo.exchangeCodeForTokens(code)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@SpotifyAuthActivity, "Connected to Spotify!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SpotifyAuthActivity, "Failed to connect to Spotify.", Toast.LENGTH_LONG).show()
                }
                navigateToMain()
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}
