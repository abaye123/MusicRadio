package dev.kdroid.musicradio.player

import org.jetbrains.compose.resources.DrawableResource

/**
 * Makes bundled artwork reachable by the OS media center, which loads the cover itself and cannot
 * see inside the app: MPRIS takes a `file://` URI, SMTC and Now Playing take a stream opened from
 * one. Returns null when there is nothing to show, or when the artwork could not be materialised -
 * a missing cover is not worth failing a track change over.
 *
 * [id] identifies the artwork across calls, so the same stream is only ever written out once.
 */
expect suspend fun mediaArtworkUri(id: String, artwork: DrawableResource?): String?
