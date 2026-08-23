package dev.kdroid.musicradio.data

import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.domain.ShiurLanguage
import dev.kdroid.musicradio.platform.Platform
import dev.kdroid.musicradio.platform.joinPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime

/** How long a cached first page is trusted before the catalog is asked again. */
private const val CACHE_TTL_MS = 12L * 60 * 60 * 1000

/**
 * Everything the app knows about a rav's shiurim, and the only thing that talks to [ShiurCatalog].
 *
 * Two caches, for two different jobs. The in-memory one keeps a scrolled list from re-fetching
 * pages the user has already seen. The on-disk one holds the first page only, and exists for a
 * single moment: the cold start where the app wants to resume a shiur and the network is either
 * absent or challenged. The audio URL is a pure function of the file id, so that one cached page
 * plus a saved position is enough to start playing without a single API call - which is the best
 * defence there is against Cloudflare deciding today is not the day.
 */
class ShiurRepository(
    private val catalog: ShiurCatalog?,
    private val now: () -> Long = { Platform.now() },
    private val dir: () -> String = { Platform.appDir() },
) {
    /** `null` where the feature does not ship, and the one thing every caller checks. */
    val available: Boolean get() = catalog != null

    private data class Key(val ravId: Int, val language: ShiurLanguage, val folderId: Int?)

    private val pages = mutableMapOf<Key, MutableMap<Int, ShiurPage>>()
    private val folderLists = mutableMapOf<Int, List<ShiurFolder>>()

    // Requests are serialised anyway to stay under the rate limit, so one lock over both the
    // caches and the calls costs nothing and removes every interleaving question.
    private val gate = Mutex()

    suspend fun page(ravId: Int, language: ShiurLanguage, fromRow: Int = 0, folderId: Int? = null): CatalogResult<ShiurPage> {
        val catalog = catalog ?: return CatalogResult.Failed(null)
        val key = Key(ravId, language, folderId)
        gate.withLock {
            pages[key]?.get(fromRow)?.let { return CatalogResult.Ok(it) }
            val result = if (folderId == null) {
                catalog.shiurim(ravId, language, fromRow, SHIUR_PAGE_SIZE)
            } else {
                catalog.shiurimInFolder(ravId, folderId, language, fromRow, SHIUR_PAGE_SIZE)
            }
            if (result is CatalogResult.Ok) {
                pages.getOrPut(key) { mutableMapOf() }[fromRow] = result.value
                if (folderId == null && fromRow == 0) writeCache(ravId, language, result.value.items)
            }
            return result
        }
    }

    suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>> {
        val catalog = catalog ?: return CatalogResult.Failed(null)
        gate.withLock {
            folderLists[ravId]?.let { return CatalogResult.Ok(it) }
            val result = catalog.folders(ravId)
            if (result is CatalogResult.Ok) folderLists[ravId] = result.value
            return result
        }
    }

    /** Pages already fetched for this list, in order, for a screen that is rebuilding its state. */
    suspend fun loaded(ravId: Int, language: ShiurLanguage, folderId: Int? = null): List<ShiurItem> = gate.withLock {
        pages[Key(ravId, language, folderId)]
            ?.entries
            ?.sortedBy { it.key }
            ?.flatMap { it.value.items }
            .orEmpty()
    }

    /**
     * The last first page written to disk, if it is still fresh, without touching the network.
     *
     * Synchronous and best-effort on purpose: it runs on the path that decides what to play at
     * startup, and a slow or failed read there should cost nothing but an empty list.
     */
    fun cachedFirstPage(ravId: Int, language: ShiurLanguage): List<ShiurItem> {
        val raw = runCatching { Platform.readText(cacheFile(ravId, language)) }.getOrNull() ?: return emptyList()
        val decoded = runCatching { decodeShiurCache(raw) }.getOrNull() ?: return emptyList()
        if (now() - decoded.writtenAt > CACHE_TTL_MS) return emptyList()
        return decoded.items
    }

    /** Called when the language changes: every list in memory was for the old one. */
    suspend fun invalidate() = gate.withLock {
        pages.clear()
        folderLists.clear()
    }

    private fun writeCache(ravId: Int, language: ShiurLanguage, items: List<ShiurItem>) {
        runCatching { Platform.writeText(cacheFile(ravId, language), encodeShiurCache(now(), items)) }
    }

    private fun cacheFile(ravId: Int, language: ShiurLanguage): String = joinPath(dir(), "shiur-cache-$ravId-${language.wireId}.txt")
}

internal class ShiurCache(val writtenAt: Long, val items: List<ShiurItem>)

private const val FIELD = '\t'

/**
 * Tab-separated, one shiur per line, with a timestamp on the first line.
 *
 * Flat rather than JSON for the same reason the settings snapshot is: no serialization dependency,
 * hand-readable, and a row that will not parse is dropped instead of taking the file with it.
 * Titles are real text and can hold anything, so every field is escaped rather than trusted.
 */
internal fun encodeShiurCache(writtenAt: Long, items: List<ShiurItem>): String = buildString {
    append(writtenAt)
    for (item in items) {
        append('\n')
        append(item.fileId).append(FIELD)
        append(item.ravId).append(FIELD)
        append(escapeField(item.title)).append(FIELD)
        append(item.recordedAt?.toString().orEmpty()).append(FIELD)
        append(item.durationMs).append(FIELD)
        append(item.language?.wireId ?: ShiurLanguage.Any.wireId).append(FIELD)
        append(item.folderId?.toString().orEmpty()).append(FIELD)
        append(escapeField(item.topic.orEmpty())).append(FIELD)
        append(escapeField(item.audioUrl)).append(FIELD)
        append(if (item.locked) 1 else 0).append(FIELD)
        append(if (item.hasAudio) 1 else 0)
    }
}

internal fun decodeShiurCache(raw: String): ShiurCache? {
    val lines = raw.split('\n')
    val writtenAt = lines.firstOrNull()?.trim()?.toLongOrNull() ?: return null
    val items = lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val f = line.split(FIELD)
        if (f.size < 11) return@mapNotNull null
        val fileId = f[0].toLongOrNull() ?: return@mapNotNull null
        val ravId = f[1].toIntOrNull() ?: return@mapNotNull null
        ShiurItem(
            fileId = fileId,
            ravId = ravId,
            title = unescapeField(f[2]),
            recordedAt = f[3].takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            durationMs = f[4].toLongOrNull() ?: 0L,
            language = ShiurLanguage.fromWireId(f[5].toIntOrNull()),
            folderId = f[6].takeIf { it.isNotBlank() }?.toIntOrNull(),
            topic = unescapeField(f[7]).takeIf { it.isNotBlank() },
            audioUrl = unescapeField(f[8]),
            locked = f[9] == "1",
            hasAudio = f[10] == "1",
        )
    }
    return ShiurCache(writtenAt, items)
}

private fun escapeField(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

private fun unescapeField(value: String): String {
    if ('\\' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '\\' || i == value.lastIndex) {
            out.append(c)
            i++
            continue
        }
        when (val next = value[i + 1]) {
            't' -> out.append('\t')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            '\\' -> out.append('\\')
            else -> out.append(next)
        }
        i += 2
    }
    return out.toString()
}
