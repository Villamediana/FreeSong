package com.villamediana.sound.online

import com.villamediana.sound.data.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubePlaylistExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.coroutines.cancellation.CancellationException
import org.schabi.newpipe.extractor.stream.AudioStream as NpAudioStream
import org.schabi.newpipe.extractor.stream.VideoStream as NpVideoStream

/**
 * YouTube / YouTube Music through NewPipeExtractor, plus a few YouTube Music pages read directly
 * ([YouTubeMusicApi]) where NewPipe has no extractor: artist pages, album pages and playlist headers.
 */
class NewPipeYouTubeSource(private val http: OkHttpClient) : YouTubeSource {

    private val service: YoutubeService get() = ServiceList.YouTube
    private val music = YouTubeMusicApi(http)

    /** Opaque search continuation: the query handler + NewPipe page + what was already shown. */
    private class Cursor(val handler: SearchQueryHandler, val page: Page, val seen: Set<String>)

    /** Continuation of a search read straight from YouTube Music (songs/albums). */
    private class MusicCursor(val token: String, val seen: Set<String>)

    private suspend fun <T> io(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        NewPipeHttp.init(http)
        onlineCall(what, block)
    }

    // ------------------------------------------------------------------------------- search

    override suspend fun search(query: String, filter: SearchFilter): SearchPage {
        val q = query.trim()
        if (q.isEmpty()) return SearchPage(query, filter, emptyList())
        return io("search") {
            // Songs and albums: read YouTube Music's shelves ourselves to also get each song's album and
            // each album's year. NewPipe's parser stays as the fallback if the layout ever changes.
            musicSearch(query, q, filter)?.let { return@io it }
            val handler = service.searchQHFactory.fromQuery(q, listOf(contentFilter(filter)), "")
            val extractor = service.getSearchExtractor(handler)
            extractor.fetchPage()
            val first = extractor.initialPage
            toPage(query, filter, handler, first.items, first.nextPage, emptySet())
        }
    }

    private fun musicSearch(query: String, q: String, filter: SearchFilter): SearchPage? {
        val params = when (filter) {
            SearchFilter.SONGS -> YouTubeMusicApi.PARAMS_SONGS
            SearchFilter.ALBUMS -> YouTubeMusicApi.PARAMS_ALBUMS
            else -> return null
        }
        val results = runCatching { YouTubeMusicParser.searchResults(music.search(q, params), filter) }.getOrNull() ?: return null
        return toMusicPage(query, filter, results, emptySet())
    }

    private fun toMusicPage(query: String, filter: SearchFilter, results: YouTubeMusicParser.SearchResults, seen: Set<String>): SearchPage {
        val keys = seen.toMutableSet()
        val items = results.items.filter { keys.add(keyOf(it)) }
        return SearchPage(query, filter, items, results.continuation?.let { MusicCursor(it, keys) })
    }

    private fun keyOf(item: SearchItem): String = when (item) {
        is SearchItem.Song -> "v:" + item.track.videoId
        is SearchItem.Collection -> "c:" + item.collection.url
    }

    override suspend fun more(page: SearchPage): SearchPage {
        (page.cursor as? MusicCursor)?.let { cursor ->
            return io("more") {
                var token: String? = cursor.token
                var seen = cursor.seen
                var result = SearchPage(page.query, page.filter, emptyList(), null)
                var tries = 0
                while (token != null && tries < 3) {
                    val parsed = YouTubeMusicParser.searchResults(music.searchContinuation(token), page.filter) ?: break
                    result = toMusicPage(page.query, page.filter, parsed, seen)
                    if (result.items.isNotEmpty()) break
                    seen = (result.cursor as? MusicCursor)?.seen ?: seen
                    token = parsed.continuation
                    tries++
                }
                result
            }
        }
        val cursor = page.cursor as? Cursor ?: return page.copy(items = emptyList(), cursor = null)
        return io("more") {
            var next: Page? = cursor.page
            var seen = cursor.seen
            var result = SearchPage(page.query, page.filter, emptyList(), null)
            var tries = 0
            // A continuation can come back made only of repeats; skip ahead a little instead of ending the list.
            while (next != null && tries < 3) {
                val more = SearchInfo.getMoreItems(service, cursor.handler, next)
                result = toPage(page.query, page.filter, cursor.handler, more.items, more.nextPage, seen)
                if (result.items.isNotEmpty()) break
                seen = (result.cursor as? Cursor)?.seen ?: seen
                next = more.nextPage
                tries++
            }
            result
        }
    }

