package dev.kdroid.musicradio.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppIntent
import dev.kdroid.musicradio.app.AppState
import dev.kdroid.musicradio.app.RavTab
import dev.kdroid.musicradio.app.ShiurError
import dev.kdroid.musicradio.data.ShiurProgress
import dev.kdroid.musicradio.domain.Rav
import dev.kdroid.musicradio.domain.ShiurFolder
import dev.kdroid.musicradio.domain.ShiurItem
import dev.kdroid.musicradio.ui.SectionHeader
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.favorite_add
import musicradio.shared.generated.resources.favorite_remove
import musicradio.shared.generated.resources.player_back
import musicradio.shared.generated.resources.shiur_continue_listening
import musicradio.shared.generated.resources.shiur_count
import musicradio.shared.generated.resources.shiur_empty
import musicradio.shared.generated.resources.shiur_finished
import musicradio.shared.generated.resources.shiur_folder_back
import musicradio.shared.generated.resources.shiur_load_failed
import musicradio.shared.generated.resources.shiur_rate_limited
import musicradio.shared.generated.resources.shiur_recent
import musicradio.shared.generated.resources.shiur_retry
import musicradio.shared.generated.resources.shiur_tab_all
import musicradio.shared.generated.resources.shiur_tab_continue
import musicradio.shared.generated.resources.shiur_tab_folders
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The continue tab is a shortcut, not a history: past a handful of rows it stops being one. */
private const val RECENT_LIMIT = 5

/** Rows drawn while the catalogue is on its way, so the list area keeps its shape. */
private const val PLACEHOLDER_ROWS = 6

private val ROW_SHAPE = RoundedCornerShape(14.dp)
private val CARD_SHAPE = RoundedCornerShape(20.dp)

/**
 * One rav's catalog: his portrait and the three ways into the shiurim themselves.
 *
 * A station is one endless URL and needs no screen of its own; a rav is a catalog with a cursor
 * into it, and every control here exists to move that cursor.
 *
 * Which rav is a question for [AppState.rav] alone: the screen never assumes there is only one,
 * and draws whichever the state currently points at.
 */
@Composable
fun RavScreen(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val screen = state.rav
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val rav = screen.rav ?: return@Column
        RavHeader(rav, rav.favoriteId in state.data.favorites, onIntent)
        TabPicker(screen.tab, onIntent, Modifier.fillMaxWidth())
        when (screen.error) {
            // No retry button on purpose: the block is counted per request, so trying again is
            // exactly what keeps it alive. Waiting is the only thing that works.
            ShiurError.RateLimited -> Note(stringResource(Res.string.shiur_rate_limited), Modifier.padding(top = 12.dp))

            is ShiurError.Failed -> LoadFailed(onIntent, Modifier.padding(top = 12.dp))

            null -> Unit
        }
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            when {
                screen.loading -> ShiurPlaceholder(Modifier.fillMaxWidth())
                screen.tab == RavTab.Continue -> ContinueTab(state, onIntent, Modifier.fillMaxSize())
                screen.tab == RavTab.All -> ShiurList(state, screen.items, onIntent, Modifier.fillMaxSize())
                else -> FoldersTab(state, onIntent, Modifier.fillMaxSize())
            }
        }
    }
}

// ---------------------------------------------------------------------------------- header

