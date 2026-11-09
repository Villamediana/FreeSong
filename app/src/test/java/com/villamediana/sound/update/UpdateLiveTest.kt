package com.villamediana.sound.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Live: the real latest release on GitHub, its ETag and its APK checked against GitHub's SHA-256. SOUND_LIVE=1 */
class UpdateLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    @Test
    fun theLatestReleaseIsReadableAndItsApkIntact() {
        assumeTrue(System.getProperty("sound.live") == "1")
        val source = ReleaseSource(http, "FreeSong/test")
        val first = source.latest(null) as ReleaseSource.Answer.Fresh
        val release = ReleaseFeed.parse(first.body)
        assertNotNull("latest release must parse: ${first.body.take(300)}", release)
        release!!
        println("latest: ${release.version} ${release.apkName} ${release.sizeBytes} B sha256=${release.sha256}")
        assertNotNull("GitHub gives the asset digest", release.sha256)
        assertNotNull("GitHub gives an ETag", first.etag)
        assertEquals(ReleaseSource.Answer.Unchanged, source.latest(first.etag))

        val target = File(tmp.root, release.apkName)
        var last = 0f
        ApkDownloader(http, "FreeSong/test").download(release, target) { last = it }
        assertEquals(release.sizeBytes, target.length())
        assertEquals(release.sha256, Updates.sha256(target))
        assertEquals(1f, last)
        // An APK is a zip: "PK".
        val head = target.inputStream().use { it.readNBytes(2) }
        assertTrue(head.contentEquals(byteArrayOf(0x50, 0x4B)))
    }
}
