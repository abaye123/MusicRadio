package dev.kdroid.musicradio.app

import androidx.compose.runtime.Immutable
import dev.kdroid.musicradio.data.ShiurProgress
import dev.kdroid.musicradio.domain.AppData
import dev.kdroid.musicradio.domain.Rav
import dev.kdroid.musicradio.domain.Ravs
import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.domain.SleepTimer
import dev.kdroid.musicradio.domain.Station
import dev.kdroid.musicradio.domain.StationCategory
import dev.kdroid.musicradio.domain.Stations
import dev.kdroid.musicradio.domain.shiurKey
import dev.kdroid.musicradio.domain.visibleStations
import dev.kdroid.musicradio.player.PlaybackStatus

@Immutable
sealed interface AppDialog {
    data object Hidden : AppDialog
    data object ConfirmReset : AppDialog
}

@Immutable
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val stationId: String = "",
    val channelId: String = "",
    /** The track the stream reports, empty when it reports none. */
    val nowPlaying: String = "",
    /**
     * The shiur playing, or `null` on radio. Radio keeps using [stationId] and [channelId], which
     * stay empty here - so every surface that only knows about stations behaves exactly as before.
     */
    val shiur: ShiurItem? = null,
) {
    val hasStation: Boolean get() = stationId.isNotEmpty()

    val isShiur: Boolean get() = shiur != null

    /** Whether there is anything at all to play, pause or report. */
    val hasSource: Boolean get() = hasStation || isShiur
}

/** The three ways into a rav's catalog. */
enum class RavTab { Continue, All, Folders }

@Immutable
sealed interface ShiurError {
    /** The one the user can do something about: waiting works, retrying does not. */
    data object RateLimited : ShiurError

    @Immutable
    data class Failed(val reason: String?) : ShiurError
}

@Immutable
data class RavScreenState(
    val ravId: Int = 0,
    val tab: RavTab = RavTab.Continue,
    val items: List<ShiurItem> = emptyList(),
    val folders: List<ShiurFolder> = emptyList(),
    /** Non-null while drilled into a folder; [items] then holds that folder's shiurim. */
    val openFolder: ShiurFolder? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: ShiurError? = null,
    /** The chosen language had nothing, so this list is every language instead. */
    val languageFallback: Boolean = false,
) {
    val rav: Rav? get() = Ravs.of(ravId)
}

@Immutable
data class AppState(
    val data: AppData = AppData(),
    val playback: PlaybackState = PlaybackState(),
    val query: String = "",
    val category: StationCategory? = null,
    val dialog: AppDialog = AppDialog.Hidden,
    val message: AppMessage? = null,
    /** Empty where the feature does not ship, which is the browser build. */
    val ravs: List<Rav> = emptyList(),
    val rav: RavScreenState = RavScreenState(),
    /** Keyed by `ravId/fileId`, so a list row can draw its own progress without a lookup table. */
    val shiurProgress: Map<String, ShiurProgress> = emptyMap(),
    val sleepTimer: SleepTimer? = null,
) {
    /** Everything the station list may show, before the search box narrows it further. */
    val browsable: List<Station>
        get() = visibleStations(data.settings.showNews).filter { category == null || it.category == category }

    val favorites: List<Station>
        get() = visibleStations(data.settings.showNews).filter { it.id in data.favorites }

    /** Starred channels, in catalog order, so the favorites grid can list them under the stations. */
    val favoriteChannels: List<ChannelEntry>
        get() = visibleStations(data.settings.showNews)
            .flatMap { station -> station.channels.map { ChannelEntry(station, it) } }
            .filter { it.channel.id in data.favorites }

    /** Starred ravs, for the favorites grid. Empty where the feature does not ship. */
    val favoriteRavs: List<Rav>
        get() = ravs.filter { it.favoriteId in data.favorites }

    val currentStation: Station? get() = Stations.of(playback.stationId)

    val currentChannel get() = Stations.channel(playback.channelId)

    /** Whether the rav card belongs on the stations grid at all. */
    val showRavs: Boolean
        get() = ravs.isNotEmpty() && (category == null || category == StationCategory.Torah)

    fun progressOf(shiur: ShiurItem): ShiurProgress? = shiurProgress[shiur.key]

    fun progressOf(ravId: Int, fileId: Long): ShiurProgress? = shiurProgress[shiurKey(ravId, fileId)]
}
