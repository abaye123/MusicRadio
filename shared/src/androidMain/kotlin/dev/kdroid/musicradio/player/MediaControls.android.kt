package dev.kdroid.musicradio.player

import android.os.Handler
import android.os.Looper

/**
 * Android's media center is the `MediaSession` held by [PlaybackService], which already handles
 * play, pause and the now-playing card on its own. What it cannot do alone is change station: the
 * session holds one live item and has no idea what comes before or after it. This hands the
 * session's skip commands - from the notification, the lock screen, Android Auto, a headset or a
 * Bluetooth car kit - to the app, which owns the station list.
 *
 * Everything else is left to the session, so [update] and [setVolume] have nothing to do here.
 */
actual fun createMediaControls(): MediaControls = SessionMediaControls

private object SessionMediaControls : MediaControls {
    override val available: Boolean = true

    override fun attach(onCommand: (MediaCommand) -> Unit) {
        StationSkips.handler = onCommand
    }

    override fun update(nowPlaying: NowPlaying, status: PlaybackStatus) = Unit

    override fun setVolume(percent: Int) = Unit

    override fun release() {
        StationSkips.handler = null
    }
}

/**
 * The service and the ViewModel share a process but never see each other, so the skip commands
 * cross over here.
 */
internal object StationSkips {
    @Volatile
    var handler: ((MediaCommand) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * Posted rather than run in place. The session calls in while dispatching a key or a
     * notification tap, and the station change goes straight back into the same player.
     *
     * With no app attached, for example after the activity was finished while audio played on,
     * the press is dropped. There is no station list to step through.
     */
    fun dispatch(command: MediaCommand) {
        val target = handler ?: return
        main.post { target(command) }
    }
}
