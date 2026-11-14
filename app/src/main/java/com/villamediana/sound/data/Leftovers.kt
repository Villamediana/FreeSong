package com.villamediana.sound.data

import java.io.File

/**
 * What features that left the app kept on the phone, deleted when the app opens: the karaoke of
 * 1.32.0 (its model, 39 MB, and the songs it worked out) left in 1.32.1. Nothing there: nothing done.
 */
object Leftovers {
    fun of(filesDir: File, cacheDir: File): List<File> = listOf(
        File(filesDir, "karaoke-model"),
        File(cacheDir, "karaoke"),
    )

    /** Deletes them; how many bytes that freed. */
    fun clear(filesDir: File, cacheDir: File): Long =
        of(filesDir, cacheDir).filter { it.exists() }.sumOf { dir ->
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
            bytes
        }
}
