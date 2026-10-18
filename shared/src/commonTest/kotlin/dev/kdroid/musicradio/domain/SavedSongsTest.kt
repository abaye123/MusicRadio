package dev.kdroid.musicradio.domain

import dev.kdroid.musicradio.data.decodeSnapshot
import dev.kdroid.musicradio.data.encodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedSongsTest {

    private val channel = "kol_hay_music/17"

    @Test
    fun `saving puts the newest song first`() {
        val data = AppData()
            .toggleSavedSong("Shwekey - Vehi Sheamda", channel, now = 1)
            .toggleSavedSong("Avraham Fried - Aderaba", channel, now = 2)
        assertEquals(listOf("Avraham Fried - Aderaba", "Shwekey - Vehi Sheamda"), data.savedSongs.map { it.title })
    }

    @Test
    fun `pressing again on the same song forgets it`() {
        val saved = AppData().toggleSavedSong("Aderaba", channel, now = 1)
        assertTrue(saved.isSongSaved("Aderaba", channel))
        assertFalse(saved.toggleSavedSong("Aderaba", channel, now = 2).isSongSaved("Aderaba", channel))
    }

    @Test
    fun `a blank title saves nothing`() {
        assertEquals(AppData(), AppData().toggleSavedSong("  ", channel, now = 1))
    }

    @Test
    fun `songs survive a restart, separators and all`() {
        // Commas, equals signs and Hebrew are all fair game in a title; tabs and newlines are not.
        val data = AppData()
            .toggleSavedSong("רחל - שמוליק קליין, דודי קאליש", channel, now = 1_760_000_000_000)
            .toggleSavedSong("a=b, c\tthen\nmore", "kol_play", now = 1_760_000_100_000)
            .toggleFavorite("kol_play")

        val restored = decodeSnapshot(encodeSnapshot(data))

        assertEquals(data.savedSongs, restored.savedSongs)
        assertEquals("a=b, c then more", restored.savedSongs.first().title)
        assertEquals(data.favorites, restored.favorites)
    }

    @Test
    fun `removing deletes only that song`() {
        val data = AppData()
            .toggleSavedSong("One", channel, now = 1)
            .toggleSavedSong("Two", channel, now = 2)
        assertEquals(listOf("Two"), data.removeSavedSong(data.savedSongs.last()).savedSongs.map { it.title })
    }
}