    private fun toPage(
        query: String,
        filter: SearchFilter,
        handler: SearchQueryHandler,
        items: List<InfoItem>,
        nextPage: Page?,
        seen: Set<String>,
    ): SearchPage {
        val keys = seen.toMutableSet()
        val out = ArrayList<SearchItem>(items.size)
        for (item in items) {
            val converted = toSearchItem(item, filter) ?: continue
            if (keys.add(keyOf(converted))) out += converted
        }
        val next = nextPage?.takeIf { it.url != null || it.body != null }
        return SearchPage(query, filter, out, next?.let { Cursor(handler, it, keys) })
    }

    private fun toSearchItem(item: InfoItem, filter: SearchFilter): SearchItem? = when (item) {
        is StreamInfoItem -> streamItemToTrack(item, catalog = filter != SearchFilter.VIDEOS, requireDuration = filter == SearchFilter.VIDEOS)
            ?.let { SearchItem.Song(it) }
        is PlaylistInfoItem -> if (filter == SearchFilter.ALBUMS || filter == SearchFilter.PLAYLISTS) {
            playlistItemToCollection(item, albumHint = filter == SearchFilter.ALBUMS)?.let { SearchItem.Collection(it) }
        } else null
        is ChannelInfoItem -> if (filter == SearchFilter.ARTISTS) channelItemToCollection(item)?.let { SearchItem.Collection(it) } else null
        else -> null
    }

    private fun contentFilter(filter: SearchFilter): String = when (filter) {
        SearchFilter.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        SearchFilter.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
        SearchFilter.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    }

    // ------------------------------------------------------------------------------- suggestions

