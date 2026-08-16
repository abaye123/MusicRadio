package dev.kdroid.musicradio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kdroid.musicradio.app.AppMessage
import dev.kdroid.musicradio.app.text
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.dialog_dismiss
import org.jetbrains.compose.resources.stringResource

private const val AUTO_DISMISS_MS = 5_000L

@Composable
fun MessageBar(message: AppMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(AUTO_DISMISS_MS)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val shown = message
        Surface(
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(shown?.text().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.dialog_dismiss), color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
