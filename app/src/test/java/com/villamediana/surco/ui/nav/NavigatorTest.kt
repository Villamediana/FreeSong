package com.villamediana.surco.ui.nav

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
import com.villamediana.surco.data.db.AlbumKey
import com.villamediana.surco.online.CollectionKind
import com.villamediana.surco.online.RemoteCollection
import com.villamediana.surco.ui.theme.SurcoTheme
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
            SurcoTheme {
                val nav = rememberNavController()
                navigator = remember(nav) { Navigator(nav) }
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
                            composable<DownloadsRoute> { Text("downloads") }
                            composable<RemoteCollectionRoute> { Text("remote ${it.toRoute<RemoteCollectionRoute>().title}") }
                        }
                        BottomBar(navigator)
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

    private fun tab(label: String) = compose.onNodeWithText(label)

    private fun assertTab(selected: String) {
        listOf("Início", "Buscar", "Biblioteca").forEach { label ->
            if (label == selected) tab(label).assertIsSelected() else tab(label).assertIsNotSelected()
        }
    }

    @Test
    fun theBottomBarLightsTheTabWhosePagesAreShowing() {
        setUp()
        assertTab("Início")
        tab("Biblioteca").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

        // Album → artist from Biblioteca: still Biblioteca (it used to jump to Início).
        nav { switchTab(LibraryRoute) }
        nav { openAlbum(AlbumKey("Soda Stereo", "Signos")) }
        nav { openArtist("Soda Stereo") }
        assertTab("Biblioteca")

        // Curtidas and Downloads from Início stay in Início (they used to light Biblioteca / Buscar).
        nav { switchTab(HomeRoute) }
        nav { openLiked() }
        assertTab("Início")
        nav { openDownloads() }
        assertTab("Início")

        nav { switchTab(SearchRoute) }
        nav { openDownloads() }
        assertTab("Buscar")

        // Back to Biblioteca's saved stack, then all the way out of it.
        nav { switchTab(LibraryRoute) }
        compose.onNodeWithText("artist Soda Stereo").assertExists()
        assertTab("Biblioteca")
        nav { back() }
        nav { back() }
        nav { back() }
        compose.onNodeWithText("home").assertExists()
        assertTab("Início")
    }

    private fun remote(title: String) = RemoteCollection(CollectionKind.ALBUM, "https://music.youtube.com/browse/$title", title, "Soda Stereo")

    @Test
    fun aSharedLinkLandsOnTheSearchScreenEvenWhenSearchHadAPageOpen() {
        setUp()
        // First link: Search resolves it into album A's page.
        nav { openSearchScreen() }
        nav { openRemote(remote("A")) }
        compose.onNodeWithText("remote A").assertExists()

        // Later, from Biblioteca: the next link must reach the Search screen, not album A again.
        nav { switchTab(LibraryRoute) }
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

    private fun routesOnStack(): List<String> = controller.currentBackStack.value
        .mapNotNull { e -> e.destination.route?.takeIf { e.destination !is androidx.navigation.NavGraph } }
        .map { it.substringAfterLast('.').substringBefore('/').substringBefore('?') }
}
