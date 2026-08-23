package dev.kdroid.musicradio.data

import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val BASE = "https://www.emess.co.il/wp-json/custom/v1/aryo_and_posts_by_maguish_divided"

/** The page size the site's own player asks for. */
private const val PAGE_SIZE = 60

/**
 * A guard, not a limit. The largest archive here is one page and a bit; anything past this is the
 * API having stopped telling us when it has run out.
 */
private const val MAX_PAGES = 6

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429

actual fun createShiurCatalog(http: HttpClient): ShiurCatalog? = EmessCatalog(http)

/**
 * One rav's archive, read whole and kept.
 *
 * These archives are tens of recordings, not thousands - the three shipped here are 56, 30 and 59 -
 * so the honest design is to fetch the lot once and answer everything else from memory. That makes
 * the programme tabs a filter instead of another round trip, and it means the list never grows
 * under the user mid-scroll.
 */
private class EmessCatalog(private val http: HttpClient) : ShiurCatalog {

    private class Archive(val items: List<ShiurItem>, val folders: List<ShiurFolder>)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val gate = Mutex()
    private val cache = mutableMapOf<Int, Archive>()

    override suspend fun shiurim(ravId: Int): CatalogResult<ShiurPage> =
        archive(ravId).map { ShiurPage(it.items, fromRow = 0, hasMore = false) }

    override suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>> = archive(ravId).map { it.folders }

    override suspend fun shiurimInFolder(ravId: Int, folderId: Int): CatalogResult<ShiurPage> = archive(ravId).map { held ->
        ShiurPage(held.items.filter { it.folderId == folderId }, fromRow = 0, hasMore = false)
    }

