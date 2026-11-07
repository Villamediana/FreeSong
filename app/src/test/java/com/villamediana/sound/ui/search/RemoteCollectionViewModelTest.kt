package com.villamediana.sound.ui.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.R
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.asTransientTrack
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.isOnline
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.OnlineException
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.playback.PlaySource
import com.villamediana.sound.playback.PlaybackState
import com.villamediana.sound.ui.nav.RemoteCollectionRoute
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteCollectionViewModelTest {
    private lateinit var db: SoundDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private val youTube = FakeYouTube()
    private val downloads = FakeDownloads()
    private val player = FakePlayer()
    private val online = MutableStateFlow(true)
    private val vms = mutableListOf<RemoteCollectionViewModel>()

    private val playlistUrl = "https://music.youtube.com/playlist?list=PL1"
    private val tracks = listOf(remoteTrack("a"), remoteTrack("b"), remoteTrack("c"))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = SoundDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        youTube.onCollection = { url, kind -> CollectionDetail(RemoteCollection(kind, url, "Rock nacional", "Sound"), tracks) }
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Video ids whose download button asked to take the song off the phone. */
    private val removed = mutableListOf<String>()

    private fun viewModel(kind: CollectionKind, url: String = playlistUrl, playOnline: Boolean = true): RemoteCollectionViewModel {
        val vm = RemoteCollectionViewModel(
            RemoteCollectionRoute(url, kind.name, "Rock nacional", "Sound"),
            youTube, library, downloads, player, online, initiallyOnline = online.value,
            playOnlineFlow = MutableStateFlow(playOnline), random = Random(7),
            onRemoveDownload = { removed += it },
        )
        vms += vm
        scope.launch { vm.lookup.collect { } }
        scope.launch { vm.progress.collect { } }
        scope.launch { vm.here.collect { } }
        return vm
    }

    private fun RemoteCollectionViewModel.action() =
        PrimaryAction.of(kind == CollectionKind.PLAYLIST, progress.value, localPlaylistId != null, enqueuing)

    private fun insertTrack(videoId: String, album: String? = null, artist: String = "Soda Stereo") = runBlocking {
        library.insertTrack(Track(videoId = videoId, title = "T $videoId", artist = artist, album = album, filePath = "/music/$videoId.m4a"))
    }

    private fun RemoteCollectionViewModel.nextEvent(): RemoteEvent = runBlocking { withTimeout(5_000) { events.first() } }

    @Test
    fun playlistGoesFromDownloadAllToProgressToInLibrary() {
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil(message = "loaded") { vm.load.value is RemoteLoad.Loaded && vm.progress.value.total == 3 }
        assertEquals(PrimaryAction.Download(3, 3), vm.action())

        vm.downloadAll()
        awaitUntil(message = "enqueued") { downloads.collectionCalls.size == 1 && !vm.enqueuing }
        assertEquals(77L, vm.localPlaylistId)
        awaitUntil { vm.action() == PrimaryAction.Progress(0, 3, 0f) }

        downloads.setState("a", DownloadState.RUNNING, 0.5f)
        insertTrack("b")
        awaitUntil { vm.action() == PrimaryAction.Progress(1, 3, 1.5f / 3f) }

        insertTrack("a")
        insertTrack("c")
        downloads.setState("a", DownloadState.DONE)
        downloads.setState("c", DownloadState.DONE)
        awaitUntil(message = "in library") { vm.action() == PrimaryAction.InLibrary }

        vm.openInLibrary()
        assertEquals(RemoteEvent.Open(LocalDestination.Playlist(77)), vm.nextEvent())
    }

    @Test
    fun knowsAPlaylistImportedBefore() {
        val id = runBlocking { library.createPlaylist("Rock nacional", sourceUrl = playlistUrl) }
        insertTrack("a")
        insertTrack("b")
        insertTrack("c")
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil(message = "in library") { vm.load.value is RemoteLoad.Loaded && vm.action() == PrimaryAction.InLibrary }
        assertEquals(id, vm.localPlaylistId)
    }

    @Test
    fun songsOnThePhoneButNoPlaylistYetOffersToSaveIt() {
        insertTrack("a")
        insertTrack("b")
        insertTrack("c")
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { vm.load.value is RemoteLoad.Loaded && vm.action() == PrimaryAction.SavePlaylist }
    }

    @Test
    fun albumOpensTheLocalAlbumAndPlaysInPageOrder() {
        insertTrack("a", album = "Signos")
        insertTrack("c", album = "Signos")
        val vm = viewModel(CollectionKind.ALBUM, "https://music.youtube.com/browse/MPREb_signos", playOnline = false)
        awaitUntil { vm.load.value is RemoteLoad.Loaded && vm.progress.value.done == 2 }
        assertEquals(PrimaryAction.Download(1, 3), vm.action())

        vm.openInLibrary()
        assertEquals(RemoteEvent.Open(LocalDestination.Album(AlbumKey("Soda Stereo", "Signos"))), vm.nextEvent())

        // A tap on a downloaded song (online play off) plays the local copies, in page order.
        vm.onSongTap(tracks[2])
        awaitUntil { player.plays.size == 1 }
        val play = player.plays.single()
        assertEquals(listOf("a", "c"), play.tracks.map { it.videoId })
        assertEquals(1, play.index)
        assertEquals(PlaySource.Type.ALBUM, play.source.type)

        // Its download button, tapped again, takes it off the phone.
        vm.onIndicatorTap(tracks[2])
        assertEquals(listOf("c"), removed)
        assertEquals(RemoteEvent.Message(R.string.search_dl_removed), vm.nextEvent())

        vm.downloadAll()
        awaitUntil { downloads.collectionCalls.size == 1 }
        assertEquals(null, vm.localPlaylistId)
    }

    @Test
    fun artistPageDownloadsTheTopSongsAsLooseSongs() {
        val vm = viewModel(CollectionKind.ARTIST, "https://music.youtube.com/channel/UC1")
        awaitUntil { vm.load.value is RemoteLoad.Loaded }
        vm.downloadAll()
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(tracks, downloads.downloadCalls.single())
        assertTrue(downloads.collectionCalls.isEmpty())
    }

    @Test
    fun singleSongDownloadAndRetryOfFailures() {
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { vm.load.value is RemoteLoad.Loaded }
        vm.onIndicatorTap(tracks[0])
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(listOf("a"), downloads.downloadCalls.single().map { it.videoId })
        awaitUntil { vm.lookup.value.stateOf("a") == ItemDownload.Queued }

        downloads.setState("a", DownloadState.FAILED)
        awaitUntil { vm.progress.value.failed == 1 }
        vm.retryFailed()
        awaitUntil { downloads.retried.size == 1 }
        awaitUntil { vm.progress.value.queued == 1 }
    }

    @Test
    fun tappingASongStreamsThePageFromIt() {
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { vm.load.value is RemoteLoad.Loaded }
        vm.onSongTap(tracks[1])
        val play = player.onlinePlays.single()
        assertEquals(tracks, play.tracks)
        assertEquals(1, play.index)
        assertEquals(PlaySource(PlaySource.Type.PLAYLIST, "Rock nacional", playlistUrl), play.source)
        assertEquals(listOf(false), player.shuffles.toList())
        assertTrue(downloads.downloadCalls.isEmpty())
    }

    @Test
    fun withOnlinePlayOffATapDownloads() {
        val vm = viewModel(CollectionKind.PLAYLIST, playOnline = false)
        awaitUntil { vm.load.value is RemoteLoad.Loaded }
        vm.onSongTap(tracks[1])
        awaitUntil { downloads.downloadCalls.size == 1 }
        assertEquals(listOf("b"), downloads.downloadCalls.single().map { it.videoId })
        assertTrue(player.onlinePlays.isEmpty())
    }

    @Test
    fun playAndShuffleStreamTheWholePageAndPlayPausesWhenItIsPlaying() {
        val albumTracks = listOf(remoteTrack("a"), remoteTrack("b"), remoteTrack("a"), remoteTrack("c"))
        youTube.onCollection = { url, kind -> CollectionDetail(RemoteCollection(kind, url, "Signos", "Soda Stereo"), albumTracks) }
        val url = "https://music.youtube.com/browse/MPREb_signos"
        val vm = viewModel(CollectionKind.ALBUM, url)
        awaitUntil { vm.load.value is RemoteLoad.Loaded }

        vm.playAll(shuffle = false)
        val first = player.onlinePlays.single()
        assertEquals(listOf("a", "b", "c"), first.tracks.map { it.videoId })
        assertEquals(0, first.index)
        assertEquals(PlaySource(PlaySource.Type.ALBUM, "Signos", url), first.source)
        assertEquals(listOf(false), player.shuffles.toList())

        vm.playAll(shuffle = true)
        assertEquals(Random(7).nextInt(3), player.onlinePlays[1].index)
        assertEquals(listOf(false, true), player.shuffles.toList())

        // The player is on this page: Play pauses/resumes instead of starting over.
        player.stateFlow.value = PlaybackState(current = remoteTrack("b").asTransientTrack(), isPlaying = true, source = first.source)
        awaitUntil { vm.here.value == HerePlaying(playing = true, shuffle = false) }
        vm.playAll(shuffle = false)
        assertEquals(1, player.toggles)
        assertEquals(2, player.onlinePlays.size)
    }

    @Test
    fun playShowsPauseWhileTheSongLoadsAndTheFirstSongIsReadyAhead() {
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { vm.load.value is RemoteLoad.Loaded }
        // Opening the page already resolves the first song's stream: Play starts fast.
        assertEquals(listOf(tracks.first().videoId), player.prefetched.toList())

        vm.playAll(shuffle = false)
        val first = player.onlinePlays.single()
        // Still loading: the button is Pause at once (with its ring), so a second tap pauses instead of looking dead.
        player.stateFlow.value = PlaybackState(current = first.tracks[0].asTransientTrack(), isBuffering = true, source = first.source)
        awaitUntil { vm.here.value == HerePlaying(playing = true, shuffle = false, loading = true) }
        player.stateFlow.value = PlaybackState(current = first.tracks[0].asTransientTrack(), isPlaying = true, source = first.source)
        awaitUntil { vm.here.value == HerePlaying(playing = true, shuffle = false, loading = false) }
    }

    @Test
    fun withoutNetworkPlayUsesWhatIsOnThePhone() {
        insertTrack("b")
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { vm.load.value is RemoteLoad.Loaded && vm.progress.value.done == 1 }
        online.value = false
        awaitUntil { !vm.online.value }
        vm.playAll(shuffle = false)
        awaitUntil { player.plays.size == 1 }
        assertEquals(listOf("b"), player.plays.single().tracks.map { it.videoId })
        assertTrue(player.onlinePlays.isEmpty())

        vm.onSongTap(tracks[0])
        assertEquals(RemoteEvent.Message(R.string.search_play_offline), vm.nextEvent())
        assertEquals(0, vm.queueAll(next = true))
        assertEquals(RemoteEvent.Message(R.string.search_play_offline), vm.nextEvent())
    }

    @Test
    fun queueingThePageUsesLocalCopiesAndStreamsTheRest() {
        insertTrack("a")
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { vm.load.value is RemoteLoad.Loaded && vm.progress.value.done == 1 }
        assertEquals(3, vm.queueAll(next = true))
        awaitUntil { player.queued.size == 1 }
        val (where, songs) = player.queued.single()
        assertEquals("next", where)
        assertEquals(listOf("a", "b", "c"), songs.map { it.videoId })
        assertEquals(listOf(false, true, true), songs.map { it.isOnline })
    }

    @Test
    fun offlinePageReloadsWhenTheNetworkComesBack() {
        online.value = false
        val vm = viewModel(CollectionKind.PLAYLIST)
        assertEquals(RemoteLoad.Failed(RemoteError.Offline), vm.load.value)
        online.value = true
        awaitUntil(message = "reloaded") { vm.load.value is RemoteLoad.Loaded }
    }

    @Test
    fun errorsAreClassified() {
        youTube.onCollection = { _, _ -> throw OnlineException("private", kind = OnlineException.Kind.NOT_FOUND) }
        val notFound = viewModel(CollectionKind.PLAYLIST)
        awaitUntil { notFound.load.value == RemoteLoad.Failed(RemoteError.NotFound) }

        youTube.onCollection = { _, _ -> throw IllegalStateException("parser broke") }
        val generic = viewModel(CollectionKind.ALBUM)
        awaitUntil { generic.load.value == RemoteLoad.Failed(RemoteError.Generic) }

        youTube.onCollection = { url, kind -> CollectionDetail(RemoteCollection(kind, url, "Ok"), tracks) }
        generic.load()
        awaitUntil { generic.load.value is RemoteLoad.Loaded }
    }

    @Test
    fun aSongOfAnImportedPlaylistComesOutWithASwipeAndUndoPutsItBack() {
        val url = "https://open.spotify.com/playlist/abcdefghijklmnopqrstuv"
        runBlocking { library.saveImported(url, "Domingo", null, null, tracks) }
        val vm = RemoteCollectionViewModel(
            RemoteCollectionRoute(url, CollectionKind.PLAYLIST.name, "Domingo"),
            youTube, library, downloads, player, online, initiallyOnline = true,
            loader = { u, _ -> library.importedDetail(u)!! },
        )
        vms += vm
        awaitUntil(message = "loaded") { vm.load.value is RemoteLoad.Loaded }
        assertTrue(vm.canRemoveSongs)

        vm.removeSong(1, tracks[1])
        assertEquals(listOf("a", "c"), vm.detail!!.tracks.map { it.videoId })
        val removed = vm.nextEvent() as RemoteEvent.Removed
        awaitUntil(message = "saved") { runBlocking { library.importedDetail(url)!!.tracks.size } == 2 }

        removed.undo()
        assertEquals(listOf("a", "b", "c"), vm.detail!!.tracks.map { it.videoId })
        awaitUntil(message = "put back") { runBlocking { library.importedDetail(url)!!.tracks.map { it.videoId } } == listOf("a", "b", "c") }
    }

    @Test
    fun onlyImportedPlaylistsLetSongsOut() {
        val vm = viewModel(CollectionKind.PLAYLIST)
        awaitUntil(message = "loaded") { vm.load.value is RemoteLoad.Loaded }
        assertTrue(!vm.canRemoveSongs)
        vm.removeSong(0, tracks[0])
        assertEquals(3, vm.detail!!.tracks.size)
    }
}

