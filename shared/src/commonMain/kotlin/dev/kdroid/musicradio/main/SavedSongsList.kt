package dev.kdroid.musicradio.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppIntent
import dev.kdroid.musicradio.domain.SavedSong
import dev.kdroid.musicradio.domain.Stations
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.song_copy
import musicradio.shared.generated.resources.song_delete
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The saved songs, newest first, each with what is needed to go and find it later. */
@Composable
fun SavedSongsList(songs: List<SavedSong>, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    // The plain-text clipboard is all a title needs; its replacement wants a platform ClipEntry
    // built separately on every target for the same one line of text.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(songs, key = { "${it.savedAt}/${it.channelId}/${it.title}" }) { song ->
            ListItem(
                headlineContent = { Text(song.title, style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(
                        listOfNotNull(heardOn(song.channelId), formatSavedAt(song.savedAt)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(song.title))
                            onIntent(AppIntent.SongCopied)
                        }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(Res.string.song_copy))
                        }
                        IconButton(onClick = { onIntent(AppIntent.RemoveSavedSong(song)) }) {
                            Icon(Icons.Outlined.Delete, stringResource(Res.string.song_delete))
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Station and channel, or `null` once the catalog no longer carries that channel. */
@Composable
private fun heardOn(channelId: String): String? {
    val channel = Stations.channel(channelId) ?: return null
    val station = Stations.stationOfChannel(channelId) ?: return null
    val name = stringResource(station.name)
    return channel.title?.takeIf { it != name }?.let { "$name - $it" } ?: name
}

private fun formatSavedAt(epochMillis: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.two() = toString().padStart(2, '0')
    return "${t.day.two()}/${t.month.ordinal.plus(1).two()}/${t.year} ${t.hour.two()}:${t.minute.two()}"
}
