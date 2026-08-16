package dev.kdroid.musicradio.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.materialkolor.rememberDynamicColorScheme
import dev.kdroid.musicradio.domain.AccentColor

/**
 * The whole Material 3 scheme is generated from the accent the user picked, so the app has one
 * knob for colour instead of a hand-maintained pair of light and dark palettes.
 *
 * Exposed on its own so the desktop window can paint its title bar with the same scheme the
 * content uses - the chrome lives outside [RadioTheme]'s composition.
 */
@Composable
fun rememberRadioColorScheme(accent: AccentColor = AccentColor.Indigo, isDark: Boolean = isSystemInDarkTheme()): ColorScheme =
    rememberDynamicColorScheme(seedColor = accent.seed, isDark = isDark)

@Composable
fun RadioTheme(
    accent: AccentColor,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = rememberRadioColorScheme(accent, isDark)) {
        Surface(modifier = modifier, content = content)
    }
}
