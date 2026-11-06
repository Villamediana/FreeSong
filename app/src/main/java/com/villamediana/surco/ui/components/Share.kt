package com.villamediana.surco.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.villamediana.surco.R
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.data.isOnline
import com.villamediana.surco.data.shareUrl
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.online.RemoteTrack
import java.io.File

/** Android share sheet for songs, files and collections. */
object Share {
    /** "Título — Artista" + link (YouTube Music). */
    fun trackLink(context: Context, track: Track) {
        val url = track.shareUrl ?: return
        text(context, "${track.title} — ${track.artist}\n$url", context.getString(R.string.share_song))
    }

    fun remoteTrack(context: Context, track: RemoteTrack) =
        text(context, "${track.title} — ${track.artist}\n${track.url}", context.getString(R.string.share_song))

    fun collection(context: Context, c: RemoteCollection) =
        text(context, listOfNotNull(c.title, c.author).joinToString(" — ") + "\n" + c.url, context.getString(R.string.share_song))

    /** Sends the audio file itself (WhatsApp, Bluetooth, Drive…). Only for downloaded songs. */
    fun trackFile(context: Context, track: Track) {
        if (track.isOnline) return trackLink(context, track)
        val file = File(track.filePath)
        if (!file.exists()) return trackLink(context, track)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = track.mimeType.ifEmpty { "audio/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "${track.title} — ${track.artist}")
            clipData = ClipData.newRawUri(track.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, intent, context.getString(R.string.share_file))
    }

    private fun text(context: Context, body: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        start(context, intent, title)
    }

    private fun start(context: Context, intent: Intent, title: String) {
        try {
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}
