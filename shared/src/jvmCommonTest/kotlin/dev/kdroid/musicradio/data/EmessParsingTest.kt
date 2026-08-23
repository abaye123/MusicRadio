package dev.kdroid.musicradio.data

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The catalogue reads a WordPress endpoint that was never designed to be read by anyone else, and
 * the three things it does oddly are the three things worth pinning down: titles come back
 * HTML-escaped, timestamps are ISO with the T knocked out, and the length field is published and
 * left empty.
 */
class EmessParsingTest {

    @Test
    fun `titles come back escaped and are decoded`() {
        // Verbatim from the live response for broadcaster 3602.
        val raw = "באר הפרשה &#8211; ח' אלול תשפ&quot;ו &#8211; 21.08.26"

        val decoded = decodeHtml(raw)

        assertEquals("באר הפרשה – ח' אלול תשפ\"ו – 21.08.26", decoded)
    }

    @Test
    fun `an ampersand is decoded last so the entities around it survive`() {
        // Decoding &amp; first would turn &amp;quot; into a stray quote rather than the text it is.
        assertEquals("a & b \"c\"", decodeHtml("a &amp; b &quot;c&quot;"))
        assertEquals("&quot;", decodeHtml("&amp;quot;"))
    }

    @Test
    fun `text with no entities is returned untouched`() {
        val plain = "לקראת שבת"
        assertEquals(plain, decodeHtml(plain))
    }

    @Test
    fun `an entity the source does not emit is left as written`() {
        // Guessing at half-known entities is how a title quietly loses characters.
        assertEquals("&hearts;", decodeHtml("&hearts;"))
    }

    @Test
    fun `the timestamp format the site publishes parses`() {
        assertEquals(LocalDateTime(2026, 8, 21, 14, 20, 6), parseTimestamp("2026-08-21 14:20:06"))
    }

    @Test
    fun `a timestamp that is not one is refused rather than guessed at`() {
        // date_iso comes back as this on every row, which is why `date` is the field being read.
        assertNull(parseTimestamp("--"))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp("21.08.26"))
    }

    @Test
    fun `the empty length field reads as unknown rather than zero-length`() {
        assertNull(parseClock(""))
        assertNull(parseClock("   "))
    }

    @Test
    fun `a length is read in either of the shapes a clock comes in`() {
        assertEquals(3_600_000L + 13 * 60_000 + 6_000, parseClock("01:13:06"))
        assertEquals(58 * 60_000L + 7_000, parseClock("58:07"))
    }

    @Test
    fun `a length that cannot be read does not become a wrong one`() {
        assertNull(parseClock("about an hour"))
        assertNull(parseClock("1:2:3:4"))
        assertNull(parseClock("00:00"))
    }
}