@Composable
private fun RavHeader(rav: Rav, favorite: Boolean, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = { onIntent(AppIntent.Back) }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.player_back))
        }
        // Drawn exactly like a station tile, only smaller: the grid a tap ago showed this same
        // portrait, and a header that reshapes it reads as a different picture.
        Image(
            painter = painterResource(rav.artwork),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(ROW_SHAPE).background(colors.surfaceContainerHighest),
        )
        // No shiur count beside the name: the tabs already slice the catalogue three ways, and a
        // single number over all of them answers none of the three questions they ask.
        Text(
            stringResource(rav.name),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = { onIntent(AppIntent.ToggleFavorite(rav.favoriteId)) }) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                stringResource(if (favorite) Res.string.favorite_remove else Res.string.favorite_add),
                tint = if (favorite) colors.primary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TabPicker(current: RavTab, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        RavTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = current == tab,
                onClick = { onIntent(AppIntent.SelectRavTab(tab)) },
                shape = SegmentedButtonDefaults.itemShape(index, RavTab.entries.size),
            ) {
                Text(tabLabel(tab), maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun tabLabel(tab: RavTab): String = when (tab) {
    RavTab.Continue -> stringResource(Res.string.shiur_tab_continue)
    RavTab.All -> stringResource(Res.string.shiur_tab_all)
    RavTab.Folders -> stringResource(Res.string.shiur_tab_folders)
}

// ---------------------------------------------------------------------------------- continue

@Composable
private fun ContinueTab(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val items = state.rav.items
    // The first thing the catalogue offers that is playable and not already heard through. The
    // list arrives newest first, so this is the most recent shiur still worth starting.
    val next = items.firstOrNull { it.playable && state.progressOf(it)?.finished != true }
    val recent = items
        .mapNotNull { item -> state.progressOf(item)?.takeIf { it.started }?.let { item to it } }
        .sortedByDescending { it.second.updatedAt }
        .take(RECENT_LIMIT)
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (next != null) {
            ContinueCard(next, state.progressOf(next), onIntent, Modifier.fillMaxWidth())
        }
        if (recent.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.shiur_recent))
            recent.forEach { (item, _) ->
                ShiurRow(state, item, onIntent, Modifier.fillMaxWidth())
            }
        }
        if (next == null && recent.isEmpty() && state.rav.error == null) {
            Note(stringResource(Res.string.shiur_empty), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ContinueCard(item: ShiurItem, progress: ShiurProgress?, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val elapsed = progress?.positionMs ?: 0L
    // The stored duration wins: the catalogue almost never states one, and where it does the
    // player has measured it since. Zero means nobody knows yet, and gets no clock at all.
    val total = progress?.durationMs?.takeIf { it > 0 } ?: item.durationMs
    Surface(
        modifier = modifier.clickable { onIntent(AppIntent.PlayShiur(item)) },
        shape = CARD_SHAPE,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.shiur_continue_listening), style = MaterialTheme.typography.labelMedium)
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                trackColor = colors.onPrimaryContainer.copy(alpha = 0.25f),
                color = colors.onPrimaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(elapsed), style = MaterialTheme.typography.labelSmall)
                if (total > 0) {
                    Text(formatClock(total), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- lists

/**
 * The shiur list, shared by the all tab and by an open folder: `items` holds whichever of the two
 * the state is currently showing, so one list serves both.
 *
 * There is no paging here and no "loading more" row: a rav's whole archive is a few dozen
 * recordings and arrives in a single call, so the list is always complete the moment it is drawn.
 */
@Composable
private fun ShiurList(state: AppState, items: List<ShiurItem>, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        EmptyList(state.rav.error == null, modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Keyed by rav and file both: a file id is only unique within the rav that published it.
        items(items, key = { it.key }) { item ->
            ShiurRow(state, item, onIntent, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ShiurRow(state: AppState, item: ShiurItem, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val progress = state.progressOf(item)
    val playing = state.playback.shiur?.fileId == item.fileId
    Surface(
        modifier = modifier.clickable { onIntent(AppIntent.PlayShiur(item)) },
        shape = ROW_SHAPE,
        color = if (playing) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (playing) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Plain ISO rather than a formatted date: this app has no calendar dependency, and
                // a half-localised date is worse than an unambiguous one.
                item.recordedAt?.let { Meta(it.date.toString()) }
                // Usually nothing: the source ships no length, so the row stays silent about it
                // rather than claiming `0:00`. Playing it once teaches the progress store the real
                // duration, and the row picks it up from there.
                val duration = progress?.durationMs?.takeIf { it > 0 } ?: item.durationMs
                if (duration > 0) {
                    Meta(formatClock(duration))
                }
                if (progress?.finished == true) {
                    Meta(stringResource(Res.string.shiur_finished), icon = Icons.Outlined.CheckCircle)
                }
            }
            if (progress != null && progress.started) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}

@Composable
private fun Meta(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------- folders

@Composable
private fun FoldersTab(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val open = state.rav.openFolder
    if (open == null) {
        FolderList(state, onIntent, modifier)
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderCrumb(open, onIntent, Modifier.fillMaxWidth())
            ShiurList(state, state.rav.items, onIntent, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun FolderList(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val folders = state.rav.folders
    if (folders.isEmpty()) {
        EmptyList(state.rav.error == null, modifier)
        return
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it.folderId }) { folder ->
            FolderRow(
                folder = folder,
                favorite = folder.favoriteId in state.data.favorites,
                onIntent = onIntent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FolderRow(folder: ShiurFolder, favorite: Boolean, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable { onIntent(AppIntent.OpenFolder(folder)) },
        shape = ROW_SHAPE,
        color = colors.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The one count that is honest: the folder knows how many it holds.
                folder.shiurCount?.let { Meta(stringResource(Res.string.shiur_count, it)) }
            }
            IconButton(onClick = { onIntent(AppIntent.ToggleFavorite(folder.favoriteId)) }) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    stringResource(if (favorite) Res.string.favorite_remove else Res.string.favorite_add),
                    modifier = Modifier.size(20.dp),
                    tint = if (favorite) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FolderCrumb(folder: ShiurFolder, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onIntent(AppIntent.CloseFolder) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(Res.string.shiur_folder_back),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            folder.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------- states

/**
 * The list area's stand-in while the catalogue loads. Deliberately not a spinner over the whole
 * screen: the header and the tabs are both already known and stay usable.
 */
@Composable
private fun ShiurPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(PLACEHOLDER_ROWS) {
            Surface(
                Modifier.fillMaxWidth().height(56.dp),
                shape = ROW_SHAPE,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {}
        }
    }
}

/** Says nothing at all when an error is already on screen: one explanation per problem. */
@Composable
private fun EmptyList(explain: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.TopStart) {
        if (explain) {
            Note(stringResource(Res.string.shiur_empty), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LoadFailed(onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(stringResource(Res.string.shiur_load_failed), Modifier.fillMaxWidth())
        Button(onClick = { onIntent(AppIntent.RetryShiurim) }) {
            Text(stringResource(Res.string.shiur_retry))
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------------------- formatting

private const val MS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

/** `H:MM:SS` past the hour, `M:SS` below it. Digits only, so it reads the same in every locale. */
private fun formatClock(ms: Long): String {
    val seconds = (ms / MS_PER_SECOND).coerceAtLeast(0)
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) "$hours:${twoDigits(minutes)}:${twoDigits(rest)}" else "$minutes:${twoDigits(rest)}"
}

private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')
