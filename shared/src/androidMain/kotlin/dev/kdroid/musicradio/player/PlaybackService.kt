package dev.kdroid.musicradio.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Holds the one ExoPlayer the app ever creates, wrapped in a [MediaSession].
 *
 * Playback lives in a service rather than in the activity for the reason radio needs most: the
 * screen going off must not stop the sound. The session is what gives Android the notification,
 * the lock screen controls and the hardware media keys, all of it for free.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // The second argument asks ExoPlayer to take audio focus, so a call or another
            // player pauses the radio and it resumes afterwards instead of talking over them.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            // Unplugging headphones pauses rather than blasting the speaker.
            .setHandleAudioBecomingNoisy(true)
            // Keeps CPU and wifi awake while playing; without it a live stream dies moments
            // after the screen turns off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // ICY declares no charset, so ExoPlayer reads the Hebrew stations that send windows-1255
        // as UTF-8 and produces mojibake, which then overrides whatever the app set. The app
        // decodes those titles correctly itself, so the in-stream metadata track is switched off
        // and the notification shows only what the app supplies.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away should stop the radio, but only when it is not actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
