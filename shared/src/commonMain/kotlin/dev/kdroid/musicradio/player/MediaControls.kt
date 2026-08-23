package dev.kdroid.musicradio.player

import androidx.compose.runtime.Immutable

/**
 * What the OS media center can ask the app to do.
 *
 * A sealed hierarchy rather than an enum because two of them carry a number. The seeking pair is
 * only ever sent for a source that has a position to seek to; a live stream reports no duration,
 * and the media centres do not offer a scrubber for one.
 */
@Immutable
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object Toggle : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data object Stop : MediaCommand

    /** Relative, and signed: the OS asked to jump [offsetMs] from wherever playback is. */
    @Immutable
    data class SeekBy(val offsetMs: Long) : MediaCommand

    /** Absolute: the OS scrubber was dropped at [positionMs]. */
    @Immutable
    data class SetPosition(val positionMs: Long) : MediaCommand
}

/**
 * What the OS media center shows about the current stream.
 *
 * [artworkUri] points at a file the media center can open on its own - see [mediaArtworkUri].
 */
@Immutable
data class NowPlaying(val station: String = "", val title: String = "", val artist: String = "", val artworkUri: String? = null)

/**
 * The system media center — SMTC on Windows, MPRIS on Linux, Now Playing on macOS. Lets the
 * hardware media keys and the OS flyout drive the app, and puts the current stream where the OS
 * expects to find it.
 */
interface MediaControls {
    val available: Boolean

    /** Replaces any previous listener. */
    fun attach(onCommand: (MediaCommand) -> Unit)

    /**
     * [progress] is what turns the OS flyout's scrubber on. It is published on state changes and
     * after a seek, not on every tick: MPRIS and SMTC both interpolate the position themselves
     * from the last one they were given, and a four-times-a-second update would be pure traffic.
     */
    fun update(nowPlaying: NowPlaying, status: PlaybackStatus, progress: PlaybackProgress = PlaybackProgress())
    fun setVolume(percent: Int)
    fun release()
}

/** Stands in where the platform has no media center to talk to. */
object NoMediaControls : MediaControls {
    override val available: Boolean = false
    override fun attach(onCommand: (MediaCommand) -> Unit) = Unit
    override fun update(nowPlaying: NowPlaying, status: PlaybackStatus, progress: PlaybackProgress) = Unit
    override fun setVolume(percent: Int) = Unit
    override fun release() = Unit
}

expect fun createMediaControls(): MediaControls
