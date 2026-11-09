package com.villamediana.sound.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** GitHub's "latest release", asked with the last ETag so an unchanged answer costs nothing. */
internal class ReleaseSource(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val url: String = ReleaseFeed.LATEST_URL,
) {
    sealed interface Answer {
        data class Fresh(val body: String, val etag: String?) : Answer
        data object Unchanged : Answer
    }

    fun latest(etag: String?): Answer {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 304) return Answer.Unchanged
            // 404: no public release yet. Anything else is a failed check.
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return Answer.Fresh(r.body.string(), r.header("ETag"))
        }
    }
}

/** Downloads the APK beside its final name, checks size and SHA-256, then puts it in place. */
internal class ApkDownloader(private val http: OkHttpClient, private val userAgent: String) {
    fun download(release: AppRelease, target: File, onProgress: (Float) -> Unit) {
        val part = File(target.path + ".part")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", userAgent).build()
        val md = MessageDigest.getInstance("SHA-256")
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.NETWORK, e.message)
        }
        response.use { r ->
            if (!r.isSuccessful) throw UpdateException(UpdateFailure.NETWORK, "HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            try {
                part.outputStream().use { out ->
                    r.body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } catch (e: IOException) {
                part.delete()
                val full = target.parentFile?.usableSpace?.let { it < 8L * 1024 * 1024 } == true
                throw UpdateException(if (full) UpdateFailure.STORAGE else UpdateFailure.NETWORK, e.message)
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val wrongSize = release.sizeBytes > 0 && done != release.sizeBytes
            val wrongSha = release.sha256 != null && sha != release.sha256
            if (wrongSize || wrongSha) {
                part.delete()
                throw UpdateException(UpdateFailure.INTEGRITY, if (wrongSha) "sha256 $sha" else "$done/${release.sizeBytes} bytes")
            }
            if (!part.renameTo(target)) {
                part.delete()
                throw UpdateException(UpdateFailure.STORAGE, "rename")
            }
        }
    }
}
