package com.villamediana.surco

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.villamediana.surco.ui.nav.ExternalRequest
import com.villamediana.surco.ui.nav.SurcoRoot
import com.villamediana.surco.ui.theme.SurcoTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Links shared to Surco (YouTube / YouTube Music) waiting to be opened by the UI. */
    private val incomingLink = MutableStateFlow<String?>(null)

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
                SurcoRoot(incomingLink = incomingLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (val request = ExternalRequest.of(intent)) {
            is ExternalRequest.OpenLink -> incomingLink.value = request.text
            null -> Unit
        }
    }
}
