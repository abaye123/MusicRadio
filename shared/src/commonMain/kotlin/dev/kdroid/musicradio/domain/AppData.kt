package dev.kdroid.musicradio.domain

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { System, Light, Dark }

enum class UiLanguage(val code: String, val label: String, val rtl: Boolean) {
    English("en", "English", false),
    Hebrew("he", "עברית", true),
    French("fr", "Français", false),
    ;

    companion object {
        /**
         * ISO 639 renamed three languages in 1989 and Java kept the old codes for compatibility.
         * Android never moved: `Locale.getLanguage()` there answers "iw" for Hebrew no matter how
         * the locale was built, so the OS language has to come through this map or every Hebrew
         * device reads as English. Desktop is on JDK 17 or later, which returns the modern codes.
         */
        private val LEGACY_CODES = mapOf("iw" to "he", "ji" to "yi", "in" to "id")

        fun fromCode(raw: String): UiLanguage {
            val code = raw.substringBefore('-').substringBefore('_').lowercase()
            val current = LEGACY_CODES[code] ?: code
            return entries.firstOrNull { it.code == current } ?: English
        }
    }
}

/** Seed colours for MaterialKolor. The whole scheme is derived from the one the user picks. */
enum class AccentColor(val seed: Color) {
    Indigo(Color(0xFF4C5BD4)),
    Teal(Color(0xFF00786B)),
    Amber(Color(0xFFB4690E)),
    Rose(Color(0xFFB3245C)),
    Violet(Color(0xFF7A4FCF)),
    Slate(Color(0xFF4F5B62)),
}

@Immutable
data class UserSettings(
    val theme: ThemeMode = ThemeMode.System,
    val accent: AccentColor = AccentColor.Indigo,
    val uiLanguage: UiLanguage = UiLanguage.English,
    /** `true` while the interface follows the OS language rather than an explicit pick. */
    val uiLanguageAuto: Boolean = true,
    val showNews: Boolean = true,
    /** `true` lays out every individual stream as its own card instead of grouping by station. */
    val streamsView: Boolean = false,
    val resumeOnLaunch: Boolean = false,
    val volume: Int = 70,
    val muted: Boolean = false,
)

@Immutable
data class AppData(
    val settings: UserSettings = UserSettings(),
    val favorites: Set<String> = emptySet(),
    /** Channel id of whatever was playing when the app last closed. */
    val lastChannel: String = "",
)

/**
 * [id] is a station id or a channel id - the set holds both, and they cannot collide because a
 * channel id is always `station/channel` while a station id never carries a slash. Storing them
 * together is what lets an existing snapshot keep working: station favorites saved before channels
 * were starrable read back unchanged.
 */
fun AppData.isFavorite(id: String): Boolean = id in favorites

fun AppData.toggleFavorite(id: String): AppData =
    copy(favorites = if (id in favorites) favorites - id else favorites + id)
