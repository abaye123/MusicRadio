package dev.kdroid.musicradio.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppIntent
import dev.kdroid.musicradio.app.AppState
import dev.kdroid.musicradio.domain.AccentColor
import dev.kdroid.musicradio.domain.ThemeMode
import dev.kdroid.musicradio.domain.UiLanguage
import dev.kdroid.musicradio.ui.SectionHeader
import dev.kdroid.musicradio.ui.SettingBlock
import dev.kdroid.musicradio.ui.SettingRow
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.language_system
import musicradio.shared.generated.resources.settings_accent
import musicradio.shared.generated.resources.settings_appearance
import musicradio.shared.generated.resources.settings_autoplay
import musicradio.shared.generated.resources.settings_autoplay_desc
import musicradio.shared.generated.resources.settings_data
import musicradio.shared.generated.resources.settings_language
import musicradio.shared.generated.resources.settings_playback
import musicradio.shared.generated.resources.settings_reset
import musicradio.shared.generated.resources.settings_reset_desc
import musicradio.shared.generated.resources.settings_show_news
import musicradio.shared.generated.resources.settings_show_news_desc
import musicradio.shared.generated.resources.settings_theme
import musicradio.shared.generated.resources.theme_dark
import musicradio.shared.generated.resources.theme_light
import musicradio.shared.generated.resources.theme_system
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(state: AppState, onIntent: (AppIntent) -> Unit, modifier: Modifier = Modifier) {
    val settings = state.data.settings
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {
            SectionHeader(stringResource(Res.string.settings_appearance))

            SettingBlock(stringResource(Res.string.settings_theme)) {
                ThemePicker(
                    settings.theme,
                    Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                ) { onIntent(AppIntent.SetTheme(it)) }
            }
            SettingRow(stringResource(Res.string.settings_accent)) {
                AccentPicker(settings.accent) { onIntent(AppIntent.SetAccent(it)) }
            }
            SettingRow(stringResource(Res.string.settings_language)) {
                LanguagePicker(
                    language = if (settings.uiLanguageAuto) null else settings.uiLanguage,
                    onPick = { onIntent(AppIntent.SetUiLanguage(it)) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader(stringResource(Res.string.settings_playback))

            SettingRow(
                stringResource(Res.string.settings_show_news),
                stringResource(Res.string.settings_show_news_desc),
            ) {
                Switch(checked = settings.showNews, onCheckedChange = { onIntent(AppIntent.SetShowNews(it)) })
            }
            SettingRow(
                stringResource(Res.string.settings_autoplay),
                stringResource(Res.string.settings_autoplay_desc),
            ) {
                Switch(checked = settings.resumeOnLaunch, onCheckedChange = { onIntent(AppIntent.SetResumeOnLaunch(it)) })
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader(stringResource(Res.string.settings_data))

            SettingRow(
                stringResource(Res.string.settings_reset),
                stringResource(Res.string.settings_reset_desc),
            ) {
                Button(
                    onClick = { onIntent(AppIntent.ResetApp) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(Res.string.settings_reset))
                }
            }
        }
    }
}

@Composable
private fun ThemePicker(current: ThemeMode, modifier: Modifier = Modifier, onPick: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onPick(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
            ) {
                Text(
                    when (mode) {
                        ThemeMode.System -> stringResource(Res.string.theme_system)
                        ThemeMode.Light -> stringResource(Res.string.theme_light)
                        ThemeMode.Dark -> stringResource(Res.string.theme_dark)
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun AccentPicker(current: AccentColor, onPick: (AccentColor) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentColor.entries.forEach { accent ->
            val selected = accent == current
            Box(
                Modifier
                    .size(if (selected) 32.dp else 26.dp)
                    .clip(CircleShape)
                    .background(accent.seed)
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    )
                    .clickable { onPick(accent) },
            )
        }
    }
}

@Composable
private fun LanguagePicker(language: UiLanguage?, onPick: (UiLanguage?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(language?.label ?: stringResource(Res.string.language_system))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.language_system)) },
                onClick = {
                    expanded = false
                    onPick(null)
                },
            )
            UiLanguage.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        expanded = false
                        onPick(entry)
                    },
                )
            }
        }
    }
}
