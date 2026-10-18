package dev.kdroid.musicradio.data

import dev.kdroid.musicradio.domain.AccentColor
import dev.kdroid.musicradio.domain.AppData
import dev.kdroid.musicradio.domain.SavedSong
import dev.kdroid.musicradio.domain.ThemeMode
import dev.kdroid.musicradio.domain.UiLanguage
import dev.kdroid.musicradio.domain.UserSettings

/**
 * A flat `key=value` snapshot of everything the app remembers between launches. Deliberately not
 * JSON: the file is small, hand-readable, and an unknown or malformed key falls back to its default
 * instead of taking the whole snapshot down with it.
 */
private const val KEY_THEME = "theme"
private const val KEY_ACCENT = "accent"
private const val KEY_LANGUAGE = "language"
private const val KEY_LANGUAGE_AUTO = "languageAuto"
private const val KEY_SHOW_NEWS = "showNews"
private const val KEY_STREAMS_VIEW = "streamsView"
private const val KEY_RESUME = "resumeOnLaunch"
private const val KEY_VOLUME = "volume"
private const val KEY_MUTED = "muted"
private const val KEY_FAVORITES = "favorites"
private const val KEY_LAST_CHANNEL = "lastChannel"
private const val KEY_SONG = "song"

fun encodeSnapshot(data: AppData): String {
    val s = data.settings
    return buildList {
        add("$KEY_THEME=${s.theme.name}")
        add("$KEY_ACCENT=${s.accent.name}")
        add("$KEY_LANGUAGE=${s.uiLanguage.code}")
        add("$KEY_LANGUAGE_AUTO=${s.uiLanguageAuto}")
        add("$KEY_SHOW_NEWS=${s.showNews}")
        add("$KEY_STREAMS_VIEW=${s.streamsView}")
        add("$KEY_RESUME=${s.resumeOnLaunch}")
        add("$KEY_VOLUME=${s.volume}")
        add("$KEY_MUTED=${s.muted}")
        add("$KEY_FAVORITES=${data.favorites.sorted().joinToString(",")}")
        add("$KEY_LAST_CHANNEL=${data.lastChannel}")
        // One line per song, repeating the key: a title can hold any character the other keys
        // would split on, short of the tab and the newline, which saving strips out.
        data.savedSongs.forEach { add("$KEY_SONG=${it.savedAt}\t${it.channelId}\t${it.title}") }
    }.joinToString("\n")
}

fun decodeSnapshot(raw: String): AppData {
    val songs = raw.lineSequence()
        .filter { it.startsWith("$KEY_SONG=") }
        .mapNotNull { line ->
            val fields = line.substringAfter('=').split('\t', limit = 3)
            val savedAt = fields.firstOrNull()?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val title = fields.getOrNull(2)?.trim().orEmpty()
            if (title.isEmpty()) null else SavedSong(title, fields[1].trim(), savedAt)
        }
        .toList()
    val map = raw.lineSequence()
        .mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        .toMap()

    fun flag(key: String, fallback: Boolean) = map[key]?.toBooleanStrictOrNull() ?: fallback

    val defaults = UserSettings()
    val settings = UserSettings(
        theme = map[KEY_THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: defaults.theme,
        accent = map[KEY_ACCENT]?.let { name -> AccentColor.entries.firstOrNull { it.name == name } } ?: defaults.accent,
        uiLanguage = map[KEY_LANGUAGE]?.let { UiLanguage.fromCode(it) } ?: defaults.uiLanguage,
        uiLanguageAuto = flag(KEY_LANGUAGE_AUTO, defaults.uiLanguageAuto),
        showNews = flag(KEY_SHOW_NEWS, defaults.showNews),
        streamsView = flag(KEY_STREAMS_VIEW, defaults.streamsView),
        resumeOnLaunch = flag(KEY_RESUME, defaults.resumeOnLaunch),
        volume = map[KEY_VOLUME]?.toIntOrNull()?.coerceIn(0, 100) ?: defaults.volume,
        muted = flag(KEY_MUTED, defaults.muted),
    )
    return AppData(
        settings = settings,
        favorites = map[KEY_FAVORITES].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
        lastChannel = map[KEY_LAST_CHANNEL].orEmpty(),
        savedSongs = songs,
    )
}
