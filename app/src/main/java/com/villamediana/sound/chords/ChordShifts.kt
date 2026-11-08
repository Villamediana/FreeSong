package com.villamediana.sound.chords

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How far each song's chords are transposed (semitones, ±11), by song key, kept in [file]: a song
 * sung lower stays lower the next time. Songs back in their own key aren't kept.
 */
class ChordShifts(private val file: File?, private val maxSongs: Int = 500) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val writing = Mutex()

    private val shifts = MutableStateFlow(
        file?.takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull() }.orEmpty(),
    )

    fun of(key: String): Flow<Int> = shifts.map { it[key] ?: 0 }.distinctUntilChanged()

    fun current(key: String): Int = shifts.value[key] ?: 0

    suspend fun set(key: String, semitones: Int) {
        val shift = semitones.coerceIn(-MAX, MAX)
        shifts.update { map ->
            LinkedHashMap(map).apply {
                remove(key)
                if (shift != 0) put(key, shift)
                while (size > maxSongs) remove(keys.first())
            }
        }
        val f = file ?: return
        writing.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(json.encodeToString(serializer, shifts.value))
                    tmp.renameTo(f)
                }
            }
        }
    }

    companion object {
        const val MAX = 11
    }
}
