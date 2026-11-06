package com.villamediana.surco.online

import com.villamediana.surco.data.AudioQuality
import okhttp3.OkHttpClient

/** YouTube / YouTube Music through NewPipeExtractor. (Stub — implemented by the online module.) */
class NewPipeYouTubeSource(private val http: OkHttpClient) : YouTubeSource {
    override suspend fun search(query: String, filter: SearchFilter): SearchPage = TODO()
    override suspend fun more(page: SearchPage): SearchPage = TODO()
    override suspend fun suggestions(query: String): List<String> = emptyList()
    override suspend fun resolve(text: String): ResolvedLink = ResolvedLink.Unsupported
    override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail = TODO()
    override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream = TODO()
    override suspend fun track(videoId: String): RemoteTrack = TODO()
}
