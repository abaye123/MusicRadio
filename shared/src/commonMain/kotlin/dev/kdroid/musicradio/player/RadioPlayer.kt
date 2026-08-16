package dev.kdroid.musicradio.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackStatus {
    Idle,
    Buffering,
    Playing,
    Paused,
    Error,
    ;

    val active: Boolean get() = this == Playing || this == Buffering
}

/**
 * The app's view of playback: a live stream either runs or it does not. Position and duration are
 * deliberately absent - every source here is a continuous broadcast with neither.
 */
interface RadioPlayer {
    val status: StateFlow<PlaybackStatus>

    /** Opens [url] and starts playing. Replaces whatever was playing before. */
    fun play(url: String)
    fun resume()
    fun pause()
    fun stop()

    /** 0..100, matching what the volume slider shows. */
    fun setVolume(percent: Int)
    fun release()

    /**
     * What the OS should show for the current stream. Supplied by the app rather than read from
     * the stream by the platform, because ICY carries no charset and the Hebrew stations that
     * send windows-1255 come out as mojibake wherever it is assumed to be UTF-8.
     *
     * A no-op where the platform has nothing to display it on.
     */
    fun setNowPlaying(station: String, song: String) = Unit
}

/** Stands in wherever a real audio backend cannot run - unit tests, previews. */
class SilentRadioPlayer : RadioPlayer {
    private val _status = MutableStateFlow(PlaybackStatus.Idle)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    override fun play(url: String) {
        _status.value = PlaybackStatus.Playing
    }

    override fun resume() {
        _status.value = PlaybackStatus.Playing
    }

    override fun pause() {
        _status.value = PlaybackStatus.Paused
    }

    override fun stop() {
        _status.value = PlaybackStatus.Idle
    }

    override fun setVolume(percent: Int) = Unit

    override fun release() {
        _status.value = PlaybackStatus.Idle
    }
}
