package dev.kdroid.musicradio.player

import io.ktor.client.HttpClient

/**
 * Where the current track's title comes from.
 *
 * A broadcaster's own feed wins when it has one, since it names tracks the stream itself leaves
 * blank. ICY, read off the stream, is the fallback - for every other station, and for the moments
 * the feed is down.
 */
class NowPlayingSource(http: HttpClient) {
    private val icy = IcyMetadata(http)
    private val kcm = KcmNowPlaying(http)

    /** The current track, or `null` when nothing usable is known. */
    suspend fun fetchTitle(streamUrl: String): String? {
        val fromFeed = if (kcm.handles(streamUrl)) kcm.fetchTitle(streamUrl) else null
        return fromFeed ?: icy.fetchTitle(streamUrl)
    }
}
