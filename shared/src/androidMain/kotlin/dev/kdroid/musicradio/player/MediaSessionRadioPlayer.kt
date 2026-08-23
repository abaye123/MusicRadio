package dev.kdroid.musicradio.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executor

/**
 * Android playback, driven through a [MediaController] bound to [PlaybackService].
 *
 * Nothing here polls. ExoPlayer may only be touched from the thread that built it, so the
 * previous backend - which was polled from a background coroutine - threw on every read and left
 * the UI stuck on "buffering" while audio played perfectly well. Here every command is posted to
 * the main looper and every state change arrives through [Player.Listener], so the reported state
 * is both correct and free.
 */
internal class MediaSessionRadioPlayer(context: Context) : RadioPlayer {

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> main.post(command) }

    private val _status = MutableStateFlow(PlaybackStatus.Idle)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private var controller: MediaController? = null

    private var seekable = false

    /**
     * Media3 pushes state changes but not the clock, so the position has to be asked for. The
     * ticker only runs while a seekable source is actually playing: a live stream has no position
     * worth reading, and the radio path stays as free as it was before shiurim existed.
     */
    private val ticker = object : Runnable {
        override fun run() {
            val connected = controller
            if (!seekable || connected == null) return
            publishProgress(connected)
            if (_status.value.active) main.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    /** Commands issued before the service connection completes, replayed once it does. */
    private var queued: ((MediaController) -> Unit)? = null
    private var volume: Float = 1f
    private var released = false

    // Kept so the media item can be rebuilt with fresh metadata without touching the stream.
    private var currentUrl: String? = null
    private var station: String = ""
    private var song: String = ""
    private var artworkUri: String? = null
    private var artworkBytes: ByteArray? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlayerError(error: PlaybackException) {
            _status.value = PlaybackStatus.Error
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                if (released) return@addListener
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                connected.addListener(listener)
                connected.volume = volume
                controller = connected
                queued?.invoke(connected)
                queued = null
                publish()
            },
            mainExecutor,
        )
    }

    override fun play(url: String, startAtMs: Long, seekable: Boolean) {
        currentUrl = url
        this.seekable = seekable
        val start = if (seekable) startAtMs.coerceAtLeast(0) else 0
        // Reported straight away: the connection may still be forming, and a dead button in the
        // moment after a tap reads as a broken app.
        _status.value = PlaybackStatus.Buffering
        _progress.value = PlaybackProgress(positionMs = start, durationMs = 0, seekable = seekable)
        onController { controller ->
            // Media3 takes the start position with the item, so unlike the desktop backend there
            // is nothing to queue: the source never opens at zero and never has to be dragged back.
            controller.setMediaItem(mediaItem(url), start)
            controller.prepare()
            controller.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (!seekable) return
        val target = positionMs.coerceAtLeast(0)
        // Moved locally first: the next tick is up to half a second away, and a scrubber that
        // snaps back in the meantime reads as a failed seek.
        _progress.value = _progress.value.copy(positionMs = target)
        onController { controller ->
            val duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            controller.seekTo(duration?.let { target.coerceAtMost(it) } ?: target)
        }
    }

    override fun setNowPlaying(station: String, song: String, artworkUri: String?) {
        if (this.station == station && this.song == song && this.artworkUri == artworkUri) return
        this.station = station
        this.song = song
        this.artworkUri = artworkUri
        // Read here, not on the main looper mediaItem() posts to: these are a few kilobytes, and
        // this method is already called from the ViewModel's background dispatcher.
        this.artworkBytes = artworkBytesOf(artworkUri)
        val url = currentUrl ?: return
        onController { controller ->
            // Replacing an item whose URI is unchanged only swaps the metadata; the stream keeps
            // running, which is the whole point of updating a track title mid-broadcast.
            if (controller.mediaItemCount > 0) controller.replaceMediaItem(0, mediaItem(url))
        }
    }

    private fun mediaItem(url: String): MediaItem {
        val cover = artworkUri?.let(Uri::parse)
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    // Song on top when the stream reports one, station underneath; with no song the
                    // station takes the title line so the notification is never blank.
                    .setTitle(song.ifBlank { station }.ifBlank { null })
                    .setArtist(station.takeIf { it.isNotBlank() && song.isNotBlank() })
                    .setStation(station.takeIf { it.isNotBlank() })
                    .setArtworkUri(cover)
                    // artworkUri is what Media3's loader would fetch; artworkData is what actually
                    // reaches the notification, lock screen and Android Auto, which cannot open
                    // our cache file.
                    .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    private fun artworkBytesOf(uri: String?): ByteArray? {
        val path = uri?.let(Uri::parse)?.path ?: return null
        return runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
    }

    override fun resume() {
        _status.value = PlaybackStatus.Buffering
        onController { controller ->
            // A live stream that was stopped has nothing buffered to resume into, so it is
            // prepared again rather than simply un-paused.
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    override fun pause() {
        onController { it.pause() }
    }

    override fun stop() {
        _status.value = PlaybackStatus.Idle
        seekable = false
        _progress.value = PlaybackProgress()
        main.removeCallbacks(ticker)
        onController { controller ->
            controller.stop()
            controller.clearMediaItems()
        }
    }

    override fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
        onController { it.volume = volume }
    }

    override fun release() {
        released = true
        seekable = false
        main.removeCallbacks(ticker)
        onController { controller ->
            controller.removeListener(listener)
            controller.release()
        }
        controller = null
        _status.value = PlaybackStatus.Idle
        _progress.value = PlaybackProgress()
    }

    private fun onController(block: (MediaController) -> Unit) {
        main.post {
            val connected = controller
            if (connected == null) queued = block else block(connected)
        }
    }

    /** Always runs on the main looper: listener callbacks and the connection both land there. */
    private fun publish() {
        val connected = controller ?: return
        _status.value = when {
            connected.playerError != null -> PlaybackStatus.Error
            connected.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Buffering
            connected.playbackState == Player.STATE_READY && connected.playWhenReady -> PlaybackStatus.Playing
            connected.playbackState == Player.STATE_READY -> PlaybackStatus.Paused
            else -> PlaybackStatus.Idle
        }
        if (!seekable) return
        // One position read on every state change, so a pause lands on screen with the exact
        // moment it happened rather than whatever the last tick caught.
        publishProgress(connected)
        main.removeCallbacks(ticker)
        if (_status.value.active) main.postDelayed(ticker, PROGRESS_TICK_MS)
    }

    /** Main looper only: [MediaController] may not be read from anywhere else. */
    private fun publishProgress(connected: MediaController) {
        val duration = connected.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        _progress.value = PlaybackProgress(
            positionMs = connected.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            seekable = true,
        )
    }

    private companion object {
        /** Twice a second is enough for a scrubber and cheap enough to run on the main looper. */
        const val PROGRESS_TICK_MS = 500L
    }
}
