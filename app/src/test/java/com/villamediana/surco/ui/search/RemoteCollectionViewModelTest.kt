package com.villamediana.surco.ui.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.SurcoDatabase
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.OnlineException
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.playback.PlaySource
import com.villamediana.surco.ui.nav.RemoteCollectionRoute
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteCollectionViewModelTest {
    private lateinit var db: SurcoDatabase
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
        db = SurcoDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        youTube.onCollection = { url, kind -> CollectionDetail(RemoteCollection(kind, url, "Rock nacional", "Surco"), tracks) }
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(kind: CollectionKind, url: String = playlistUrl): RemoteCollectionViewModel {
        val vm = RemoteCollectionViewModel(
            RemoteCollectionRoute(url, kind.name, "Rock nacional", "Surco"),
            youTube, library, downloads, player, online, initiallyOnline = online.value,
        )
        vms += vm
        scope.launch { vm.lookup.collect { } }
        scope.launch { vm.progress.collect { } }
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
        val vm = viewModel(CollectionKind.ALBUM, "https://music.youtube.com/browse/MPREb_signos")
        awaitUntil { vm.load.value is RemoteLoad.Loaded && vm.progress.value.done == 2 }
        assertEquals(PrimaryAction.Download(1, 3), vm.action())

        vm.openInLibrary()
        assertEquals(RemoteEvent.Open(LocalDestination.Album(AlbumKey("Soda Stereo", "Signos"))), vm.nextEvent())

        vm.onSongTap(tracks[2])
        awaitUntil { player.plays.size == 1 }
        val play = player.plays.single()
        assertEquals(listOf("a", "c"), play.tracks.map { it.videoId })
        assertEquals(1, play.index)
        assertEquals(PlaySource.Type.ALBUM, play.source.type)

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
        vm.onSongTap(tracks[0])
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
}
