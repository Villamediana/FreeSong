package com.villamediana.surco.online

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Real (trimmed) API answers, captured from the services, used by the offline tests. */
internal object OnlineFixtures {

    /** iTunes `search?term=Soda Stereo De Música Ligera&entity=song&country=BR` (first 6 results). */
    val itunesDeMusicaLigera = """
        {"resultCount": 6, "results": [
        {"wrapperType": "track", "kind": "song", "artistName": "Soda Stereo", "collectionName": "Canción Animal", "trackName": "De Música Ligera", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/60/18/68/601868f7-68ba-9416-af42-b26b2f68035f/mzi.qyqtvoxs.jpg/100x100bb.jpg", "releaseDate": "1990-08-07T07:00:00Z", "discNumber": 1, "trackNumber": 6, "trackTimeMillis": 212747, "primaryGenreName": "Alternativo e rock latino", "isStreamable": true, "currency": "BRL"},
        {"wrapperType": "track", "kind": "song", "artistName": "Soda Stereo", "collectionName": "El Último Concierto B (En Vivo)", "trackName": "De Música Ligera (En Vivo)", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/22/c1/f4/22c1f4bc-6cc4-0b5c-b610-eb599c4fe11f/mzi.uogolmwn.jpg/100x100bb.jpg", "releaseDate": "1990-01-01T12:00:00Z", "discNumber": 1, "trackNumber": 11, "trackTimeMillis": 291373, "primaryGenreName": "Alternativo e rock latino"},
        {"wrapperType": "track", "kind": "song", "artistName": "Soda Stereo", "collectionName": "Lo Esencial: Soda Stereo", "trackName": "De Música Ligera", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music/78/74/e4/mzi.fuwethvc.jpg/100x100bb.jpg", "releaseDate": "1990-08-07T07:00:00Z", "discNumber": 1, "trackNumber": 11, "trackTimeMillis": 210280, "primaryGenreName": "Alternativo e rock latino"},
        {"wrapperType": "track", "kind": "song", "artistName": "Soda Stereo", "collectionName": "Gira Me Verás Volver, Vol. 2", "trackName": "De Música Ligera", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music128/v4/06/d9/ed/06d9ed2a-e5cf-853d-44e1-398f32a7a976/888880823892.jpg/100x100bb.jpg", "releaseDate": "2008-07-14T07:00:00Z", "discNumber": 1, "trackNumber": 9, "trackTimeMillis": 221827, "primaryGenreName": "Alternativo e rock latino"},
        {"wrapperType": "track", "kind": "song", "artistName": "Soda Stereo", "collectionName": "SÉP7IMO DÍA", "trackName": "De Música Ligera (SÉP7IMO DÍA)", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/ad/21/09/ad210993-ed02-6865-2ecf-59dd0c58838b/886446346540.jpg/100x100bb.jpg", "releaseDate": "2017-03-10T12:00:00Z", "discNumber": 1, "trackNumber": 20, "trackTimeMillis": 349038, "primaryGenreName": "Alternativo e rock latino"},
        {"wrapperType": "track", "kind": "song", "artistName": "Soda Stereo", "collectionName": "Me Verás Volver (Hits & Más)", "trackName": "De Música Ligera", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/46/99/42/469942d2-3f7d-32cc-8d9f-f0ee7844cd7b/mzi.rpicvdyw.jpg/100x100bb.jpg", "releaseDate": "1990-08-07T07:00:00Z", "discNumber": 1, "trackNumber": 12, "trackTimeMillis": 212974, "primaryGenreName": "Alternativo e rock latino"}
        ]}
    """.trimIndent()

