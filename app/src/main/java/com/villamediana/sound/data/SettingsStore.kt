package com.villamediana.sound.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sound_settings")

enum class AudioQuality {
    /** Highest bitrate available (usually Opus ~160 kbps). */
    BEST,
    /** Prefer AAC/M4A (~128 kbps) — widest compatibility, smaller files. */
    COMPATIBLE,
    /** Lowest bitrate (~50–70 kbps) — saves space. */
    SAVER,
}

enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class Settings(
    val audioQuality: AudioQuality = AudioQuality.BEST,
    val wifiOnly: Boolean = false,
    val fetchLyrics: Boolean = true,
    val parallelDownloads: Int = 2,
    /** Smart shuffle = weighted by taste, no same artist back to back. Plain shuffle otherwise. */
    val smartShuffle: Boolean = true,
    /** When the queue ends, keep playing similar songs from the library. */
    val autoplaySimilar: Boolean = true,
    /** Normalize volume between tracks (ExoPlayer loudness). */
    val normalizeVolume: Boolean = false,
    /** Tapping a search result streams it right away (true) or downloads it (false). */
    val playOnlineByDefault: Boolean = true,
    /** "Curtir já baixa": the heart of a streamed song also downloads it (only the heart shows on rows). */
    val likeDownloads: Boolean = false,
    /** The "Início" pill (suggestions). Off, the app opens straight on the songs. */
    val showHome: Boolean = true,
    /** The music video in the player: the clip behind the cover, a tap on it opens the video. Off = plain cover. */
    val playerVideo: Boolean = true,
    /** Crossfade between songs, in seconds (0 = off). Set in the equalizer. */
    val crossfadeSeconds: Int = 5,
    /** Lyrics in another language show their translation under each line. */
    val translateLyrics: Boolean = true,
    /** "Downloads inteligentes": the songs heard most online download by themselves on Wi-Fi. */
    val smartDownloads: Boolean = false,
    /** Chords over the lyrics (heard from the song on the phone). */
    val chords: Boolean = false,
    /** The instrument chord shapes are drawn for ([com.villamediana.sound.chords.Instrument] name). */
    val chordInstrument: String = "VIOLAO",
    val theme: ThemeMode = ThemeMode.DARK,
    /** Highlight color id (see [com.villamediana.sound.ui.theme.SoundAccents]). */
    val accent: String = "purple",
    /** Genres picked on the first Home ("O que você curte?"), ids of [com.villamediana.sound.discover.GenreCatalog]. */
    val favoriteGenres: Set<String> = emptySet(),
    /** The genre picker was answered (or skipped): Home shows the suggestions from then on. */
    val genresChosen: Boolean = false,
    /** Listening time of plays already pruned from the history ([com.villamediana.sound.data.LibraryRepository.pruneHistory]). */
    val listenedBeforeMs: Long = 0L,
    val lastQueueJson: String? = null,
)

