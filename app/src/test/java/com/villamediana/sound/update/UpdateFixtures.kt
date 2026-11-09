package com.villamediana.sound.update

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest

internal object UpdateFixtures {
    /** GitHub `repos/Villamediana/FreeSong/releases/latest` on 2026-11-09 (fields the app doesn't read trimmed). */
    val release1124 = """
        {
         "url": "https://api.github.com/repos/Villamediana/FreeSong/releases/438554210",
         "html_url": "https://github.com/Villamediana/FreeSong/releases/tag/v1.12.4",
         "id": 438554210,
         "tag_name": "v1.12.4",
         "target_commitish": "main",
         "name": "FreeSong 1.12.4",
         "draft": false,
         "immutable": false,
         "prerelease": false,
         "created_at": "2026-11-08T22:02:00Z",
         "published_at": "2026-11-08T22:06:16Z",
         "body": "Primeira versão pública.\n\nInstale a APK no celular (Android 8.0 ou mais novo) e permita a instalação de fora da loja quando o Android pedir.",
         "assets": [
          {
           "url": "https://api.github.com/repos/Villamediana/FreeSong/releases/assets/725345241",
           "id": 725345241,
           "name": "FreeSong-1.12.4.apk",
           "label": "",
           "content_type": "application/vnd.android.package-archive",
           "state": "uploaded",
           "size": 25984012,
           "digest": "sha256:1a2e7f5515b2fdbcaf7feb56d7f66c7ca2ae3be8ba5d3eae3981a04485701958",
           "download_count": 1,
           "created_at": "2026-11-08T22:06:14Z",
           "browser_download_url": "https://github.com/Villamediana/FreeSong/releases/download/v1.12.4/FreeSong-1.12.4.apk"
          }
         ]
        }
    """.trimIndent()

    const val APK_HOST = "https://github.com/Villamediana/FreeSong/releases/download"

    fun release(
        version: String,
        tag: String = "v$version",
        notes: String = "- Novidade",
        apkName: String = "FreeSong-$version.apk",
        size: Long = 0,
        digest: String? = "sha256:" + "0".repeat(64),
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String {
        val digestField = digest?.let { "\"digest\": \"$it\"," } ?: ""
        return """
            {"tag_name": "$tag", "name": "FreeSong $version", "draft": $draft, "prerelease": $prerelease,
             "body": ${quote(notes)},
             "assets": [{"name": "$apkName", "size": $size, $digestField
                         "browser_download_url": "$APK_HOST/$tag/$apkName"}]}
        """.trimIndent()
    }

    /** A release whose APK is [bytes] (size and digest that match them). */
    fun releaseOf(version: String, bytes: ByteArray, notes: String = "- Novidade"): String =
        release(version, notes = notes, size = bytes.size.toLong(), digest = "sha256:" + sha256(bytes))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** Answers by URL; records what was asked. */
    class FakeGitHub(var handler: (Request) -> Answer) : Interceptor {
        data class Answer(val code: Int, val body: ByteArray = ByteArray(0), val etag: String? = null)

        val requests = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            synchronized(requests) { requests += request }
            val a = handler(request)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(a.code)
                .message("fake")
                .apply { if (a.etag != null) header("ETag", a.etag) }
                .body(a.body.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        fun count(pred: (Request) -> Boolean) = synchronized(requests) { requests.count(pred) }
    }
}
