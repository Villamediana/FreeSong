package com.villamediana.sound

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.lifecycleScope
import com.villamediana.sound.ui.nav.ExternalRequest
import com.villamediana.sound.ui.nav.Inbound
import com.villamediana.sound.ui.nav.SoundRoot
import com.villamediana.sound.ui.nav.VoiceSearch
import com.villamediana.sound.ui.theme.SoundTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Shared links (YouTube / YouTube Music) and online voice searches waiting for the Search screen. */
    private val incoming = MutableStateFlow<Inbound?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Only called when the system showed the splash: the logo stays a moment over the ready
        // app (it used to vanish with the first frame), then grows and fades away.
        splash.setOnExitAnimationListener { provider ->
            runCatching { provider.iconView }.getOrNull()?.animate()
                ?.scaleX(1.18f)?.scaleY(1.18f)?.alpha(0f)
                ?.setStartDelay(SPLASH_HOLD_MS)?.setDuration(SPLASH_EXIT_MS)?.setInterpolator(AccelerateInterpolator(1.4f))?.start()
            provider.view.animate().alpha(0f).setStartDelay(SPLASH_HOLD_MS + SPLASH_EXIT_MS / 3).setDuration(SPLASH_EXIT_MS)
                .setInterpolator(DecelerateInterpolator()).withEndAction { provider.remove() }.start()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        // First frame already in the saved theme (no flash of the other one).
        val settingsStore = SoundApplication.graph.settings
        val initialSettings = kotlinx.coroutines.runBlocking { settingsStore.current() }
        val initialTheme = initialSettings.theme
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        com.villamediana.sound.ui.theme.SoundColors.palette = com.villamediana.sound.ui.theme.paletteFor(initialTheme, night, initialSettings.accent)
        setContent {
            val settings by settingsStore.settings.collectAsState(initial = null)
            SoundTheme(mode = settings?.theme ?: initialTheme, accent = settings?.accent ?: initialSettings.accent) {
                SoundRoot(incoming = incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (val request = ExternalRequest.of(intent)) {
            is ExternalRequest.OpenLink -> incoming.value = Inbound.Link(request.text)
            is ExternalRequest.PlayFromSearch -> playFromSearch(request.query)
            null -> Unit
        }
    }

    /** Voice search: the library plays it where the app is; with nothing there, Search looks online. */
    private fun playFromSearch(query: String) {
        val graph = SoundApplication.graph
        lifecycleScope.launch {
            val played = VoiceSearch.play(query, graph.library, graph.player) { q -> getString(R.string.playback_source_search, q) }
            if (!played) incoming.value = Inbound.Query(query)
        }
    }
}

private const val SPLASH_HOLD_MS = 700L
private const val SPLASH_EXIT_MS = 480L
