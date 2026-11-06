package com.villamediana.surco.ui.nav

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import com.villamediana.surco.ui.theme.SurcoTheme
import org.junit.Assert.assertEquals
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
}
