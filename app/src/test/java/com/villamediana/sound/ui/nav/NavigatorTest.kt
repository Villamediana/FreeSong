package com.villamediana.sound.ui.nav

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.villamediana.sound.data.db.AlbumKey
import com.villamediana.sound.online.CollectionKind
import com.villamediana.sound.online.RemoteCollection
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real Navigator over a NavHost with stand-in pages (the detail screens' way of building their ViewModel). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavigatorTest {
    @get:Rule val compose = createComposeRule()

    /** Like AlbumViewModel: built from the route, under the default (class-name) key. */
    class PageViewModel(val title: String) : ViewModel()

    private lateinit var navigator: Navigator
    private val controller: NavHostController get() = navigator.controller

    private fun setUp() {
        compose.setContent {
            SoundTheme {
                val nav = rememberNavController()
                navigator = remember(nav) { Navigator(nav) }
                navigator.FollowBackStack()
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    Box {
                        NavHost(nav, startDestination = HomeRoute) {
                            composable<HomeRoute> { Text("home") }
                            composable<SearchRoute> { Text("search") }
                            composable<LibraryRoute> { Text("library") }
                            composable<AlbumRoute> {
                                val route = it.toRoute<AlbumRoute>()
                                val vm = viewModel { PageViewModel(route.title) }
                                Text("album ${vm.title}")
                            }
                            composable<ArtistRoute> {
                                val route = it.toRoute<ArtistRoute>()
                                val vm = viewModel { PageViewModel(route.name) }
                                Text("artist ${vm.title}")
                            }
                            composable<LikedRoute> { Text("liked") }
                            composable<EqualizerRoute> { Text("equalizer") }
                            composable<DownloadsRoute> { Text("downloads") }
                            composable<RemoteCollectionRoute> { Text("remote ${it.toRoute<RemoteCollectionRoute>().title}") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun nav(block: Navigator.() -> Unit) {
        compose.runOnIdle { navigator.block() }
        compose.waitForIdle()
    }

    private fun albumsOnStack(): Int = controller.currentBackStack.value.count { it.destination.hasRoute(AlbumRoute::class) }

    @Test
    fun anotherAlbumFromAnAlbumOpensItsOwnPageAndBackReturns() {
        setUp()
        nav { openAlbum(AlbumKey("Soda Stereo", "Canción Animal")) }
        compose.onNodeWithText("album Canción Animal").assertExists()

        // "Mais de Soda Stereo" → Signos: a new page, not the first one with new arguments.
        nav { openAlbum(AlbumKey("Soda Stereo", "Signos")) }
        compose.onNodeWithText("album Signos").assertExists()
        compose.onNodeWithText("album Canción Animal").assertDoesNotExist()
        assertEquals(2, albumsOnStack())

        // The same album again pushes nothing.
        nav { openAlbum(AlbumKey("Soda Stereo", "Signos")) }
        assertEquals(2, albumsOnStack())

        nav { back() }
        compose.onNodeWithText("album Canción Animal").assertExists()
    }

    @Test
    fun anotherArtistFromTheFullPlayerOpensItAndKeepsTheFirst() {
        setUp()
        nav { openArtist("Soda Stereo") }
        nav { openArtist("Gustavo Cerati") }
        compose.onNodeWithText("artist Gustavo Cerati").assertExists()
        nav { openLiked() }
        nav { openLiked() }
        nav { back() }
        compose.onNodeWithText("artist Gustavo Cerati").assertExists()
        nav { back() }
        compose.onNodeWithText("artist Soda Stereo").assertExists()
    }

    private fun remote(title: String) = RemoteCollection(CollectionKind.ALBUM, "https://music.youtube.com/browse/$title", title, "Soda Stereo")

    @Test
    fun aSharedLinkLandsOnTheSearchScreenEvenWhenSearchHadAPageOpen() {
        setUp()
        // First link: Search resolves it into album A's page.
        nav { openSearchScreen() }
        nav { openRemote(remote("A")) }
        compose.onNodeWithText("remote A").assertExists()

        // The next link must reach the Search screen, not album A again.
        nav { openSearchScreen() }
        compose.onNodeWithText("search").assertExists()
        assertTrue(controller.currentBackStackEntry!!.destination.hasRoute(SearchRoute::class))
        assertEquals(listOf("HomeRoute", "SearchRoute"), routesOnStack())

        // Same while standing on a page opened from Search (Downloads).
        nav { openDownloads() }
        nav { openSearchScreen() }
        compose.onNodeWithText("search").assertExists()
        assertEquals(listOf("HomeRoute", "SearchRoute"), routesOnStack())
    }

    @Test
    fun theSearchCircleGrowsOnlyWhenSearchIsOpenedNotWhenItIsComeBackTo() {
        setUp()
        SearchReveal.armed = false
        nav { openSearchScreen() }
        assertTrue("pushed: grows from the magnifier", SearchReveal.armed)

        SearchReveal.armed = false
        nav { openRemote(remote("A")) }
        nav { openSearchScreen() }
        compose.onNodeWithText("search").assertExists()
        assertEquals("back to it: no circle", false, SearchReveal.armed)
    }

    private fun routesOnStack(): List<String> = controller.currentBackStack.value
        .mapNotNull { e -> e.destination.route?.takeIf { e.destination !is androidx.navigation.NavGraph } }
        .map { it.substringAfterLast('.').substringBefore('/').substringBefore('?') }

    // ------------------------------------------------------------------ pages opened from the player

    /** Stand-in for the player layer: open/closed and what navigation asked of it. */
    private class FakePlayerLayer {
        var open = false
        var expands = 0
        var sheetsClosed = 0
        val hooks = PlayerHooks(
            isOpen = { open },
            collapse = { open = false },
            closeSheets = { sheetsClosed++ },
            expand = {
                open = true
                expands++
            },
        )
    }

    private fun playerLayer(): FakePlayerLayer = FakePlayerLayer().also { p -> compose.runOnIdle { navigator.player = p.hooks } }

    @Test
    fun backFromAPageOpenedInThePlayerOpensThePlayerAgain() {
        setUp()
        val player = playerLayer()
        player.open = true
        nav { openArtist("Soda Stereo") }
        compose.onNodeWithText("artist Soda Stereo").assertExists()
        assertEquals(false, player.open)

        // Deeper and back: only the page under the player brings it back.
        nav { openAlbum(AlbumKey("Soda Stereo", "Signos")) }
        nav { back() }
        assertEquals(0, player.expands)
        nav { back() }
        compose.onNodeWithText("home").assertExists()
        assertEquals(1, player.expands)
        assertTrue(player.open)
    }

    @Test
    fun theEqualizerGoesOverTheOpenPlayerWithoutClosingIt() {
        setUp()
        val player = playerLayer()
        player.open = true
        nav { openEqualizer() }
        compose.onNodeWithText("equalizer").assertExists()
        assertTrue("the player stays open under the full page", player.open)
        assertEquals(1, player.sheetsClosed)
        nav { back() }
        compose.onNodeWithText("home").assertExists()
        assertTrue(player.open)
        assertEquals(0, player.expands)
    }

    @Test
    fun aPageOpenedWithThePlayerClosedLeavesItClosedOnBack() {
        setUp()
        val player = playerLayer()
        nav { openArtist("Soda Stereo") }
        nav { back() }
        compose.onNodeWithText("home").assertExists()
        assertEquals(0, player.expands)
        assertEquals(false, player.open)
    }

    @Test
    fun switchingTabsForgetsTheWayBackToThePlayer() {
        setUp()
        val player = playerLayer()
        player.open = true
        nav { openArtist("Soda Stereo") }
        nav { switchTab(LibraryRoute) }
        compose.onNodeWithText("library").assertExists()
        nav { back() }
        compose.onNodeWithText("home").assertExists()
        assertEquals(0, player.expands)
    }
}
