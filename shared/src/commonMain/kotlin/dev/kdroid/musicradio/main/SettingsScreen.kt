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
import dev.kdroid.musicradio.domain.ShiurLanguage
import dev.kdroid.musicradio.domain.SleepTimer
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
import musicradio.shared.generated.resources.shiur_lang_any
import musicradio.shared.generated.resources.shiur_lang_english
import musicradio.shared.generated.resources.shiur_lang_french
import musicradio.shared.generated.resources.shiur_lang_hebrew
import musicradio.shared.generated.resources.shiur_lang_yiddish
import musicradio.shared.generated.resources.shiur_language
import musicradio.shared.generated.resources.shiur_language_auto
import musicradio.shared.generated.resources.shiur_language_desc
import musicradio.shared.generated.resources.sleep_timer
import musicradio.shared.generated.resources.sleep_timer_desc
import musicradio.shared.generated.resources.sleep_timer_end_of_shiur
import musicradio.shared.generated.resources.sleep_timer_minutes
import musicradio.shared.generated.resources.sleep_timer_off
import musicradio.shared.generated.resources.theme_dark
import musicradio.shared.generated.resources.theme_light
import musicradio.shared.generated.resources.theme_system
import org.jetbrains.compose.resources.StringResource
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
                    onPick = { onIntent(AppIntent.SetTheme(it)) },
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                )
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
            // The browser build ships without the shiurim feature, and a setting for a feature that
            // is not there is worse than no setting at all.
            if (state.ravs.isNotEmpty()) {
                SettingRow(
                    stringResource(Res.string.shiur_language),
                    stringResource(Res.string.shiur_language_desc),
                ) {
                    ShiurLanguagePicker(
                        language = settings.shiurLanguage,
                        onPick = { onIntent(AppIntent.SetShiurLanguage(it)) },
                    )
                }
            }
            SettingRow(
                stringResource(Res.string.sleep_timer),
                stringResource(Res.string.sleep_timer_desc),
            ) {
                SleepTimerPicker(
                    timer = state.sleepTimer,
                    offerEndOfShiur = state.playback.isShiur,
                    onPick = { onIntent(AppIntent.SetSleepTimer(it)) },
                )
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
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
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

/**
 * The order the picker offers, which is not the enum's: the concrete languages come first, in the
 * order a listener is likely to want them, and "all" reads as the fallback it is, so it goes last.
 */
private val shiurLanguages = listOf(
    ShiurLanguage.Hebrew,
    ShiurLanguage.Yiddish,
    ShiurLanguage.English,
    ShiurLanguage.French,
    ShiurLanguage.Any,
)

private val ShiurLanguage.label: StringResource
    get() = when (this) {
        ShiurLanguage.Hebrew -> Res.string.shiur_lang_hebrew
        ShiurLanguage.Yiddish -> Res.string.shiur_lang_yiddish
        ShiurLanguage.English -> Res.string.shiur_lang_english
        ShiurLanguage.French -> Res.string.shiur_lang_french
        ShiurLanguage.Any -> Res.string.shiur_lang_any
    }

/** `null` is the "follow the interface language" default, exactly as in [LanguagePicker]. */
@Composable
private fun ShiurLanguagePicker(language: ShiurLanguage?, onPick: (ShiurLanguage?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(language?.label ?: Res.string.shiur_language_auto))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.shiur_language_auto)) },
                onClick = {
                    expanded = false
                    onPick(null)
                },
            )
            shiurLanguages.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label)) },
                    onClick = {
                        expanded = false
                        onPick(entry)
                    },
                )
            }
        }
    }
}

/**
 * [offerEndOfShiur] is false on radio, where a live stream has no end to wait for.
 *
 * A running [SleepTimer.EndOfShiur] that playback has since left behind still shows as the chosen
 * value even though the menu no longer offers it: the timer really is still armed and really will
 * stop playback, so labelling the row "off" would be a lie the user only finds out about when the
 * music stops. Picking anything else from the menu replaces it, as usual.
 */
@Composable
private fun SleepTimerPicker(timer: SleepTimer?, offerEndOfShiur: Boolean, onPick: (SleepTimer?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                when (timer) {
                    null -> stringResource(Res.string.sleep_timer_off)
                    is SleepTimer.After -> stringResource(Res.string.sleep_timer_minutes, timer.minutes)
                    SleepTimer.EndOfShiur -> stringResource(Res.string.sleep_timer_end_of_shiur)
                },
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.sleep_timer_off)) },
                onClick = {
                    expanded = false
                    onPick(null)
                },
            )
            SleepTimer.presets.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.sleep_timer_minutes, minutes)) },
                    onClick = {
                        expanded = false
                        onPick(SleepTimer.After(minutes))
                    },
                )
            }
            if (offerEndOfShiur) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.sleep_timer_end_of_shiur)) },
                    onClick = {
                        expanded = false
                        onPick(SleepTimer.EndOfShiur)
                    },
                )
            }
        }
    }
}
