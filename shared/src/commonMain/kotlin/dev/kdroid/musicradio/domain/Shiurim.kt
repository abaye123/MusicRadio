package dev.kdroid.musicradio.domain

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDateTime
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.rav_biderman
import musicradio.shared.generated.resources.rav_ettinger
import musicradio.shared.generated.resources.rav_lasri
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/**
 * A rav whose recordings the app can play. A station is one endless URL; a rav is a catalogue with
 * a cursor into it, so the two are separate types that only meet on the stations grid.
 *
 * [id] is the broadcaster id the source site uses, and is what every catalogue call is keyed on.
 */
@Immutable
data class Rav(val id: Int, val name: StringResource, val artwork: DrawableResource) {
    /** Shares the namespace of station and channel ids, and cannot collide: no station id is numeric. */
    val favoriteId: String get() = "rav:$id"
}

object Ravs {
    val all: List<Rav> = listOf(
        Rav(3602, Res.string.rav_biderman, Res.drawable.rav_biderman),
        Rav(1433, Res.string.rav_ettinger, Res.drawable.rav_ettinger),
        Rav(3601, Res.string.rav_lasri, Res.drawable.rav_lasri),
    )

    private val byId: Map<Int, Rav> = all.associateBy { it.id }

    fun of(id: Int): Rav? = byId[id]
}

/**
 * One recording, reduced to what the app renders and plays.
 *
 * [durationMs] is routinely zero: the source publishes a length field and leaves it empty. The
 * player learns the real duration when it opens the file, so a list row shows no length until it
 * has been played once, and everything downstream already treats zero as "not known yet".
 */
@Immutable
data class ShiurItem(
    val fileId: Long,
    val ravId: Int,
    val title: String,
    val recordedAt: LocalDateTime?,
    /** Zero when the source did not say, which is almost always. */
    val durationMs: Long,
    /** The programme this belongs to, matching a [ShiurFolder.folderId]. */
    val folderId: Int?,
    val audioUrl: String,
) {
    val key: String get() = shiurKey(ravId, fileId)

    /** Everything on this source is open; the field stays so callers need not care which source. */
    val playable: Boolean get() = audioUrl.isNotEmpty()
}

/** Stable across launches, and the key both the progress store and the player state agree on. */
fun shiurKey(ravId: Int, fileId: Long): String = "$ravId/$fileId"

/** One of a rav's programmes: the site groups his recordings into a handful of them. */
@Immutable
data class ShiurFolder(val folderId: Int, val ravId: Int, val name: String, val shiurCount: Int?) {
    val favoriteId: String get() = "folder:$ravId/$folderId"
}
