# Add project specific ProGuard rules here.

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# org.json
-keep class org.json.** { *; }

# App data models
-keep class com.lyricsauto.app.data.SpotifyTrack { *; }
-keep class com.lyricsauto.app.data.LyricLine { *; }

# AndroidX Media
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }
