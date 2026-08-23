package dev.kdroid.musicradio.data

import dev.kdroid.kolhalashon.KolHalashonClient
import dev.kdroid.kolhalashon.KolHalashonError
import dev.kdroid.kolhalashon.KolHalashonOptions
import dev.kdroid.kolhalashon.KolHalashonRateLimitedError
import dev.kdroid.kolhalashon.KolHalashonUrls
import dev.kdroid.kolhalashon.RavFolder
import dev.kdroid.kolhalashon.Shiur
import dev.kdroid.kolhalashon.ShiurQuery
import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.domain.ShiurLanguage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.kdroid.kolhalashon.ShiurLanguage as WireLanguage

/**
 * Serialised, and slowly. Cloudflare trips on roughly a dozen requests in a minute from one IP and
 * the challenge it raises does not clear on its own, so throughput is worth nothing here: one
 * request a second, one at a time, is the setting that keeps the feature working at all.
 */
private const val MIN_REQUEST_INTERVAL_MS = 1_000L

/**
 * No clearance provider, no catalogue.
 *
 * The site's API sits behind a bot check that turns away any plain HTTP client on its very first
 * request - not after a burst, and regardless of host, method or headers. The only thing that gets
 * through it is a real browser session, so a platform with no browser engine to drive has no way to
 * read this API and says so by shipping no catalogue at all, rather than a rav card that fails on
 * every tap.
 */
actual fun createShiurCatalog(http: HttpClient): ShiurCatalog? {
    val clearance = createClearanceProvider() ?: return null
    return KolHalashonCatalog(http, clearance)
}

private class KolHalashonCatalog(private val http: HttpClient, private val clearanceProvider: ClearanceProvider) : ShiurCatalog {

    private val gate = Mutex()

    /**
     * The cookie header to send, re-read on every request.
     *
     * Held in a field rather than baked into the client so a refresh costs an assignment. The
     * user-agent cannot be treated the same way - the library sends it per request from its own
     * options, and it has to match the browser that earned the cookie - but a given device's
     * WebView reports the same user-agent every time, so the client is still built only once.
     */
    private var cookieHeader: String = ""
    private var client: KolHalashonClient? = null

    override suspend fun shiurim(ravId: Int, language: ShiurLanguage, fromRow: Int, rowsPerPage: Int): CatalogResult<ShiurPage> =
        guard { client ->
            val page = client.ravShiurim(
                ShiurQuery(ravId = ravId, fromRow = fromRow, rowsPerPage = rowsPerPage, language = language.wire()),
            )
            ShiurPage(items = page.items.toItems(ravId), fromRow = fromRow, hasMore = page.hasMore)
        }

    override suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>> = guard { client ->
        client.ravFolders(ravId).mapNotNull { it.toFolder(ravId) }
    }

    override suspend fun shiurimInFolder(
        ravId: Int,
        folderId: Int,
        language: ShiurLanguage,
        fromRow: Int,
        rowsPerPage: Int,
    ): CatalogResult<ShiurPage> = guard { client ->
        val page = client.shiurimUnderFolder(
            folderId = folderId,
            fromRow = fromRow,
            rowsPerPage = rowsPerPage,
            language = language.wire(),
        )
        ShiurPage(items = page.items.toItems(ravId), fromRow = fromRow, hasMore = page.hasMore)
    }

    /**
     * One call, with one retry behind a fresh clearance.
     *
     * A challenge is the only reliable sign that clearance has expired - it carries no expiry the
     * client can read - so the first one is treated as "go and get another", and only a second
     * challenge is reported as such. Everything else collapses to a message, because there is
     * nothing the user can do differently about any of it.
     */
    private suspend fun <T> guard(block: suspend (KolHalashonClient) -> T): CatalogResult<T> {
        var refresh = false
        repeat(ATTEMPTS) { attempt ->
            val client = client(refresh) ?: return CatalogResult.Failed(null)
            try {
                return CatalogResult.Ok(block(client))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: KolHalashonRateLimitedError) {
                if (attempt == ATTEMPTS - 1) return CatalogResult.RateLimited
                refresh = true
            } catch (failure: KolHalashonError) {
                return CatalogResult.Failed(failure.message)
            }
        }
        return CatalogResult.RateLimited
    }

