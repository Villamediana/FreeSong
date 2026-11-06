package com.villamediana.sound.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.villamediana.sound.data.Storage
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.online.NewPipeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Square JPEG covers in `covers/`. Songs of one album share a single file (named after a hash of
 * the album key), singles use their videoId, imported playlists use `playlist_<id>`.
 */
internal class CoverArt(
    private val http: OkHttpClient,
    private val storage: Storage,
    private val maxSize: Int = MAX_SIZE,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Returns the cover called [name], downloading the first of [urls] that works when the file does
     * not exist yet (or always, with [overwrite]). Null when nothing could be fetched.
     */
    suspend fun obtain(name: String, urls: List<String>, overwrite: Boolean = false): File? {
        val file = storage.coverFile(name)
        val lock = locks.getOrPut(name) { Mutex() }
        return lock.withLock {
            if (!overwrite && file.isFile && file.length() > 0) return@withLock file
            for (url in urls.filter { it.isNotBlank() }.distinct()) {
                val bytes = fetch(url) ?: continue
                val jpeg = withContext(Dispatchers.Default) { squareJpeg(bytes, maxSize) } ?: continue
                withContext(Dispatchers.IO) { writeAtomically(file, jpeg) }
                return@withLock file
            }
            if (file.isFile && file.length() > 0) file else null
        }
    }

    private suspend fun fetch(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).header("User-Agent", NewPipeHttp.USER_AGENT).build()
        http.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) return@executeCancellable null
            val length = response.body.contentLength()
            if (length > MAX_IMAGE_BYTES) return@executeCancellable null
            response.body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null // malformed url
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        const val MAX_SIZE = 1000
        private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024

        /** Cover file name shared by every song of an album (case/space-insensitive). */
        fun albumCoverName(key: AlbumKey): String = "album_" + sha1(key.normalized).take(20)

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Decodes [bytes] (subsampled with inSampleSize so big artwork never blows the heap), trims black
 * letterbox bars, center-crops a square, scales it to at most [maxSize] px and encodes JPEG q[quality].
 * Null when the bytes are not an image.
 */
internal fun squareJpeg(bytes: ByteArray, maxSize: Int = CoverArt.MAX_SIZE, quality: Int = 90): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (min(w, h) / (sample * 2) >= maxSize) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val square = squareCrop(decoded, maxSize)
    return ByteArrayOutputStream().use { out ->
        square.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }.also {
        if (square !== decoded) square.recycle()
        decoded.recycle()
    }
}

/** Center square of [source] (after trimming letterbox bars), scaled down to at most [maxSize]. */
internal fun squareCrop(source: Bitmap, maxSize: Int): Bitmap {
    val (top, bottom) = letterbox(source)
    val contentHeight = source.height - top - bottom
    val side = min(source.width, contentHeight)
    val x = (source.width - side) / 2
    val y = top + (contentHeight - side) / 2
    val cropped = if (x == 0 && y == 0 && side == source.width && side == source.height) source
    else Bitmap.createBitmap(source, x, y, side, side)
    if (side <= maxSize) return cropped
    return Bitmap.createScaledBitmap(cropped, maxSize, maxSize, true).also { if (cropped !== source) cropped.recycle() }
}

/**
 * Rows of black bars at the top and bottom (a 16:9 video inside a 4:3 thumbnail). Only trimmed when
 * both bars exist and are clearly bars (≥ 5% of the height each), so dark artwork is left alone.
 */
internal fun letterbox(bmp: Bitmap): Pair<Int, Int> {
    val h = bmp.height
    val w = bmp.width
    if (h < 40 || w < 40) return 0 to 0
    val step = max(1, w / 48)
    fun dark(y: Int): Boolean {
        var x = 0
        while (x < w) {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (max(r, max(g, b)) > 24) return false
            x += step
        }
        return true
    }
    val limit = h / 3
    var top = 0
    while (top < limit && dark(top)) top++
    var bottom = 0
    while (bottom < limit && dark(h - 1 - bottom)) bottom++
    val minBar = h / 20
    return if (top >= minBar && bottom >= minBar && top < limit && bottom < limit) top to bottom else 0 to 0
}
