package com.villamediana.sound.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads YouTube Music browse responses. Sections are recognised by structure (page types,
 * endpoints), never by their localized titles.
 */
internal object YouTubeMusicParser {

    data class Header(
        val title: String,
        val author: String?,
        val year: Int?,
        val thumbnailUrl: String?,
        val trackCount: Long?,
        val description: String?,
        /** OLAK5uy_… (albums) or the playlist id itself. */
        val playlistId: String?,
    )

    data class BrowseRef(val browseId: String, val params: String?)

    data class ArtistPage(
        /** YouTube Music's own id for the artist (same for the topic and the official channel). */
        val channelId: String?,
        val name: String,
        val thumbnailUrl: String?,
        val description: String?,
        /** Playlist with the artist's songs ("Top songs" → see all). */
        val songsPlaylistId: String?,
        /** The few songs shown on the page itself (used when there is no playlist). */
        val songs: List<RemoteTrack>,
        /** Album/single/EP shelves: their items plus the "see all" page, when there is one. */
        val releaseShelves: List<Pair<List<RemoteCollection>, BrowseRef?>>,
        val playlists: List<RemoteCollection>,
    ) {
        val looksLikeArtist: Boolean get() = songsPlaylistId != null || songs.isNotEmpty() || releaseShelves.any { it.first.isNotEmpty() }
    }

    private val yearRe = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b")
    private val countRe = Regex("(\\d[\\d.,\\s ]*)")

    // ---------------------------------------------------------------- album / playlist page

    /** Header of an album (MPREb_… or VL+OLAK5uy_…) or playlist (VL+id) page. */
    fun header(json: JsonObject, fallbackPlaylistId: String? = null): Header? {
        val h = json.findFirst("musicResponsiveHeaderRenderer") as? JsonObject
            ?: json.findFirst("musicDetailHeaderRenderer") as? JsonObject
            ?: json.findFirst("musicEditablePlaylistDetailHeaderRenderer")?.findFirst("musicResponsiveHeaderRenderer") as? JsonObject
            ?: return null
        val title = h.obj("title").text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val subtitleRuns = h.obj("subtitle")?.arr("runs").objects().mapNotNull { it.str("text") }
        val strapline = h.obj("straplineTextOne").text()?.trim()
        // Old layout: "Album • Artist • 1995"; new layout: artist in the strapline.
        val author = strapline?.takeIf { it.isNotEmpty() }
            ?: h.obj("subtitle")?.arr("runs").objects().firstOrNull { it.at("navigationEndpoint.browseEndpoint.browseId") != null }?.str("text")
            ?: h.obj("facepile").findFirst("text")?.let { (it as? JsonObject).text() }
        val year = subtitleRuns.firstNotNullOfOrNull { yearRe.matchEntire(it.trim())?.value?.toIntOrNull() }
        val count = (h.obj("secondSubtitle")?.arr("runs").objects().firstOrNull()?.str("text"))
            ?.let { countRe.find(it)?.value?.filter(Char::isDigit)?.toLongOrNull() }
        val description = (h.obj("description")?.findFirst("description") as? JsonObject).text()
            ?: h.obj("description").text()
        val thumbs = thumbnails(h.obj("thumbnail"))
        return Header(
            title = title,
            author = author?.let(TitleCleaner::cleanArtist),
            year = year,
            thumbnailUrl = Thumbnails.best(thumbs, Thumbnails.COLLECTION),
            trackCount = count,
            description = description?.trim()?.ifEmpty { null },
            playlistId = albumPlaylistId(json) ?: fallbackPlaylistId,
        )
    }

    /** The OLAK5uy_… playlist behind an album page. */
    fun albumPlaylistId(json: JsonObject): String? {
        json.at("microformat.microformatDataRenderer.urlCanonical").asString()
            ?.toHttpUrlOrNull()?.queryParameter("list")?.let { return it }
        json.findFirst("audioPlaylistId").asString()?.takeIf { it.isNotBlank() }?.let { return it }
        return findOlak(json)
    }

    /** First album page (MPREb_…) linked from a response — e.g. from the rows of an album playlist. */
    fun albumBrowseId(json: JsonObject): String? = findBrowseId(json) { it.startsWith("MPREb_") }

