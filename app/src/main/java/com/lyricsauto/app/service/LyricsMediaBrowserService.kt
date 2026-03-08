package com.lyricsauto.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.lyricsauto.app.MainActivity
import com.lyricsauto.app.R
import com.lyricsauto.app.data.LyricsRepository
import com.lyricsauto.app.data.SpotifyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LyricsMediaBrowserService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "LyricsMediaBrowserSvc"
        private const val MEDIA_ROOT_ID = "root"
        private const val MEDIA_NOW_PLAYING_ID = "now_playing"
        private const val MEDIA_LYRICS_ID = "lyrics"
        private const val LYRICS_ITEM_PREFIX = "lyric_"
        private const val CHANNEL_ID = "lyrics_auto_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 3_000L
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var spotifyRepo: SpotifyRepository
    private lateinit var lyricsRepo: LyricsRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var pollJob: Job? = null
    private var currentTrackId: String? = null
    private var currentLyrics: List<com.lyricsauto.app.data.LyricLine> = emptyList()

    override fun onCreate() {
        super.onCreate()
        spotifyRepo = SpotifyRepository(this)
        lyricsRepo = LyricsRepository()

        createNotificationChannel()

        // Build MediaSession
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(sessionActivityPendingIntent)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(MediaSessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        serviceJob.cancel()
        pollJob?.cancel()
    }

    // ---- MediaBrowserService ----

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        when (parentId) {
            MEDIA_ROOT_ID -> result.sendResult(buildRootItems())
            MEDIA_NOW_PLAYING_ID -> result.sendResult(buildNowPlayingItems())
            MEDIA_LYRICS_ID -> result.sendResult(buildLyricsItems())
            else -> result.sendResult(emptyList())
        }
    }

    private fun buildRootItems(): List<MediaBrowserCompat.MediaItem> {
        return listOf(
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(MEDIA_NOW_PLAYING_ID)
                    .setTitle(getString(R.string.now_playing_section))
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            ),
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(MEDIA_LYRICS_ID)
                    .setTitle(getString(R.string.lyrics_section))
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
        )
    }

    private fun buildNowPlayingItems(): List<MediaBrowserCompat.MediaItem> {
        val meta = mediaSession.controller.metadata ?: return emptyList()
        val title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: return emptyList()
        val artist = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
        return listOf(
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("current_track")
                    .setTitle(title)
                    .setSubtitle(artist)
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
    }

    private fun buildLyricsItems(): List<MediaBrowserCompat.MediaItem> {
        if (currentLyrics.isEmpty()) return emptyList()
        return currentLyrics.mapIndexed { index, lyricLine ->
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("$LYRICS_ITEM_PREFIX$index")
                    .setTitle(lyricLine.text)
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }
    }

    // ---- Polling ----

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (true) {
                pollSpotify()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollSpotify() {
        if (!spotifyRepo.isConnected()) return
        try {
            val track = spotifyRepo.getCurrentlyPlaying() ?: return
            updateMediaSession(track)

            if (track.id != currentTrackId) {
                currentTrackId = track.id
                val lyrics = lyricsRepo.getLyrics(track.artist, track.name)
                currentLyrics = lyrics
                notifyChildrenChanged(MEDIA_LYRICS_ID)
                updateNotification("${track.name} — ${track.artist}")
            }
            notifyChildrenChanged(MEDIA_NOW_PLAYING_ID)
        } catch (e: Exception) {
            Log.e(TAG, "pollSpotify exception", e)
        }
    }

    private fun updateMediaSession(track: com.lyricsauto.app.data.SpotifyTrack) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.albumName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            .build()
        mediaSession.setMetadata(metadata)

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(
                if (track.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                track.progressMs,
                1f
            )
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---- MediaSession callbacks ----

    inner class MediaSessionCallback : MediaSessionCompat.Callback() {

        override fun onPlay() {
            // Spotify manages its own playback; skip/toggle handled by Spotify app
        }

        override fun onPause() {
            // Spotify manages its own playback
        }

        override fun onSkipToNext() {
            serviceScope.launch {
                spotifyRepo.skipToNext()
            }
        }

        override fun onSkipToPrevious() {
            serviceScope.launch {
                spotifyRepo.skipToPrevious()
            }
        }
    }
}
