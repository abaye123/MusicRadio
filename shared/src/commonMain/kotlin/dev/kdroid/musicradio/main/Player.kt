package dev.kdroid.musicradio.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppIntent
import dev.kdroid.musicradio.app.AppState
import dev.kdroid.musicradio.app.SKIP_STEP_MS
import dev.kdroid.musicradio.domain.Rav
import dev.kdroid.musicradio.domain.Ravs
import dev.kdroid.musicradio.domain.Station
import dev.kdroid.musicradio.domain.isFavorite
import dev.kdroid.musicradio.player.PlaybackStatus
import dev.kdroid.musicradio.ui.StationArtwork
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.favorite_add
import musicradio.shared.generated.resources.favorite_remove
import musicradio.shared.generated.resources.player_back
import musicradio.shared.generated.resources.player_buffering
import musicradio.shared.generated.resources.player_live
import musicradio.shared.generated.resources.player_mute
import musicradio.shared.generated.resources.player_next
import musicradio.shared.generated.resources.player_nothing
import musicradio.shared.generated.resources.player_pause
import musicradio.shared.generated.resources.player_paused
import musicradio.shared.generated.resources.player_play
import musicradio.shared.generated.resources.player_previous
import musicradio.shared.generated.resources.player_stop
import musicradio.shared.generated.resources.player_unmute
import musicradio.shared.generated.resources.shiur_back_15
import musicradio.shared.generated.resources.shiur_forward_15
import musicradio.shared.generated.resources.shiur_next
import musicradio.shared.generated.resources.shiur_previous
import musicradio.shared.generated.resources.sleep_timer_remaining
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The channel's own name, falling back to the station's for a station's flagship stream. */
@Composable
fun channelTitle(state: AppState): String {
    val station = state.currentStation ?: return ""
    val channel = state.currentChannel ?: return stringResource(station.name)
    return channel.title ?: stringResource(station.name)
}

@Composable
private fun statusLabel(status: PlaybackStatus): String = when (status) {
    PlaybackStatus.Buffering -> stringResource(Res.string.player_buffering)
    PlaybackStatus.Playing -> stringResource(Res.string.player_live)
    PlaybackStatus.Paused -> stringResource(Res.string.player_paused)
    PlaybackStatus.Idle, PlaybackStatus.Error -> ""
}

/**
 * The desktop player: a fixed strip across the bottom of the window, always showing what is on
 * and what else the current station carries.
 *
 * The scrubber is hoisted here rather than owned by [SeekBar], because a held skip button moves the
 * same preview the bar draws: one value, written by the transport row and read by the scrubber.
 */
