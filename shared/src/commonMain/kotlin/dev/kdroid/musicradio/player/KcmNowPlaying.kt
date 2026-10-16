package dev.kdroid.musicradio.player

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kol Hay Music's own now-playing feed, the one kcm.fm shows under its player.
 *
 * The ICY block on these streams cannot be relied on: the flagship mount parks `line` in it
 * permanently, and several sub-channels send it empty while the site still names the track. One
 * request to this feed returns every channel at once, keyed by the same stream URLs the catalog
 * plays.
 */
class KcmNowPlaying(private val http: HttpClient) {

    fun handles(streamUrl: String): Boolean = streamUrl.contains(STREAM_HOST)

    /** The current track, or `null` when the feed is unreachable or has nothing for [streamUrl]. */
    suspend fun fetchTitle(streamUrl: String): String? = runCatching {
        val response = http.get(FEED_URL) { header("User-Agent", USER_AGENT) }
        if (!response.status.isSuccess()) return@runCatching null
        parseKcmTitle(response.bodyAsText(), streamUrl)
    }.getOrNull()

    private companion object {
        const val STREAM_HOST = "live.kcm.fm/"
        const val FEED_URL = "https://kcm.fm/Home/LiveJ/"
        const val USER_AGENT = "MusicRadio"
    }
}

/**
 * Picks [streamUrl]'s track out of the feed. The feed lists some channels by their HLS playlist
 * (`/17/hls.m3u8`) where the catalog plays the plain mount (`/17`), so both sides are compared
 * without it.
 */
internal fun parseKcmTitle(json: String, streamUrl: String): String? {
    val wanted = streamUrl.mountKey()
    val items = Json.parseToJsonElement(json).jsonObject["items"]?.jsonArray ?: return null
    val item = items.firstOrNull { it.jsonObject.string("url")?.mountKey() == wanted } ?: return null
    return item.jsonObject.string("playing")?.let { usableTitle(it.repairLatin1()) }
}

/**
 * The feed passes along what the stream reported, and for a stream sending windows-1255 that
 * arrives read as Latin-1: Hebrew letters turn into accented ones. No real title here mixes the
 * two, so a string with Latin-1 letters and no Hebrew is turned back into its bytes and decoded
 * the way the ICY path does it.
 */
private fun String.repairLatin1(): String {
    if (any { it.code in HEBREW } || none { it.code in LATIN1_HIGH }) return this
    if (any { it.code > LATIN1_HIGH.last }) return this
    return decodeIcyText(ByteArray(length) { this[it].code.toByte() })
}

private val HEBREW = 0x0590..0x05FF
private val LATIN1_HIGH = 0xC0..0xFF

private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

private fun String.mountKey(): String = trim()
    .substringAfter("://")
    .removeSuffix("/")
    .removeSuffix("/hls.m3u8")
    .lowercase()