    override suspend fun suggestions(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                NewPipeHttp.init(http)
                val fromMusic = runCatching { music.suggestions(q) }.getOrNull().orEmpty()
                val list = fromMusic.ifEmpty { runCatching { service.suggestionExtractor.suggestionList(q) }.getOrNull().orEmpty() }
                list.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_SUGGESTIONS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------------------- links

    override suspend fun resolve(text: String): ResolvedLink {
        val link = YouTubeLinks.parse(text) ?: return ResolvedLink.Unsupported
        return when (link) {
            is YouTubeLink.Video -> ResolvedLink.Track(track(link.videoId))
            is YouTubeLink.Playlist -> ResolvedLink.Collection(io("resolve") { playlistHeader(link.playlistId, link.kind) })
            is YouTubeLink.AlbumBrowse -> io("resolve") {
                val json = music.browse(link.browseId)
                val header = YouTubeMusicParser.header(json)
                val playlistId = header?.playlistId ?: YouTubeMusicParser.albumPlaylistId(json)
                if (header == null || playlistId == null) null else header.toCollection(playlistId, CollectionKind.ALBUM)
            }?.let { ResolvedLink.Collection(it) } ?: ResolvedLink.Unsupported
            is YouTubeLink.Channel -> ResolvedLink.Collection(io("resolve") { artistHeader(link.channelId) })
            is YouTubeLink.ChannelPath -> ResolvedLink.Collection(io("resolve") { artistHeader(YoutubeChannelHelper.resolveChannelId(link.path)) })
        }
    }

    private fun playlistHeader(playlistId: String, kind: CollectionKind): RemoteCollection {
        val header = runCatching { musicHeader(playlistId) }.getOrNull()
        if (header != null) return header.toCollection(playlistId, kind)
        val extractor = playlistExtractor(playlistId)
        extractor.fetchPage()
        return RemoteCollection(
            kind = kind,
            url = YouTubeLinks.playlistUrl(playlistId),
            title = if (kind == CollectionKind.ALBUM) stripAlbumPrefix(extractor.name) else extractor.name,
            author = runCatching { extractor.uploaderName }.getOrNull()?.let(TitleCleaner::cleanArtist)?.ifBlank { null },
            thumbnailUrl = best(runCatching { extractor.thumbnails }.getOrNull().orEmpty(), Thumbnails.COLLECTION),
            itemCount = runCatching { extractor.streamCount }.getOrNull()?.takeIf { it >= 0 },
        )
    }

    /**
     * YouTube Music header of a playlist. Album playlists (OLAK5uy_…) answer with a bare track list,
     * so their album page (MPREb_…, linked from the rows) is read instead — that's where year and art live.
     */
    private fun musicHeader(playlistId: String): YouTubeMusicParser.Header? {
        val json = music.browse("VL$playlistId")
        YouTubeMusicParser.header(json, playlistId)?.let { return it }
        // Only an album playlist may borrow the album page — a user playlist links many albums.
        if (YouTubeLinks.playlistKind(playlistId) != CollectionKind.ALBUM) return null
        val albumId = YouTubeMusicParser.albumBrowseId(json) ?: return null
        return YouTubeMusicParser.header(music.browse(albumId), playlistId)
    }

    private fun artistHeader(channelId: String): RemoteCollection {
        val page = runCatching { YouTubeMusicParser.artistPage(music.browse(channelId)) }.getOrNull()
        if (page != null) {
            return RemoteCollection(CollectionKind.ARTIST, YouTubeLinks.channelUrl(page.channelId ?: channelId), page.name, null, page.thumbnailUrl)
        }
        val info = ChannelInfo.getInfo(service, channelPageUrl(channelId))
        return RemoteCollection(
            kind = CollectionKind.ARTIST,
            url = YouTubeLinks.channelUrl(channelId),
            title = TitleCleaner.cleanArtist(info.name),
            thumbnailUrl = best(info.avatars, Thumbnails.COLLECTION),
        )
    }

    // ------------------------------------------------------------------------------- collections

    override suspend fun collection(url: String, kind: CollectionKind, limit: Int): CollectionDetail {
        val max = limit.coerceAtLeast(1)
        val link = YouTubeLinks.parse(url)
            ?: throw OnlineException("Not a YouTube collection: $url", kind = OnlineException.Kind.NOT_FOUND)
        return when (kind) {
            CollectionKind.ARTIST -> {
                val channelId = when (link) {
                    is YouTubeLink.Channel -> link.channelId
                    is YouTubeLink.ChannelPath -> io("artist") { YoutubeChannelHelper.resolveChannelId(link.path) }
                    else -> throw OnlineException("Not an artist link: $url", kind = OnlineException.Kind.NOT_FOUND)
                }
                artistCollection(channelId, max)
            }
            CollectionKind.ALBUM, CollectionKind.PLAYLIST -> {
                val (playlistId, preloaded) = when (link) {
                    is YouTubeLink.Playlist -> link.playlistId to null
                    is YouTubeLink.AlbumBrowse -> io("album") {
                        val json = music.browse(link.browseId)
                        val id = YouTubeMusicParser.albumPlaylistId(json)
                            ?: throw OnlineException("Album without playlist: ${link.browseId}", kind = OnlineException.Kind.NOT_FOUND)
                        id to YouTubeMusicParser.header(json, id)
                    }
                    else -> throw OnlineException("Not a playlist link: $url", kind = OnlineException.Kind.NOT_FOUND)
                }
                val effectiveKind = if (playlistId.startsWith("OLAK5uy_")) CollectionKind.ALBUM else kind
                playlistCollection(playlistId, effectiveKind, max, preloaded)
            }
        }
    }

    private suspend fun playlistCollection(
        playlistId: String,
        kind: CollectionKind,
        limit: Int,
        preloaded: YouTubeMusicParser.Header?,
    ): CollectionDetail = withContext(Dispatchers.IO) {
        NewPipeHttp.init(http)
        coroutineScope {
            val headerJob = async {
                preloaded ?: runCatching { musicHeader(playlistId) }.getOrNull()
            }
            val listing = onlineCall("playlist") { loadPlaylist(playlistId, limit) }
            val header = headerJob.await()
            buildPlaylistDetail(playlistId, kind, listing, header)
        }
    }

    private class Listing(
        val name: String,
        val uploader: String?,
        val thumbnails: List<Image>,
        val streamCount: Long,
        val description: String?,
        val items: List<StreamInfoItem>,
    )

    private fun playlistExtractor(playlistId: String): YoutubePlaylistExtractor =
        // Built directly: NewPipe routes every RD… id (also curated RDCLAK… playlists) to the endless mix extractor.
        YoutubePlaylistExtractor(service, YoutubePlaylistLinkHandlerFactory.getInstance().fromId(playlistId))

    private fun loadPlaylist(playlistId: String, limit: Int): Listing {
        val extractor = playlistExtractor(playlistId)
        extractor.fetchPage()
        val items = ArrayList<StreamInfoItem>()
        var page = extractor.initialPage
        items += page.items
        var guard = 0
        while (page.hasNextPage() && items.size < limit && guard++ < 100) {
            page = extractor.getPage(page.nextPage)
            if (page.items.isEmpty() && !page.hasNextPage()) break
            items += page.items
        }
        return Listing(
            name = extractor.name,
            uploader = runCatching { extractor.uploaderName }.getOrNull(),
            thumbnails = runCatching { extractor.thumbnails }.getOrNull().orEmpty(),
            streamCount = runCatching { extractor.streamCount }.getOrNull() ?: -1,
            description = runCatching { extractor.description?.content }.getOrNull(),
            items = items.take(limit),
        )
    }

    private fun buildPlaylistDetail(
        playlistId: String,
        kind: CollectionKind,
        listing: Listing,
        header: YouTubeMusicParser.Header?,
    ): CollectionDetail {
        val isAlbum = kind == CollectionKind.ALBUM
        val rawTracks = listing.items.mapNotNull { streamItemToTrack(it, catalog = isAlbum) }.distinctBy { it.videoId }
        val title = header?.title?.let { if (isAlbum) stripAlbumPrefix(it) else it }
            ?: if (isAlbum) stripAlbumPrefix(listing.name) else listing.name
        val author = header?.author?.ifBlank { null }
            ?: listing.uploader?.let(TitleCleaner::cleanArtist)?.ifBlank { null }
            ?: if (isAlbum) mostCommonArtist(rawTracks) else null
        val cover = header?.thumbnailUrl ?: best(listing.thumbnails, Thumbnails.COLLECTION)
        val tracks = if (isAlbum) {
            rawTracks.map { it.copy(album = title, artist = it.artist.ifBlank { author.orEmpty() }, thumbnailUrl = cover ?: it.thumbnailUrl) }
        } else rawTracks
        val collection = RemoteCollection(
            kind = kind,
            url = YouTubeLinks.playlistUrl(playlistId),
            title = title,
            author = author,
            thumbnailUrl = cover,
            itemCount = listing.streamCount.takeIf { it >= 0 } ?: header?.trackCount ?: tracks.size.toLong(),
            year = if (isAlbum) header?.year else null,
        )
        return CollectionDetail(
            collection = collection,
            tracks = tracks,
            description = header?.description ?: listing.description?.let(AutoGeneratedDescription::htmlToText)?.trim()?.ifEmpty { null },
        )
    }

    private suspend fun artistCollection(channelId: String, limit: Int): CollectionDetail = withContext(Dispatchers.IO) {
        NewPipeHttp.init(http)
        val page = try {
            YouTubeMusicParser.artistPage(music.browse(channelId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnlineException) {
            if (e.kind == OnlineException.Kind.NO_NETWORK || e.kind == OnlineException.Kind.RATE_LIMITED) throw e
            null
        } catch (_: Exception) {
            null
        }
        if (page == null || !page.looksLikeArtist) return@withContext channelCollection(channelId, limit)

        coroutineScope {
            val songsJob = async {
                val id = page.songsPlaylistId ?: return@async null
                runCatching { onlineCall("artist songs") { loadPlaylist(id, limit) } }.getOrNull()
            }
            val releasesJobs = page.releaseShelves.map { (items, more) ->
                async {
                    if (more == null) items
                    else runCatching { YouTubeMusicParser.releasesGrid(music.browse(more.browseId, more.params), page.name) }
                        .getOrNull()?.takeIf { it.isNotEmpty() } ?: items
                }
            }
            val listing = songsJob.await()
            val tracks = listing?.items?.mapNotNull { streamItemToTrack(it, catalog = true) }?.distinctBy { it.videoId }
                ?.takeIf { it.isNotEmpty() }
                ?: page.songs
            val related = (releasesJobs.flatMap { it.await() } + page.playlists).distinctBy { it.url }
            CollectionDetail(
                collection = RemoteCollection(
                    kind = CollectionKind.ARTIST,
                    url = YouTubeLinks.channelUrl(page.channelId ?: channelId),
                    title = page.name,
                    author = null,
                    thumbnailUrl = page.thumbnailUrl,
                    itemCount = tracks.take(limit).size.toLong(),
                ),
                tracks = tracks.take(limit),
                description = page.description,
                related = related,
            )
        }
    }

    /** Channels that aren't YouTube Music artists: their uploads and playlists, via NewPipe. */
    private fun channelCollection(channelId: String, limit: Int): CollectionDetail = onlineCall("channel") {
        val info = ChannelInfo.getInfo(service, channelPageUrl(channelId))
        val name = TitleCleaner.cleanArtist(info.name)
        val tracks = ArrayList<RemoteTrack>()
        val related = ArrayList<RemoteCollection>()
        for (tab in info.tabs) {
            val filters = tab.contentFilters
            when {
                ChannelTabs.VIDEOS in filters || ChannelTabs.TRACKS in filters -> {
                    val tabInfo = runCatching { ChannelTabInfo.getInfo(service, tab) }.getOrNull() ?: continue
                    tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNullTo(tracks) { streamItemToTrack(it, catalog = false) }
                    var next = tabInfo.nextPage
                    var guard = 0
                    while (next != null && tracks.size < limit && guard++ < 20) {
                        val more = runCatching { ChannelTabInfo.getMoreItems(service, tab, next) }.getOrNull() ?: break
                        more.items.filterIsInstance<StreamInfoItem>().mapNotNullTo(tracks) { streamItemToTrack(it, catalog = false) }
                        next = more.nextPage
                    }
                }
                ChannelTabs.ALBUMS in filters || ChannelTabs.PLAYLISTS in filters -> {
                    val tabInfo = runCatching { ChannelTabInfo.getInfo(service, tab) }.getOrNull() ?: continue
                    tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>()
                        .mapNotNullTo(related) { playlistItemToCollection(it, albumHint = ChannelTabs.ALBUMS in filters) }
                }
            }
        }
        val unique = tracks.distinctBy { it.videoId }.take(limit)
        CollectionDetail(
            collection = RemoteCollection(
                kind = CollectionKind.ARTIST,
                url = YouTubeLinks.channelUrl(channelId),
                title = name,
                thumbnailUrl = best(info.avatars, Thumbnails.COLLECTION),
                itemCount = unique.size.toLong(),
            ),
            tracks = unique,
            description = info.description?.let(AutoGeneratedDescription::htmlToText)?.trim()?.ifEmpty { null },
            related = related.distinctBy { it.url },
        )
    }

    // ------------------------------------------------------------------------------- streams

    override suspend fun audioStream(videoId: String, quality: AudioQuality): AudioStream = io("audio") {
        val info = StreamInfo.getInfo(service, YouTubeLinks.watchUrl(videoId, music = false))
        AudioStreams.pick(info.audioStreams, quality)
            ?: throw OnlineException("No downloadable audio for $videoId", kind = OnlineException.Kind.UNAVAILABLE)
    }

    // ------------------------------------------------------------------------------- up next

    /**
     * YouTube Music's radio after the song (the "A seguir" panel of music.youtube.com); YouTube's
     * related videos when that answers nothing. The seed itself is left out. Never throws.
     */
    override suspend fun related(videoId: String, limit: Int): List<RemoteTrack> {
        if (limit <= 0 || videoId.isBlank()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                NewPipeHttp.init(http)
                val fromMusic = runCatching { onlineCall("next") { YouTubeMusicParser.watchNext(music.next(videoId)) } }.getOrNull().orEmpty()
                    .filter { it.videoId != videoId }
                val list = fromMusic.ifEmpty { runCatching { relatedVideos(videoId) }.getOrNull().orEmpty() }
                list.filter { it.videoId != videoId }.distinctBy { it.videoId }.take(limit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun relatedVideos(videoId: String): List<RemoteTrack> = onlineCall("related") {
        val info = StreamInfo.getInfo(service, YouTubeLinks.watchUrl(videoId, music = false))
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { streamItemToTrack(it, catalog = false, requireDuration = true) }
    }

    // ------------------------------------------------------------------------------- video

    /**
     * "Ver o vídeo": best H.264 video ≤ [maxHeight] + the M4A audio (or a muxed stream). For catalog
     * audio tracks ("Artist - Topic", a still cover) the official music video is looked up first.
     */
    override suspend fun videoStream(videoId: String, maxHeight: Int): VideoStream = io("video") {
        val info = StreamInfo.getInfo(service, YouTubeLinks.watchUrl(videoId, music = false))
        if (TitleCleaner.isTopicChannel(info.uploaderName.orEmpty())) {
            val official = runCatching { officialVideo(info) }.getOrNull()
            official?.let { o -> pickVideo(o, maxHeight)?.copy(substituteVideoId = YouTubeLinks.videoIdOf(o.url) ?: o.id) }?.let { return@io it }
        }
        pickVideo(info, maxHeight)
            ?: throw OnlineException("No playable video for $videoId", kind = OnlineException.Kind.UNAVAILABLE)
    }

    private fun pickVideo(info: StreamInfo, maxHeight: Int): VideoStream? {
        fun usable(s: NpVideoStream) = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl && !s.content.isNullOrBlank()
        fun describe(s: NpVideoStream) = VideoStreams.Video(
            url = s.content,
            width = s.width,
            height = s.height.takeIf { it > 0 } ?: VideoStreams.heightOf(s.getResolution()),
            fps = s.fps,
            codec = s.codec?.ifBlank { null },
            mimeType = s.format?.mimeType.orEmpty(),
            bitrate = s.bitrate,
        )
        val videoOnly = info.videoOnlyStreams.filter(::usable).map(::describe)
        val muxed = info.videoStreams.filter { usable(it) && !it.isVideoOnly }.map(::describe)
        val audio = AudioStreams.pick(info.audioStreams, AudioQuality.COMPATIBLE)
        return VideoStreams.pick(videoOnly, muxed, audio, maxHeight)
    }

    /** Searches the videos for "artist title" and reads the best match (see [MusicVideoMatch]). */
    private fun officialVideo(art: StreamInfo): StreamInfo? {
        val artist = TitleCleaner.cleanArtist(art.uploaderName.orEmpty())
        val title = art.name.orEmpty()
        if (artist.isBlank() || title.isBlank()) return null
        val handler = service.searchQHFactory.fromQuery("$artist ${TitleCleaner.baseTitle(title)}", listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val extractor = service.getSearchExtractor(handler)
        extractor.fetchPage()
        val candidates = extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
            .filter { it.streamType != StreamType.LIVE_STREAM && it.streamType != StreamType.AUDIO_LIVE_STREAM }
            .take(MAX_VIDEO_CANDIDATES)
            .mapNotNull { item ->
                val id = YouTubeLinks.videoIdOf(item.url) ?: return@mapNotNull null
                MusicVideoMatch.Candidate(id, item.name.orEmpty(), item.uploaderName.orEmpty(), item.duration.coerceAtLeast(0) * 1000)
            }
        val durationMs = art.duration.takeIf { it > 0 }?.times(1000)
        val best = MusicVideoMatch.best(title, artist, durationMs, candidates, exclude = art.id) ?: return null
        return StreamInfo.getInfo(service, YouTubeLinks.watchUrl(best.videoId, music = false))
    }

    override suspend fun track(videoId: String): RemoteTrack = io("track") {
        val info = StreamInfo.getInfo(service, YouTubeLinks.watchUrl(videoId, music = false))
        val uploader = info.uploaderName.orEmpty()
        // "Artist - Topic" channels publish YouTube Music's own audio tracks (clean titles, album in the description).
        val catalog = TitleCleaner.isTopicChannel(uploader)
        RemoteTrack(
            videoId = videoId,
            url = YouTubeLinks.watchUrl(videoId, music = catalog),
            title = info.name,
            artist = TitleCleaner.cleanArtist(uploader),
            album = if (catalog) AutoGeneratedDescription.album(info.description?.content) else null,
            durationMs = info.duration.takeIf { it > 0 }?.times(1000),
            thumbnailUrl = best(info.thumbnails, Thumbnails.SONG),
            fromMusicCatalog = catalog,
        )
    }

    // ------------------------------------------------------------------------------- mapping

    private fun streamItemToTrack(item: StreamInfoItem, catalog: Boolean, requireDuration: Boolean = false): RemoteTrack? {
        val videoId = YouTubeLinks.videoIdOf(item.url) ?: return null
        val type = item.streamType
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) return null
        when (runCatching { item.contentAvailability }.getOrNull()) {
            ContentAvailability.MEMBERSHIP, ContentAvailability.PAID, ContentAvailability.UPCOMING -> return null
            else -> Unit
        }
        val name = item.name?.trim().orEmpty()
        if (name.isEmpty() || name == "[Private video]" || name == "[Deleted video]") return null
        // Shorts, premieres and other duration-less video results aren't songs.
        if (requireDuration && item.duration <= 0) return null
        return RemoteTrack(
            videoId = videoId,
            url = YouTubeLinks.watchUrl(videoId, music = catalog),
            title = name,
            artist = TitleCleaner.cleanArtist(item.uploaderName.orEmpty()),
            album = null,
            durationMs = item.duration.takeIf { it > 0 }?.times(1000),
            thumbnailUrl = best(item.thumbnails, Thumbnails.SONG),
            fromMusicCatalog = catalog,
        )
    }

    private fun playlistItemToCollection(item: PlaylistInfoItem, albumHint: Boolean): RemoteCollection? {
        val link = YouTubeLinks.parse(item.url) as? YouTubeLink.Playlist ?: return null
        val kind = if (albumHint || link.kind == CollectionKind.ALBUM) CollectionKind.ALBUM else CollectionKind.PLAYLIST
        val title = item.name?.trim()?.ifEmpty { null } ?: return null
        return RemoteCollection(
            kind = kind,
            url = YouTubeLinks.playlistUrl(link.playlistId),
            title = if (kind == CollectionKind.ALBUM) stripAlbumPrefix(title) else title,
            author = item.uploaderName?.let(TitleCleaner::cleanArtist)?.ifBlank { null },
            thumbnailUrl = best(item.thumbnails, Thumbnails.COLLECTION),
            itemCount = item.streamCount.takeIf { it >= 0 },
            year = item.description?.content?.let(::yearIn),
        )
    }

    private fun channelItemToCollection(item: ChannelInfoItem): RemoteCollection? {
        val link = YouTubeLinks.parse(item.url) as? YouTubeLink.Channel ?: return null
        val name = item.name?.trim()?.ifEmpty { null } ?: return null
        return RemoteCollection(
            kind = CollectionKind.ARTIST,
            url = YouTubeLinks.channelUrl(link.channelId),
            title = TitleCleaner.cleanArtist(name),
            thumbnailUrl = best(item.thumbnails, Thumbnails.COLLECTION),
        )
    }

    private fun YouTubeMusicParser.Header.toCollection(playlistId: String, kind: CollectionKind) = RemoteCollection(
        kind = kind,
        url = YouTubeLinks.playlistUrl(playlistId),
        title = if (kind == CollectionKind.ALBUM) stripAlbumPrefix(title) else title,
        author = author,
        thumbnailUrl = thumbnailUrl,
        itemCount = trackCount,
        year = if (kind == CollectionKind.ALBUM) year else null,
    )

    private fun best(images: List<Image>, size: Int): String? =
        Thumbnails.best(images.map { Thumbnails.Thumb(it.url, it.width, it.height) }, size)

    private fun channelPageUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"

    internal companion object {
        const val MAX_SUGGESTIONS = 8
        /** Search results looked at when hunting for the official music video. */
        const val MAX_VIDEO_CANDIDATES = 12
        private val albumPrefix = Regex("^(album|álbum|ep|single)\\s*[-–—:]\\s+", RegexOption.IGNORE_CASE)
        private val year = Regex("\\b(19[0-9]{2}|20[0-9]{2})\\b")

        /** "Album – Sueño Stereo" (YouTube's name for album playlists) → "Sueño Stereo". */
        fun stripAlbumPrefix(name: String): String = name.trim().replace(albumPrefix, "").trim().ifEmpty { name.trim() }

        fun yearIn(text: String): Int? = year.find(text)?.value?.toIntOrNull()

        fun mostCommonArtist(tracks: List<RemoteTrack>): String? =
            tracks.map { it.artist }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

/** Chooses the audio-only stream for each [AudioQuality]. */
internal object AudioStreams {
    fun pick(streams: List<NpAudioStream>, quality: AudioQuality): AudioStream? {
        val usable = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !it.content.isNullOrBlank() }
        // Auto-dubbed / described tracks exist on some videos: keep the original audio.
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { usable }
        if (original.isEmpty()) return null
        val chosen = when (quality) {
            AudioQuality.BEST -> original.maxWithOrNull(compareBy<NpAudioStream> { kbps(it) }.thenBy { if (isOpus(it)) 1 else 0 })
            AudioQuality.COMPATIBLE -> original.filter { it.format == MediaFormat.M4A }.maxByOrNull { kbps(it) }
                ?: original.maxByOrNull { kbps(it) }
            AudioQuality.SAVER -> original.filter { kbps(it) >= 48 }
                .minWithOrNull(compareBy<NpAudioStream> { kbps(it) }.thenBy { if (isOpus(it)) 0 else 1 })
                ?: original.minByOrNull { kbps(it) }
        } ?: return null
        val m4a = chosen.format == MediaFormat.M4A || chosen.format?.mimeType == "audio/mp4"
        return AudioStream(
            url = chosen.content,
            mimeType = if (m4a) "audio/mp4" else chosen.format?.mimeType ?: "audio/webm",
            extension = if (m4a) "m4a" else "webm",
            bitrate = chosen.bitrate.takeIf { it > 0 } ?: (kbps(chosen) * 1000),
            contentLength = chosen.itagItem?.contentLength?.takeIf { it > 0 },
            codec = chosen.codec?.ifBlank { null },
        )
    }

    private fun kbps(s: NpAudioStream): Int = s.averageBitrate.takeIf { it > 0 } ?: (s.bitrate / 1000)
    private fun isOpus(s: NpAudioStream): Boolean = s.format == MediaFormat.WEBMA_OPUS || s.codec?.contains("opus") == true
}

/** Reads YouTube's "Provided to YouTube by …" descriptions of catalog audio tracks. */
internal object AutoGeneratedDescription {
    /**
     * ```
     * Provided to YouTube by Sony Music
     *
     * De Música Ligera (Remasterizado 2007) · Soda Stereo
     *
     * Canción Animal
     * ```
     */
    fun album(description: String?): String? {
        if (description.isNullOrBlank() || !description.contains("Provided to YouTube", ignoreCase = true)) return null
        val blocks = htmlToText(description).split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val titleLine = blocks.indexOfFirst { it.contains(" · ") }
        if (titleLine < 0) return null
        val album = blocks.getOrNull(titleLine + 1)?.lines()?.firstOrNull()?.trim() ?: return null
        if (album.startsWith("℗") || album.startsWith("©") || album.startsWith("Released on", ignoreCase = true)) return null
        return album.ifEmpty { null }
    }

    /** NewPipe hands YouTube descriptions over as HTML ("…<br><br>…"). */
    fun htmlToText(s: String): String {
        if (!s.contains('<') && !s.contains('&')) return s.replace("\r", "")
        return s.replace("\r", "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }
}
