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
        fun fromCode(raw: String): UiLanguage {
            val code = raw.substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.code == code } ?: English
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

fun AppData.isFavorite(stationId: String): Boolean = stationId in favorites

fun AppData.toggleFavorite(stationId: String): AppData =
    copy(favorites = if (stationId in favorites) favorites - stationId else favorites + stationId)
