package com.villamediana.sound.download

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import com.villamediana.sound.online.RemoteTrack
import java.io.File
import java.io.IOException

/**
 * Whether the phone has room for songs, counted the way Android counts it: the space it keeps free
 * (its "storage is running out" line) is never eaten into, and other apps' caches may be cleared to
 * make room. A full phone gets no download: nothing is half written, nothing fails later.
 */
internal interface DiskRoom {
    /** [bytes] fit (a quick look, nothing is cleared). */
    fun fits(bytes: Long): Boolean

    /** Makes room for [bytes] right before they are written (Android may clear other apps' caches); false when they don't fit. */
    fun take(bytes: Long): Boolean = fits(bytes)

    /** [tracks] from the first, as many as fit together. */
    fun fitting(tracks: List<RemoteTrack>): List<RemoteTrack> {
        var total = 0L
        val out = ArrayList<RemoteTrack>(tracks.size)
        for (t in tracks) {
            val next = total + estimate(t)
            if (!fits(next)) break
            total = next
            out += t
        }
        return out
    }

    companion object {
        /** Room for everything (tests, a build without storage checks). */
        val ANY: DiskRoom = object : DiskRoom {
            override fun fits(bytes: Long) = true
        }

        /** About how big a song is on the phone, before its stream says: ~160 kbps (the best quality) + cover and lyrics. */
        fun estimate(durationMs: Long?): Long {
            val seconds = (durationMs ?: 0L).coerceAtLeast(0L) / 1000
            if (seconds <= 0) return UNKNOWN_SONG_BYTES
            return seconds * BEST_BYTES_PER_SECOND + EXTRAS_BYTES
        }

        fun estimate(track: RemoteTrack): Long = estimate(track.durationMs)

        private const val BEST_BYTES_PER_SECOND = 20_000L // 160 kbps
        /** The cover and the lyrics that come with a song. */
        const val EXTRAS_BYTES = 1_000_000L
        private const val UNKNOWN_SONG_BYTES = 12_000_000L
    }
}

/** [DiskRoom] of the volume where [dir] lives. */
internal class AndroidDiskRoom(context: Context, private val dir: () -> File) : DiskRoom {
    private val manager: StorageManager? = context.getSystemService(StorageManager::class.java)

    override fun fits(bytes: Long): Boolean = allocatable()?.let { it >= bytes } ?: statFsFits(bytes)

    override fun take(bytes: Long): Boolean {
        val sm = manager ?: return statFsFits(bytes)
        return try {
            val uuid = sm.getUuidForPath(dir())
            if (sm.getAllocatableBytes(uuid) < bytes) return false
            sm.allocateBytes(uuid, bytes)
            true
        } catch (e: IOException) {
            Log.i(TAG, "no room for $bytes bytes: $e")
            false
        } catch (e: RuntimeException) {
            // A volume Android can't answer for: fall back to the plain count.
            statFsFits(bytes)
        }
    }

    private fun allocatable(): Long? {
        val sm = manager ?: return null
        return try {
            sm.getAllocatableBytes(sm.getUuidForPath(dir()))
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Free space minus Android's own "running out" line (5% of the disk, at most 500 MB). */
    private fun statFsFits(bytes: Long): Boolean = try {
        val fs = StatFs(dir().path)
        val keep = minOf(fs.totalBytes / 20, 500L * 1024 * 1024)
        fs.availableBytes - keep >= bytes
    } catch (e: RuntimeException) {
        true
    }

    private companion object {
        const val TAG = "SoundDownload"
    }
}

/** No room for a song on the phone: the download fails at once, before anything is written. */
internal class NoRoomException(bytes: Long) : IOException("No room for $bytes bytes")
