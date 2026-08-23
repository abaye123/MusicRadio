package dev.kdroid.musicradio.app

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import dev.kdroid.musicradio.data.AppStore
import dev.kdroid.musicradio.data.CatalogResult
import dev.kdroid.musicradio.data.ProgressStore
import dev.kdroid.musicradio.data.SHIUR_PAGE_SIZE
import dev.kdroid.musicradio.data.ShiurProgress
import dev.kdroid.musicradio.data.ShiurRepository
import dev.kdroid.musicradio.data.isFinishedAt
import dev.kdroid.musicradio.data.seedData
import dev.kdroid.musicradio.domain.Channel
import dev.kdroid.musicradio.domain.Rav
import dev.kdroid.musicradio.domain.Ravs
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.domain.ShiurLanguage
import dev.kdroid.musicradio.domain.SleepTimer
import dev.kdroid.musicradio.domain.Station
import dev.kdroid.musicradio.domain.Stations
import dev.kdroid.musicradio.domain.UserSettings
import dev.kdroid.musicradio.domain.effectiveShiurLanguage
import dev.kdroid.musicradio.domain.shiurKey
import dev.kdroid.musicradio.domain.toggleFavorite
import dev.kdroid.musicradio.platform.Platform
import dev.kdroid.musicradio.platform.localizedString
import dev.kdroid.musicradio.platform.systemUiLanguage
import dev.kdroid.musicradio.player.IcyMetadata
import dev.kdroid.musicradio.player.MediaCommand
import dev.kdroid.musicradio.player.MediaControls
import dev.kdroid.musicradio.player.NoMediaControls
import dev.kdroid.musicradio.player.NowPlaying
import dev.kdroid.musicradio.player.PlaybackProgress
import dev.kdroid.musicradio.player.PlaybackStatus
import dev.kdroid.musicradio.player.RadioPlayer
import dev.kdroid.musicradio.player.mediaArtworkUri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.santimattius.structured.annotations.StructuredScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tracks run minutes, and each poll costs a real (if small) read off the stream. */
private const val METADATA_POLL_MS = 20_000L

/** Rewinding a little on resume is what makes a shiur pick up mid-sentence rather than mid-word. */
private const val RESUME_REWIND_MS = 10_000L

/** How far the transport buttons jump. */
const val SKIP_STEP_MS = 15_000L

/** Positions are written this often while playing, plus immediately on every transition. */
private const val PROGRESS_SAVE_MS = 5_000L

/** Close enough to the end to count as over, and to move on. */
private const val END_OF_SHIUR_MS = 1_000L

/** How many pages to walk looking for something unheard before giving up and taking the newest. */
private const val AUTO_PICK_MAX_PAGES = 5

private const val SLEEP_TICK_MS = 1_000L

/**
 * The parts of playback that change several times a second.
 *
 * Kept out of [AppState] on purpose. A scrubber needs four updates a second, and putting those in
 * the single app state would hand every screen that reads it four recompositions a second for a
 * clock most of them do not draw. Only the player surfaces collect this.
 */
@Immutable
data class PlayerTick(
    val progress: PlaybackProgress = PlaybackProgress(),
    /** Millis left on a running [SleepTimer.After]; `0` when no countdown is running. */
    val sleepRemainingMs: Long = 0,
)

