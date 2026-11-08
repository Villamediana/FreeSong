package com.villamediana.sound.ui.search

import com.villamediana.sound.ui.theme.withAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import com.villamediana.sound.R
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.data.db.Track
import com.villamediana.sound.online.CollectionDetail
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.online.ResolvedLink
import com.villamediana.sound.online.SearchItem
import com.villamediana.sound.online.SearchPage
import com.villamediana.sound.ui.nav.Inbound
import com.villamediana.sound.ui.nav.LinkInbox
import com.villamediana.sound.ui.nav.RemoteCollectionRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    private lateinit var db: SoundDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private lateinit var harness: ScreenHarness
    private val youTube = FakeYouTube()
    private val downloads = FakeDownloads()
    private val player = FakePlayer()
    private val online = MutableStateFlow(true)

    @Before
    fun setUp() {
        db = SoundDatabase.inMemory(context)
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

    /**
     * Opened from the magnifier, the page comes in step by step after the circle: nothing at first,
     * then the field, then the recent searches one by one, then the genre cards. Frames in
     * build/screenshots/search_entrance_*.png.
     */
    @Test
    fun theSearchPageComesInStepByStep() {
        insertTrack("lib1", "Persiana Americana", genre = "Rock en español")
        insertTrack("lib3", "Zona de Promesas", genre = "Pop")
        val recents = listOf("charly garcía", "cerati", "soda stereo")
        runBlocking { RecentSearchStore(context).clear(); recents.forEach { RecentSearchStore(context).add(it) } }
        val vm = searchViewModel()
        // The page's data as it is when the circle starts (DataStore and the library answer in milliseconds).
        val warm = listOf(scope.launch { vm.recents.collect {} }, scope.launch { vm.genres.collect {} })
        val deadline = System.currentTimeMillis() + 20_000
        while (!(vm.recents.value.size == 3 && vm.genres.value.isNotEmpty()) && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        harness.put(vm)
        rule.mainClock.autoAdvance = false
        harness.show {
            androidx.compose.runtime.CompositionLocalProvider(com.villamediana.sound.ui.nav.LocalSearchEntrance provides com.villamediana.sound.ui.nav.SearchEntrance()) { SearchScreen() }
        }
        val background = com.villamediana.sound.ui.theme.SoundColors.Ink.toArgb()
        fun inked(text: String, @Suppress("UNUSED_PARAMETER") at: Int): Int {
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            val b = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().first().boundsInRoot
            var n = 0
            for (x in b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(bmp.width)) for (y in b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(bmp.height)) {
                val p = bmp.getPixel(x, y)
                val d = kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(background)) + kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(background)) + kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(background))
                if (d > 40) n++
            }
            return n
        }
        val field = s(R.string.search_field_hint)
        var now = 0L
        fun at(ms: Long) {
            rule.mainClock.advanceTimeBy(ms - now)
            now = ms
            harness.screenshot("search_entrance_${ms}ms")
        }
        at(120)
        // The circle is still growing: nothing on the page yet.
        assertEquals(0, inked(field, 120))
        assertEquals(0, inked("soda stereo", 120))
        at(620)
        // Field in; the newest search coming in, the oldest still to come; no genres yet.
        assert(inked(field, 620) > 0)
        assert(inked("soda stereo", 620) > 0)
        assertEquals(0, inked("charly", 620))
        assertEquals(0, inked("Rock en español", 620))
        at(1_000)
        assert(inked("charly", 1_000) > 0)
        at(1_250)
        at(2_400)
        assert(inked("Rock en español", 2_400) > 0)
        rule.mainClock.autoAdvance = true
        warm.forEach { it.cancel() }
    }

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

        // A tap on a song streams its radio (what follows sounds like it, not the other results).
        rule.onNodeWithText("Persiana Americana").performClick()
        rule.waitUntil(20_000) { player.onlinePlays.isNotEmpty() }
        assertEquals(listOf("lib1"), player.onlinePlays.single().tracks.map { it.videoId })
        assertEquals(com.villamediana.sound.playback.PlaySource.Type.RADIO, player.onlinePlays.single().source.type)

        // Long-press another song → selection bar.
        rule.onNodeWithText("Nada Personal").performTouchInput { longClick() }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(context.resources.getQuantityString(R.plurals.search_download_n, 1, 1))).fetchSemanticsNodes().isNotEmpty() }
        harness.screenshot("search_selection")
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.search_download_n, 1, 1)).performClick()
        rule.waitUntil(20_000) { downloads.downloadCalls.size == 2 }
        assertEquals(listOf("bbbbbbbbbbb"), downloads.downloadCalls[1].map { it.videoId })
    }

    @Test
    fun searchDrawsOnItsOwnColorWithTextInContrast() {
        val before = com.villamediana.sound.ui.theme.SoundColors.palette
        com.villamediana.sound.ui.theme.SoundColors.palette =
            com.villamediana.sound.ui.theme.SoundPalettes.Dark.withAccent(com.villamediana.sound.ui.theme.SoundAccents.byId("purple"))
        try {
            insertTrack("lib1", "Persiana Americana", genre = "Rock en español")
            youTube.onSearch = { q, f ->
                SearchPage(q, f, listOf(SearchItem.Song(remoteTrack("aaaaaaaaaaa", "De Música Ligera")), SearchItem.Song(remoteTrack("lib1", "Persiana Americana"))))
            }
            val listener = object : com.villamediana.sound.recognition.SongListener {
                override suspend fun recognize(onLevel: (Float) -> Unit, onSeconds: (Float) -> Unit) = com.villamediana.sound.recognition.Recognition.NotFound
            }
            harness.put(SearchViewModel(youTube, library, downloads, player, RecentSearchStore(context), online, true, listener = listener))
            val colors = com.villamediana.sound.ui.theme.FixedColors(com.villamediana.sound.ui.theme.searchPalette(com.villamediana.sound.ui.theme.SoundColors.palette))
            harness.show {
                androidx.compose.runtime.CompositionLocalProvider(com.villamediana.sound.ui.theme.LocalSoundColors provides colors) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(com.villamediana.sound.ui.theme.SoundColors.Search)) { SearchScreen() }
                }
            }
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("Rock en español")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription(s(R.string.hear_button)).assertExists()
            harness.screenshot("search_colored_idle")
            rule.onNode(hasSetTextAction()).performTextInput("soda")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("De Música Ligera")).fetchSemanticsNodes().isNotEmpty() }
            harness.screenshot("search_colored_results")
        } finally {
            com.villamediana.sound.ui.theme.SoundColors.palette = before
        }
    }

    @Test
    fun sharedSongLinkShowsTheLinkCard() {
        val song = remoteTrack("dQw4w9WgXcQ", "Té para Tres", artist = "Soda Stereo")
        youTube.onResolve = { ResolvedLink.Track(song) }
        LinkInbox.post(Inbound.Link("Olha essa https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
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
    fun aVoiceRequestTheLibraryCannotPlayIsSearchedOnline() {
        youTube.onSearch = { q, f -> songPage(q, f, listOf("aaaaaaaaaaa")) }
        LinkInbox.post(Inbound.Query("cerati crimen"))
        harness.put(searchViewModel())
        harness.show { SearchScreen() }

        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Song aaaaaaaaaaa")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(LinkInbox.pending.value)
        assertEquals("cerati crimen", youTube.searches.first().first)
        rule.onNode(hasSetTextAction() and hasText("cerati crimen")).assertExists()
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
        youTube.onCollection = { u, kind -> CollectionDetail(RemoteCollection(kind, u, "Rock nacional", "Sound", itemCount = 3), tracks) }
        val route = RemoteCollectionRoute(url, CollectionKind.PLAYLIST.name, "Rock nacional", "Sound")
        harness.put(RemoteCollectionViewModel(route, youTube, library, downloads, player, online, true), key = "remote:${route.kind}:${route.url}")
        harness.show { RemoteCollectionScreen(route) }

        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Trátame Suavemente")).fetchSemanticsNodes().isNotEmpty() }
        // No "Baixar tudo" on a playlist: its songs are kept one by one (heart / arrow on each row).
        rule.onNodeWithText(s(R.string.search_remote_download_all)).assertDoesNotExist()
        harness.screenshot("remote_playlist")

        // Play streams the whole playlist from the first song.
        rule.onNodeWithContentDescription(s(R.string.search_remote_play)).performClick()
        rule.waitUntil(20_000) { player.onlinePlays.isNotEmpty() }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), player.onlinePlays.single().tracks.map { it.videoId })
        assertEquals(0, player.onlinePlays.single().index)
        rule.onNodeWithContentDescription(s(R.string.search_remote_shuffle)).assertExists()

        // ⋮ → share the playlist.
        // The page's ⋮ (the songs have theirs too, as buttons in their rows).
        check(rule.onAllNodesWithContentDescription(s(R.string.more_options)).fetchSemanticsNodes().size > 1)
        rule.onNode(
            androidx.compose.ui.test.hasContentDescription(s(R.string.more_options)) and
                androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Role),
        ).performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(s(R.string.search_remote_share))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.detail_menu_play_next)).performClick()
        rule.waitUntil(20_000) { player.queued.isNotEmpty() }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), player.queued.single().second.map { it.videoId })
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(s(R.string.search_remote_share))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun remoteAlbumPageHasNoDownloadAll() {
        val url = "https://music.youtube.com/browse/MPREb_signos"
        youTube.onCollection = { u, kind -> CollectionDetail(RemoteCollection(kind, u, "Signos", "Soda Stereo", itemCount = 2), listOf(remoteTrack("aaaaaaaaaaa", "Persiana Americana"), remoteTrack("bbbbbbbbbbb", "Signos"))) }
        val route = RemoteCollectionRoute(url, CollectionKind.ALBUM.name, "Signos", "Soda Stereo")
        harness.put(RemoteCollectionViewModel(route, youTube, library, downloads, player, online, true), key = "remote:${route.kind}:${route.url}")
        harness.show { RemoteCollectionScreen(route) }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Persiana Americana")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(s(R.string.search_remote_download_all)).assertDoesNotExist()
        rule.onNodeWithContentDescription(s(R.string.search_remote_play)).assertExists()
        harness.screenshot("remote_album")
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
        rule.onNodeWithText(s(R.string.search_artist_albums)).assertExists()
        // No "Baixar populares": an artist's songs are kept one by one (heart / arrow on each row).
        rule.onNodeWithText(s(R.string.search_remote_download_all)).assertDoesNotExist()
        rule.onNodeWithContentDescription(s(R.string.search_remote_play)).assertExists()
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
