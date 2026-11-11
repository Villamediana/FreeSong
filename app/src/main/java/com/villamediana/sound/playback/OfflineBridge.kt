package com.villamediana.sound.playback

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.playback.stream.StreamErrors
import com.villamediana.sound.smart.Taste

/**
 * The connection gone under an online song — the metro: the phone loses its network, or keeps one
 * that carries nothing and the stream's requests time out. The music goes on with the songs on the
 * phone: the next downloaded song of the queue or, with none ahead, songs of yours like what was
 * playing, put in right after it (the online songs stay after them, for when the connection is
 * back). Pure: the service does the queue work.
 */
internal object OfflineBridge {
    /** Songs of yours put in when the queue has no downloaded song ahead. */
    const val SIZE = QueueContinuation.SIZE

    /**
     * The stream of the online song playing couldn't get through at all (no network, or every
     * request of it failed to connect): the connection's fault, not the song's. Whatever the phone
     * says about its network — in a tunnel it often still "has" one.
     */
    fun isConnectionLoss(errorCode: Int, currentIsOnline: Boolean): Boolean =
        currentIsOnline && StreamErrors.kindOf(errorCode) == OnlineException.Kind.NO_NETWORK

    /**
     * Songs of yours to go on with: like the ones [heard] (play order, up to the one that stopped),
     * none of them again — or, when every song of yours was heard already, the ones heard longest ago.
     */
    fun songs(source: PlaySource?, heard: List<Track>, library: List<Track>, taste: Taste, seed: Long): List<Track> =
        SmartPlans.extension(source, heard, library, taste, seed, fill = true).take(SIZE)
            .ifEmpty { QueueContinuation.again(heard, library, taste, seed, SIZE) }
}