@AssistedInject
class AppViewModel(
    private val store: AppStore,
    private val progressStore: ProgressStore,
    private val shiurim: ShiurRepository,
    private val player: RadioPlayer,
    private val mediaControls: MediaControls = NoMediaControls,
    /**
     * Deliberately non-null and without a default. Metro keys bindings on the full Kotlin type,
     * nullability included, so `IcyMetadata?` matches no binding - and rather than failing the
     * build it quietly took the default, leaving [watchNowPlaying] with nothing to poll and the
     * track title permanently blank on every platform. Written this way, the same mistake is a
     * compile error instead of a feature that is simply never there.
     */
    private val icyMetadata: IcyMetadata,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    @Assisted private val onQuit: () -> Unit = {},
) : ViewModel() {

    @AssistedFactory
    fun interface Factory {
        fun create(onQuit: () -> Unit): AppViewModel
    }

    private val job = SupervisorJob()

    @StructuredScope
    private val scope = CoroutineScope(job + dispatcher)

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _tick = MutableStateFlow(PlayerTick())
    val tick: StateFlow<PlayerTick> = _tick.asStateFlow()

    val backStack: NavBackStack<AppKey> = NavBackStack(AppKey.Stations)

    private var saveJob: Job? = null
    private var progressSaveJob: Job? = null
    private var catalogJob: Job? = null

    /** Epoch millis a [SleepTimer.After] fires at, or `null`. */
    private var sleepDeadline: Long? = null

    init {
        applyVolume(_state.value.data.settings)
        scope.launch {
            player.status.collect { status -> onPlaybackStatus(status) }
        }
        watchProgress()
        bindMediaControls()
        watchNowPlaying()
        runSleepTimer()
        resumeOnLaunch()
    }

    override fun onCleared() {
        flushProgress()
        mediaControls.release()
        player.release()
        job.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------ startup

    private fun restore(): AppState {
        var data = store.load()
        // Re-resolve on every launch: the OS language can change between runs.
        if (data.settings.uiLanguageAuto) {
            data = data.copy(settings = data.settings.copy(uiLanguage = systemUiLanguage()))
        }
        val channel = Stations.channel(data.lastChannel)
        val station = channel?.let { Stations.stationOfChannel(it.id) }
        if (channel == null && data.lastChannel.isNotEmpty()) {
            // The catalog moved on: forget a channel that no longer exists.
            data = data.copy(lastChannel = "")
        }
        val ravs = if (shiurim.available) Ravs.all else emptyList()
        if (ravs.isEmpty() && data.lastShiur.isNotEmpty()) data = data.copy(lastShiur = "")
        return AppState(
            data = data,
            playback = PlaybackState(
                status = PlaybackStatus.Idle,
                stationId = station?.id.orEmpty(),
                channelId = channel?.id.orEmpty(),
            ),
            ravs = ravs,
            shiurProgress = runCatching { progressStore.load() }.getOrElse { emptyMap() },
        )
    }

    /**
     * Brings back whatever was playing last - which may have been a shiur.
     *
     * The shiur path deliberately reads the on-disk page cache rather than the network: the audio
     * URL is derived from the file id, so a cached row plus a saved position is enough to be
     * playing before a single API call has been made, or when none can be.
     */
    private fun resumeOnLaunch() {
        val state = _state.value
        if (!state.data.settings.resumeOnLaunch) return
        val lastShiur = state.data.lastShiur
        if (lastShiur.isNotEmpty()) {
            val (ravId, fileId) = parseShiurKey(lastShiur) ?: return
            val cached = shiurim.cachedFirstPage(ravId, state.data.settings.effectiveShiurLanguage())
            val shiur = cached.firstOrNull { it.fileId == fileId } ?: return
            startShiur(shiur, resume = true)
            return
        }
        val channel = state.playback.channelId
        if (channel.isNotEmpty()) Stations.channel(channel)?.let { player.play(it.streamUrl) }
    }

    // ------------------------------------------------------------------ media centre

    /**
     * Hardware media keys and the OS media flyout drive the app through the same intents the UI
     * uses, so there is one code path for "pause", not two.
     */
    private fun bindMediaControls() {
        if (mediaControls.available) {
            mediaControls.attach { command ->
                when (command) {
                    MediaCommand.Toggle -> onIntent(AppIntent.TogglePlay)

                    MediaCommand.Play -> if (!_state.value.playback.status.active) onIntent(AppIntent.TogglePlay)

                    MediaCommand.Pause -> if (_state.value.playback.status.active) onIntent(AppIntent.TogglePlay)

                    MediaCommand.Next -> onIntent(AppIntent.NextStation)

                    MediaCommand.Previous -> onIntent(AppIntent.PreviousStation)

                    MediaCommand.Stop -> onIntent(AppIntent.Stop)

                    // Dropped by the intents themselves when nothing seekable is playing, which
                    // is where that is actually known.
                    is MediaCommand.SeekBy -> onIntent(AppIntent.SkipBy(command.offsetMs))

                    is MediaCommand.SetPosition -> onIntent(AppIntent.SeekTo(command.positionMs))
                }
            }
        }
        // Runs whether or not MediaControls exists. Android has no implementation of it, yet its
        // player still needs these names for the media session behind the notification and the
        // lock screen - gating this on `available` left that session permanently blank.
        scope.launch {
            _state
                .map { it.playback }
                .distinctUntilChanged()
                .collect { playback -> publishNowPlaying(playback) }
        }
    }

    private suspend fun publishNowPlaying(playback: PlaybackState) {
        val language = _state.value.data.settings.uiLanguage.code
        val shiur = playback.shiur
        if (shiur != null) {
            publishShiurNowPlaying(shiur, playback, language)
            return
        }
        val station = Stations.of(playback.stationId)
        val stationName = station?.let { runCatching { localizedString(language, it.name) }.getOrNull() }.orEmpty()
        val channel = Stations.channel(playback.channelId)
        val channelLabel = channel?.title ?: stationName
        // Most stations send "Artist - Track"; when they don't, the whole string is the title.
        val song = playback.nowPlaying
        val separator = song.indexOf(" - ")
        val songArtist = if (separator > 0) song.take(separator).trim() else ""
        val songTitle = if (separator > 0) song.drop(separator + 3).trim() else song.trim()
        // A channel only carries its own cover when the broadcaster publishes one;
        // otherwise the station logo stands in, exactly as in the station list.
        val artworkUri = mediaArtworkUri(
            id = playback.channelId.ifEmpty { playback.stationId },
            artwork = channel?.artwork ?: station?.artwork,
        )
        mediaControls.update(
            NowPlaying(
                station = stationName,
                title = songTitle.ifBlank { channelLabel },
                artist = songArtist.ifBlank { if (song.isBlank()) channelLabel else stationName },
                artworkUri = artworkUri,
            ),
            playback.status,
            _tick.value.progress,
        )
        // Android reads this off the player's own session rather than through MediaControls.
        player.setNowPlaying(station = channelLabel, song = song, artworkUri = artworkUri)
    }

    private suspend fun publishShiurNowPlaying(shiur: ShiurItem, playback: PlaybackState, language: String) {
        val rav = Ravs.of(shiur.ravId)
        val ravName = rav?.let { runCatching { localizedString(language, it.name) }.getOrNull() }.orEmpty()
        val artworkUri = mediaArtworkUri(id = "rav-${shiur.ravId}", artwork = rav?.artwork)
        mediaControls.update(
            // Title and artist the way a podcast app fills them: the episode on top, the speaker
            // underneath. The rav is the constant, so it reads better as the artist.
            NowPlaying(station = ravName, title = shiur.title, artist = ravName, artworkUri = artworkUri),
            playback.status,
            _tick.value.progress,
        )
        player.setNowPlaying(station = ravName, song = shiur.title, artworkUri = artworkUri)
    }

    /**
     * Reads the track title off the stream while it plays. Keyed on the channel and whether audio
     * is running - not on the state as a whole, or writing the title back would restart the poll
     * that produced it. A shiur has no ICY metadata and never gets here: its channel id is empty.
     */
    private fun watchNowPlaying() {
        scope.launch {
            _state
                .map { it.playback.channelId to it.playback.status.active }
                .distinctUntilChanged()
                .collectLatest { (channelId, active) ->
                    if (!active || channelId.isEmpty()) {
                        setNowPlaying("")
                        return@collectLatest
                    }
                    val url = Stations.channel(channelId)?.streamUrl ?: return@collectLatest
                    while (isActive) {
                        setNowPlaying(icyMetadata.fetchTitle(url).orEmpty())
                        delay(METADATA_POLL_MS)
                    }
                }
        }
    }

    private fun setNowPlaying(title: String) {
        mutate { s ->
            if (s.playback.nowPlaying == title) s else s.copy(playback = s.playback.copy(nowPlaying = title))
        }
    }

    // ------------------------------------------------------------------ intents

    @Suppress("CyclomaticComplexMethod")
    fun onIntent(intent: AppIntent) {
        when (intent) {
            AppIntent.Quit -> {
                flushProgress()
                player.stop()
                onQuit()
            }

            is AppIntent.SelectStation -> selectStation(intent.stationId)

            is AppIntent.SelectChannel -> selectChannel(intent.channelId)

            AppIntent.TogglePlay -> togglePlay()

            AppIntent.Stop -> {
                flushProgress()
                player.stop()
                mutate { it.copy(playback = it.playback.copy(status = PlaybackStatus.Idle)) }
            }

            AppIntent.NextStation -> if (_state.value.playback.isShiur) stepShiur(1) else step(1)

            AppIntent.PreviousStation -> if (_state.value.playback.isShiur) stepShiur(-1) else step(-1)

            AppIntent.NextShiur -> stepShiur(1)

            AppIntent.PreviousShiur -> stepShiur(-1)

            is AppIntent.SkipBy -> skipBy(intent.deltaMs)

            is AppIntent.SeekTo -> seekTo(intent.positionMs)

            is AppIntent.SetVolume -> setVolume(intent.percent)

            AppIntent.ToggleMute -> toggleMute()

            is AppIntent.OpenUrl -> Platform.openUrl(intent.url)

            AppIntent.ConfirmDialog -> confirmDialog()

            is AppIntent.OpenRav -> openRav(intent.ravId)

            is AppIntent.PlayShiur -> startShiur(intent.shiur, resume = true)

            is AppIntent.SetShiurLanguage -> setShiurLanguage(intent.language)

            is AppIntent.OpenFolder -> openFolder(intent.folder)

            AppIntent.CloseFolder -> closeFolder()

            AppIntent.LoadMoreShiurim -> loadMore()

            AppIntent.RetryShiurim -> loadFirstPage(_state.value.rav.ravId)

            is AppIntent.SetSleepTimer -> setSleepTimer(intent.timer)

            else -> {
                applyNavigation(intent)
                mutate { reduce(it, intent) }
                afterReduce(intent)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun reduce(s: AppState, intent: AppIntent): AppState = when (intent) {
        is AppIntent.Navigate -> s.copy(message = null)

        AppIntent.OpenNowPlaying, AppIntent.Back -> s

        is AppIntent.ToggleFavorite -> s.copy(data = s.data.toggleFavorite(intent.id))

        is AppIntent.SetSearchQuery -> s.copy(query = intent.query)

        is AppIntent.SetCategory -> s.copy(category = intent.category)

        is AppIntent.SelectRavTab -> s.copy(rav = s.rav.copy(tab = intent.tab))

        is AppIntent.SetTheme -> s.updateSettings { it.copy(theme = intent.mode) }

        is AppIntent.SetAccent -> s.updateSettings { it.copy(accent = intent.accent) }

        is AppIntent.SetUiLanguage -> s.updateSettings {
            it.copy(uiLanguage = intent.language ?: systemUiLanguage(), uiLanguageAuto = intent.language == null)
        }

        is AppIntent.SetShowNews -> s.updateSettings { it.copy(showNews = intent.on) }

        is AppIntent.SetStreamsView -> s.updateSettings { it.copy(streamsView = intent.on) }

        is AppIntent.SetResumeOnLaunch -> s.updateSettings { it.copy(resumeOnLaunch = intent.on) }

        AppIntent.ResetApp -> s.copy(dialog = AppDialog.ConfirmReset)

        AppIntent.DismissDialog -> s.copy(dialog = AppDialog.Hidden)

        AppIntent.DismissMessage -> s.copy(message = null)

        else -> s
    }

    private fun afterReduce(intent: AppIntent) {
        when (intent) {
            is AppIntent.SetSearchQuery, is AppIntent.SetCategory,
            AppIntent.DismissMessage, AppIntent.DismissDialog,
            is AppIntent.Navigate, AppIntent.OpenNowPlaying, AppIntent.Back,
            is AppIntent.SelectRavTab,
            -> Unit

            // Hiding the news category can pull the playing station out from under the user.
            is AppIntent.SetShowNews -> {
                if (!intent.on) stopIfHidden()
                persist()
            }

            else -> persist()
        }
    }

    private fun applyNavigation(intent: AppIntent) {
        when (intent) {
            is AppIntent.Navigate -> setMain(intent.destination)

            AppIntent.OpenNowPlaying -> if (backStack.lastOrNull() != AppKey.NowPlaying) backStack.add(AppKey.NowPlaying)

            // removeAt, not removeLast(): Kotlin resolves removeLast() to the java.util.List
            // method added in Java 21, which only exists from API 35. On Android 14 and below the
            // call throws NoSuchMethodError, so every press of Back crashed the app there.
            AppIntent.Back -> if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)

            else -> Unit
        }
    }

    private fun setMain(key: AppKey) {
        backStack.clear()
        backStack.add(key)
    }

    // ------------------------------------------------------------------ radio

    private fun selectStation(stationId: String) {
        val station = Stations.of(stationId) ?: return
        val current = _state.value.playback
        // Tapping the station you are already on is a play/pause toggle, not a restart.
        if (current.stationId == stationId && current.status.active) {
            player.pause()
            return
        }
        val channel = station.channels.firstOrNull { it.id == current.channelId }
            ?: station.channels.first()
        start(station, channel.id)
    }

    private fun selectChannel(channelId: String) {
        val station = Stations.stationOfChannel(channelId) ?: return
        start(station, channelId)
    }

    private fun start(station: Station, channelId: String) {
        val channel = station.channels.firstOrNull { it.id == channelId } ?: return
        flushProgress()
        mutate {
            it.copy(
                playback = PlaybackState(PlaybackStatus.Buffering, station.id, channel.id),
                data = it.data.copy(lastChannel = channel.id, lastShiur = ""),
                message = null,
            )
        }
        _tick.value = _tick.value.copy(progress = PlaybackProgress())
        player.play(channel.streamUrl)
        persist()
    }

    private fun togglePlay() {
        val playback = _state.value.playback
        val shiur = playback.shiur
        if (shiur != null) {
            if (playback.status.active) player.pause() else resumeShiur(shiur, playback)
            return
        }
        val channel = Stations.channel(playback.channelId)
        when {
            channel == null -> {
                // Nothing has been picked yet: start at the top of the list the user is looking at.
                val first = _state.value.browsable.firstOrNull() ?: return
                start(first, first.channels.first().id)
            }

            playback.status.active -> player.pause()

            else -> {
                // Rodio and Media3 both drop a finished live stream, so a cold resume replays the URL.
                if (playback.status == PlaybackStatus.Paused) player.resume() else player.play(channel.streamUrl)
                mutate { it.copy(playback = it.playback.copy(status = PlaybackStatus.Buffering), message = null) }
            }
        }
    }

    private fun step(delta: Int) {
        val list = _state.value.browsable
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _state.value.playback.stationId }
        val next = if (index < 0) 0 else ((index + delta) % list.size + list.size) % list.size
        val station = list[next]
        start(station, station.channels.first().id)
    }

    private fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        mutate { it.updateSettings { s -> s.copy(volume = clamped, muted = clamped == 0 && s.muted) } }
        applyVolume(_state.value.data.settings)
        persist()
    }

    private fun toggleMute() {
        mutate { it.updateSettings { s -> s.copy(muted = !s.muted) } }
        applyVolume(_state.value.data.settings)
        persist()
    }

    private fun applyVolume(settings: UserSettings) {
        val level = if (settings.muted) 0 else settings.volume
        player.setVolume(level)
        mediaControls.setVolume(level)
    }

    /** A station that just left the visible list must not keep playing behind a hidden row. */
    private fun stopIfHidden() {
        val id = _state.value.playback.stationId
        if (id.isEmpty()) return
        val stillVisible = _state.value.browsable.any { it.id == id }
        if (stillVisible) return
        player.stop()
        mutate { it.copy(playback = PlaybackState()) }
    }

    private fun onPlaybackStatus(status: PlaybackStatus) {
        mutate { current ->
            if (!current.playback.hasSource && status != PlaybackStatus.Error) {
                current
            } else {
                current.copy(
                    playback = current.playback.copy(status = status),
                    message = if (status == PlaybackStatus.Error) AppMessage.StreamFailed else current.message,
                )
            }
        }
    }

    // ------------------------------------------------------------------ shiurim

    /**
     * Opens a rav and starts the right shiur, in that order.
     *
     * "The right shiur" is the newest one the user has not finished, resumed ten seconds before
     * where they stopped. Walking the newest-first list and skipping what is already finished is
     * all three of the rules this feature was asked for, in one pass: nothing heard yet gives the
     * newest from zero, something half-heard gives that one back, and a finished newest is stepped
     * over to the one before it, for as many as are finished.
     */
    private fun openRav(ravId: Int) {
        if (!shiurim.available || Ravs.of(ravId) == null) return
        if (backStack.lastOrNull() != AppKey.Rav(ravId)) backStack.add(AppKey.Rav(ravId))
        val already = _state.value.rav
        mutate {
            it.copy(
                rav = if (already.ravId == ravId && already.items.isNotEmpty()) {
                    already.copy(openFolder = null)
                } else {
                    RavScreenState(ravId = ravId, loading = true)
                },
            )
        }
        loadFirstPage(ravId, autoPlay = true)
    }

    private fun loadFirstPage(ravId: Int, autoPlay: Boolean = false) {
        if (ravId == 0) return
        catalogJob?.cancel()
        mutate { it.copy(rav = it.rav.copy(loading = true, error = null, languageFallback = false)) }
        catalogJob = scope.launch {
            val chosen = _state.value.data.settings.effectiveShiurLanguage()
            var fallback = false
            var result = shiurim.page(ravId, chosen)
            // An empty answer is not an error, but it looks exactly like one on screen. A rav with
            // nothing in the chosen language is common - Biderman has no French - so widen once
            // rather than showing a blank list under a language chip.
            if (result is CatalogResult.Ok && result.value.items.isEmpty() && chosen != ShiurLanguage.Any) {
                fallback = true
                result = shiurim.page(ravId, ShiurLanguage.Any)
            }
            when (result) {
                is CatalogResult.Ok -> {
                    mutate {
                        it.copy(
                            rav = it.rav.copy(
                                ravId = ravId,
                                items = result.value.items,
                                loading = false,
                                hasMore = result.value.hasMore,
                                error = null,
                                languageFallback = fallback,
                            ),
                        )
                    }
                    if (autoPlay) autoPlay(result.value.items)
                    loadFolders(ravId)
                }

                else -> mutate { it.copy(rav = it.rav.copy(loading = false, error = result.toError())) }
            }
        }
    }

    private suspend fun loadFolders(ravId: Int) {
        if (_state.value.rav.folders.isNotEmpty()) return
        val result = shiurim.folders(ravId)
        if (result is CatalogResult.Ok) {
            val visible = result.value.filterNot { Platform.isPhone && it.hiddenOnPhone }
            mutate { it.copy(rav = it.rav.copy(folders = visible)) }
        }
    }

    private suspend fun autoPlay(firstPage: List<ShiurItem>) {
        // Never interrupt: opening a rav while something is already running is browsing, not a
        // request to change what is playing.
        if (_state.value.playback.status.active) return
        val target = pickResumeTarget(firstPage) ?: return
        startShiur(target, resume = true)
    }

    private suspend fun pickResumeTarget(firstPage: List<ShiurItem>): ShiurItem? {
        val ravId = _state.value.rav.ravId
        val language = _state.value.data.settings.effectiveShiurLanguage()
        var page = firstPage
        var fromRow = 0
        var walked = 0
        var newest: ShiurItem? = null
        while (walked < AUTO_PICK_MAX_PAGES) {
            val playable = page.filter { it.playable }
            if (newest == null) newest = playable.firstOrNull()
            playable.firstOrNull { _state.value.progressOf(it)?.finished != true }?.let { return it }
            if (page.size < SHIUR_PAGE_SIZE) break
            fromRow += SHIUR_PAGE_SIZE
            val next = shiurim.page(ravId, language, fromRow)
            if (next !is CatalogResult.Ok || next.value.items.isEmpty()) break
            page = next.value.items
            walked++
        }
        // Everything within reach has been heard. Replaying the newest beats playing nothing.
        return newest
    }

    private fun startShiur(shiur: ShiurItem, resume: Boolean) {
        if (!shiur.playable) return
        flushProgress()
        val saved = _state.value.progressOf(shiur)
        val startAt = when {
            !resume || saved == null || saved.finished -> 0L
            else -> (saved.positionMs - RESUME_REWIND_MS).coerceAtLeast(0)
        }
        mutate {
            it.copy(
                playback = PlaybackState(status = PlaybackStatus.Buffering, shiur = shiur),
                data = it.data.copy(lastShiur = shiur.key, lastChannel = ""),
                message = null,
            )
        }
        _tick.value = _tick.value.copy(
            progress = PlaybackProgress(positionMs = startAt, durationMs = shiur.durationMs, seekable = true),
        )
        player.play(shiur.audioUrl, startAtMs = startAt, seekable = true)
        persist()
    }

    private fun resumeShiur(shiur: ShiurItem, playback: PlaybackState) {
        if (playback.status == PlaybackStatus.Paused) {
            player.resume()
            mutate { it.copy(playback = it.playback.copy(status = PlaybackStatus.Buffering)) }
            return
        }
        // Stopped or errored: the source is gone and has to be opened again, from where it was.
        startShiur(shiur, resume = true)
    }

    /**
     * Steps through the visible list. `+1` goes down it - older, the direction "keep going" runs
     * in; `-1` goes back up towards the newest.
     */
    private fun stepShiur(delta: Int) {
        val current = _state.value.playback.shiur ?: return
        val list = _state.value.rav.items.filter { it.playable }
        val index = list.indexOfFirst { it.fileId == current.fileId }
        if (index < 0) return
        val next = list.getOrNull(index + delta) ?: return
        startShiur(next, resume = true)
    }

    private fun skipBy(deltaMs: Long) {
        val progress = _tick.value.progress
        if (!progress.seekable) return
        seekTo(progress.positionMs + deltaMs)
    }

    private fun seekTo(positionMs: Long) {
        val progress = _tick.value.progress
        if (!progress.seekable) return
        val duration = progress.durationMs
        val target = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        player.seekTo(target)
        _tick.value = _tick.value.copy(progress = progress.copy(positionMs = target))
        // The OS flyout interpolates from whatever position it was last handed, so after a jump it
        // is wrong until it is told. Publishing here and on state changes is enough; publishing on
        // every tick would be four D-Bus messages a second for a number the OS can work out.
        scope.launch { publishNowPlaying(_state.value.playback) }
    }

    private fun setShiurLanguage(language: ShiurLanguage?) {
        mutate { it.updateSettings { s -> s.copy(shiurLanguage = language) } }
        persist()
        val ravId = _state.value.rav.ravId
        scope.launch {
            shiurim.invalidate()
            if (ravId != 0) loadFirstPage(ravId)
        }
    }

    private fun openFolder(folder: dev.kdroid.musicradio.domain.ShiurFolder) {
        catalogJob?.cancel()
        mutate { it.copy(rav = it.rav.copy(openFolder = folder, items = emptyList(), loading = true, error = null)) }
        catalogJob = scope.launch {
            val language = _state.value.data.settings.effectiveShiurLanguage()
            val result = shiurim.page(folder.ravId, language, fromRow = 0, folderId = folder.folderId)
            mutate {
                when (result) {
                    is CatalogResult.Ok -> it.copy(
                        rav = it.rav.copy(
                            items = result.value.items,
                            loading = false,
                            hasMore = result.value.hasMore,
                            error = null,
                        ),
                    )

                    else -> it.copy(rav = it.rav.copy(loading = false, error = result.toError()))
                }
            }
        }
    }

    private fun closeFolder() {
        catalogJob?.cancel()
        val ravId = _state.value.rav.ravId
        mutate { it.copy(rav = it.rav.copy(openFolder = null, items = emptyList(), loading = true)) }
        loadFirstPage(ravId)
    }

    private fun loadMore() {
        val rav = _state.value.rav
        if (rav.loading || rav.loadingMore || !rav.hasMore || rav.ravId == 0) return
        mutate { it.copy(rav = it.rav.copy(loadingMore = true)) }
        scope.launch {
            val language = _state.value.data.settings.effectiveShiurLanguage()
            val result = shiurim.page(
                ravId = rav.ravId,
                language = language,
                fromRow = rav.items.size,
                folderId = rav.openFolder?.folderId,
            )
            mutate {
                when (result) {
                    is CatalogResult.Ok -> it.copy(
                        rav = it.rav.copy(
                            items = it.rav.items + result.value.items,
                            loadingMore = false,
                            hasMore = result.value.hasMore,
                        ),
                    )

                    else -> it.copy(rav = it.rav.copy(loadingMore = false, error = result.toError()))
                }
            }
        }
    }

    // ------------------------------------------------------------------ progress

    private fun watchProgress() {
        scope.launch {
            player.progress.collect { progress ->
                if (!progress.seekable) return@collect
                _tick.value = _tick.value.copy(progress = progress)
                val shiur = _state.value.playback.shiur ?: return@collect
                recordProgress(shiur, progress)
            }
        }
    }

    private fun recordProgress(shiur: ShiurItem, progress: PlaybackProgress) {
        val duration = if (progress.durationMs > 0) progress.durationMs else shiur.durationMs
        val ended = duration > 0 && progress.positionMs >= duration - END_OF_SHIUR_MS
        val finished = ended || isFinishedAt(progress.positionMs, duration)
        val row = ShiurProgress(
            positionMs = progress.positionMs,
            durationMs = duration,
            finished = finished || _state.value.progressOf(shiur)?.finished == true,
            updatedAt = Platform.now(),
        )
        mutate { it.copy(shiurProgress = it.shiurProgress + (shiur.key to row)) }
        if (ended) onShiurEnded(shiur) else scheduleProgressSave()
    }

    /**
     * A finished shiur rolls on to the next one down the list, which is what makes a rav feel like
     * a station rather than a file browser. Unless a sleep timer was waiting for exactly this.
     */
    private fun onShiurEnded(shiur: ShiurItem) {
        flushProgress()
        if (_state.value.sleepTimer == SleepTimer.EndOfShiur) {
            clearSleepTimer()
            player.stop()
            mutate { it.copy(playback = it.playback.copy(status = PlaybackStatus.Idle)) }
            return
        }
        val list = _state.value.rav.items.filter { it.playable }
        val index = list.indexOfFirst { it.fileId == shiur.fileId }
        val next = list.getOrNull(index + 1)
        if (next == null) {
            player.stop()
            mutate { it.copy(playback = it.playback.copy(status = PlaybackStatus.Idle)) }
        } else {
            startShiur(next, resume = true)
        }
    }

    private fun scheduleProgressSave() {
        if (progressSaveJob?.isActive == true) return
        progressSaveJob = scope.launch {
            delay(PROGRESS_SAVE_MS)
            runCatching { progressStore.save(_state.value.shiurProgress) }
        }
    }

    /** Written now rather than on the next tick: the next tick may never come. */
    private fun flushProgress() {
        progressSaveJob?.cancel()
        runCatching { progressStore.save(_state.value.shiurProgress) }
    }

    // ------------------------------------------------------------------ sleep timer

    private fun setSleepTimer(timer: SleepTimer?) {
        sleepDeadline = (timer as? SleepTimer.After)?.let { Platform.now() + it.minutes * 60_000L }
        mutate { it.copy(sleepTimer = timer) }
        _tick.value = _tick.value.copy(sleepRemainingMs = remainingSleepMs())
    }

    private fun clearSleepTimer() {
        sleepDeadline = null
        mutate { it.copy(sleepTimer = null) }
        _tick.value = _tick.value.copy(sleepRemainingMs = 0)
    }

    private fun remainingSleepMs(): Long = sleepDeadline?.let { (it - Platform.now()).coerceAtLeast(0) } ?: 0

    private fun runSleepTimer() {
        scope.launch {
            while (isActive) {
                delay(SLEEP_TICK_MS)
                val deadline = sleepDeadline ?: continue
                val remaining = (deadline - Platform.now()).coerceAtLeast(0)
                _tick.value = _tick.value.copy(sleepRemainingMs = remaining)
                if (remaining > 0) continue
                // Paused, not stopped: whoever wakes up mid-shiur should find it where it was.
                flushProgress()
                player.pause()
                clearSleepTimer()
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun confirmDialog() {
        if (_state.value.dialog != AppDialog.ConfirmReset) {
            mutate { it.copy(dialog = AppDialog.Hidden) }
            return
        }
        player.stop()
        store.clear()
        progressStore.clear()
        val fresh = seedData()
        mutate {
            AppState(data = fresh, message = AppMessage.ResetDone, ravs = it.ravs)
        }
        applyVolume(fresh.settings)
        persist(now = true)
        setMain(AppKey.Stations)
    }

    private fun persist(now: Boolean = false) {
        saveJob?.cancel()
        val snapshot = _state.value.data
        if (now) {
            store.save(snapshot)
            return
        }
        saveJob = scope.launch {
            delay(200)
            store.save(snapshot)
        }
    }

    private fun mutate(block: (AppState) -> AppState) = _state.update(block)

    private fun AppState.updateSettings(block: (UserSettings) -> UserSettings): AppState =
        copy(data = data.copy(settings = block(data.settings)))
}

private fun CatalogResult<*>.toError(): ShiurError = when (this) {
    is CatalogResult.RateLimited -> ShiurError.RateLimited
    is CatalogResult.Failed -> ShiurError.Failed(reason)
    is CatalogResult.Ok -> ShiurError.Failed(null)
}

/** `ravId/fileId` back into its two halves, or `null` if the snapshot held something else. */
internal fun parseShiurKey(key: String): Pair<Int, Long>? {
    val slash = key.indexOf('/')
    if (slash <= 0) return null
    val ravId = key.substring(0, slash).toIntOrNull() ?: return null
    val fileId = key.substring(slash + 1).toLongOrNull() ?: return null
    return ravId to fileId
}

/** The station list after the search box, resolved against names the caller has already localised. */
fun filterStations(stations: List<Station>, query: String, names: Map<String, String>): List<Station> {
    val needle = query.trim()
    if (needle.isEmpty()) return stations
    return stations.filter { station ->
        names[station.id].orEmpty().contains(needle, ignoreCase = true) ||
            station.channels.any { it.title?.contains(needle, ignoreCase = true) == true }
    }
}

/** One row of the flat view: a single stream, carrying the station it belongs to. */
@Immutable
data class ChannelEntry(val station: Station, val channel: Channel)

/** Every stream on its own, rather than one entry per station. */
fun filterChannels(stations: List<Station>, query: String, names: Map<String, String>): List<ChannelEntry> {
    val entries = stations.flatMap { station -> station.channels.map { ChannelEntry(station, it) } }
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { (station, channel) ->
        val stationName = names[station.id].orEmpty()
        stationName.contains(needle, ignoreCase = true) ||
            channel.title?.contains(needle, ignoreCase = true) == true
    }
}

/** Ravs matching the search box, by their localised names. */
fun filterRavs(ravs: List<Rav>, query: String, names: Map<Int, String>): List<Rav> {
    val needle = query.trim()
    if (needle.isEmpty()) return ravs
    return ravs.filter { names[it.id].orEmpty().contains(needle, ignoreCase = true) }
}
