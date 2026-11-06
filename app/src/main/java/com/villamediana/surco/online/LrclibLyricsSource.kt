package com.villamediana.surco.online

import okhttp3.OkHttpClient

/** Lyrics from lrclib.net (synced LRC when available). (Stub — implemented by the online module.) */
class LrclibLyricsSource(private val http: OkHttpClient) : LyricsSource {
    override suspend fun find(title: String, artist: String, album: String?, durationMs: Long?): Lyrics? = null
}