    /** iTunes `search?term=Anitta Envolver` (BR). */
    val itunesEnvolver = """
        {"resultCount": 4, "results": [
        {"wrapperType": "track", "kind": "song", "artistName": "Anitta", "collectionName": "Envolver - Single", "trackName": "Envolver", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/aa/bb/cc/x/196589.jpg/100x100bb.jpg", "releaseDate": "2021-11-11T12:00:00Z", "trackNumber": 1, "trackTimeMillis": 193806, "primaryGenreName": "Pop em espanhol"},
        {"wrapperType": "track", "kind": "song", "artistName": "Anitta & Justin Quiles", "collectionName": "Envolver Remix - Single", "trackName": "Envolver Remix", "releaseDate": "2022-02-17T08:00:00Z", "trackNumber": 1, "trackTimeMillis": 182863, "primaryGenreName": "Pop em espanhol"},
        {"wrapperType": "track", "kind": "song", "artistName": "Anitta", "collectionName": "Versions of Me", "trackName": "Envolver", "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/dd/ee/ff/y/196922.jpg/100x100bb.jpg", "releaseDate": "2021-11-11T12:00:00Z", "trackNumber": 1, "trackTimeMillis": 193806, "primaryGenreName": "Pop em espanhol"},
        {"wrapperType": "track", "kind": "song", "artistName": "Anitta", "collectionName": "Versions of Me (Deluxe)", "trackName": "Envolver", "releaseDate": "2021-11-11T08:00:00Z", "trackNumber": 6, "trackTimeMillis": 193806, "primaryGenreName": "Pop em espanhol"}
        ]}
    """.trimIndent()

    /** iTunes for "Bad Bunny DtMF": the real song isn't indexed, only look-alikes. */
    val itunesDtmfLookalikes = """
        {"resultCount": 3, "results": [
        {"wrapperType": "track", "kind": "song", "artistName": "D'SH!T", "collectionName": "DtMF Bad Bunny - Single", "trackName": "DtMF Bad Bunny", "releaseDate": "2026-03-26T12:00:00Z", "trackNumber": 1, "trackTimeMillis": 132942, "primaryGenreName": "Música Latina"},
        {"wrapperType": "track", "kind": "song", "artistName": "Bad Bunny", "collectionName": "Un Verano Sin Ti", "trackName": "Después de la Playa", "releaseDate": "2022-05-06T12:00:00Z", "trackNumber": 2, "trackTimeMillis": 230400, "primaryGenreName": "Música Latina"},
        {"wrapperType": "track", "kind": "song", "artistName": "Lil Baby Grand", "collectionName": "DtMF (piano version) - EP", "trackName": "DtMF (piano version)", "releaseDate": "2025-01-27T12:00:00Z", "trackNumber": 1, "trackTimeMillis": 136375, "primaryGenreName": "Música latina urbana"}
        ]}
    """.trimIndent()

    /** lrclib `/api/get` hit. */
    val lrclibGet = """
        {"id":1040024,"name":"De Música Ligera","trackName":"De Música Ligera","artistName":"Soda Stereo","albumName":"Canción Animal","duration":213.0,"instrumental":false,"hasWordSync":false,
         "plainLyrics":"Ella durmió al calor de las masas\nY yo desperté queriendo soñarla",
         "syncedLyrics":"[00:20.50] Ella durmió al calor de las masas\n[00:24.10] Y yo desperté queriendo soñarla"}
    """.trimIndent()

    val lrclibNotFound = """{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}"""

    /** lrclib `/api/search?track_name=DtMF&artist_name=Bad Bunny` (trimmed). */
    val lrclibSearchDtmf = """
        [
        {"id":30345518,"trackName":"DtMF","artistName":"Bad Bunny","albumName":"3:58","duration":180.0,"instrumental":false,"plainLyrics":"short edit","syncedLyrics":"[00:01.00] short edit"},
        {"id":30346957,"trackName":"Dtmf","artistName":"Bad Bunny","albumName":"German Top 100","duration":237.0,"instrumental":false,"plainLyrics":"Eh-eh, eh-eh","syncedLyrics":null},
        {"id":30058786,"trackName":"DtMF","artistName":"Bad Bunny","albumName":"DeBÍ TiRAR MáS FOToS","duration":238.0,"instrumental":false,"plainLyrics":"Eh-eh, eh-eh","syncedLyrics":"[00:10.00] Eh-eh, eh-eh"},
        {"id":99,"trackName":"DtMF (Instrumental)","artistName":"Bad Bunny","albumName":null,"duration":237.0,"instrumental":true,"plainLyrics":null,"syncedLyrics":null},
        {"id":100,"trackName":"Otra Canción","artistName":"Bad Bunny","albumName":null,"duration":237.0,"instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00] x"}
        ]
    """.trimIndent()

    /** A client whose every call is answered by [handler] (status, body) — no network. */
    class FakeHttp(private val handler: (Request) -> Pair<Int, String>) : Interceptor {
        val requests = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            synchronized(requests) { requests += req }
            val (code, body) = handler(req)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "ERR")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
