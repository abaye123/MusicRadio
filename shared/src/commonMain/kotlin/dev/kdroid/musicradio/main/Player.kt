package dev.kdroid.musicradio.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppIntent
import dev.kdroid.musicradio.app.AppState
import dev.kdroid.musicradio.domain.Station
import dev.kdroid.musicradio.player.PlaybackStatus
import dev.kdroid.musicradio.ui.StationArtwork
import musicradio.shared.generated.resources.Res
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
 */
@Composable
fun PlayerBar(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val station = state.currentStation
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                NowPlayingLabel(state, station, Modifier.weight(1f))
                ChannelPicker(state, onIntent)
                TransportControls(state, onIntent, big = false)
                VolumeControl(state, onIntent, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NowPlayingLabel(state: AppState, station: Station?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (station == null) {
            Text(
                stringResource(Res.string.player_nothing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        StationArtwork(station, Modifier.size(56.dp), RoundedCornerShape(12.dp), state.currentChannel)
        Column(Modifier.widthIn(max = 320.dp)) {
            Text(
                stringResource(station.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The track pushes the status line out: while something is playing, its name is the
            // more useful of the two, and "Live" is already implied by the running button.
            val song = state.playback.nowPlaying
            Text(
                song.ifBlank { statusLabel(state.playback.status) },
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
    val station = state.currentStation ?: return
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
                StationArtwork(station, Modifier.size(44.dp), RoundedCornerShape(10.dp), state.currentChannel)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(station.name),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.playback.nowPlaying.ifBlank { statusLabel(state.playback.status) },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayButton(state.playback.status, size = 44.dp) { onIntent(AppIntent.TogglePlay) }
            }
        }
    }
}

/** Compact host: the player as a full screen of its own. */
@Composable
fun NowPlayingScreen(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val station = state.currentStation
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (station == null) {
            Text(stringResource(Res.string.player_nothing), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        StationArtwork(station, Modifier.fillMaxWidth(0.8f).aspectRatio(1f), RoundedCornerShape(28.dp), state.currentChannel)
        Text(stringResource(station.name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        val song = state.playback.nowPlaying
        if (song.isNotBlank()) {
            Text(
                song,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            statusLabel(state.playback.status),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChannelPicker(state, onIntent)
        TransportControls(state, onIntent, big = true)
        VolumeControl(state, onIntent, Modifier.fillMaxWidth())
    }
}

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

@Composable
fun TransportControls(state: AppState, onIntent: (AppIntent) -> Unit, big: Boolean, modifier: Modifier = Modifier) {
    // The Row already puts "previous" on the right in Hebrew, but the glyphs do not follow the
    // layout direction on their own. SkipNext and SkipPrevious are exact mirror images, so each
    // button simply takes the other's glyph rather than being flipped through a graphics layer.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previousIcon = if (rtl) Icons.Outlined.SkipNext else Icons.Outlined.SkipPrevious
    val nextIcon = if (rtl) Icons.Outlined.SkipPrevious else Icons.Outlined.SkipNext
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (big) 20.dp else 8.dp),
    ) {
        IconButton(onClick = { onIntent(AppIntent.PreviousStation) }) {
            Icon(previousIcon, stringResource(Res.string.player_previous))
        }
        PlayButton(state.playback.status, size = if (big) 72.dp else 52.dp) { onIntent(AppIntent.TogglePlay) }
        IconButton(onClick = { onIntent(AppIntent.NextStation) }) {
            Icon(nextIcon, stringResource(Res.string.player_next))
        }
        if (state.playback.status != PlaybackStatus.Idle) {
            IconButton(onClick = { onIntent(AppIntent.Stop) }) {
                Icon(Icons.Outlined.Stop, stringResource(Res.string.player_stop))
            }
        }
    }
}

@Composable
private fun PlayButton(status: PlaybackStatus, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
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

@Composable
fun VolumeControl(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val settings = state.data.settings
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
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
