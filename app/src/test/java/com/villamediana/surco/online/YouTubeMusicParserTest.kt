package com.villamediana.surco.online

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes captured from music.youtube.com browse responses (trimmed to what the parser reads). */
class YouTubeMusicParserTest {

    private fun json(s: String) = OnlineJson.parseToJsonElement(s).jsonObject

    private val albumPage = json(
        """
        {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicResponsiveHeaderRenderer":{
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
              {"url":"https://yt3.googleusercontent.com/nAW1=w60-h60-s-l90-rj","width":60,"height":60},
              {"url":"https://yt3.googleusercontent.com/nAW1=w544-h544-s-l90-rj","width":544,"height":544}]}}},
            "title":{"runs":[{"text":"Sueño Stereo"}]},
            "subtitle":{"runs":[{"text":"Álbum"},{"text":" • "},{"text":"1995"}]},
            "straplineTextOne":{"runs":[{"text":"Soda Stereo","navigationEndpoint":{"browseEndpoint":{"browseId":"UCQJuTjhtb6Oyy2kStA1BUKw"}}}]},
            "secondSubtitle":{"runs":[{"text":"12 músicas"},{"text":" • "},{"text":"53 minutos"}]},
            "description":{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Sexto álbum de estudio."}]}}}
          }}]}}}}],
          "secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"1zzzby2tAQM"}}}]}}]}}}},
         "microformat":{"microformatDataRenderer":{"urlCanonical":"https://music.youtube.com/playlist?list=OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4"}}}
        """,
    )

    private val oldAlbumPage = json(
        """
        {"header":{"musicDetailHeaderRenderer":{
           "title":{"runs":[{"text":"Canción Animal"}]},
           "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"Soda Stereo","navigationEndpoint":{"browseEndpoint":{"browseId":"UCQJuTjhtb6Oyy2kStA1BUKw"}}},{"text":" • "},{"text":"1990"}]},
           "secondSubtitle":{"runs":[{"text":"10 songs"},{"text":" • "},{"text":"38 minutes"}]},
           "thumbnail":{"croppedSquareThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/fq=w226-h226-l90-rj","width":226,"height":226}]}}}}},
         "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
           {"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"sWft4cMiwXQ","playlistId":"OLAK5uy_nhHzfeEh8qiJo8EbAoWRfLH6wFcYPSigI"}}}}}}}}]}}]}}}}]}}}
        """,
    )

