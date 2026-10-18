package dev.kdroid.musicradio.domain

import androidx.compose.runtime.Immutable

/**
 * A track the listener bookmarked while it was on air, so it can be looked up later. Radio gives no
 * way back to a song once it is over, and the title the stream reports is all there is to go on.
 *
 * [channelId] is where it was heard and [savedAt] when, in epoch milliseconds.
 */
@Immutable
data class SavedSong(val title: String, val channelId: String, val savedAt: Long)

fun AppData.isSongSaved(title: String, channelId: String): Boolean = savedSongs.any { it.title == title && it.channelId == channelId }

/**
 * Saves the track, or forgets it when it is already saved - the button reads as a toggle, like the
 * star beside it. Newest first, since the one you just heard is the one you came looking for.
 */
fun AppData.toggleSavedSong(title: String, channelId: String, now: Long): AppData {
    val clean = title.cleanSongTitle()
    if (clean.isEmpty()) return this
    val kept = savedSongs.filterNot { it.title == clean && it.channelId == channelId }
    return copy(savedSongs = if (kept.size < savedSongs.size) kept else listOf(SavedSong(clean, channelId, now)) + savedSongs)
}

fun AppData.removeSavedSong(song: SavedSong): AppData = copy(savedSongs = savedSongs - song)

/** The snapshot keeps one song per line with tab-separated fields, so neither may reach a title. */
internal fun String.cleanSongTitle(): String = replace(Regex("[\\t\\r\\n]+"), " ").trim()