    private suspend fun archive(ravId: Int): CatalogResult<Archive> = gate.withLock {
        cache[ravId]?.let { return CatalogResult.Ok(it) }
        val items = mutableListOf<ShiurItem>()
        var programmes: Map<Int, String> = emptyMap()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = when (val fetched = fetch(ravId, page)) {
                is CatalogResult.Ok -> fetched.value
                is CatalogResult.RateLimited -> return CatalogResult.RateLimited
                is CatalogResult.Failed -> return fetched
            }
            val parsed = runCatching { parse(body, ravId) }.getOrNull()
                ?: return CatalogResult.Failed("the archive could not be read")
            if (parsed.first.isEmpty()) break
            items += parsed.first
            // Every page repeats the programme list; the first one carrying it is enough.
            if (programmes.isEmpty()) programmes = parsed.second
            page++
        }
        // Newest first, and made so here rather than trusted: the API documents no ordering.
        val ordered = items.sortedWith(
            compareByDescending<ShiurItem> { it.recordedAt != null }.thenByDescending { it.recordedAt },
        )
        val counts = ordered.groupingBy { it.folderId }.eachCount()
        val folders = programmes
            .map { (id, name) -> ShiurFolder(id, ravId, name, counts[id]) }
            // A programme with nothing in the archive is a heading over an empty room.
            .filter { (it.shiurCount ?: 0) > 0 }
            .sortedByDescending { it.shiurCount ?: 0 }
        val held = Archive(ordered, folders)
        cache[ravId] = held
        return CatalogResult.Ok(held)
    }

    private suspend fun fetch(ravId: Int, page: Int): CatalogResult<String> = try {
        val response = http.get("$BASE/$ravId?per_page=$PAGE_SIZE&page=$page") {
            header("accept", "application/json")
            header("user-agent", USER_AGENT)
        }
        when {
            response.status.isSuccess() -> CatalogResult.Ok(response.bodyAsText())

            // 429 is the site asking for quiet, 403 is usually a filter sitting in front of it.
            // Both are worth waiting out rather than retrying.
            response.status.value == HTTP_TOO_MANY_REQUESTS ||
                response.status.value == HTTP_FORBIDDEN -> CatalogResult.RateLimited

            else -> CatalogResult.Failed("HTTP ${response.status.value}")
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        CatalogResult.Failed(failure.message)
    }

    /**
     * Hand-walked rather than mapped onto declared types, because the shape is not stable: `posts`
     * is an object holding the list while there are results, and a bare empty array once there are
     * not, which no single type describes.
     */
    private fun parse(body: String, ravId: Int): Pair<List<ShiurItem>, Map<Int, String>> {
        val root = json.parseToJsonElement(body).jsonObject
        val rows = (root["posts"] as? JsonObject)?.get("aryo_programs")?.jsonArray.orEmpty()
        val items = rows.mapNotNull { row -> runCatching { row.jsonObject.toItem(ravId) }.getOrNull() }
        val programmes = (root["tax_broadcasters"] as? JsonObject)?.entries
            ?.mapNotNull { (key, value) ->
                val id = key.toIntOrNull() ?: return@mapNotNull null
                val name = (value as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                id to decodeHtml(name)
            }
            ?.toMap()
            .orEmpty()
        return items to programmes
    }

    private fun JsonObject.toItem(ravId: Int): ShiurItem? {
        val fileId = get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val audio = (get("audio_in_content")?.jsonArray?.firstOrNull() as? JsonObject) ?: return null
        val url = audio["audio_file"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val title = get("title")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val programme = (get("featured_image") as? JsonObject)
            ?.let { it["image"] as? JsonObject }
            ?.get("tax_broadcaster_id")?.jsonPrimitive?.content?.toIntOrNull()
        return ShiurItem(
            fileId = fileId,
            ravId = ravId,
            title = decodeHtml(title).trim(),
            recordedAt = get("date")?.jsonPrimitive?.content?.let(::parseTimestamp),
            // The site publishes this field and leaves it empty. Read it anyway, in case it fills.
            durationMs = audio["audio_lenght"]?.jsonPrimitive?.content?.let(::parseClock) ?: 0L,
            folderId = programme,
            audioUrl = url,
        )
    }
}

private inline fun <T, R> CatalogResult<T>.map(transform: (T) -> R): CatalogResult<R> = when (this) {
    is CatalogResult.Ok -> CatalogResult.Ok(transform(value))
    is CatalogResult.RateLimited -> CatalogResult.RateLimited
    is CatalogResult.Failed -> this
}

/** `2026-08-21 14:20:06`, which is ISO with the T replaced by a space. */
internal fun parseTimestamp(raw: String): LocalDateTime? {
    val trimmed = raw.trim()
    if (trimmed.length < "0000-00-00 00:00".length) return null
    return runCatching { LocalDateTime.parse(trimmed.replaceFirst(' ', 'T')) }.getOrNull()
}

internal fun parseClock(text: String): Long? {
    val parts = text.trim().substringBefore('.').split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) return null
    val numbers = parts.map { part -> part.trim().toLongOrNull() ?: return null }
    val seconds = when (numbers.size) {
        3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0]
    }
    return seconds.takeIf { it > 0 }?.times(1_000)
}

/**
 * WordPress hands titles back HTML-escaped - `&#8211;` and `&quot;` appear in nearly every one.
 * Only the handful the source actually emits are decoded; anything else is left as written rather
 * than guessed at.
 */
internal fun decodeHtml(raw: String): String {
    if ('&' !in raw) return raw
    var out = raw
    for ((entity, char) in NAMED_ENTITIES) out = out.replace(entity, char)
    return NUMERIC_ENTITY.replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        if (code in 1..MAX_CODE_POINT) code.toChar().toString() else match.value
    }
}

private const val MAX_CODE_POINT = 0xFFFF

private val NAMED_ENTITIES = listOf(
    "&quot;" to "\"",
    "&apos;" to "'",
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    // Last, or it would un-escape the others' ampersands first and corrupt them.
    "&amp;" to "&",
)

private val NUMERIC_ENTITY = Regex("&#(\\d{1,7});")
