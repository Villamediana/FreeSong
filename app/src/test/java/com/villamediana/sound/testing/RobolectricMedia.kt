package com.villamediana.sound.testing

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSessionService
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric "connects" bindService with a null ComponentName, which crashes Media3's
 * MediaController. Screen tests that run the real AppGraph make the playback service unbindable
 * instead: the connection then just stays disconnected (as on a phone before the service is up).
 */
fun blockMediaSessionBinding() {
    val app = shadowOf(RuntimeEnvironment.getApplication())
    app.declareActionUnbindable(MediaSessionService.SERVICE_INTERFACE)
    app.declareActionUnbindable(MediaLibraryService.SERVICE_INTERFACE)
}
