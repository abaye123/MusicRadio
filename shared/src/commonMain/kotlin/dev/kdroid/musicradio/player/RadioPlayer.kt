package dev.kdroid.musicradio.player

import androidx.compose.runtime.Immutable
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
 * Where playback has got to, for the sources that have anywhere to get to.
 *
 * A live stream reports [seekable] `false` and a [durationMs] of zero for its whole life, which is
 * what every radio surface keys on to stay exactly as it was before on-demand audio existed. Only
 * a recorded shiur ever answers otherwise.
 */
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0,
    /** Zero while unknown, and for good on a live stream. */
    val durationMs: Long = 0,
    val seekable: Boolean = false,
) {
    /** `0f` until the duration is known, so a progress bar starts empty rather than full. */
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/**
 * The app's view of playback. Two kinds of source run through it: a live stream, which has neither
 * a position nor an end, and a recorded shiur, which has both. The distinction is not sniffed from
 * the URL - the caller states it in [play], because the app already knows which of the two it asked
 * for and guessing would only be wrong at the edges.
 */
interface RadioPlayer {
    val status: StateFlow<PlaybackStatus>

    /** [PlaybackProgress.seekable] is `false` and the rest zero unless a seekable source is playing. */
    val progress: StateFlow<PlaybackProgress>

    /**
     * Opens [url] and starts playing. Replaces whatever was playing before.
     *
     * [startAtMs] is honoured only when [seekable]; a live stream has no position to start from.
     * The seek may not be possible until the source has opened, so it is applied as soon as the
     * backend can accept it rather than synchronously here.
     */
    fun play(url: String, startAtMs: Long = 0, seekable: Boolean = false)
    fun resume()
    fun pause()
    fun stop()

    /** Ignored unless a seekable source is playing. Clamped to the source by the implementation. */
    fun seekTo(positionMs: Long)

    /** 0..100, matching what the volume slider shows. */
    fun setVolume(percent: Int)
    fun release()

    /**
     * What the OS should show for the current stream. Supplied by the app rather than read from
     * the stream by the platform, because ICY carries no charset and the Hebrew stations that
     * send windows-1255 come out as mojibake wherever it is assumed to be UTF-8.
     *
     * [artworkUri] is the file [mediaArtworkUri] produced. Android puts it on the MediaSession so
     * the notification and lock screen can show the cover; desktop ignores it here and goes
     * through [MediaControls] instead.
     *
     * A no-op where the platform has nothing to display it on.
     */
    fun setNowPlaying(station: String, song: String, artworkUri: String? = null) = Unit
}

/** Stands in wherever a real audio backend cannot run - unit tests, previews. */
class SilentRadioPlayer : RadioPlayer {
    private val _status = MutableStateFlow(PlaybackStatus.Idle)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    override fun play(url: String, startAtMs: Long, seekable: Boolean) {
        // No clock runs here, but the position still has to move where the caller put it, or a
        // test cannot tell a resumed shiur from one that started over.
        _progress.value = PlaybackProgress(
            positionMs = if (seekable) startAtMs.coerceAtLeast(0) else 0,
            durationMs = 0,
            seekable = seekable,
        )
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
        _progress.value = PlaybackProgress()
    }

    override fun seekTo(positionMs: Long) {
        val current = _progress.value
        if (!current.seekable) return
        _progress.value = current.copy(positionMs = positionMs.coerceAtLeast(0))
    }

    override fun setVolume(percent: Int) = Unit

    override fun release() {
        _status.value = PlaybackStatus.Idle
        _progress.value = PlaybackProgress()
    }
}
