package com.villamediana.surco.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where Surco keeps its files. Everything lives in app-specific storage (no storage permission,
 * removed with the app):
 *   Music/surco/audio/<videoId>.<ext>
 *   Music/surco/covers/<name>.jpg
 *   Music/surco/lyrics/<videoId>.lrc|.txt
 */
class Storage(context: Context) {
    private val root: File = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"))
        .resolve("surco")

    val audioDir: File get() = root.resolve("audio").apply { mkdirs() }
    val coversDir: File get() = root.resolve("covers").apply { mkdirs() }
    val lyricsDir: File get() = root.resolve("lyrics").apply { mkdirs() }
    val tempDir: File get() = root.resolve("tmp").apply { mkdirs() }

    fun audioFile(videoId: String, extension: String): File = audioDir.resolve("${safe(videoId)}.${extension.trimStart('.')}")
    fun coverFile(name: String): File = coversDir.resolve("${safe(name)}.jpg")
    fun lyricsFile(videoId: String, synced: Boolean): File = lyricsDir.resolve("${safe(videoId)}.${if (synced) "lrc" else "txt"}")
    fun tempFile(name: String): File = tempDir.resolve(safe(name) + ".part")

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /** Keeps file names to [A-Za-z0-9_-], max 120 chars. */
        fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120).ifEmpty { "_" }
    }
}