    private fun findBrowseId(e: JsonElement?, accept: (String) -> Boolean): String? {
        when (e) {
            is JsonObject -> {
                e["browseId"].asString()?.takeIf(accept)?.let { return it }
                for (v in e.values) findBrowseId(v, accept)?.let { return it }
            }
            is JsonArray -> for (v in e) findBrowseId(v, accept)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun findOlak(e: JsonElement?): String? {
        when (e) {
            is JsonObject -> {
                e["playlistId"].asString()?.takeIf { it.startsWith("OLAK5uy_") }?.let { return it }
                for (v in e.values) findOlak(v)?.let { return it }
            }
            is JsonArray -> for (v in e) findOlak(v)?.let { return it }
            else -> Unit
        }
        return null
    }

    // ---------------------------------------------------------------- artist page

    fun artistPage(json: JsonObject): ArtistPage? {
        val headerObj = json.obj("header")?.values?.firstOrNull() as? JsonObject
        val name = headerObj?.obj("title").text()?.trim()
            ?: json.at("microformat.microformatDataRenderer.title").asString()
            ?: return null
        val thumb = thumbnails(json.at("microformat.microformatDataRenderer.thumbnail"))
            .ifEmpty { thumbnails(headerObj.obj("foregroundThumbnail")) }
            .ifEmpty { thumbnails(headerObj.obj("thumbnail")) }
        var description = headerObj.obj("description").text()

        val sections = json.at("contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents").objects()
        var songsPlaylistId: String? = null
        val songs = mutableListOf<RemoteTrack>()
        val releaseShelves = mutableListOf<Pair<List<RemoteCollection>, BrowseRef?>>()
        val playlists = mutableListOf<RemoteCollection>()

        for (section in sections) {
            section.obj("musicShelfRenderer")?.let { shelf ->
                val ref = shelf.obj("title")?.arr("runs").objects().firstNotNullOfOrNull { browseRef(it.obj("navigationEndpoint")) }
                    ?: browseRef(shelf.obj("bottomEndpoint"))
                if (songsPlaylistId == null) {
                    songsPlaylistId = ref?.browseId?.takeIf { it.startsWith("VL") }?.drop(2)
                }
                shelf.arr("contents").objects().mapNotNullTo(songs) { item ->
                    item.obj("musicResponsiveListItemRenderer")?.let { songRow(it, name) }
                }
            }
            section.obj("musicCarouselShelfRenderer")?.let { carousel ->
                val header = carousel.at("header.musicCarouselShelfBasicHeaderRenderer")
                val more = header.obj("title")?.arr("runs").objects().firstNotNullOfOrNull { browseRef(it.obj("navigationEndpoint")) }
                    ?: browseRef(header.at("moreContentButton.buttonRenderer.navigationEndpoint"))
                val items = carousel.arr("contents").objects().mapNotNull { it.obj("musicTwoRowItemRenderer") }
                val albums = items.filter { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }.mapNotNull { twoRowAlbum(it, name) }
                val lists = items.filter { pageType(it) == "MUSIC_PAGE_TYPE_PLAYLIST" }.mapNotNull { twoRowPlaylist(it) }
                if (albums.isNotEmpty()) releaseShelves += albums to more?.takeIf { it.browseId.startsWith("MPAD") || it.params != null }
                playlists += lists
            }
            if (description == null) {
                section.obj("musicDescriptionShelfRenderer")?.obj("description").text()?.let { description = it }
            }
        }

        return ArtistPage(
            channelId = canonicalChannelId(headerObj),
            name = name,
            thumbnailUrl = Thumbnails.best(thumb, Thumbnails.COLLECTION),
            description = description?.trim()?.ifEmpty { null },
            songsPlaylistId = songsPlaylistId,
            songs = songs.distinctBy { it.videoId },
            releaseShelves = releaseShelves,
            playlists = playlists.distinctBy { it.url },
        )
    }

    private val channelIdRe = Regex("UC[A-Za-z0-9_-]{22}")

    /**
     * The share button carries the artist's canonical id (protobuf, base64): browsing either the
     * "Artist - Topic" channel or the official artist channel gives the same one.
     */
    private fun canonicalChannelId(header: JsonObject?): String? {
        val serialized = header.at("shareEndpoint.shareEntityEndpoint.serializedShareEntity").asString() ?: return null
        return runCatching {
            val b64 = java.net.URLDecoder.decode(serialized, "UTF-8")
            val bytes = java.util.Base64.getUrlDecoder().decode(b64.replace('+', '-').replace('/', '_').trimEnd('='))
            channelIdRe.find(String(bytes, Charsets.ISO_8859_1))?.value
        }.getOrNull()
    }

    /** "See all" grid of albums/singles (discography page). */
    fun releasesGrid(json: JsonObject, artistName: String): List<RemoteCollection> {
        val grid = json.findFirst("gridRenderer") ?: return emptyList()
        return grid.arr("items").objects().mapNotNull { it.obj("musicTwoRowItemRenderer") }
            .filter { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }
            .mapNotNull { twoRowAlbum(it, artistName) }
    }

    // ---------------------------------------------------------------- search

    data class SearchResults(val items: List<SearchItem>, val continuation: String?)

    /**
     * Songs or albums of a search page or continuation. Null when the response isn't laid out as
     * expected (the caller then falls back to NewPipe's own parser).
     */
    fun searchResults(json: JsonObject, filter: SearchFilter): SearchResults? {
        val shelves: List<JsonObject> = when {
            json.obj("continuationContents") != null ->
                listOfNotNull(json.at("continuationContents.musicShelfContinuation") as? JsonObject)
            json.at("contents.tabbedSearchResultsRenderer") != null ->
                json.at("contents.tabbedSearchResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents")
                    .objects().mapNotNull { it.obj("musicShelfRenderer") }
            else -> return null
        }
        val items = shelves.flatMap { it.arr("contents").objects() }
            .mapNotNull { it.obj("musicResponsiveListItemRenderer") }
            .filter { it.str("musicItemRendererDisplayPolicy") != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT" }
            .mapNotNull { row ->
                when (filter) {
                    SearchFilter.SONGS -> songSearchRow(row)?.let { SearchItem.Song(it) }
                    SearchFilter.ALBUMS -> albumSearchRow(row)?.let { SearchItem.Collection(it) }
                    else -> null
                }
            }
        val continuation = shelves.lastOrNull()?.arr("continuations").objects()
            .firstNotNullOfOrNull { it.at("nextContinuationData.continuation").asString() }
        return SearchResults(items, continuation)
    }

    /** "Artist, Other • Album • 3:58" */
    private fun songSearchRow(row: JsonObject): RemoteTrack? {
        val columns = row.arr("flexColumns").objects().map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
        val videoId = row.at("playlistItemData.videoId").asString()
            ?: columns.firstOrNull()?.arr("runs").objects().firstNotNullOfOrNull { it.at("navigationEndpoint.watchEndpoint.videoId").asString() }
            ?: return null
        val title = columns.firstOrNull().text()?.trim()?.ifEmpty { null } ?: return null
        val groups = groups(columns.getOrNull(1)?.arr("runs").objects())
        val album = groups.flatten().firstOrNull { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("MPREb") == true }
            ?.str("text")?.trim()
        val duration = groups.lastOrNull()?.joinToString("") { it.str("text").orEmpty() }?.trim()?.let(::durationMs)
        val artistGroup = groups.firstOrNull { g -> g.any { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("UC") == true } }
            ?: groups.firstOrNull()?.takeIf { g -> g.none { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("MPREb") == true } }
        val artist = artistGroup?.joinToString("") { it.str("text").orEmpty() }?.trim().orEmpty()
        return RemoteTrack(
            videoId = videoId,
            url = YouTubeLinks.watchUrl(videoId, music = true),
            title = title,
            artist = TitleCleaner.cleanArtist(artist),
            album = album?.ifEmpty { null },
            durationMs = duration,
            thumbnailUrl = Thumbnails.best(thumbnails(row.obj("thumbnail")), Thumbnails.SONG),
            fromMusicCatalog = true,
        )
    }

    /** "Álbum • Artist • 1990" */
    private fun albumSearchRow(row: JsonObject): RemoteCollection? {
        val columns = row.arr("flexColumns").objects().map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
        val title = columns.firstOrNull().text()?.trim()?.ifEmpty { null } ?: return null
        val browseId = row.at("navigationEndpoint.browseEndpoint.browseId").asString()
        val playlistId = row.obj("overlay")?.findFirst("watchPlaylistEndpoint").str("playlistId")?.takeIf { it.startsWith("OLAK5uy_") }
        val url = when {
            playlistId != null -> YouTubeLinks.playlistUrl(playlistId)
            browseId != null && browseId.startsWith("MPREb_") -> YouTubeLinks.albumBrowseUrl(browseId)
            else -> return null
        }
        val groups = groups(columns.getOrNull(1)?.arr("runs").objects())
        val texts = groups.map { g -> g.joinToString("") { it.str("text").orEmpty() }.trim() }
        val year = texts.firstNotNullOfOrNull { yearRe.matchEntire(it)?.value?.toIntOrNull() }
        val artistGroup = groups.firstOrNull { g -> g.any { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("UC") == true } }
        val author = artistGroup?.joinToString("") { it.str("text").orEmpty() }?.trim()
            ?: texts.getOrNull(1)?.takeIf { yearRe.matchEntire(it) == null }
        return RemoteCollection(
            kind = CollectionKind.ALBUM,
            url = url,
            title = title,
            author = author?.let(TitleCleaner::cleanArtist)?.ifEmpty { null },
            thumbnailUrl = Thumbnails.best(thumbnails(row.obj("thumbnail")), Thumbnails.COLLECTION),
            year = year,
        )
    }

    /** Splits description runs on the " • " separators. */
    private fun groups(runs: List<JsonObject>): List<List<JsonObject>> {
        val out = mutableListOf<MutableList<JsonObject>>(mutableListOf())
        for (r in runs) {
            if (r.str("text")?.trim() == "•") out += mutableListOf<JsonObject>() else out.last() += r
        }
        return out.filter { it.isNotEmpty() }
    }

    private val durationRe = Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$")

    fun durationMs(text: String): Long? {
        val m = durationRe.matchEntire(text.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        return ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000
    }

    // ---------------------------------------------------------------- up next

    /**
     * Songs of a "next" response (the radio panel after a song). Rows wrapped for the audio/video
     * switch are unwrapped; unplayable rows are skipped. The seed song is usually the first row.
     */
    fun watchNext(json: JsonObject): List<RemoteTrack> {
        val panel = json.findFirst("playlistPanelRenderer") ?: return emptyList()
        return panel.arr("contents").objects()
            .mapNotNull { c ->
                c.obj("playlistPanelVideoRenderer")
                    ?: c.at("playlistPanelVideoWrapperRenderer.primaryRenderer.playlistPanelVideoRenderer") as? JsonObject
            }
            .mapNotNull(::panelRow)
            .distinctBy { it.videoId }
    }

    /** "Artist • Album • 1990" (songs) or "Artist • 1,2 mi de visualizações" (videos). */
    private fun panelRow(r: JsonObject): RemoteTrack? {
        if (r["unplayableText"] != null) return null
        val videoId = r.str("videoId") ?: r.at("navigationEndpoint.watchEndpoint.videoId").asString() ?: return null
        val title = r.obj("title").text()?.trim()?.ifEmpty { null } ?: return null
        val groups = groups((r.obj("longBylineText") ?: r.obj("shortBylineText"))?.arr("runs").objects())
        val artistGroup = groups.firstOrNull { g -> g.any { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("UC") == true } }
            ?: groups.firstOrNull()
        val artist = artistGroup?.joinToString("") { it.str("text").orEmpty() }?.trim().orEmpty()
        val album = groups.flatten().firstOrNull { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("MPREb") == true }
            ?.str("text")?.trim()?.ifEmpty { null }
        val videoType = r.at("navigationEndpoint.watchEndpoint.watchEndpointMusicSupportedConfigs.watchEndpointMusicConfig.musicVideoType").asString()
        val catalog = videoType != "MUSIC_VIDEO_TYPE_UGC"
        // User uploads in the radio carry video titles ("Artist - Song (Official Video)"): shown as the song.
        val shown = TitleCleaner.clean(title, artist, fromMusicCatalog = catalog)
        return RemoteTrack(
            videoId = videoId,
            // The url host carries the catalog flag through the player's transient Track
            // (OnlinePlay.toRemoteTrack reads it back when the song is downloaded).
            url = YouTubeLinks.watchUrl(videoId, music = catalog),
            title = shown.title.ifBlank { title },
            artist = shown.artist.ifBlank { TitleCleaner.cleanArtist(artist) },
            album = album,
            durationMs = r.obj("lengthText").text()?.let(::durationMs),
            thumbnailUrl = Thumbnails.best(thumbnails(r.obj("thumbnail")), Thumbnails.SONG),
            // User uploads in the radio carry video titles; catalog songs and official videos are clean.
            fromMusicCatalog = catalog,
        )
    }

    // ---------------------------------------------------------------- suggestions

    fun suggestions(json: JsonObject): List<String> =
        json.arr("contents").objects()
            .flatMap { it.at("searchSuggestionsSectionRenderer.contents").objects() }
            .mapNotNull { it.obj("searchSuggestionRenderer") }
            .mapNotNull { r -> r.at("navigationEndpoint.searchEndpoint.query").asString() ?: r.obj("suggestion").text() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    // ---------------------------------------------------------------- items

    private fun songRow(item: JsonObject, artistName: String): RemoteTrack? {
        val columns = item.arr("flexColumns").objects().map { it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") }
        val videoId = item.at("playlistItemData.videoId").asString()
            ?: columns.firstOrNull()?.arr("runs").objects().firstNotNullOfOrNull { it.at("navigationEndpoint.watchEndpoint.videoId") }
                .asString()
            ?: return null
        val title = columns.firstOrNull().text()?.trim()?.ifEmpty { null } ?: return null
        val runs = columns.drop(1).flatMap { it?.arr("runs").objects() }
        val artists = runs.filter { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("UC") == true }
            .mapNotNull { it.str("text") }
        val album = runs.firstOrNull { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("MPREb") == true }
            ?.str("text")
        val thumb = Thumbnails.best(thumbnails(item.obj("thumbnail")), Thumbnails.SONG)
        return RemoteTrack(
            videoId = videoId,
            url = YouTubeLinks.watchUrl(videoId, music = true),
            title = title,
            artist = TitleCleaner.cleanArtist(artists.joinToString(", ").ifEmpty { artistName }),
            album = album,
            thumbnailUrl = thumb,
            fromMusicCatalog = true,
        )
    }

    private fun twoRowAlbum(item: JsonObject, artistName: String): RemoteCollection? {
        val title = item.obj("title").text()?.trim()?.ifEmpty { null } ?: return null
        val browseId = item.at("navigationEndpoint.browseEndpoint.browseId").asString()
        val playlistId = (item.findFirst("watchPlaylistEndpoint")?.let { it.str("playlistId") })
            ?.takeIf { it.startsWith("OLAK5uy_") }
        val url = when {
            playlistId != null -> YouTubeLinks.playlistUrl(playlistId)
            browseId != null && browseId.startsWith("MPREb_") -> YouTubeLinks.albumBrowseUrl(browseId)
            else -> return null
        }
        val subtitle = item.obj("subtitle")?.arr("runs").objects().mapNotNull { it.str("text") }
        val year = subtitle.firstNotNullOfOrNull { yearRe.matchEntire(it.trim())?.value?.toIntOrNull() }
        return RemoteCollection(
            kind = CollectionKind.ALBUM,
            url = url,
            title = title,
            author = artistName,
            thumbnailUrl = Thumbnails.best(thumbnails(item.obj("thumbnailRenderer")), Thumbnails.COLLECTION),
            year = year,
        )
    }

    private fun twoRowPlaylist(item: JsonObject): RemoteCollection? {
        val title = item.obj("title").text()?.trim()?.ifEmpty { null } ?: return null
        val browseId = item.at("navigationEndpoint.browseEndpoint.browseId").asString()
            ?: return null
        val id = if (browseId.startsWith("VL")) browseId.drop(2) else browseId
        val kind = YouTubeLinks.playlistKind(id) ?: return null
        val runs = item.obj("subtitle")?.arr("runs").objects()
        val author = runs.firstOrNull { it.at("navigationEndpoint.browseEndpoint.browseId").asString()?.startsWith("UC") == true }?.str("text")
            ?: runs.mapNotNull { it.str("text")?.trim() }.filter { it.isNotEmpty() && it != "•" }.let { if (it.size >= 2) it[1] else null }
        return RemoteCollection(
            kind = kind,
            url = YouTubeLinks.playlistUrl(id),
            title = title,
            author = author,
            thumbnailUrl = Thumbnails.best(thumbnails(item.obj("thumbnailRenderer")), Thumbnails.COLLECTION),
        )
    }

    private fun pageType(item: JsonObject): String? =
        item.at("navigationEndpoint.browseEndpoint.browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType").asString()

    private fun browseRef(endpoint: JsonElement?): BrowseRef? {
        val be = endpoint.obj("browseEndpoint") ?: return null
        val id = be.str("browseId") ?: return null
        return BrowseRef(id, be.str("params"))
    }

    /** Any `{…Renderer:{thumbnail:{thumbnails:[…]}}}` / `{thumbnails:[…]}` shape. */
    fun thumbnails(e: JsonElement?): List<Thumbnails.Thumb> {
        val list = (e as? JsonObject)?.let { o -> o.arr("thumbnails") ?: o.findFirst("thumbnails") as? JsonArray }
            ?: return emptyList()
        return list.objects().mapNotNull { t ->
            val url = t.str("url") ?: return@mapNotNull null
            Thumbnails.Thumb(url, t.str("width")?.toIntOrNull() ?: 0, t.str("height")?.toIntOrNull() ?: 0)
        }
    }
}
