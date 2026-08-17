package dev.kdroid.musicradio.player

import org.jetbrains.compose.resources.DrawableResource

/**
 * Android never goes through [MediaControls]: the notification and lock screen read their artwork
 * off the MediaSession the player owns, so there is nothing to materialise here.
 */
actual suspend fun mediaArtworkUri(id: String, artwork: DrawableResource?): String? = null
