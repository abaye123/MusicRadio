package dev.kdroid.musicradio.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shaped after a real response from kcm.fm/Home/LiveJ/, trimmed to the fields that matter. */
class KcmNowPlayingTest {

    private val feed = """
        {"status":1,"items":[
          {"id":1,"title":"Main","playing":"Avraham Fried - Aderaba","url":"https://live.kcm.fm/livemusic"},
          {"id":17,"title":"Oneg","playing":" Shwekey - Vehi Sheamda ","url":"https://live.kcm.fm/17/hls.m3u8"},
          {"id":114,"title":"Sharim","playing":"","url":"https://live.kcm.fm/665"},
          {"id":2,"title":"Quoted","playing":"Say \"Amen\"","url":"https://live.kcm.fm/02"},
          {"id":112,"title":"Dash","playing":"-","url":"https://live.kcm.fm/112"},
          {"id":41,"title":"Latin-1","playing":"éåàìé - ðùéîä","url":"https://live.kcm.fm/41/hls.m3u8"}
        ]}
    """.trimIndent()

    @Test
    fun `finds the flagship stream by its url`() {
        assertEquals("Avraham Fried - Aderaba", parseKcmTitle(feed, "https://live.kcm.fm/livemusic"))
    }

    @Test
    fun `matches a plain mount against the feed's hls playlist`() {
        // The catalog plays /17, the feed lists /17/hls.m3u8; the padding is the feed's own.
        assertEquals("Shwekey - Vehi Sheamda", parseKcmTitle(feed, "https://live.kcm.fm/17"))
    }

    @Test
    fun `keeps escaped quotes in a title`() {
        assertEquals("Say \"Amen\"", parseKcmTitle(feed, "https://live.kcm.fm/02"))
    }

    @Test
    fun `has nothing to report for an empty title`() {
        assertNull(parseKcmTitle(feed, "https://live.kcm.fm/665"))
    }

    @Test
    fun `treats a lone dash as no title`() {
        // Most idle channels report "-"; returning null lets the ICY fallback have a go.
        assertNull(parseKcmTitle(feed, "https://live.kcm.fm/112"))
    }

    @Test
    fun `repairs windows-1255 that the feed read as latin-1`() {
        // Observed on channel 41: "יואלי - נשימה" arrives as accented Latin letters.
        assertEquals("יואלי - נשימה", parseKcmTitle(feed, "https://live.kcm.fm/41"))
    }

    @Test
    fun `has nothing to report for a stream the feed does not list`() {
        assertNull(parseKcmTitle(feed, "https://live.kcm.fm/999"))
    }

    @Test
    fun `only claims kol hay music streams`() {
        val source = KcmNowPlaying(HttpClient(MockEngine { error("offline") }))
        assertTrue(source.handles("https://live.kcm.fm/livemusic"))
        assertFalse(source.handles("https://cdn.cybercdn.live/Kol_Barama/Music/icecast.audio"))
    }
}
