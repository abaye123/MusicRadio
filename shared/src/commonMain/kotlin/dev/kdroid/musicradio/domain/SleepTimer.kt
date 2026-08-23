package dev.kdroid.musicradio.domain

import androidx.compose.runtime.Immutable

/**
 * A standing instruction to stop playing.
 *
 * Deliberately app-wide rather than a shiur feature: someone falling asleep to a niggun wants this
 * as much as someone falling asleep to a shiur, and two timers that behaved differently would be
 * one more thing to explain.
 *
 * Never persisted. A timer that survived a restart would stop playback for a reason the user set
 * yesterday and has long forgotten.
 */
@Immutable
sealed interface SleepTimer {
    /** Counts down from when it was set, whatever is playing. */
    @Immutable
    data class After(val minutes: Int) : SleepTimer

    /**
     * Stops when the current shiur ends, instead of cutting it off mid-sentence. Offered only
     * while a shiur is playing: a live stream has no end to wait for.
     */
    data object EndOfShiur : SleepTimer

    companion object {
        /** The choices the picker offers, in order. */
        val presets: List<Int> = listOf(5, 15, 30, 45, 60, 90)
    }
}
