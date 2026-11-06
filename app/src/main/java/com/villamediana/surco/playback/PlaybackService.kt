package com.villamediana.surco.playback

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/** Foreground media service hosting ExoPlayer + MediaLibrarySession. (Stub — implemented by the playback module.) */
class PlaybackService : MediaLibraryService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = null
}
