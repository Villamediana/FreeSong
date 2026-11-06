package com.villamediana.surco.ui.search

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.R
import com.villamediana.surco.data.LibraryRepository
import com.villamediana.surco.data.db.DownloadState
import com.villamediana.surco.data.db.SurcoDatabase
import com.villamediana.surco.data.db.Track
import com.villamediana.surco.online.CollectionDetail
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.online.ResolvedLink
import com.villamediana.surco.online.SearchItem
import com.villamediana.surco.online.SearchPage
import com.villamediana.surco.ui.nav.LinkInbox
import com.villamediana.surco.ui.nav.RemoteCollectionRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real screens on Robolectric with fake online/download services and pokes at them. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreensSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SurcoDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private lateinit var harness: ScreenHarness
    private val youTube = FakeYouTube()
    private val downloads = FakeDownloads()
    private val player = FakePlayer()
    private val online = MutableStateFlow(true)

    @Before
    fun setUp() {
        db = SurcoDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(db, scope)
        harness = ScreenHarness(rule)
    }

    @After
    fun tearDown() {
        harness.clear()
        scope.cancel()
        db.close()
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun insertTrack(videoId: String, title: String, genre: String? = null) = runBlocking {
        library.insertTrack(Track(videoId = videoId, title = title, artist = "Soda Stereo", album = "Signos", genre = genre, filePath = "/music/$videoId.m4a"))
    }

    private fun searchViewModel() = SearchViewModel(youTube, library, downloads, player, RecentSearchStore(context), online, true)

    @Test
    fun searchIdleThenResultsThenDownloadAndMultiSelect() {
        insertTrack("lib1", "Persiana Americana", genre = "Rock en español")
        insertTrack("lib2", "Signos", genre = "Rock en español")
        insertTrack("lib3", "Zona de Promesas", genre = "Pop")
        youTube.onSearch = { q, f ->
            SearchPage(
                q, f,
                listOf(
                    SearchItem.Song(remoteTrack("aaaaaaaaaaa", "De Música Ligera")),
                    SearchItem.Song(remoteTrack("lib1", "Persiana Americana")),
                    SearchItem.Song(remoteTrack("bbbbbbbbbbb", "Nada Personal")),
                    SearchItem.Collection(RemoteCollection(CollectionKind.ALBUM, "https://music.youtube.com/browse/MPREb_1", "Canción Animal", "Soda Stereo", year = 1990)),
                    SearchItem.Collection(RemoteCollection(CollectionKind.ARTIST, "https://music.youtube.com/channel/UC1", "Soda Stereo")),
                ),
            )
        }
        harness.put(searchViewModel())
        harness.show { SearchScreen() }

        rule.onNodeWithText(s(R.string.search_title)).assertExists()
        rule.onNodeWithText(s(R.string.search_hint_body)).assertExists()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Rock en español")).fetchSemanticsNodes().isNotEmpty() }
        harness.screenshot("search_idle")

        rule.onNode(hasSetTextAction()).performTextInput("soda")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("De Música Ligera")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Canción Animal").assertExists()
        rule.onNodeWithText(s(R.string.search_filter_albums)).assertExists()
        rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription(s(R.string.search_dl_done)).fetchSemanticsNodes().isNotEmpty() }
        harness.screenshot("search_results")

        // Tap the first download button → queued.
        rule.onAllNodesWithContentDescription(s(R.string.search_dl_download))[0].performClick()
        rule.waitUntil(20_000) { downloads.downloadCalls.isNotEmpty() }
        assertEquals(listOf("aaaaaaaaaaa"), downloads.downloadCalls.single().map { it.videoId })
        rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription(s(R.string.search_dl_queued)).fetchSemanticsNodes().isNotEmpty() }

        // A tap on a song streams the visible songs from it.
        rule.onNodeWithText("Persiana Americana").performClick()
        rule.waitUntil(20_000) { player.onlinePlays.isNotEmpty() }
        assertEquals(listOf("aaaaaaaaaaa", "lib1", "bbbbbbbbbbb"), player.onlinePlays.single().tracks.map { it.videoId })
        assertEquals(1, player.onlinePlays.single().index)

        // Long-press another song → selection bar.
        rule.onNodeWithText("Nada Personal").performTouchInput { longClick() }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(context.resources.getQuantityString(R.plurals.search_download_n, 1, 1))).fetchSemanticsNodes().isNotEmpty() }
        harness.screenshot("search_selection")
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.search_download_n, 1, 1)).performClick()
        rule.waitUntil(20_000) { downloads.downloadCalls.size == 2 }
        assertEquals(listOf("bbbbbbbbbbb"), downloads.downloadCalls[1].map { it.videoId })
    }

    @Test
    fun sharedSongLinkShowsTheLinkCard() {
        val song = remoteTrack("dQw4w9WgXcQ", "Té para Tres", artist = "Soda Stereo")
        youTube.onResolve = { ResolvedLink.Track(song) }
        LinkInbox.post("Olha essa https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        harness.put(searchViewModel())
        harness.show { SearchScreen() }

        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Té para Tres")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(LinkInbox.pending.value)
        assertEquals(listOf("https://music.youtube.com/watch?v=dQw4w9WgXcQ"), youTube.resolved.toList())
        harness.screenshot("search_link_card")

        rule.onNodeWithText(s(R.string.search_link_listen)).performClick()
        rule.waitUntil(20_000) { player.onlinePlays.isNotEmpty() }
        assertEquals(listOf(song), player.onlinePlays.single().tracks)

        rule.onNodeWithText(s(R.string.search_link_download)).performClick()
        rule.waitUntil(20_000) { downloads.downloadCalls.isNotEmpty() }
        downloads.setState("dQw4w9WgXcQ", DownloadState.RUNNING, 0.4f)
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(s(R.string.search_link_percent, 40))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.search_link_listen)).assertExists()
        harness.screenshot("search_link_card_downloading")
    }

    @Test
    fun typingShowsSuggestionsAndTheLibrary() {
        insertTrack("lib1", "Persiana Americana", genre = "Rock")
        youTube.onSuggestions = { listOf("persiana americana", "persiana americana soda stereo", "persiana americana letra") }
        harness.put(searchViewModel())
        harness.show { SearchScreen() }
        rule.onNode(hasSetTextAction()).performTextInput("persiana")
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasText("persiana americana letra")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(hasText(s(R.string.search_in_library))).fetchSemanticsNodes().isNotEmpty()
        }
        harness.screenshot("search_typing")
        rule.onNodeWithText("Persiana Americana").performClick()
        rule.waitUntil(20_000) { player.plays.isNotEmpty() }
        assertEquals("lib1", player.plays.single().tracks.single().videoId)
    }

    @Test
    fun remotePlaylistPage() {
        val url = "https://music.youtube.com/playlist?list=PL1"
        val tracks = listOf(remoteTrack("aaaaaaaaaaa", "Cuando Pase el Temblor"), remoteTrack("bbbbbbbbbbb", "Trátame Suavemente"), remoteTrack("ccccccccccc", "Prófugos"))
        youTube.onCollection = { u, kind -> CollectionDetail(RemoteCollection(kind, u, "Rock nacional", "Surco", itemCount = 3), tracks) }
        val route = RemoteCollectionRoute(url, CollectionKind.PLAYLIST.name, "Rock nacional", "Surco")
        harness.put(RemoteCollectionViewModel(route, youTube, library, downloads, player, online, true), key = "remote:${route.kind}:${route.url}")
        harness.show { RemoteCollectionScreen(route) }

        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Trátame Suavemente")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.search_remote_download_all)).assertExists()
        rule.onNodeWithText(s(R.string.search_remote_playlist_note, "Rock nacional")).assertExists()
        harness.screenshot("remote_playlist")

        // Play streams the whole playlist from the first song.
        rule.onNodeWithContentDescription(s(R.string.search_remote_play)).performClick()
        rule.waitUntil(20_000) { player.onlinePlays.isNotEmpty() }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), player.onlinePlays.single().tracks.map { it.videoId })
        assertEquals(0, player.onlinePlays.single().index)
        rule.onNodeWithContentDescription(s(R.string.search_remote_shuffle)).assertExists()

        // ⋮ → share the playlist.
        rule.onNodeWithContentDescription(s(R.string.more_options)).performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(s(R.string.search_remote_share))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.detail_menu_play_next)).performClick()
        rule.waitUntil(20_000) { player.queued.isNotEmpty() }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), player.queued.single().second.map { it.videoId })
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(s(R.string.search_remote_share))).fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithText(s(R.string.search_remote_download_all)).performClick()
        rule.waitUntil(20_000) { downloads.collectionCalls.isNotEmpty() }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(s(R.string.search_remote_progress, 0, 3))).fetchSemanticsNodes().isNotEmpty() }
        harness.screenshot("remote_playlist_downloading")
    }

    @Test
    fun remoteArtistPage() {
        val url = "https://music.youtube.com/channel/UC1"
        youTube.onCollection = { u, kind ->
            CollectionDetail(
                RemoteCollection(kind, u, "Soda Stereo"),
                listOf(remoteTrack("aaaaaaaaaaa", "De Música Ligera"), remoteTrack("bbbbbbbbbbb", "Persiana Americana")),
                related = listOf(
                    RemoteCollection(CollectionKind.ALBUM, "https://music.youtube.com/browse/MPREb_1", "Canción Animal", "Soda Stereo", year = 1990),
                    RemoteCollection(CollectionKind.ALBUM, "https://music.youtube.com/browse/MPREb_2", "Signos", "Soda Stereo", year = 1986),
                ),
            )
        }
        val route = RemoteCollectionRoute(url, CollectionKind.ARTIST.name, "Soda Stereo")
        harness.put(RemoteCollectionViewModel(route, youTube, library, downloads, player, online, true), key = "remote:${route.kind}:${route.url}")
        harness.show { RemoteCollectionScreen(route) }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Canción Animal")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.search_remote_popular)).assertExists()
        rule.onNodeWithText(s(R.string.search_remote_albums)).assertExists()
        rule.onNodeWithText(s(R.string.search_remote_download_popular)).assertExists()
        harness.screenshot("remote_artist")
    }

    /** What NavHost does with the Search page while a page opened from it is on top: only saveable state survives. */
    private fun showSearchThatCanBeLeft(away: () -> Boolean) = harness.show {
        val holder = rememberSaveableStateHolder()
        if (away()) Text("página aberta") else holder.SaveableStateProvider("search") { SearchScreen() }
    }

    private fun songIds(n: Int) = (1..n).map { "v" + it.toString().padStart(10, '0') }

    @Test
    fun resultsKeepTheirScrollAfterComingBackFromAnOpenedPage() {
        youTube.onSearch = { q, f -> SearchPage(q, f, songIds(60).mapIndexed { i, id -> SearchItem.Song(remoteTrack(id, "Canción ${i + 1}")) }) }
        var away by mutableStateOf(false)
        harness.put(searchViewModel())
        showSearchThatCanBeLeft { away }

        rule.onNode(hasSetTextAction()).performTextInput("soda")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Canción 1")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Canción 45"))
        rule.onNodeWithText("Canción 1").assertDoesNotExist()

        away = true
        rule.waitForIdle()
        away = false
        rule.waitForIdle()

        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Canción 45")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Canción 1").assertDoesNotExist()
    }
}
