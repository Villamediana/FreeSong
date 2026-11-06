package com.villamediana.sound

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            SoundTheme {
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
