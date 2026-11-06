package com.villamediana.sound.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import java.io.File

/**
 * Covers for other apps (car, watch, Bluetooth browsers, the system's resume card). Sound's covers
 * are files in its private storage, which a file:// uri can't open from another app; what other
 * apps see — browse items and the queue itself — carries a content:// uri of the app's FileProvider
 * (`<applicationId>.files`, covering external-files Music/) instead, and the apps get read
 * permission for them. Online thumbnails (https) pass as they are. Inside the app covers stay
 * file paths ([MediaItems.of]); the service turns them into content:// for what it hands out.
 */
internal class ArtworkShare(private val context: Context) {
    private val authority = "${context.packageName}.files"
    private val granted = HashSet<String>()
    private val contentUris = HashMap<String, Uri?>()

    /** [item] with a cover other apps can be allowed to read (file:// → content://; one outside the shared folders is dropped). */
    fun shareable(item: MediaItem): MediaItem {
        val art = item.mediaMetadata.artworkUri ?: return item
        if (art.scheme != "file") return item
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(contentUri(art)).build())
            .build()
    }

    /** The uri [shareable] gives a cover: content:// for a file, anything else as it is. */
    fun shareableUri(art: Uri?): Uri? = if (art?.scheme == "file") contentUri(art) else art

    fun forController(items: List<MediaItem>, controllerPackage: String?): List<MediaItem> =
        items.map { forController(it, controllerPackage) }

    /** [shareable], with read permission on its cover for [controllerPackage]. */
    fun forController(item: MediaItem, controllerPackage: String?): MediaItem {
        val shared = shareable(item)
        shared.mediaMetadata.artworkUri?.takeIf { it.scheme == "content" && it.authority == authority }?.let { grant(controllerPackage, it, prefix = false) }
        return shared
    }

    /**
     * Read permission on every cover in [coversDir] for [controllerPackage]: the queue's items reach
     * all controllers alike (Android Auto's queue, the system's media controls), with no per-app step.
     */
    fun grantCovers(controllerPackage: String?, coversDir: File) {
        contentUri(Uri.fromFile(coversDir))?.let { grant(controllerPackage, it, prefix = true) }
    }

    /** content:// for a cover file under the provider's roots; null when it can't be shared. */
    fun contentUri(fileUri: Uri): Uri? {
        val path = fileUri.path ?: return null
        synchronized(contentUris) { if (path in contentUris) return contentUris[path] }
        // getUriForFile resolves the canonical path (file system calls): the queue asks for the same covers over and over.
        val uri = runCatching { FileProvider.getUriForFile(context, authority, File(path)) }.getOrNull()
        synchronized(contentUris) {
            if (contentUris.size > MAX_REMEMBERED) contentUris.clear()
            contentUris[path] = uri
        }
        return uri
    }

    private fun grant(controllerPackage: String?, uri: Uri, prefix: Boolean) {
        val pkg = controllerPackage?.takeIf { it.isNotBlank() && it != context.packageName } ?: return
        synchronized(granted) {
            if (granted.size > MAX_REMEMBERED) granted.clear()
            if (!granted.add("$pkg|$uri|$prefix")) return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (prefix) Intent.FLAG_GRANT_PREFIX_URI_PERMISSION else 0)
        // Legacy controllers can come with a placeholder package name: then there's nobody to grant to.
        runCatching { context.grantUriPermission(pkg, uri, flags) }
    }

    private companion object {
        const val MAX_REMEMBERED = 4_000
    }
}
