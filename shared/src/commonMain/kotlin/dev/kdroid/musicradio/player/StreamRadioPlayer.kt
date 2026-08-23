package dev.kdroid.musicradio.player

import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayer
import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayerState
import io.github.kdroidfilter.composemediaplayer.audio.ErrorListener
import io.github.santimattius.structured.annotations.StructuredScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 250L

/**
 * [RadioPlayer] on top of Compose Media Player's audio module - Media3 on Android, Rodio on the
 * desktop JVM.
 *
 * The backend only offers a pollable state, so this class polls it. What it does *not* do is trust
 * that state blindly: connecting to an Icecast stream can take seconds, during which the backend
 * still reports `IDLE`. Reporting that back as "stopped" would flip the play button under the
 * user's finger, so a fresh [play] holds [PlaybackStatus.Buffering] until either the stream comes
 * up or the backend reports an error.
 *
 * The backend reports position and duration in milliseconds (`getPositionMs` / `getDurationMs`
 * underneath), so nothing here converts units.
 */
class StreamRadioPlayer(dispatcher: CoroutineDispatcher = Dispatchers.Default, private val backend: AudioPlayer = AudioPlayer()) :
    RadioPlayer {

    private enum class Wanted { Stopped, Playing, Paused }

    // Owned by this player and cancelled in release(), so the poll loop cannot outlive the backend.
    @StructuredScope
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _status = MutableStateFlow(PlaybackStatus.Idle)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private var wanted: Wanted = Wanted.Stopped
    private var seekable: Boolean = false

    /**
     * Where [play] was asked to start, held until the backend can act on it.
     *
     * Rodio cannot seek into a source it has not opened yet, and a seek issued in that window is
     * silently dropped - which is how a resumed shiur ends up starting from zero. So the value
     * waits here until a poll reports a duration, which is the first moment the source is known to
     * be open, and is cleared the instant it is applied.
     */
    private var pendingSeekMs: Long? = null

    init {
        backend.setOnErrorListener(
            object : ErrorListener {
                override fun onError(message: String?) = fail()
            },
        )
        scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                sync()
            }
        }
    }

    override fun play(url: String, startAtMs: Long, seekable: Boolean) {
        wanted = Wanted.Playing
        this.seekable = seekable
        pendingSeekMs = startAtMs.takeIf { seekable && it > 0 }
        _status.value = PlaybackStatus.Buffering
        // Published before the source opens so a resumed shiur shows its saved position straight
        // away instead of flicking through zero on the way there.
        _progress.value = PlaybackProgress(
            positionMs = if (seekable) startAtMs.coerceAtLeast(0) else 0,
            durationMs = 0,
            seekable = seekable,
        )
        runCatching { backend.play(url) }.onFailure { fail() }
    }

    override fun resume() {
        if (wanted == Wanted.Playing) return
        wanted = Wanted.Playing
        _status.value = PlaybackStatus.Buffering
        runCatching { backend.play() }.onFailure { fail() }
    }

    override fun pause() {
        wanted = Wanted.Paused
        _status.value = PlaybackStatus.Paused
        runCatching { backend.pause() }.onFailure { fail() }
    }

    override fun stop() {
        wanted = Wanted.Stopped
        seekable = false
        pendingSeekMs = null
        _status.value = PlaybackStatus.Idle
        _progress.value = PlaybackProgress()
        runCatching { backend.stop() }.onFailure { fail() }
    }

    override fun seekTo(positionMs: Long) {
        if (!seekable) return
        val duration = _progress.value.durationMs
        val target = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        // A seek before the source is open would be dropped, so it joins the queue of one instead.
        if (duration <= 0) {
            pendingSeekMs = target
        } else {
            runCatching { backend.seekTo(target) }
        }
        // Moved locally as well: the backend needs a poll or two to catch up, and a scrubber that
        // snaps back to where it was for a quarter of a second reads as a failed seek.
        _progress.value = _progress.value.copy(positionMs = target)
    }

    override fun setVolume(percent: Int) {
        runCatching { backend.setVolume(percent.coerceIn(0, 100) / 100f) }
    }

    override fun release() {
        wanted = Wanted.Stopped
        seekable = false
        pendingSeekMs = null
        runCatching { backend.stop() }
        runCatching { backend.release() }
        _status.value = PlaybackStatus.Idle
        _progress.value = PlaybackProgress()
        scope.cancel()
    }

    private fun fail() {
        wanted = Wanted.Stopped
        _status.value = PlaybackStatus.Error
    }

    private fun sync() {
        // An error stays on screen until the user acts on it; polling must not wipe it.
        if (_status.value == PlaybackStatus.Error) return
        val reported = runCatching { backend.currentPlayerState() }.getOrNull()
        _status.value = when (wanted) {
            Wanted.Stopped -> PlaybackStatus.Idle

            Wanted.Paused -> PlaybackStatus.Paused

            // IDLE while we asked for playback means the connection is still being opened.
            Wanted.Playing -> when (reported) {
                AudioPlayerState.PLAYING -> PlaybackStatus.Playing
                AudioPlayerState.PAUSED -> PlaybackStatus.Paused
                else -> PlaybackStatus.Buffering
            }
        }
        if (seekable) syncProgress()
    }

    private fun syncProgress() {
        val duration = runCatching { backend.currentDuration() }.getOrNull() ?: 0L
        val pending = pendingSeekMs
        if (pending != null && duration > 0) {
            pendingSeekMs = null
            runCatching { backend.seekTo(pending.coerceAtMost(duration)) }
            _progress.value = PlaybackProgress(pending.coerceAtMost(duration), duration, seekable = true)
            return
        }
        // While a start position is still queued the backend is sitting at zero, and publishing
        // that would drag the scrubber back to the beginning before the seek lands.
        val position = if (pending != null) {
            _progress.value.positionMs
        } else {
            runCatching { backend.currentPosition() }.getOrNull() ?: 0L
        }
        _progress.value = PlaybackProgress(position, duration, seekable = true)
    }
}
