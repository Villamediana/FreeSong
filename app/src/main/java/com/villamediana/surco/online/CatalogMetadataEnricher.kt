package com.villamediana.surco.online

import okhttp3.OkHttpClient

/** Title cleanup + iTunes Search API lookup. (Stub — implemented by the online module.) */
class CatalogMetadataEnricher(private val http: OkHttpClient) : MetadataEnricher {
    override suspend fun enrich(input: MetaInput): TrackMeta = TrackMeta(
        title = input.title,
        artist = input.artist,
        album = input.album,
        albumArtist = input.albumArtist,
        trackNumber = input.trackNumber,
        year = input.year,
    )
}