    private val artistPage = json(
        """
        {"header":{"musicImmersiveHeaderRenderer":{
            "title":{"runs":[{"text":"Soda Stereo"}]},
            "description":{"runs":[{"text":"Soda Stereo foi uma banda de rock argentina."}]},
            "shareEndpoint":{"shareEntityEndpoint":{"serializedShareEntity":"GhhVQ1FKdVRqaHRiNk95eTJrU3RBMUJVS3c%3D"}},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/v0SO=w1440-h600-p-l90-rj","width":1440,"height":600}]}}}}},
         "microformat":{"microformatDataRenderer":{"title":"Soda Stereo","thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/v0SO=w544-h544-p-l90-rj","width":544,"height":544}]}}},
         "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
           {"musicShelfRenderer":{
              "title":{"runs":[{"text":"Top músicas","navigationEndpoint":{"browseEndpoint":{"browseId":"VLOLAK5uy_l51TBhOIYKgmeTU3zeDPNx6lm6g8lOzDs","params":"ggMCCAI%3D"}}}]},
              "contents":[
                {"musicResponsiveListItemRenderer":{
                   "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/fqz=w120-h120-l90-rj","width":120,"height":120}]}}},
                   "flexColumns":[
                     {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"De Música Ligera (Remasterizado 2007)","navigationEndpoint":{"watchEndpoint":{"videoId":"sWft4cMiwXQ"}}}]}}},
                     {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Soda Stereo","navigationEndpoint":{"browseEndpoint":{"browseId":"UCQJuTjhtb6Oyy2kStA1BUKw"}}}]}}},
                     {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Tocou 592 mi vezes"}]}}},
                     {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Canción Animal","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x7Lv"}}}]}}}]}},
                {"musicResponsiveListItemRenderer":{
                   "playlistItemData":{"videoId":"vo7jBHB66q4"},
                   "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"En la Ciudad de la Furia"}]}}}]}}]}},
           {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Álbuns","navigationEndpoint":{"browseEndpoint":{"browseId":"MPADUCQJuTjhtb6Oyy2kStA1BUKw","params":"ggMIegYIARoCAQI%3D"}}}]}}},
              "contents":[
                {"musicTwoRowItemRenderer":{
                   "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/nAW1=w226-h226-s-l90-rj","width":226,"height":226}]}}},
                   "title":{"runs":[{"text":"Sueño Stereo"}]},
                   "subtitle":{"runs":[{"text":"1995"}]},
                   "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_7LvbQPQlUGp","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
                   "thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4"}}}}}}}}]}},
           {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Singles e EPs"}]}}},
              "contents":[
                {"musicTwoRowItemRenderer":{
                   "title":{"runs":[{"text":"Languis"}]},
                   "subtitle":{"runs":[{"text":"EP"},{"text":" • "},{"text":"1989"}]},
                   "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_lc1XfxpYzzM","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}]}},
           {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Vídeos"}]}}},
              "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"De Música Ligera"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"T_FkEw27XJ0"}}}}]}},
           {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Incluído em"}]}}},
              "contents":[
                {"musicTwoRowItemRenderer":{
                   "title":{"runs":[{"text":"Apresentamos os Soda Stereo"}]},
                   "subtitle":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"YouTube Music"}]},
                   "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDCLAK5uy_nyD9oEfxn-d5aJD7sgL89sZoTJ5q3jZ4w","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}}]}},
           {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Os fãs também podem gostar de..."}]}}},
              "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Gustavo Cerati"}]},
                 "navigationEndpoint":{"browseEndpoint":{"browseId":"UCUdjkQsRH3EgFvbVWNznYPQ","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}}]}}
         ]}}}}]}}}
        """,
    )

    private val discography = json(
        """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"gridRenderer":{"items":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Sueño Stereo"}]},"subtitle":{"runs":[{"text":"Álbum"},{"text":" • "},{"text":"1995"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_7LvbQPQlUGp","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
            "thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4"}}}}}}}},
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Canción Animal"}]},"subtitle":{"runs":[{"text":"Álbum"},{"text":" • "},{"text":"1990"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_aaa","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
            "thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"OLAK5uy_nhHzfeEh8qiJo8EbAoWRfLH6wFcYPSigI"}}}}}}}}
        ]}}]}}}}]}}}
        """,
    )

    @Test fun albumHeaderNewLayout() {
        val h = YouTubeMusicParser.header(albumPage)!!
        assertEquals("Sueño Stereo", h.title)
        assertEquals("Soda Stereo", h.author)
        assertEquals(1995, h.year)
        assertEquals(12L, h.trackCount)
        assertEquals("Sexto álbum de estudio.", h.description)
        assertEquals("https://yt3.googleusercontent.com/nAW1=w1080-h1080-s-l90-rj", h.thumbnailUrl)
        assertEquals("OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4", h.playlistId)
    }

    @Test fun albumHeaderOldLayout() {
        val h = YouTubeMusicParser.header(oldAlbumPage)!!
        assertEquals("Canción Animal", h.title)
        assertEquals("Soda Stereo", h.author)
        assertEquals(1990, h.year)
        assertEquals(10L, h.trackCount)
        assertEquals("https://lh3.googleusercontent.com/fq=w1080-h1080-l90-rj", h.thumbnailUrl)
        assertEquals("OLAK5uy_nhHzfeEh8qiJo8EbAoWRfLH6wFcYPSigI", YouTubeMusicParser.albumPlaylistId(oldAlbumPage))
    }

    @Test fun noHeader() {
        assertNull(YouTubeMusicParser.header(json("""{"contents":{}}""")))
    }