class SettingsStore(private val context: Context) {
    private object K {
        val quality = stringPreferencesKey("audio_quality")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val lyrics = booleanPreferencesKey("fetch_lyrics")
        val parallel = intPreferencesKey("parallel_downloads")
        val smartShuffle = booleanPreferencesKey("smart_shuffle")
        val autoplay = booleanPreferencesKey("autoplay_similar")
        val normalize = booleanPreferencesKey("normalize_volume")
        val lastQueue = stringPreferencesKey("last_queue")
        val playOnline = booleanPreferencesKey("play_online_default")
        val likeDownloads = booleanPreferencesKey("like_downloads")
        val showHome = booleanPreferencesKey("show_home")
        val crossfade = intPreferencesKey("crossfade_seconds")
        val playerVideo = booleanPreferencesKey("player_video")
        val translateLyrics = booleanPreferencesKey("translate_lyrics")
        val smartDownloads = booleanPreferencesKey("smart_downloads")
        val chords = booleanPreferencesKey("chords")
        val chordInstrument = stringPreferencesKey("chord_instrument")
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val genres = stringSetPreferencesKey("favorite_genres")
        val genresChosen = booleanPreferencesKey("genres_chosen")
        val listenedBefore = androidx.datastore.preferences.core.longPreferencesKey("listened_before_ms")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            audioQuality = p[K.quality]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: AudioQuality.BEST,
            wifiOnly = p[K.wifiOnly] ?: false,
            fetchLyrics = p[K.lyrics] ?: true,
            parallelDownloads = (p[K.parallel] ?: 2).coerceIn(1, 4),
            smartShuffle = p[K.smartShuffle] ?: true,
            autoplaySimilar = p[K.autoplay] ?: true,
            normalizeVolume = p[K.normalize] ?: false,
            playOnlineByDefault = p[K.playOnline] ?: true,
            likeDownloads = p[K.likeDownloads] ?: false,
            showHome = p[K.showHome] ?: true,
            crossfadeSeconds = (p[K.crossfade] ?: 5).coerceIn(0, MAX_CROSSFADE_SECONDS),
            playerVideo = p[K.playerVideo] ?: true,
            translateLyrics = p[K.translateLyrics] ?: true,
            smartDownloads = p[K.smartDownloads] ?: false,
            chords = p[K.chords] ?: false,
            chordInstrument = p[K.chordInstrument] ?: "VIOLAO",
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            accent = p[K.accent] ?: "purple",
            favoriteGenres = p[K.genres].orEmpty(),
            genresChosen = p[K.genresChosen] ?: false,
            listenedBeforeMs = p[K.listenedBefore] ?: 0L,
            lastQueueJson = p[K.lastQueue],
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setAudioQuality(v: AudioQuality) = context.dataStore.edit { it[K.quality] = v.name }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setFetchLyrics(v: Boolean) = context.dataStore.edit { it[K.lyrics] = v }
    suspend fun setParallelDownloads(v: Int) = context.dataStore.edit { it[K.parallel] = v.coerceIn(1, 4) }
    suspend fun setSmartShuffle(v: Boolean) = context.dataStore.edit { it[K.smartShuffle] = v }
    suspend fun setAutoplaySimilar(v: Boolean) = context.dataStore.edit { it[K.autoplay] = v }
    suspend fun setNormalizeVolume(v: Boolean) = context.dataStore.edit { it[K.normalize] = v }
    suspend fun setFavoriteGenres(ids: Set<String>) = context.dataStore.edit {
        it[K.genres] = ids
        it[K.genresChosen] = true
    }
    suspend fun addListenedBefore(ms: Long) = context.dataStore.edit { it[K.listenedBefore] = (it[K.listenedBefore] ?: 0L) + ms }
    suspend fun setAccent(id: String) = context.dataStore.edit { it[K.accent] = id }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setPlayOnlineByDefault(v: Boolean) = context.dataStore.edit { it[K.playOnline] = v }
    suspend fun setLikeDownloads(v: Boolean) = context.dataStore.edit { it[K.likeDownloads] = v }
    suspend fun setShowHome(v: Boolean) = context.dataStore.edit { it[K.showHome] = v }
    suspend fun setPlayerVideo(v: Boolean) = context.dataStore.edit { it[K.playerVideo] = v }
    suspend fun setCrossfadeSeconds(v: Int) = context.dataStore.edit { it[K.crossfade] = v.coerceIn(0, MAX_CROSSFADE_SECONDS) }
    suspend fun setTranslateLyrics(v: Boolean) = context.dataStore.edit { it[K.translateLyrics] = v }
    suspend fun setSmartDownloads(v: Boolean) = context.dataStore.edit { it[K.smartDownloads] = v }
    suspend fun setChords(v: Boolean) = context.dataStore.edit { it[K.chords] = v }
    suspend fun setChordInstrument(id: String) = context.dataStore.edit { it[K.chordInstrument] = id }
    suspend fun setLastQueueJson(v: String?) = context.dataStore.edit { if (v == null) it.remove(K.lastQueue) else it[K.lastQueue] = v }
}

const val MAX_CROSSFADE_SECONDS = 12
