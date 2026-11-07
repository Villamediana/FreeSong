package com.villamediana.sound.ui.search

import android.app.Application
import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.R
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchFilter
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.SmartSeed
import com.villamediana.sound.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SearchViewModelTest {
    private lateinit var db: SoundDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private val youTube = FakeYouTube()
    private val downloads = FakeDownloads()
    private val player = FakePlayer()
    private val online = MutableStateFlow(true)
    private val playOnline = MutableStateFlow(true)
    private lateinit var vm: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = SoundDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        vm = SearchViewModel(youTube, library, downloads, player, RecentSearchStore(context), online, initiallyOnline = true, playOnlineFlow = playOnline)
        // The screen keeps these subscribed.
        scope.launch { vm.lookup.collect { } }
        scope.launch { vm.nowPlaying.collect { } }
        scope.launch { vm.local.collect { } }
        scope.launch { vm.suggestions.collect { } }
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun type(text: String) = vm.onFieldChange(TextFieldValue(text))

    private fun ids() = vm.results.value?.items.orEmpty().map { (it as SearchItem.Song).track.videoId }

    private fun insertTrack(videoId: String, title: String = "T $videoId", artist: String = "Soda Stereo", album: String? = null) = runBlocking {
        library.insertTrack(Track(videoId = videoId, title = title, artist = artist, album = album, filePath = "/music/$videoId.m4a"))
    }

    private fun nextEvent(): SearchEvent = runBlocking { withTimeout(5_000) { vm.events.first() } }

    @Test
    fun submitLoadsTheFirstPageAndInfiniteScrollAppends() {
        youTube.onSearch = { q, f -> songPage(q, f, listOf("a", "b", "c"), cursor = 1) }
        youTube.onMore = { p ->
            if (p.cursor == 1) songPage(p.query, p.filter, listOf("c", "d"), cursor = 2) else songPage(p.query, p.filter, listOf("e"))
        }
        type("soda stereo")
        assertEquals(SearchMode.Typing, vm.mode)
        vm.submit()
        assertEquals(SearchMode.Results, vm.mode)
        awaitUntil(message = "first page") { ids() == listOf("a", "b", "c") }
        assertEquals(listOf("soda stereo" to SearchFilter.SONGS), youTube.searches.toList())

        vm.loadMore()
        awaitUntil(message = "second page") { ids() == listOf("a", "b", "c", "d") } // "c" came twice
        vm.loadMore()
        awaitUntil(message = "last page") { ids() == listOf("a", "b", "c", "d", "e") }
        assertFalse(vm.results.value!!.hasMore)
        vm.loadMore()
        Thread.sleep(50)
        assertEquals(5, ids().size)
    }

    @Test
    fun eachFilterIsSearchedOnceAndCached() {
        youTube.onSearch = { q, f -> songPage(q, f, listOf(f.name.lowercase())) }
        type("cerati")
        vm.submit()
        awaitUntil { ids() == listOf("songs") }
        vm.selectFilter(SearchFilter.ALBUMS)
        awaitUntil { ids() == listOf("albums") }
        vm.selectFilter(SearchFilter.SONGS)
        awaitUntil { ids() == listOf("songs") }
        assertEquals(listOf(SearchFilter.SONGS, SearchFilter.ALBUMS), youTube.searches.map { it.second })
        // A new submit starts fresh.
        vm.submit()
        awaitUntil { youTube.searches.size == 3 }
    }

    @Test
    fun offlineSearchWaitsForTheNetworkAndRetriesByItself() {
        youTube.onSearch = { q, f -> songPage(q, f, listOf("x")) }
        online.value = false
        awaitUntil { !vm.online.value }
        type("sui generis")
        vm.submit()
        assertEquals(SearchError.Offline, vm.results.value?.error)
        assertTrue(youTube.searches.isEmpty())
        online.value = true
        awaitUntil(message = "auto retry") { ids() == listOf("x") }
    }

    @Test
    fun errorsAreClassifiedAndRetryRecovers() {
        youTube.onSearch = { _, _ -> throw OnlineException("slow down", kind = OnlineException.Kind.RATE_LIMITED) }
        type("charly")
        vm.submit()
        awaitUntil { vm.results.value?.error == SearchError.RateLimited }

        youTube.onSearch = { _, _ -> throw IllegalStateException("boom") }
        vm.retry()
        awaitUntil { vm.results.value?.error == SearchError.Generic }

        youTube.onSearch = { _, _ -> throw OnlineException("no net", kind = OnlineException.Kind.NO_NETWORK) }
        vm.retry()
        awaitUntil { vm.results.value?.error == SearchError.Offline }

        youTube.onSearch = { q, f -> songPage(q, f, listOf("ok")) }
        vm.retry()
        awaitUntil { ids() == listOf("ok") }
    }

    @Test
    fun failedNextPageCanBeRetried() {
        var failMore = true
        youTube.onSearch = { q, f -> songPage(q, f, listOf("a"), cursor = 1) }
        youTube.onMore = { p -> if (failMore) throw OnlineException("x") else songPage(p.query, p.filter, listOf("b")) }
        type("q")
        vm.submit()
        awaitUntil { ids() == listOf("a") }
        vm.loadMore()
        awaitUntil { vm.results.value?.moreError == true }
        vm.loadMore() // blocked until the user retries
        failMore = false
        vm.retry()
        awaitUntil { ids() == listOf("a", "b") }
        assertFalse(vm.results.value!!.moreError)
    }

    @Test
    fun editingGoesBackToTypingAndClearingToIdle() {
        youTube.onSearch = { q, f -> songPage(q, f, listOf("a")) }
        type("soda")
        vm.submit()
        assertEquals(SearchMode.Results, vm.mode)
        type("soda s")
        assertEquals(SearchMode.Typing, vm.mode)
        vm.clearField()
        assertEquals(SearchMode.Idle, vm.mode)
        assertNull(vm.submitted)
        assertNull(vm.results.value)
    }

    @Test
    fun submittingALinkResolvesItInsteadOfSearching() {
        val song = remoteTrack("dQw4w9WgXcQ", title = "Persiana Americana")
        youTube.onResolve = { ResolvedLink.Track(song) }
        type("olha https://youtu.be/dQw4w9WgXcQ?si=abc")
        vm.submit()
        awaitUntil { vm.link == LinkCard.Found(song) }
        assertEquals(listOf("https://youtu.be/dQw4w9WgXcQ?si=abc"), youTube.resolved.toList())
        assertTrue(youTube.searches.isEmpty())
        assertEquals("", vm.query.text)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", vm.handledClip)
        vm.dismissLink()
        assertNull(vm.link)
    }

    @Test
    fun sharedPlaylistOpensTheRemotePage() {
        val playlist = RemoteCollection(CollectionKind.PLAYLIST, "https://music.youtube.com/playlist?list=PL1", "Rock nacional")
        youTube.onResolve = { ResolvedLink.Collection(playlist) }
        vm.resolveLink("https://music.youtube.com/playlist?list=PL1")
        assertEquals(SearchEvent.OpenRemote(playlist), nextEvent())
        assertNull(vm.link)
    }

    @Test
    fun unsupportedFailedAndOfflineLinksExplainThemselves() {
        youTube.onResolve = { ResolvedLink.Unsupported }
        vm.resolveLink("https://www.youtube.com/@canal")
        assertEquals(SearchEvent.Message(R.string.search_link_unsupported), nextEvent())

        youTube.onResolve = { throw OnlineException("gone", kind = OnlineException.Kind.NOT_FOUND) }
        vm.resolveLink("https://youtu.be/dQw4w9WgXcQ")
        assertEquals(SearchEvent.Message(R.string.search_link_not_found), nextEvent())

        online.value = false
        awaitUntil { !vm.online.value }
        val before = youTube.resolved.size
        vm.resolveLink("https://youtu.be/dQw4w9WgXcQ")
        assertEquals(SearchEvent.Message(R.string.search_link_offline), nextEvent())
        assertEquals(before, youTube.resolved.size)
    }

    @Test
    fun downloadSkipsWhatIsAlreadyThereAndShowsQueuedRightAway() {
        insertTrack("lib")
        downloads.taskFlow.value = listOf(DownloadTask(id = 100, videoId = "q", url = "u", title = "q", artist = "a"))
        awaitUntil(message = "library + queue") { vm.lookup.value.isDownloaded("lib") && vm.lookup.value.isKnown("q") }

        vm.download(listOf(remoteTrack("lib"), remoteTrack("q"), remoteTrack("new"), remoteTrack("new")))
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(listOf("new"), downloads.downloadCalls.single().map { it.videoId })
        awaitUntil { vm.lookup.value.stateOf("new") == ItemDownload.Queued }

        // Fails → a tap retries that task.
        downloads.setState("new", DownloadState.FAILED)
        awaitUntil { vm.lookup.value.stateOf("new") is ItemDownload.Failed }
        val failed = vm.lookup.value.stateOf("new") as ItemDownload.Failed
        vm.onIndicatorTap(remoteTrack("new"), listOf(remoteTrack("new")), "q")
        awaitUntil { downloads.retried.toList() == listOf(failed.taskId) }
        awaitUntil { vm.lookup.value.stateOf("new") == ItemDownload.Queued }

        // Running shows progress.
        downloads.setState("new", DownloadState.RUNNING, 0.3f)
        awaitUntil { vm.lookup.value.stateOf("new") == ItemDownload.Running(0.3f) }
    }

    @Test
    fun tappingADownloadedSongPlaysTheDownloadedResultsInOrderWhenOnlinePlayIsOff() {
        playOnline.value = false
        insertTrack("a")
        insertTrack("c")
        awaitUntil { vm.lookup.value.isDownloaded("a") && vm.lookup.value.isDownloaded("c") }
        vm.onSongTap(remoteTrack("c"), listOf(remoteTrack("a"), remoteTrack("b"), remoteTrack("c")), "soda")
        // The copy on the phone and what sounds like it in the library — not the other results.
        awaitUntil { player.smartPlays.size == 1 }
        val seed = player.smartPlays.single() as SmartSeed.Radio
        assertEquals("c", seed.track.videoId)
        assertTrue(player.plays.isEmpty())
        assertTrue(player.onlinePlays.isEmpty())

        // Not downloaded → the tap downloads it, as before online play existed.
        vm.onSongTap(remoteTrack("b"), listOf(remoteTrack("a"), remoteTrack("b"), remoteTrack("c")), "soda")
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(listOf("b"), downloads.downloadCalls.single().map { it.videoId })
        assertTrue(player.onlinePlays.isEmpty())
    }

    @Test
    fun tappingASongStreamsItsRadioNotTheOtherResults() {
        insertTrack("b") // downloaded songs stream too: the player picks the local copy itself
        awaitUntil { vm.lookup.value.isDownloaded("b") }
        val songs = listOf(remoteTrack("a"), remoteTrack("b"), remoteTrack("c"), remoteTrack("b"))
        vm.onSongTap(songs[1], songs, "soda")
        // Results share a name, not a sound: the song alone, as a radio the player keeps going.
        val play = player.onlinePlays.single()
        assertEquals(listOf("b"), play.tracks.map { it.videoId })
        assertEquals(0, play.index)
        assertEquals(PlaySource.Type.RADIO, play.source.type)
        assertEquals("b", play.source.id)
        assertTrue(player.plays.isEmpty())
        assertTrue(downloads.downloadCalls.isEmpty())

        // The trailing button still keeps a song on the phone.
        vm.onIndicatorTap(songs[2], songs, "soda")
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(listOf("c"), downloads.downloadCalls.single().map { it.videoId })
        assertEquals(1, player.onlinePlays.size)
    }

    @Test
    fun withoutNetworkOnlyDownloadedSongsPlayAndTheRestExplain() {
        insertTrack("a")
        awaitUntil { vm.lookup.value.isDownloaded("a") }
        online.value = false
        awaitUntil { !vm.online.value }
        val songs = listOf(remoteTrack("a"), remoteTrack("b"))
        vm.onSongTap(songs[0], songs, "soda")
        awaitUntil { player.smartPlays.size == 1 }
        assertEquals("a", (player.smartPlays.single() as SmartSeed.Radio).track.videoId)

        vm.onSongTap(songs[1], songs, "soda")
        assertEquals(SearchEvent.Message(R.string.search_play_offline), nextEvent())
        assertTrue(player.onlinePlays.isEmpty())
        assertTrue(downloads.downloadCalls.isEmpty())
    }

    @Test
    fun listenNowStreamsTheSharedSong() {
        val song = remoteTrack("dQw4w9WgXcQ", "Té para Tres")
        vm.listenNow(song)
        val play = player.onlinePlays.single()
        assertEquals(listOf(song), play.tracks)
        assertEquals(0, play.index)
        assertEquals(PlaySource.Type.RADIO, play.source.type)
    }

    @Test
    fun theSongPlayingNowLightsUpItsRow() {
        assertNull(vm.nowPlaying.value)
        player.stateFlow.value = PlaybackState(current = Track(id = -5, videoId = "a", title = "A", artist = "B", filePath = "https://music.youtube.com/watch?v=a"), isPlaying = true)
        awaitUntil { vm.nowPlaying.value == NowPlaying("a", true) }
        assertEquals(true, vm.nowPlaying.value.of("a"))
        assertNull(vm.nowPlaying.value.of("b"))
        player.stateFlow.value = player.stateFlow.value.copy(isPlaying = false)
        awaitUntil { vm.nowPlaying.value == NowPlaying("a", false) }
    }

    @Test
    fun multiSelectDownloadsTheChosenSongs() {
        vm.toggleSelection(remoteTrack("a"))
        vm.toggleSelection(remoteTrack("b"))
        vm.toggleSelection(remoteTrack("a"))
        vm.toggleSelection(remoteTrack("c"))
        assertEquals(listOf("b", "c"), vm.selection.keys.toList())
        vm.downloadSelection()
        assertTrue(vm.selection.isEmpty())
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(listOf("b", "c"), downloads.downloadCalls.single().map { it.videoId })
    }

    @Test
    fun suggestionsAreCleanedAndSkippedForLinks() {
        youTube.onSuggestions = { listOf("soda stereo", "Soda Stereo", "soda", " cerati ", "") }
        type("soda")
        awaitUntil(message = "suggestions") { vm.suggestions.value == listOf("soda stereo", "cerati") }
        type("https://youtu.be/dQw4w9WgXcQ")
        awaitUntil(message = "no suggestions for links") { vm.suggestions.value.isEmpty() }
    }

    @Test
    fun localMatchesIgnoreAccents() {
        insertTrack("x", title = "De Música Ligera")
        type("musica lig")
        awaitUntil(message = "local") { vm.local.value?.tracks?.map { it.videoId } == listOf("x") }
        vm.playLocal(vm.local.value!!.tracks, 0, "musica lig")
        assertEquals("x", player.plays.single().tracks.single().videoId)
    }
}
