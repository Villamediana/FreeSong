package com.villamediana.sound.ui.nav

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The search circle shows over a page of the same background: it comes in the accent color. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchRevealTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun midwayTheCircleIsTheAccentColorNotTheBackground() {
        lateinit var navigator: Navigator
        compose.setContent {
            SoundTheme {
                val nav = rememberNavController()
                navigator = remember(nav) { Navigator(nav) }
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    NavHost(nav, startDestination = HomeRoute, enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.KeepUntilTransitionsFinished }) {
                        composable<HomeRoute> { Box(Modifier.fillMaxSize().background(SoundColors.Ink)) { Text("home") } }
                        composable<SearchRoute> { entry ->
                            CircularReveal(entry, navigator) { Box(Modifier.fillMaxSize().background(SoundColors.Ink)) { Text("search") } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        SearchReveal.origin = Offset(240f, 60f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigator.openSearchScreen() }
        compose.mainClock.advanceTimeBy(140)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        runCatching {
            File("build/screenshots").apply { mkdirs() }.resolve("search_reveal_mid.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val px = bmp.getPixel(240, 90)
        val ink = SoundColors.Ink.toArgb()
        val diff = abs(android.graphics.Color.red(px) - android.graphics.Color.red(ink)) +
            abs(android.graphics.Color.green(px) - android.graphics.Color.green(ink)) +
            abs(android.graphics.Color.blue(px) - android.graphics.Color.blue(ink))
        assertTrue("inside the circle the color shows (diff $diff)", diff > 60)
        compose.mainClock.autoAdvance = true
    }
}
