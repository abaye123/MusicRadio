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
import kotlinx.coroutines.CancellationException
import dev.kdroid.kolhalashon.ShiurLanguage as WireLanguage

/**
 * Serialised, and slowly. Cloudflare trips on roughly a dozen requests in a minute from one IP and
 * the challenge it raises does not clear on its own, so throughput is worth nothing here: one
 * request a second, one at a time, is the setting that keeps the feature working at all.
 */
private const val MIN_REQUEST_INTERVAL_MS = 1_000L

actual fun createShiurCatalog(http: HttpClient): ShiurCatalog? = KolHalashonCatalog(http)

private class KolHalashonCatalog(http: HttpClient) : ShiurCatalog {

    private val client = KolHalashonClient(
        options = KolHalashonOptions(
            // www, not srv: that is the host a real browser talks to, and the one least likely to
            // be treated as automation.
            baseUrl = KolHalashonUrls.SITE_API_BASE_URL,
            minRequestIntervalMillis = MIN_REQUEST_INTERVAL_MS,
            maxConcurrency = 1,
        ),
        // Borrowed, so the app keeps one connection pool and the platform's certificate handling -
        // which on Android and on a filtered desktop line is not something to set up twice.
        httpClient = http,
    )

    override suspend fun shiurim(ravId: Int, language: ShiurLanguage, fromRow: Int, rowsPerPage: Int): CatalogResult<ShiurPage> = guard {
        val page = client.ravShiurim(
            ShiurQuery(
                ravId = ravId,
                fromRow = fromRow,
                rowsPerPage = rowsPerPage,
                language = language.wire(),
            ),
        )
        ShiurPage(
            items = page.items.toItems(ravId),
            fromRow = fromRow,
            hasMore = page.hasMore,
        )
    }

    override suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>> = guard {
        client.ravFolders(ravId).mapNotNull { it.toFolder(ravId) }
    }

    override suspend fun shiurimInFolder(
        ravId: Int,
        folderId: Int,
        language: ShiurLanguage,
        fromRow: Int,
        rowsPerPage: Int,
    ): CatalogResult<ShiurPage> = guard {
        val page = client.shiurimUnderFolder(
            folderId = folderId,
            fromRow = fromRow,
            rowsPerPage = rowsPerPage,
            language = language.wire(),
        )
        ShiurPage(
            items = page.items.toItems(ravId),
            fromRow = fromRow,
            hasMore = page.hasMore,
        )
    }

    /**
     * A rate limit is kept as its own case all the way to the screen. Everything else collapses to
     * a message, because there is nothing the user can do differently about any of it.
     */
    private inline fun <T> guard(block: () -> T): CatalogResult<T> = try {
        CatalogResult.Ok(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: KolHalashonRateLimitedError) {
        CatalogResult.RateLimited
    } catch (failure: KolHalashonError) {
        CatalogResult.Failed(failure.message)
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
