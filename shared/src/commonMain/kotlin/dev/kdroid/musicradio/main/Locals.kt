package dev.kdroid.musicradio.main

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.kdroid.musicradio.app.PlayerTick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** `true` when the host draws its own title bar, so the app must not repeat the brand strip. */
val LocalHostHasTitleBar = staticCompositionLocalOf { false }

/**
 * A surface the window can be dragged by. Empty everywhere except the desktop host, which supplies
 * Nucleus's drag modifier without the shared UI having to depend on Nucleus.
 */
val LocalWindowDrag = staticCompositionLocalOf<Modifier> { Modifier }

/** `true` on a phone-width window: one pane at a time, bottom navigation, player on its own screen. */
val LocalCompactLayout = compositionLocalOf { false }

/**
 * The playback clock, as a flow rather than a value.
 *
 * Providing the flow keeps this local's own value constant, so nothing recomposes just because the
 * position moved. Only the handful of composables that draw a scrubber or a countdown collect it,
 * and only they repaint four times a second.
 */
val LocalPlayerTick = staticCompositionLocalOf<StateFlow<PlayerTick>> { MutableStateFlow(PlayerTick()) }
