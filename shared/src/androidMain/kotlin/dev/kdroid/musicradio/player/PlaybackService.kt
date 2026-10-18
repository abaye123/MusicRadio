package dev.kdroid.musicradio.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.Commands
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
        session = MediaSession.Builder(this, LiveAwarePlayer(player)).build()
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

/**
 * Makes "play" mean *live* again on a source that has no position.
 *
 * ExoPlayer resumes a paused source out of its buffer, which is as old as the pause: pausing
 * through an ad break and coming back minutes later carried on in the middle of the ads. The app's
 * own play button asks for the URL again, but the notification, the lock screen and Android Auto
 * talk to this session directly and never reach the app - so the reset has to live on the player
 * the session actually holds.
 *
 * Only a source sitting paused and ready is reset. The app's own path prepares first and arrives
 * here while still buffering, so it is not prepared twice.
 */
private class LiveAwarePlayer(player: Player) : ForwardingPlayer(player) {
    override fun play() {
        // isCurrentMediaItemSeekable is false for exactly the sources with nothing to seek to -
        // an endless Icecast stream. Anything that reports a duration is left alone.
        if (playbackState == Player.STATE_READY && !playWhenReady && mediaItemCount > 0 && !isCurrentMediaItemSeekable) {
            stop()
            seekToDefaultPosition()
            prepare()
        }
        super.play()
    }

    // Skip commands. The session holds a single live item, so ExoPlayer has nothing to skip to and
    // never offers these commands. Without them the notification, the lock screen, Android Auto and
    // a headset's next/previous buttons have no way to change station. The commands are advertised
    // here and handed to the app, which owns the station list.

    override fun getAvailableCommands(): Commands = super.getAvailableCommands().withSkips()

    override fun isCommandAvailable(command: Int): Boolean = command in SKIP_COMMANDS || super.isCommandAvailable(command)

    override fun seekToNext() = StationSkips.dispatch(MediaCommand.Next)

    override fun seekToNextMediaItem() = StationSkips.dispatch(MediaCommand.Next)

    override fun seekToPrevious() = StationSkips.dispatch(MediaCommand.Previous)

    override fun seekToPreviousMediaItem() = StationSkips.dispatch(MediaCommand.Previous)

    // The session reads the command set off onAvailableCommandsChanged, not off
    // getAvailableCommands(), and ForwardingPlayer passes ExoPlayer's set along unchanged. Each time
    // ExoPlayer reported a change, the skip buttons would drop out of the notification again.
    private val wrapped = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapper = synchronized(wrapped) {
            if (listener in wrapped) return
            SkipAwareListener(listener).also { wrapped[listener] = it }
        }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(wrapped) { wrapped.remove(listener) } ?: return
        super.removeListener(wrapper)
    }

    /**
     * Every callback is forwarded by hand. Player.Listener is a Java interface made only of default
     * methods, and `by` delegation generates nothing for those: a delegating wrapper compiles to an
     * empty listener, and the session never heard that playback started.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "TooManyFunctions")
    private class SkipAwareListener(private val listener: Player.Listener) : Player.Listener {
        override fun onAvailableCommandsChanged(availableCommands: Commands) =
            listener.onAvailableCommandsChanged(availableCommands.withSkips())

        override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
            listener.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            listener.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(reason: Int) = listener.onPlaybackSuppressionReasonChanged(reason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(enabled: Boolean) = listener.onShuffleModeEnabledChanged(enabled)
        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = listener.onPlaybackParametersChanged(parameters)
        override fun onSeekBackIncrementChanged(incrementMs: Long) = listener.onSeekBackIncrementChanged(incrementMs)
        override fun onSeekForwardIncrementChanged(incrementMs: Long) = listener.onSeekForwardIncrementChanged(incrementMs)
        override fun onMaxSeekToPreviousPositionChanged(positionMs: Long) = listener.onMaxSeekToPreviousPositionChanged(positionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(attributes: AudioAttributes) = listener.onAudioAttributesChanged(attributes)
        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(enabled: Boolean) = listener.onSkipSilenceEnabledChanged(enabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = listener.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)
    }

    private companion object {
        val SKIP_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        fun Commands.withSkips(): Commands = buildUpon().addAll(*SKIP_COMMANDS).build()
    }
}
