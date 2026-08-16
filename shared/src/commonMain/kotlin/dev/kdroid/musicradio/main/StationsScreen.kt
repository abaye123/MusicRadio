package dev.kdroid.musicradio.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppIntent
import dev.kdroid.musicradio.app.AppState
import dev.kdroid.musicradio.app.ChannelEntry
import dev.kdroid.musicradio.app.filterChannels
import dev.kdroid.musicradio.app.filterStations
import dev.kdroid.musicradio.domain.Station
import dev.kdroid.musicradio.domain.StationCategory
import dev.kdroid.musicradio.domain.isFavorite
import dev.kdroid.musicradio.ui.ChannelCard
import dev.kdroid.musicradio.ui.StationCard
import dev.kdroid.musicradio.ui.label
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.category_all
import musicradio.shared.generated.resources.favorites_empty
import musicradio.shared.generated.resources.stations_empty
import musicradio.shared.generated.resources.stations_search
import musicradio.shared.generated.resources.view_stations
import musicradio.shared.generated.resources.view_streams
import org.jetbrains.compose.resources.stringResource

/** Shared by the search field and the view toggle so they line up exactly. */
private val CONTROL_HEIGHT = 52.dp

@Composable
fun StationsScreen(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val stations = state.browsable
    // Names live in the resource bundle, so the search box can only be applied once they are resolved.
    val names = stations.associate { it.id to stringResource(it.name) }
    val streamsView = state.data.settings.streamsView

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Pill shaped and the same height as the toggle beside it, so the row reads as one
            // control strip rather than a text box parked next to some buttons.
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(AppIntent.SetSearchQuery(it)) },
                modifier = Modifier.weight(1f).height(CONTROL_HEIGHT),
                singleLine = true,
                shape = CircleShape,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                placeholder = { Text(stringResource(Res.string.stations_search), style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            ViewToggle(streamsView, Modifier.height(CONTROL_HEIGHT)) { onIntent(AppIntent.SetStreamsView(it)) }
        }
        CategoryFilter(state, onIntent, Modifier.padding(vertical = 12.dp))
        if (streamsView) {
            ChannelGrid(
                entries = filterChannels(stations, state.query, names),
                state = state,
                onIntent = onIntent,
                emptyText = stringResource(Res.string.stations_empty),
                modifier = Modifier.weight(1f),
            )
        } else {
            StationGrid(
                stations = filterStations(stations, state.query, names),
                state = state,
                onIntent = onIntent,
                emptyText = stringResource(Res.string.stations_empty),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun FavoritesScreen(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    StationGrid(
        stations = state.favorites,
        state = state,
        onIntent = onIntent,
        emptyText = stringResource(Res.string.favorites_empty),
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
    )
}

/** Icon-only so it stays out of the search field's way; the labels live in the descriptions. */
@Composable
private fun ViewToggle(streamsView: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = !streamsView,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = {},
        ) {
            Icon(Icons.Outlined.Radio, stringResource(Res.string.view_stations), Modifier.size(20.dp))
        }
        SegmentedButton(
            selected = streamsView,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = {},
        ) {
            Icon(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(Res.string.view_streams), Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CategoryFilter(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val categories = StationCategory.entries.filter { state.data.settings.showNews || it != StationCategory.News }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.category == null,
            onClick = { onIntent(AppIntent.SetCategory(null)) },
            label = { Text(stringResource(Res.string.category_all)) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = state.category == category,
                onClick = { onIntent(AppIntent.SetCategory(category.takeIf { it != state.category })) },
                label = { Text(category.label()) },
            )
        }
    }
}

@Composable
private fun StationGrid(
    stations: List<Station>,
    state: AppState,
    onIntent: (AppIntent) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    if (stations.isEmpty()) {
        EmptyGrid(emptyText, modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(stations, key = { it.id }) { station ->
            StationCard(
                station = station,
                playing = state.playback.stationId == station.id && state.playback.status.active,
                favorite = state.data.isFavorite(station.id),
                onClick = { onIntent(AppIntent.SelectStation(station.id)) },
                onToggleFavorite = { onIntent(AppIntent.ToggleFavorite(station.id)) },
            )
        }
    }
}

@Composable
private fun ChannelGrid(
    entries: List<ChannelEntry>,
    state: AppState,
    onIntent: (AppIntent) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        EmptyGrid(emptyText, modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.channel.id }) { entry ->
            ChannelCard(
                station = entry.station,
                channel = entry.channel,
                playing = state.playback.channelId == entry.channel.id && state.playback.status.active,
                onClick = { onIntent(AppIntent.SelectChannel(entry.channel.id)) },
            )
        }
    }
}

@Composable
private fun EmptyGrid(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