@Composable
fun PlayerBar(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val station = state.currentStation
    var preview by remember { mutableStateOf<Long?>(null) }
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                NowPlayingLabel(state, station, Modifier.weight(1f))
                // The desktop bar is the only player surface on a wide window, so without this
                // there is no way to bookmark what is playing: the grid tile that carries the
                // other star is not on screen once you are inside a station's channels.
                FavoriteButton(state, onIntent)
                ChannelPicker(state, onIntent)
                TransportControls(state, onIntent, big = false, onScrub = { preview = it })
                SleepCountdown()
                VolumeControl(state, onIntent, Modifier.weight(1f))
            }
            // Nothing at all on a live stream, so the strip keeps the height it always had.
            SeekBar(onIntent, preview, { preview = it }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun NowPlayingLabel(state: AppState, station: Station?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val shiur = state.playback.shiur
        val rav = shiur?.let { Ravs.of(it.ravId) }
        if (shiur == null && station == null) {
            Text(
                stringResource(Res.string.player_nothing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        if (rav != null) {
            RavArtwork(rav, Modifier.size(56.dp), RoundedCornerShape(12.dp))
        } else if (station != null) {
            StationArtwork(station, Modifier.size(56.dp), RoundedCornerShape(12.dp), state.currentChannel)
        }
        Column(Modifier.widthIn(max = 320.dp)) {
            Text(
                shiur?.title ?: station?.let { stringResource(it.name) }.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // On a shiur the second line is whose shiur it is. On radio the track pushes the status
            // line out: while something is playing, its name is the more useful of the two, and
            // "Live" is already implied by the running button.
            val second = when {
                shiur != null -> rav?.let { stringResource(it.name) }.orEmpty()
                else -> state.playback.nowPlaying.ifBlank { statusLabel(state.playback.status) }
            }
            Text(
                second,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact host: a tappable strip that opens the full player. */
@Composable
fun MiniPlayerBar(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val station = state.currentStation
    val shiur = state.playback.shiur
    if (station == null && shiur == null) return
    val rav = shiur?.let { Ravs.of(it.ravId) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().clickable { onIntent(AppIntent.OpenNowPlaying) },
        color = colors.surfaceContainerHigh,
    ) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (rav != null) {
                    RavArtwork(rav, Modifier.size(44.dp), RoundedCornerShape(10.dp))
                } else if (station != null) {
                    StationArtwork(station, Modifier.size(44.dp), RoundedCornerShape(10.dp), state.currentChannel)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        shiur?.title ?: station?.let { stringResource(it.name) }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val second = when {
                        shiur != null -> rav?.let { stringResource(it.name) }.orEmpty()
                        else -> state.playback.nowPlaying.ifBlank { statusLabel(state.playback.status) }
                    }
                    Text(
                        second,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FavoriteButton(state, onIntent)
                PlayButton(state.playback.status, size = 44.dp) { onIntent(AppIntent.TogglePlay) }
            }
        }
    }
}

/** Toggles the playing station, so it is reachable without going back to the grid. */
@Composable
private fun FavoriteButton(state: AppState, onIntent: (AppIntent) -> Unit, size: Dp = 24.dp) {
    val station = state.currentStation ?: return
    // On a multi-channel station the star follows the channel you are actually on; bookmarking the
    // whole of Kol Chai Music because you liked one of its 83 streams is not what the press means.
    // A single-channel station has nothing finer to point at, so it stays the station itself, and
    // favorites saved before channels were starrable keep matching.
    val target = state.currentChannel?.id?.takeIf { station.multiChannel } ?: station.id
    val favorite = state.data.isFavorite(target)
    IconButton(onClick = { onIntent(AppIntent.ToggleFavorite(target)) }) {
        Icon(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            stringResource(if (favorite) Res.string.favorite_remove else Res.string.favorite_add),
            modifier = Modifier.size(size),
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The treatment [StationArtwork] gives a station, for the rav whose shiur is playing. */
@Composable
private fun RavArtwork(rav: Rav, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    Image(
        painter = painterResource(rav.artwork),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * Compact host: the player as a full screen of its own, and the only screen that is not inside
 * [MainShell] - there is no bottom bar behind it, so it carries its own way back.
 *
 * Sized off the height rather than the width. The artwork used to be 80% of the width with a square
 * ratio, which on a short screen is taller than everything else put together: the transport row and
 * the volume slider were pushed past the bottom edge with nothing to scroll, so on a phone like the
 * F21 the play button simply was not there. The artwork is now capped by whichever of the two
 * dimensions runs out first, and the controls step down a size once the window is short.
 */
@Composable
fun NowPlayingScreen(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val short = maxHeight < ShortWindowHeight
        val gap = if (short) 8.dp else 20.dp
        val artworkMax = maxWidth * 0.8f
        var preview by remember { mutableStateOf<Long?>(null) }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = if (short) 8.dp else 16.dp)) {
            IconButton(onClick = { onIntent(AppIntent.Back) }, modifier = Modifier.align(Alignment.Start)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.player_back))
            }
            val station = state.currentStation
            val shiur = state.playback.shiur
            val rav = shiur?.let { Ravs.of(it.ravId) }
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (station == null && shiur == null) {
                    Text(stringResource(Res.string.player_nothing), style = MaterialTheme.typography.bodyLarge)
                    return@Column
                }
                // The artwork is the only thing here that can give: weight(fill = false) hands it
                // whatever is left once the rows below have taken their natural height, so the
                // transport row keeps its real size instead of being squeezed - it used to be
                // compressed to nothing on a short window, which is how the play button vanished.
                val artwork = Modifier.weight(1f, fill = false).widthIn(max = artworkMax).aspectRatio(1f)
                if (rav != null) {
                    RavArtwork(rav, artwork, RoundedCornerShape(28.dp))
                } else if (station != null) {
                    StationArtwork(station, artwork, RoundedCornerShape(28.dp), state.currentChannel)
                }
                Text(
                    shiur?.title ?: station?.let { stringResource(it.name) }.orEmpty(),
                    style = if (short) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = if (shiur == null) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Whose shiur it is, or what the stream says it is playing.
                val second = when {
                    shiur != null -> rav?.let { stringResource(it.name) }.orEmpty()
                    else -> state.playback.nowPlaying
                }
                if (second.isNotBlank()) {
                    Text(
                        second,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = if (short) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The status line is the first thing to go: it repeats what the play button already
                // shows, and on a short window that row is worth more than the word "Live". A shiur
                // never shows it at all - it is not live, and the scrubber says more than a word.
                if (!short && shiur == null) {
                    Text(
                        statusLabel(state.playback.status),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SleepCountdown()
                // Skipped whole on a shiur: both of these are a station's, and an empty row still
                // takes a gap out of the height the artwork is fighting for.
                if (station != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FavoriteButton(state, onIntent, size = 28.dp)
                        ChannelPicker(state, onIntent)
                    }
                }
                SeekBar(onIntent, preview, { preview = it }, Modifier.widthIn(max = 520.dp).fillMaxWidth())
                TransportControls(state, onIntent, big = !short, onScrub = { preview = it })
                VolumeControl(state, onIntent, Modifier.fillMaxWidth(), Alignment.CenterHorizontally)
            }
        }
    }
}

/**
 * Below this the compact player switches to its tighter sizes. A 16:9 phone in portrait clears it
 * comfortably; the ones that do not are the short-and-wide ones, and landscape.
 */
private val ShortWindowHeight = 600.dp

@Composable
fun ChannelPicker(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val station = state.currentStation ?: return
    if (!station.multiChannel) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(channelTitle(state), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            station.channels.forEach { channel ->
                val title = channel.title ?: stringResource(station.name)
                DropdownMenuItem(
                    text = {
                        Text(
                            title,
                            fontWeight = if (channel.id == state.playback.channelId) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onIntent(AppIntent.SelectChannel(channel.id))
                    },
                )
            }
        }
    }
}

/**
 * [onScrub] is how a held skip button hands its previewed position to whoever draws the scrubber;
 * it is called with `null` once the gesture is over. A radio player never calls it.
 */
@Composable
fun TransportControls(
    state: AppState,
    onIntent: (AppIntent) -> Unit,
    big: Boolean,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
) {
    // The Row already puts "previous" on the right in Hebrew, but the glyphs do not follow the
    // layout direction on their own. SkipNext and SkipPrevious are exact mirror images, so each
    // button simply takes the other's glyph rather than being flipped through a graphics layer.
    // FastRewind and FastForward are the same pair, and are mirrored the same way.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previousIcon = if (rtl) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious
    val nextIcon = if (rtl) Icons.Outlined.SkipPrevious else Icons.Outlined.SkipNext
    val backIcon = if (rtl) Icons.Outlined.FastForward else Icons.Outlined.FastRewind
    val forwardIcon = if (rtl) Icons.Outlined.FastRewind else Icons.Outlined.FastForward
    val shiur = state.playback.isShiur
    // The flag, not the clock: a transport row that collected the whole tick would be rebuilt four
    // times a second for a boolean that changes once a session.
    val clock = LocalPlayerTick.current
    val seekable by remember(clock) { clock.map { it.progress.seekable }.distinctUntilChanged() }
        .collectAsState(clock.value.progress.seekable)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (big) 20.dp else 8.dp),
    ) {
        IconButton(onClick = { onIntent(if (shiur) AppIntent.PreviousShiur else AppIntent.PreviousStation) }) {
            Icon(previousIcon, stringResource(if (shiur) Res.string.shiur_previous else Res.string.player_previous))
        }
        if (seekable) {
            SkipButton(-SKIP_STEP_MS, backIcon, stringResource(Res.string.shiur_back_15), onIntent, onScrub)
        }
        PlayButton(state.playback.status, size = if (big) 72.dp else 52.dp) { onIntent(AppIntent.TogglePlay) }
        if (seekable) {
            SkipButton(SKIP_STEP_MS, forwardIcon, stringResource(Res.string.shiur_forward_15), onIntent, onScrub)
        }
        IconButton(onClick = { onIntent(if (shiur) AppIntent.NextShiur else AppIntent.NextStation) }) {
            Icon(nextIcon, stringResource(if (shiur) Res.string.shiur_next else Res.string.player_next))
        }
        if (state.playback.status != PlaybackStatus.Idle) {
            IconButton(onClick = { onIntent(AppIntent.Stop) }) {
                Icon(Icons.Outlined.Stop, stringResource(Res.string.player_stop))
            }
        }
    }
}

@Composable
private fun PlayButton(status: PlaybackStatus, size: Dp, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(size), shape = CircleShape) {
        when (status) {
            // The spinner replaces the glyph rather than sitting next to it: the button keeps its
            // hit target, and a stream that takes four seconds to open still looks like it is working.
            PlaybackStatus.Buffering -> CircularProgressIndicator(
                Modifier.size(size / 2.4f),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )

            PlaybackStatus.Playing -> Icon(Icons.Outlined.Pause, stringResource(Res.string.player_pause), Modifier.size(size / 2.2f))

            else -> Icon(Icons.Filled.PlayArrow, stringResource(Res.string.player_play), Modifier.size(size / 2.2f))
        }
    }
}

// ------------------------------------------------------------------ the shiur clock

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val AN_HOUR_MS = 3_600_000L

/** How long a skip button has to be held before it stops being a tap and starts scrubbing. */
private const val HOLD_START_MS = 400L

/** How often a held skip button moves its preview. */
private const val HOLD_TICK_MS = 100L

/** Ticks spent at one step before the next one up. */
private const val HOLD_RAMP = 5

private val SkipButtonSize = 40.dp

/**
 * `H:MM:SS` once the source runs past an hour, `M:SS` below it. [withHours] is decided by the
 * duration rather than by the number being drawn, so the elapsed label does not change shape - and
 * shove the scrubber sideways - as it crosses the hour.
 */
private fun formatClock(ms: Long, withHours: Boolean): String {
    val total = (ms / MS_PER_SECOND).coerceAtLeast(0)
    val seconds = twoDigits(total % SECONDS_PER_MINUTE)
    if (!withHours) return "${total / SECONDS_PER_MINUTE}:$seconds"
    return "${total / SECONDS_PER_HOUR}:${twoDigits(total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE)}:$seconds"
}

private fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()

/**
 * How far one held tick jumps, by how many have already gone by. It accelerates and then stops
 * accelerating: ten seconds a tick is ten minutes of audio for six seconds of holding, which is
 * enough to cross a long shiur without overshooting a short one.
 */
private fun holdStepMs(ticks: Int): Long = when {
    ticks < HOLD_RAMP -> 1_000L
    ticks < HOLD_RAMP * 2 -> 2_000L
    ticks < HOLD_RAMP * 3 -> 5_000L
    else -> 10_000L
}

/**
 * The scrubber and the two labels that read it. Draws nothing at all unless the source has a
 * position to draw, which is what keeps every radio surface exactly as it was.
 *
 * [preview] is where a drag - or a held skip button - is currently standing. While it is non-null
 * the bar and the labels follow it rather than the player's own clock, and the seek is sent once,
 * when the gesture ends.
 */
@Composable
private fun SeekBar(onIntent: (AppIntent) -> Unit, preview: Long?, onPreview: (Long?) -> Unit, modifier: Modifier = Modifier) {
    // Collected here rather than by the player around it: this row and the countdown are the only
    // things on screen with any reason to repaint four times a second.
    val tick by LocalPlayerTick.current.collectAsState()
    val progress = tick.progress
    if (!progress.seekable) return
    val duration = progress.durationMs
    val position = (preview ?: progress.positionMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
    val withHours = duration >= AN_HOUR_MS
    val label = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(formatClock(position, withHours), style = label, color = muted)
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { onPreview((it * duration).toLong()) },
            modifier = Modifier.weight(1f),
            enabled = duration > 0,
            // The one seek of the whole drag. Everything before this only moved the preview.
            onValueChangeFinished = {
                preview?.let { onIntent(AppIntent.SeekTo(it)) }
                onPreview(null)
            },
        )
        Text("-${formatClock(duration - position, withHours)}", style = label, color = muted)
    }
}

/**
 * A skip button that is also a scrubber. A tap steps by [deltaMs]; holding it past [HOLD_START_MS]
 * starts walking a preview forwards or backwards, faster the longer it is held.
 *
 * The walk moves nothing but the preview. One [AppIntent.SeekTo] goes out on release, because a
 * seek per tick would be ten a second: that stutters the desktop backend and throws away the
 * Android buffer over and over on the way to somewhere the listener has not chosen yet.
 */
@Composable
private fun SkipButton(
    deltaMs: Long,
    icon: ImageVector,
    description: String,
    onIntent: (AppIntent) -> Unit,
    onScrub: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = LocalPlayerTick.current
    // The gesture outlives the composition that started it, so the callbacks are read through the
    // latest composition rather than captured - pointerInput must not restart when they change.
    val intent by rememberUpdatedState(onIntent)
    val scrub by rememberUpdatedState(onScrub)
    val interactions = remember { MutableInteractionSource() }
    val direction = if (deltaMs < 0) -1 else 1
    Box(
        modifier
            .size(SkipButtonSize)
            .clip(CircleShape)
            .indication(interactions, LocalIndication.current)
            .pointerInput(deltaMs) {
                // Where the hold has walked to, or null while it is still just a press.
                var preview: Long? = null
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactions.emit(press)
                        preview = null
                        coroutineScope {
                            val walk = launch {
                                delay(HOLD_START_MS)
                                var ticks = 0
                                while (isActive) {
                                    val at = clock.value.progress
                                    val end = if (at.durationMs > 0) at.durationMs else Long.MAX_VALUE
                                    val from = preview ?: at.positionMs
                                    preview = (from + direction * holdStepMs(ticks)).coerceIn(0L, end)
                                    scrub(preview)
                                    ticks++
                                    delay(HOLD_TICK_MS)
                                }
                            }
                            val released = tryAwaitRelease()
                            // Joined, not just cancelled: nothing may write the preview after this.
                            walk.cancelAndJoin()
                            interactions.emit(
                                if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                            )
                            val walked = preview
                            when {
                                !released -> Unit
                                walked == null -> intent(AppIntent.SkipBy(deltaMs))
                                else -> intent(AppIntent.SeekTo(walked))
                            }
                            // Back to the player's own clock, whichever way the gesture ended.
                            scrub(null)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description)
    }
}

/** What is left on the sleep timer, and nothing at all when none is running. */
@Composable
private fun SleepCountdown(modifier: Modifier = Modifier) {
    val tick by LocalPlayerTick.current.collectAsState()
    val remaining = tick.sleepRemainingMs
    if (remaining <= 0) return
    Text(
        stringResource(Res.string.sleep_timer_remaining, formatClock(remaining, withHours = false)),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * [alignment] is trailing-edge for the desktop bar, where the row is the last thing in a wide strip
 * and belongs against the window edge. The compact player passes centre: there the row is given the
 * full width under a centred column, so hugging the edge left the slider visibly off-axis from
 * everything above it.
 */
@Composable
fun VolumeControl(
    state: AppState,
    onIntent: (AppIntent) -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.End,
) {
    val settings = state.data.settings
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment),
    ) {
        IconButton(onClick = { onIntent(AppIntent.ToggleMute) }) {
            Icon(
                if (settings.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                stringResource(if (settings.muted) Res.string.player_unmute else Res.string.player_mute),
            )
        }
        Slider(
            value = settings.volume.toFloat(),
            onValueChange = { onIntent(AppIntent.SetVolume(it.toInt())) },
            valueRange = 0f..100f,
            modifier = Modifier.widthIn(max = 220.dp).width(180.dp),
        )
    }
}
