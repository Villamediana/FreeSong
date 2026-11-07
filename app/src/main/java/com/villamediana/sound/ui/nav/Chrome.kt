package com.villamediana.sound.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-shell toggles a screen can ask for while it is on screen. */
object Chrome {
    /**
     * The first-run genre picker owns the whole screen: no bottom bar, no mini player over it
     * (music keeps playing).
     */
    var hideMiniPlayer: Boolean by mutableStateOf(false)
}
