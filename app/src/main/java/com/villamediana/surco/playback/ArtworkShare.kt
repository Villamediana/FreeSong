package com.villamediana.surco.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import java.io.File

/**
 * Covers for other apps (car, watch, Bluetooth browsers). Surco's covers are files in its private
 * storage, which a file:// uri can't open from another app; browse items carry a content:// uri of
 * the app's FileProvider (`<applicationId>.files`, covering external-files Music/) instead, and the
 * app asking gets read permission for each one it is shown. Online thumbnails (https) pass as they are.
 */
internal class ArtworkShare(private val context: Context) {
    private val authority = "${context.packageName}.files"
    private val granted = HashSet<String>()

    fun forController(items: List<MediaItem>, controllerPackage: String?): List<MediaItem> =
        items.map { forController(it, controllerPackage) }

    fun forController(item: MediaItem, controllerPackage: String?): MediaItem {
        val art = item.mediaMetadata.artworkUri ?: return item
        if (art.scheme != "file") return item
        val content = contentUri(art)
        if (content != null) grant(controllerPackage, content)
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(content).build())
            .build()
    }

    /** content:// for a cover file under the provider's roots; null when it can't be shared. */
    fun contentUri(fileUri: Uri): Uri? {
        val path = fileUri.path ?: return null
        return runCatching { FileProvider.getUriForFile(context, authority, File(path)) }.getOrNull()
    }

    private fun grant(controllerPackage: String?, uri: Uri) {
        val pkg = controllerPackage?.takeIf { it.isNotBlank() && it != context.packageName } ?: return
        synchronized(granted) {
            if (granted.size > MAX_REMEMBERED) granted.clear()
            if (!granted.add("$pkg|$uri")) return
        }
        // Legacy controllers can come with a placeholder package name: then there's nobody to grant to.
        runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private companion object {
        const val MAX_REMEMBERED = 4_000
    }
}
