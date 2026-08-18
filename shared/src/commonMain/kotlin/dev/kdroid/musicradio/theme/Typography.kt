package dev.kdroid.musicradio.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/**
 * Material 3 default everywhere except the web canvas, which has no system Hebrew face
 * until Compose 1.12's Noto downloader.
 */
@Composable
internal expect fun radioTypography(): Typography
