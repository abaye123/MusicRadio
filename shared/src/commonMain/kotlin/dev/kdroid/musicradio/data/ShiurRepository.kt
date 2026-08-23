package dev.kdroid.musicradio.data

import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.platform.Platform
import dev.kdroid.musicradio.platform.joinPath
import kotlinx.datetime.LocalDateTime

/** How long a cached archive is trusted before the site is asked again. */
private const val CACHE_TTL_MS = 12L * 60 * 60 * 1000

/**
 * Everything the app knows about a rav's recordings, and the only thing that talks to
 * [ShiurCatalog].
 *
 * The catalogue already holds a whole archive in memory once it has read one, so there is no page
 * cache here. What this adds is the on-disk copy, and it exists for one moment: the cold start
 * where the app wants to resume where it left off and the network is slow, absent or unhappy.
 *
 * That copy matters more than it would have with a derivable media URL. Here the audio address is a
 * hashed upload path with nothing predictable about it, so without a stored row there is no way to
 * resume a recording at all until the archive has been fetched again.
 */
class ShiurRepository(
    private val catalog: ShiurCatalog?,
    private val now: () -> Long = { Platform.now() },
    private val dir: () -> String = { Platform.appDir() },
) {
    /** `false` where the feature does not ship, which is the browser build. */
    val available: Boolean get() = catalog != null

    suspend fun shiurim(ravId: Int): CatalogResult<ShiurPage> {
        val catalog = catalog ?: return CatalogResult.Failed(null)
        val result = catalog.shiurim(ravId)
        if (result is CatalogResult.Ok) writeCache(ravId, result.value.items)
        return result
    }

    suspend fun folders(ravId: Int): CatalogResult<List<ShiurFolder>> {
        val catalog = catalog ?: return CatalogResult.Failed(null)
        return catalog.folders(ravId)
    }

    suspend fun shiurimInFolder(ravId: Int, folderId: Int): CatalogResult<ShiurPage> {
        val catalog = catalog ?: return CatalogResult.Failed(null)
        return catalog.shiurimInFolder(ravId, folderId)
    }

    /**
     * The archive last written to disk, if it is still fresh, without touching the network.
     *
     * Synchronous and best-effort on purpose: it runs on the path that decides what to play at
     * startup, and a slow or failed read there should cost nothing but an empty list.
     */
    fun cached(ravId: Int): List<ShiurItem> {
        val raw = runCatching { Platform.readText(cacheFile(ravId)) }.getOrNull() ?: return emptyList()
        val decoded = runCatching { decodeShiurCache(raw) }.getOrNull() ?: return emptyList()
        if (now() - decoded.writtenAt > CACHE_TTL_MS) return emptyList()
        return decoded.items
    }

    private fun writeCache(ravId: Int, items: List<ShiurItem>) {
        runCatching { Platform.writeText(cacheFile(ravId), encodeShiurCache(now(), items)) }
    }

    private fun cacheFile(ravId: Int): String = joinPath(dir(), "shiur-cache-$ravId.txt")
}

internal class ShiurCache(val writtenAt: Long, val items: List<ShiurItem>)

private const val FIELD = '\t'
private const val FIELD_COUNT = 7

/**
 * Tab-separated, one recording per line, with a timestamp on the first line.
 *
 * Flat rather than JSON for the same reason the settings snapshot is: no serialization to set up,
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
        append(item.folderId?.toString().orEmpty()).append(FIELD)
        append(escapeField(item.audioUrl))
    }
}

internal fun decodeShiurCache(raw: String): ShiurCache? {
    val lines = raw.split('\n')
    val writtenAt = lines.firstOrNull()?.trim()?.toLongOrNull() ?: return null
    val items = lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val f = line.split(FIELD)
        if (f.size < FIELD_COUNT) return@mapNotNull null
        val fileId = f[0].toLongOrNull() ?: return@mapNotNull null
        val ravId = f[1].toIntOrNull() ?: return@mapNotNull null
        val audioUrl = unescapeField(f[6]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ShiurItem(
            fileId = fileId,
            ravId = ravId,
            title = unescapeField(f[2]),
            recordedAt = f[3].takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            durationMs = f[4].toLongOrNull() ?: 0L,
            folderId = f[5].takeIf { it.isNotBlank() }?.toIntOrNull(),
            audioUrl = audioUrl,
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