    @Test fun artistPage() {
        val p = YouTubeMusicParser.artistPage(artistPage)!!
        assertTrue(p.looksLikeArtist)
        assertEquals("Soda Stereo", p.name)
        assertEquals("UCQJuTjhtb6Oyy2kStA1BUKw", p.channelId)
        assertEquals("Soda Stereo foi uma banda de rock argentina.", p.description)
        // Square portrait from the microformat, not the wide banner.
        assertEquals("https://lh3.googleusercontent.com/v0SO=w1080-h1080-p-l90-rj", p.thumbnailUrl)
        assertEquals("OLAK5uy_l51TBhOIYKgmeTU3zeDPNx6lm6g8lOzDs", p.songsPlaylistId)

        assertEquals(listOf("sWft4cMiwXQ", "vo7jBHB66q4"), p.songs.map { it.videoId })
        val first = p.songs[0]
        assertEquals("De Música Ligera (Remasterizado 2007)", first.title)
        assertEquals("Soda Stereo", first.artist)
        assertEquals("Canción Animal", first.album)
        assertTrue(first.fromMusicCatalog)
        assertEquals("https://music.youtube.com/watch?v=sWft4cMiwXQ", first.url)
        assertEquals("Soda Stereo", p.songs[1].artist)

        assertEquals(2, p.releaseShelves.size)
        val (albums, more) = p.releaseShelves[0]
        assertEquals(YouTubeMusicParser.BrowseRef("MPADUCQJuTjhtb6Oyy2kStA1BUKw", "ggMIegYIARoCAQI%3D"), more)
        val sueno = albums.single()
        assertEquals(CollectionKind.ALBUM, sueno.kind)
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_lqFiSiZrrWzJcqFIqQ6VI9LFURooEFFD4", sueno.url)
        assertEquals(1995, sueno.year)
        assertEquals("Soda Stereo", sueno.author)
        val (singles, noMore) = p.releaseShelves[1]
        assertNull(noMore)
        // No play overlay: falls back to the album page link, resolved later.
        assertEquals("https://music.youtube.com/browse/MPREb_lc1XfxpYzzM", singles.single().url)
        assertEquals(1989, singles.single().year)

        // Videos and related artists aren't collections to download; playlists are.
        val playlist = p.playlists.single()
        assertEquals(CollectionKind.PLAYLIST, playlist.kind)
        assertEquals("https://music.youtube.com/playlist?list=RDCLAK5uy_nyD9oEfxn-d5aJD7sgL89sZoTJ5q3jZ4w", playlist.url)
        assertEquals("YouTube Music", playlist.author)
    }

    @Test fun nonArtistChannel() {
        val p = YouTubeMusicParser.artistPage(
            json(
                """
                {"header":{"musicVisualHeaderRenderer":{"title":{"runs":[{"text":"MrBeast"}]}}},
                 "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
                   {"musicCarouselShelfRenderer":{"contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"x"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}]}}]}}}}]}}}
                """,
            ),
        )!!
        assertEquals("MrBeast", p.name)
        assertFalse(p.looksLikeArtist)
    }

    @Test fun discographyGrid() {
        val albums = YouTubeMusicParser.releasesGrid(discography, "Soda Stereo")
        assertEquals(listOf("Sueño Stereo", "Canción Animal"), albums.map { it.title })
        assertEquals(listOf(1995, 1990), albums.map { it.year })
        assertTrue(albums.all { it.url.startsWith("https://music.youtube.com/playlist?list=OLAK5uy_") })
    }

    @Test fun suggestions() {
        val s = YouTubeMusicParser.suggestions(
            json(
                """
                {"contents":[
                  {"searchSuggestionsSectionRenderer":{"contents":[
                    {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"soda ste","bold":true},{"text":"reo"}]},"navigationEndpoint":{"searchEndpoint":{"query":"soda stereo"}}}},
                    {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"soda stereo musica ligera"}]}}},
                    {"searchSuggestionRenderer":{"navigationEndpoint":{"searchEndpoint":{"query":"soda stereo"}}}}]}},
                  {"searchSuggestionsSectionRenderer":{"contents":[{"musicResponsiveListItemRenderer":{}}]}}]}
                """,
            ),
        )
        assertEquals(listOf("soda stereo", "soda stereo musica ligera"), s)
    }
}
