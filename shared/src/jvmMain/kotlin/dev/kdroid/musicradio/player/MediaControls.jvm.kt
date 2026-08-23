package dev.kdroid.musicradio.player

import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaControlService
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus

actual fun createMediaControls(): MediaControls = if (MediaControlService.isAvailable()) NucleusMediaControls() else NoMediaControls

private class NucleusMediaControls : MediaControls {
    override val available: Boolean = true

    init {
        MediaControlService.configure(dbusName = MPRIS_BUS_NAME, displayName = APP_DISPLAY_NAME)
    }

    override fun attach(onCommand: (MediaCommand) -> Unit) {
        MediaControlService.attach { event ->
            val command = when (event) {
                MediaControlEvent.Play -> MediaCommand.Play

                MediaControlEvent.Pause -> MediaCommand.Pause

                MediaControlEvent.Toggle -> MediaCommand.Toggle

                MediaControlEvent.Next -> MediaCommand.Next

                MediaControlEvent.Previous -> MediaCommand.Previous

                MediaControlEvent.Stop -> MediaCommand.Stop

                // Forwarded unconditionally. The app drops them when nothing seekable is playing,
                // and it is the one place that knows - the media centre only knows what it was
                // last told, which may be a stream that has since been replaced.
                is MediaControlEvent.SeekBy -> MediaCommand.SeekBy(event.offsetMs)

                is MediaControlEvent.SetPosition -> MediaCommand.SetPosition(event.positionMs)

                // Volume and window commands are the OS's business, not ours.
                else -> null
            }
            if (command != null) onCommand(command)
        }
    }

    override fun update(nowPlaying: NowPlaying, status: PlaybackStatus, progress: PlaybackProgress) {
        MediaControlService.setMetadata(
            MediaMetadata(
                title = nowPlaying.title.ifBlank { nowPlaying.station }.takeIf { it.isNotBlank() },
                artist = nowPlaying.artist.takeIf { it.isNotBlank() },
                album = nowPlaying.station.takeIf { it.isNotBlank() },
                coverUrl = nowPlaying.artworkUri,
                // Null for a live stream, which has no length, and the OS hides the scrubber.
                // A shiur reports its real one and gets a scrubber that works.
                duration = progress.durationMs.takeIf { progress.seekable && it > 0 },
            ),
        )
        MediaControlService.setPlaybackState(
            MediaPlaybackState(
                status = when (status) {
                    PlaybackStatus.Playing, PlaybackStatus.Buffering -> MediaPlaybackStatus.PLAYING
                    PlaybackStatus.Paused -> MediaPlaybackStatus.PAUSED
                    PlaybackStatus.Idle, PlaybackStatus.Error -> MediaPlaybackStatus.STOPPED
                },
                positionMs = progress.positionMs.takeIf { progress.seekable },
            ),
        )
    }

    override fun setVolume(percent: Int) {
        MediaControlService.setVolume(percent.coerceIn(0, 100) / 100.0)
    }

    override fun release() {
        MediaControlService.detach()
    }

    private companion object {
        const val APP_DISPLAY_NAME = "Music Radio"

        // Left to itself, Nucleus builds the MPRIS name from app.id, which the plugin injects
        // verbatim from packageName - "Music Radio", space included. A space is illegal in a
        // D-Bus well-known name: g_bus_own_name() asserts and returns without ever calling
        // on_name_acquired or on_name_lost, so the native attach() waits on a condition variable
        // nothing will signal. It runs on the Tao main thread during first composition, which
        // means the window never appears and the process keeps holding the single-instance lock.
        // Passing a legal name of our own sidesteps all of it.
        // https://github.com/NucleusFramework/Nucleus/issues/548
        const val MPRIS_BUS_NAME = "org.mpris.MediaPlayer2.MusicRadio"
    }
}
