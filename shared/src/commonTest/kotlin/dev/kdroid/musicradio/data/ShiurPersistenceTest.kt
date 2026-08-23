package dev.kdroid.musicradio.data

import dev.kdroid.musicradio.app.parseShiurKey
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.domain.shiurKey
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two flat files carry everything the shiurim feature remembers: where each shiur was left off, and
 * the last page of a rav's catalog. Both are hand-rolled formats rather than JSON, so the things
 * worth pinning down are the ones a format like that gets wrong - a field containing the separator,
 * a row that will not parse, and the row limit that stops the file growing forever.
 */
class ShiurPersistenceTest {

    private fun item(fileId: Long, title: String = "A shiur", audioUrl: String = "https://example.test/audio/$fileId") = ShiurItem(
        fileId = fileId,
        ravId = 3602,
        title = title,
        recordedAt = LocalDateTime(2026, 8, 18, 21, 37, 19),
        durationMs = 5_454_000,
        folderId = 571,
        audioUrl = audioUrl,
    )

    // ------------------------------------------------------------------ progress rows

    @Test
    fun `a progress row survives a round trip`() {
        val rows = mapOf(
            shiurKey(3602, 42740657) to ShiurProgress(845_000, 3_612_000, finished = false, updatedAt = 1_755_900_000),
            shiurKey(3602, 42731234) to ShiurProgress(3_600_000, 3_612_000, finished = true, updatedAt = 1_755_813_600),
        )
        assertEquals(rows, decodeProgress(encodeProgress(rows)))
    }

    @Test
    fun `an unreadable row is dropped and the rest of the file still loads`() {
        val good = shiurKey(3602, 1) + "=100,200,0,300"
        val raw = listOf("garbage with no equals", "3602/2=not-a-number,200,0,300", good, "").joinToString("\n")

        val decoded = decodeProgress(raw)

        assertEquals(1, decoded.size, "one bad row took the whole file with it")
        assertEquals(100, decoded.getValue(shiurKey(3602, 1)).positionMs)
    }

    @Test
    fun `the row limit keeps the newest and forgets the rest`() {
        val rows = (1..PROGRESS_ROW_LIMIT + 50).associate { i ->
            shiurKey(3602, i.toLong()) to ShiurProgress(0, 0, finished = false, updatedAt = i.toLong())
        }

        val capped = capProgressRows(rows)

        assertEquals(PROGRESS_ROW_LIMIT, capped.size)
        assertTrue(shiurKey(3602, (PROGRESS_ROW_LIMIT + 50).toLong()) in capped, "the newest row was evicted")
        assertTrue(shiurKey(3602, 1) !in capped, "the oldest row survived the cap")
    }

    @Test
    fun `finishing is judged on the tail, not on the exact end`() {
        // Half an hour in, of an hour: plainly not finished.
        assertTrue(!isFinishedAt(positionMs = 1_800_000, durationMs = 3_600_000))
        // Ten seconds from the end: nobody is coming back for that.
        assertTrue(isFinishedAt(positionMs = 3_590_000, durationMs = 3_600_000))
        // A duration that was never learned cannot make anything finished.
        assertTrue(!isFinishedAt(positionMs = 3_590_000, durationMs = 0))
    }

    // ------------------------------------------------------------------ the page cache

    @Test
    fun `a cached page survives a round trip`() {
        val items = listOf(item(1), item(2))

        val decoded = decodeShiurCache(encodeShiurCache(writtenAt = 1_755_900_000, items = items))

        assertNotNull(decoded)
        assertEquals(1_755_900_000, decoded.writtenAt)
        assertEquals(items, decoded.items)
    }

    @Test
    fun `a title carrying the separators still round trips`() {
        // Titles are free text off a website. Tabs and newlines in one would otherwise split a row
        // into pieces and quietly eat every field after the title.
        val awkward = item(3, title = "Line one\tstill line one\nline two \\ backslash")

        val decoded = decodeShiurCache(encodeShiurCache(1, listOf(awkward)))

        assertNotNull(decoded)
        assertEquals(awkward.title, decoded.items.single().title)
    }

    @Test
    fun `a truncated cache row is dropped rather than half read`() {
        val raw = "1\n3602\t3602\tonly two fields"

        val decoded = decodeShiurCache(raw)

        assertNotNull(decoded)
        assertEquals(emptyList(), decoded.items)
    }

    @Test
    fun `a cache with no timestamp is refused outright`() {
        assertNull(decodeShiurCache("not a timestamp\n674\t674\ttitle"))
    }

    // ------------------------------------------------------------------ the key

    @Test
    fun `a shiur key round trips through the snapshot`() {
        assertEquals(3602 to 42740657L, parseShiurKey(shiurKey(3602, 42740657)))
    }

    @Test
    fun `a key from an older or corrupted snapshot is refused`() {
        assertNull(parseShiurKey(""))
        assertNull(parseShiurKey("3602"))
        assertNull(parseShiurKey("/42740657"))
        assertNull(parseShiurKey("rav/42740657"))
        assertNull(parseShiurKey("3602/not-a-number"))
    }
}
