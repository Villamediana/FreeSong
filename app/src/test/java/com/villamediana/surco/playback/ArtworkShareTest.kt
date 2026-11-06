package com.villamediana.surco.playback

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Browse-tree covers for cars and watches: content:// from the app's FileProvider instead of private file paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtworkShareTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val share = ArtworkShare(context)

    private fun item(art: Uri?) = MediaItem.Builder()
        .setMediaId("album/x")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Signos").setArtworkUri(art).build())
        .build()

    @Test fun coverFilesBecomeProviderUris() {
        val covers = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.resolve("surco/covers").apply { mkdirs() }
        val cover = File(covers, "signos.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val shared = share.forController(item(Uri.fromFile(cover)), "com.google.android.projection.gearhead")
        val art = shared.mediaMetadata.artworkUri!!
        assertEquals("content", art.scheme)
        assertEquals("${context.packageName}.files", art.authority)
        assertEquals("Signos", shared.mediaMetadata.title.toString())
        assertEquals("album/x", shared.mediaId)
        // The provider serves the very file.
        context.contentResolver.openInputStream(art)!!.use { assertEquals(listOf<Byte>(1, 2, 3), it.readBytes().toList()) }
    }

    @Test fun onlineAndMissingArtworkPassUntouched() {
        val online = item(Uri.parse("https://lh3.googleusercontent.com/x=w720"))
        assertSame(online, share.forController(online, "com.example.car"))
        val none = item(null)
        assertSame(none, share.forController(none, "com.example.car"))
    }

    @Test fun filesOutsideTheSharedFoldersAreNotExposed() {
        val secret = File(context.filesDir.parentFile, "databases/surco.db")
        val shared = share.forController(item(Uri.fromFile(secret)), "com.example.car")
        assertNull(shared.mediaMetadata.artworkUri)
    }
}
