package com.villamediana.sound.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether music is playing right now, for whoever needs to know without making the player
 * connection (the social line stays up while music plays, so messages arrive at once).
 */
object PlaybackPulse {
    val playing = MutableStateFlow(false)
}
