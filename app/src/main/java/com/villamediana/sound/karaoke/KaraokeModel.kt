package com.villamediana.sound.karaoke

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The model that tells the voice from the band: Spleeter's two stems (Deezer, MIT), its two
 * networks in half precision, 39 MB in all. Not in the APK — everyone would carry it at every
 * update: it comes once, the first time someone sings, and stays in [dir].
 */
class KaraokeModel(
    private val http: OkHttpClient,
    private val dir: File,
    private val online: () -> Boolean = { true },
    private val files: List<Part> = PARTS,
) {
    class Part(val name: String, val url: String, val bytes: Long, val sha256: String)

    /** Both networks are on the phone. */
    val present: Boolean get() = files.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.bytes } }

    fun voice(): File = File(dir, files[0].name)

    fun band(): File = File(dir, files[1].name)

    /** Makes sure the model is on the phone, fetching what is missing ([progress] 0..1 of all of it). */
    suspend fun fetch(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (present) return@withContext
        if (!online()) throw KaraokeOffline()
        dir.mkdirs()
        val total = files.sumOf { it.bytes }.toFloat()
        var before = 0L
        for (part in files) {
            val target = File(dir, part.name)
            if (!(target.isFile && target.length() == part.bytes)) {
                download(part, target) { progress((before + it) / total) }
            }
            before += part.bytes
            progress(before / total)
        }
    }

    private suspend fun download(part: Part, target: File, got: (Long) -> Unit) {
        val context = currentCoroutineContext()
        val partial = File(dir, part.name + ".part")
        try {
            val response = try {
                http.newCall(Request.Builder().url(part.url).build()).execute()
            } catch (e: IOException) {
                throw KaraokeOffline(e)
            }
            response.use { r ->
                if (!r.isSuccessful) throw IOException("${part.name}: HTTP ${r.code}")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                partial.outputStream().use { out ->
                    val source = r.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        got(read)
                    }
                }
                val sum = digest.digest().joinToString("") { "%02x".format(it) }
                if (read != part.bytes || sum != part.sha256) throw IOException("${part.name}: not the file expected ($read bytes)")
            }
            if (!partial.renameTo(target)) throw IOException("${part.name}: could not be kept")
        } finally {
            partial.delete()
        }
    }

    /** What the model takes on the phone (Ajustes › Armazenamento); [clear] lets it go (it comes again when someone sings). */
    fun bytes(): Long = dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    companion object {
        private const val BASE = "https://github.com/Villamediana/FreeSong/releases/download/karaoke-1"

        /** The voice's network first, the band's second. */
        val PARTS = listOf(
            Part("vocals.onnx", "$BASE/spleeter-2stems-vocals-fp16.onnx", 19_681_039, "0f3eb651fb81ac8836d70cb4cf62462991f8ae6422cd4ddff0f7a2595bf66c53"),
            Part("accompaniment.onnx", "$BASE/spleeter-2stems-accompaniment-fp16.onnx", 19_681_046, "9c4b9ea41d9262e3defcc2d1a2930aa2e9fa325d780e34397a3ba55a5f4d14c2"),
        )
    }
}
