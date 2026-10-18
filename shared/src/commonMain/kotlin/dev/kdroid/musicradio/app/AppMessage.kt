package dev.kdroid.musicradio.app

import androidx.compose.runtime.Composable
import musicradio.shared.generated.resources.Res
import musicradio.shared.generated.resources.error_stream
import musicradio.shared.generated.resources.message_reset_done
import musicradio.shared.generated.resources.message_song_copied
import musicradio.shared.generated.resources.message_song_saved
import org.jetbrains.compose.resources.stringResource

/** A one-line notice shown in the bar at the bottom of the window. */
enum class AppMessage {
    StreamFailed,
    ResetDone,

    /** Says where the song went: the list sits behind a tab of Favorites, nowhere near the button. */
    SongSaved,
    SongCopied,
}

@Composable
fun AppMessage.text(): String = when (this) {
    AppMessage.StreamFailed -> stringResource(Res.string.error_stream)
    AppMessage.ResetDone -> stringResource(Res.string.message_reset_done)
    AppMessage.SongSaved -> stringResource(Res.string.message_song_saved)
    AppMessage.SongCopied -> stringResource(Res.string.message_song_copied)
}
