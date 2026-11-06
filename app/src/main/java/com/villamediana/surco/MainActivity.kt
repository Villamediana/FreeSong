package com.villamediana.surco

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.villamediana.surco.ui.nav.ExternalRequest
import com.villamediana.surco.ui.nav.Inbound
import com.villamediana.surco.ui.nav.SurcoRoot
import com.villamediana.surco.ui.nav.VoiceSearch
import com.villamediana.surco.ui.theme.SurcoTheme
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
            SurcoTheme {
                SurcoRoot(incoming = incoming)
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
        val graph = SurcoApplication.graph
        lifecycleScope.launch {
            val played = VoiceSearch.play(query, graph.library, graph.player) { q -> getString(R.string.playback_source_search, q) }
            if (!played) incoming.value = Inbound.Query(query)
        }
    }
}
