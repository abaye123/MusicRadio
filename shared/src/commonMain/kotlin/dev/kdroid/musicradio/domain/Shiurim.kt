package dev.kdroid.musicradio.domain

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDateTime
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.rav_biderman
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/**
 * The languages a shiur can be recorded in, carrying the ids the Kol Halashon API uses on the wire.
 *
 * Deliberately the app's own type rather than the client library's: the library is not on the
 * browser build (see `ShiurCatalog`), so anything `commonMain` names has to exist without it.
 * Only the languages this app can plausibly need are listed; the API defines eighteen.
 */
enum class ShiurLanguage(val wireId: Int) {
    Any(-1),
    Hebrew(1),
    English(2),
    Yiddish(3),
    French(4),
    ;

    companion object {
        fun fromWireId(id: Int?): ShiurLanguage? = entries.firstOrNull { it.wireId == id }

        /**
         * What to ask for when the user has never chosen: whatever the interface is already in.
         * A language the catalog does not model falls back to Hebrew rather than to [Any], because
         * an unfiltered list of a Hebrew rav is a worse answer than a Hebrew one.
         */
        fun forUi(ui: UiLanguage): ShiurLanguage = when (ui) {
            UiLanguage.Hebrew -> Hebrew
            UiLanguage.English -> English
            UiLanguage.French -> French
        }
    }
}

/**
 * A rav whose shiurim the app can play. A station is one endless URL; a rav is a catalog with a
 * cursor into it, so the two are separate types that only meet on the stations grid.
 */
@Immutable
data class Rav(
    val id: Int,
    val name: StringResource,
    val artwork: DrawableResource,
    /** The chips offered before the catalog has been asked what it actually holds. */
    val languages: List<ShiurLanguage>,
) {
    /** Shares the namespace of station and channel ids, and cannot collide: no station id is numeric. */
    val favoriteId: String get() = "rav:$id"
}

object Ravs {
    val all: List<Rav> = listOf(
        Rav(
            id = 674,
            name = Res.string.rav_biderman,
            artwork = Res.drawable.rav_biderman,
            languages = listOf(ShiurLanguage.Hebrew, ShiurLanguage.Yiddish),
        ),
    )

    private val byId: Map<Int, Rav> = all.associateBy { it.id }

    fun of(id: Int): Rav? = byId[id]
}

/**
 * One shiur, reduced to what the app actually renders and plays.
 *
 * [audioUrl] is a pure function of [fileId] on the server side, which is what lets a resumed shiur
 * start playing from a cached row without any API call at all.
 */
@Immutable
data class ShiurItem(
    val fileId: Long,
    val ravId: Int,
    val title: String,
    val recordedAt: LocalDateTime?,
    /** Zero when the catalog did not say, which happens. */
    val durationMs: Long,
    val language: ShiurLanguage?,
    val folderId: Int?,
    val topic: String?,
    val audioUrl: String,
    /** Subscriber-only. Never auto-selected and never played; the list shows it with a badge. */
    val locked: Boolean,
    val hasAudio: Boolean,
) {
    val key: String get() = shiurKey(ravId, fileId)

    /** What the app is willing to start on its own. */
    val playable: Boolean get() = hasAudio && !locked
}

/** Stable across launches, and the key both the progress store and the player state agree on. */
fun shiurKey(ravId: Int, fileId: Long): String = "$ravId/$fileId"

/**
 * A folder in a rav's own arrangement of his shiurim.
 *
 * [hiddenOnPhone] is honoured rather than ignored: the site hides these on small screens for its
 * own reasons, and a list that disagrees with the site is a list the user cannot cross-check.
 */
@Immutable
data class ShiurFolder(val folderId: Int, val ravId: Int, val name: String, val shiurCount: Int?, val hiddenOnPhone: Boolean) {
    val favoriteId: String get() = "folder:$ravId/$folderId"
}
