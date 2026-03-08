package com.lyricsauto.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import com.lyricsauto.app.data.LyricsRepository
import com.lyricsauto.app.data.SpotifyRepository
import com.lyricsauto.app.databinding.ActivityMainBinding
import com.lyricsauto.app.service.LyricsMediaBrowserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var spotifyRepo: SpotifyRepository
    private lateinit var lyricsRepo: LyricsRepository

    private var mediaBrowser: MediaBrowserCompat? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spotifyRepo = SpotifyRepository(this)
        lyricsRepo = LyricsRepository()

        binding.btnConnect.setOnClickListener { launchSpotifyAuth() }
        binding.btnDisconnect.setOnClickListener { disconnectSpotify() }

        connectMediaBrowser()
    }

    override fun onResume() {
        super.onResume()
        updateUiForConnectionState()
        if (spotifyRepo.isConnected()) {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaBrowser?.disconnect()
    }

    // ---- Spotify OAuth ----

    private fun launchSpotifyAuth() {
        val url = spotifyRepo.buildAuthUrl()
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
    }

    private fun disconnectSpotify() {
        spotifyRepo.clearTokens()
        updateUiForConnectionState()
        binding.tvNowPlaying.setText(R.string.no_track_playing)
        binding.tvNowPlayingArtist.visibility = View.GONE
        binding.tvLyricsLabel.visibility = View.GONE
        binding.tvLyrics.visibility = View.GONE
        pollJob?.cancel()
    }

    // ---- UI state ----

    private fun updateUiForConnectionState() {
        val connected = spotifyRepo.isConnected()
        if (connected) {
            binding.tvStatus.setText(R.string.status_connected)
            binding.tvStatus.setTextColor(getColor(R.color.spotify_green))
            binding.btnConnect.visibility = View.GONE
            binding.btnDisconnect.visibility = View.VISIBLE
        } else {
            binding.tvStatus.setText(R.string.status_disconnected)
            binding.tvStatus.setTextColor(getColor(R.color.on_surface_variant))
            binding.btnConnect.visibility = View.VISIBLE
            binding.btnDisconnect.visibility = View.GONE
        }
    }

    // ---- Polling ----

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                refreshNowPlaying()
                delay(3_000L)
            }
        }
    }

    private suspend fun refreshNowPlaying() {
        val track = withContext(Dispatchers.IO) { spotifyRepo.getCurrentlyPlaying() }
        if (track == null) {
            binding.tvNowPlaying.setText(R.string.no_track_playing)
            binding.tvNowPlayingArtist.visibility = View.GONE
            binding.tvLyricsLabel.visibility = View.GONE
            binding.tvLyrics.visibility = View.GONE
            return
        }

        binding.tvNowPlaying.text = track.name
        binding.tvNowPlayingArtist.text = track.artist
        binding.tvNowPlayingArtist.visibility = View.VISIBLE
        binding.tvLyricsLabel.visibility = View.VISIBLE
        binding.tvLyrics.visibility = View.VISIBLE
        binding.tvLyrics.setText(R.string.lyrics_loading)

        val lyrics = withContext(Dispatchers.IO) { lyricsRepo.getLyrics(track.artist, track.name) }
        binding.tvLyrics.text = lyrics.joinToString("\n") { it.text }
    }

    // ---- MediaBrowser for Android Auto ----

    private fun connectMediaBrowser() {
        val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                mediaBrowser?.sessionToken?.let { token ->
                    MediaControllerCompat.setMediaController(this@MainActivity,
                        MediaControllerCompat(this@MainActivity, token))
                }
            }
        }
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, LyricsMediaBrowserService::class.java),
            connectionCallback,
            null
        )
        mediaBrowser?.connect()
    }
}
