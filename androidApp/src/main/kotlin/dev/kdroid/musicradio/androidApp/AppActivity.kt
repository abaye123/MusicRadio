package dev.kdroid.musicradio.androidApp

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import dev.kdroid.musicradio.App
import dev.kdroid.musicradio.platform.bindAndroidContext

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindAndroidContext(this)
        // The hardware volume keys should move the media stream, not the ringer, whether or not
        // anything is playing yet.
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent {
            App(
                onThemeChange = { ThemeChanged(it) },
                onQuit = { finish() },
            )
        }
    }
}

@Composable
private fun ThemeChanged(isDark: Boolean) {
    val view = LocalView.current
    LaunchedEffect(isDark) {
        val window = (view.context as Activity).window
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