    private suspend fun client(refresh: Boolean): KolHalashonClient? = gate.withLock {
        val clearance = clearanceProvider.clearance(refresh) ?: return null
        cookieHeader = clearance.cookieHeader
        client?.let { return it }
        val built = KolHalashonClient(
            options = KolHalashonOptions(
                // www, not srv: that is the host a real browser talks to, and the host the
                // clearance was earned on - a cookie does not travel between them.
                baseUrl = KolHalashonUrls.SITE_API_BASE_URL,
                // Must be the string the browser sent. Cloudflare binds clearance to the
                // user-agent as well as the address, and a mismatch is rejected exactly as a
                // missing cookie would be.
                userAgent = clearance.userAgent,
                minRequestIntervalMillis = MIN_REQUEST_INTERVAL_MS,
                maxConcurrency = 1,
            ),
            // Derived from the app's client, so the engine and its connection pool are shared and
            // the platform's certificate handling is not set up twice.
            httpClient = http.config {
                defaultRequest {
                    // Read per request, so a refresh is an assignment rather than a rebuild.
                    if (cookieHeader.isNotEmpty()) header(HttpHeaders.Cookie, cookieHeader)
                }
            },
        )
        client = built
        return built
    }

    private companion object {
        const val ATTEMPTS = 2
    }
}

private fun ShiurLanguage.wire(): WireLanguage = WireLanguage.fromId(wireId) ?: WireLanguage.ANY

/**
 * Sorted here rather than trusted from the server.
 *
 * `SearchOrder = 7` is what the site's own rav page sends and what the client library calls
 * `NEWEST_FIRST`, but the API notes are explicit that which order id means "newest" was never
 * established. Everything downstream - which shiur plays on opening a rav, what "next" means -
 * depends on newest-first being true, so it is made true here instead of assumed. Rows with no
 * date sort last; they cannot be placed and should not displace one that can.
 */
private fun List<Shiur>.toItems(ravId: Int): List<ShiurItem> = mapNotNull { it.toItem(ravId) }.sortedWith(
    compareByDescending<ShiurItem> { it.recordedAt != null }.thenByDescending { it.recordedAt },
)

private fun Shiur.toItem(ravId: Int): ShiurItem? {
    val title = titleHebrew?.takeIf { it.isNotBlank() }
        ?: titleEnglish?.takeIf { it.isNotBlank() }
        ?: return null
    return ShiurItem(
        fileId = fileId,
        ravId = this.ravId ?: ravId,
        title = title.trim(),
        recordedAt = recordDate,
        durationMs = durationMillis(),
        language = ShiurLanguage.fromWireId(languageId),
        folderId = folderId,
        topic = (mainTopicHebrew ?: mainTopicEnglish)?.takeIf { it.isNotBlank() }?.trim(),
        audioUrl = audioUrl,
        locked = isLocked,
        hasAudio = hasAudio,
    )
}

/**
 * The library parses `ShiurDuration` where it can, but the field is not always the `HH:MM:SS` the
 * API notes describe - captured responses carry `58:07` too - so a text fallback earns its keep.
 * A duration that cannot be read is zero, which every progress bar already treats as "unknown".
 */
private fun Shiur.durationMillis(): Long = duration?.inWholeMilliseconds ?: durationText?.let(::parseClock) ?: 0L

private fun parseClock(text: String): Long? {
    val parts = text.substringBefore('.').split(':').map { it.trim() }
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    val seconds = when (numbers.size) {
        3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0]
    }
    return seconds.takeIf { it > 0 }?.times(1_000)
}

private fun RavFolder.toFolder(ravId: Int): ShiurFolder? {
    val label = name?.takeIf { it.isNotBlank() }
        ?: labelHebrew?.takeIf { it.isNotBlank() }
        ?: labelEnglish?.takeIf { it.isNotBlank() }
        ?: return null
    return ShiurFolder(
        folderId = folderId,
        ravId = this.ravId ?: ravId,
        name = label.trim(),
        shiurCount = leafCount,
        hiddenOnPhone = hiddenFromPhone,
    )
}
