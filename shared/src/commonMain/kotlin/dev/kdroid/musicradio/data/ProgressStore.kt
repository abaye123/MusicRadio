package dev.kdroid.musicradio.data

import androidx.compose.runtime.Immutable
import dev.kdroid.musicradio.platform.Platform
import dev.kdroid.musicradio.platform.joinPath
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** How close to the end counts as heard: the last half minute is credits, not content. */
private const val FINISHED_TAIL_MS = 30_000L

/**
 * The most rows kept. Five hundred is about 20 KB and covers years of listening; the point is
 * simply that this file must not grow without bound on a phone.
 */
internal const val PROGRESS_ROW_LIMIT = 500

@Immutable
data class ShiurProgress(
    val positionMs: Long,
    /** Zero when it was never learned, which is why [finished] is stored rather than derived. */
    val durationMs: Long,
    val finished: Boolean,
    /** Epoch millis, and the key the row limit evicts on. */
    val updatedAt: Long,
) {
    /** `0f` until the duration is known, so a list row starts empty rather than full. */
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Anything more than a moment in counts as started, so a stray tap does not litter the list. */
    val started: Boolean get() = !finished && positionMs > FINISHED_TAIL_MS
}

/**
 * Whether a position that far into something that long means the user is done with it.
 *
 * Kept as a function rather than a field on [ShiurProgress] because it is asked at the moment a
 * position arrives, before there is a row to ask.
 */
fun isFinishedAt(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs - FINISHED_TAIL_MS

/**
 * Where each shiur was left off.
 *
 * Deliberately not part of [AppStore]. That snapshot is rewritten whole on every settings change
 * and is meant to stay hand-readable; this one changes every few seconds while audio plays, and
 * mixing the two would mean a stuck write to either could take the other down with it.
 */
interface ProgressStore {
    fun load(): Map<String, ShiurProgress>
    fun save(rows: Map<String, ShiurProgress>)
    fun clear()
}

class MemoryProgressStore(private var rows: Map<String, ShiurProgress> = emptyMap()) : ProgressStore {
    override fun load(): Map<String, ShiurProgress> = rows

    override fun save(rows: Map<String, ShiurProgress>) {
        this.rows = capProgressRows(rows)
    }

    override fun clear() {
        rows = emptyMap()
    }
}

@ContributesBinding(AppScope::class)
@Inject
class FileProgressStore(private val dir: () -> String = { Platform.appDir() }) : ProgressStore {
    private val file get() = joinPath(dir(), "shiurim.txt")

    override fun load(): Map<String, ShiurProgress> {
        val raw = Platform.readText(file)
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { decodeProgress(raw) }.getOrElse { emptyMap() }
    }

    override fun save(rows: Map<String, ShiurProgress>) {
        Platform.writeText(file, encodeProgress(rows))
    }

    override fun clear() {
        Platform.delete(file)
    }
}

/** Newest [PROGRESS_ROW_LIMIT] rows by [ShiurProgress.updatedAt]; the rest are forgotten. */
internal fun capProgressRows(rows: Map<String, ShiurProgress>): Map<String, ShiurProgress> {
    if (rows.size <= PROGRESS_ROW_LIMIT) return rows
    return rows.entries
        .sortedByDescending { it.value.updatedAt }
        .take(PROGRESS_ROW_LIMIT)
        .associate { it.key to it.value }
}

/**
 * One row per shiur: `ravId/fileId=positionMs,durationMs,finished,updatedAt`.
 *
 * Same discipline as [encodeSnapshot]: flat, hand-readable, and a row that cannot be read is
 * dropped rather than taking the file down with it. Losing one shiur's position is a shrug;
 * losing every position because one row was truncated by a half-finished write is not.
 */
fun encodeProgress(rows: Map<String, ShiurProgress>): String = capProgressRows(rows).entries
    .sortedBy { it.key }
    .joinToString("\n") { (key, p) ->
        "$key=${p.positionMs},${p.durationMs},${if (p.finished) 1 else 0},${p.updatedAt}"
    }

fun decodeProgress(raw: String): Map<String, ShiurProgress> = raw.lineSequence()
    .mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) return@mapNotNull null
        val key = line.substring(0, split).trim()
        val parts = line.substring(split + 1).split(',')
        if (key.isEmpty() || parts.size < 4) return@mapNotNull null
        val position = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
        val duration = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
        val finished = parts[2].trim() == "1"
        val updatedAt = parts[3].trim().toLongOrNull() ?: return@mapNotNull null
        key to ShiurProgress(
            positionMs = position.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            finished = finished,
            updatedAt = updatedAt,
        )
    }
    .toMap()
