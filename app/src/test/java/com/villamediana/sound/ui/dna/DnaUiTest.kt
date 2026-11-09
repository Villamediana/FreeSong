package com.villamediana.sound.ui.dna

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.villamediana.sound.data.ThemeMode
import com.villamediana.sound.smart.DayPart
import com.villamediana.sound.smart.DnaSummary
import com.villamediana.sound.social.Profile
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Your DNA on your page, as it looks (SOUND_SHOTS=1 writes PNGs to app/build/sound-shots/). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
class DnaUiTest {
    @org.junit.Before
    fun blockPlaybackBinding() = com.villamediana.sound.testing.blockMediaSessionBinding()
    @get:Rule val compose = createComposeRule()

    private val summary = DnaSummary(
        hourMinutes = List(24) { h -> if (h in 19..23) 120L else if (h in 7..9) 60L else 5L },
        weekdayMinutes = listOf(80, 70, 90, 60, 120, 200, 180),
        parts = listOf(
            DnaSummary.Part(DayPart.NIGHT_LATE, 20, listOf("Rubel"), null),
            DnaSummary.Part(DayPart.MORNING, 180, listOf("Billy Joel", "Coldplay"), "rock"),
            DnaSummary.Part(DayPart.AFTERNOON, 60, listOf("Djavan"), "mpb"),
            DnaSummary.Part(DayPart.EVENING, 600, listOf("Gaiteros de Pillopo", "Paramore"), "latin"),
        ),
        weekdayTop = listOf("Coldplay", "Keane"),
        weekendTop = listOf("Gaiteros de Pillopo"),
        places = listOf(DnaSummary.Place(1, 300, DayPart.EVENING, true, listOf("Gaiteros de Pillopo"))),
        people = listOf(DnaSummary.Person("uana", 90, listOf("Paramore"), listOf("The Only Exception" to "Paramore"))),
    )
    private val ana = Profile("uana", "ana", "Ana Lima", relation = "friends", friendsSince = 1)

    @Test
    fun yourPageTellsHowYouListen() {
        compose.setContent {
            Host(dark = true) {
                androidx.compose.foundation.lazy.LazyColumn {
                    dnaSections(summary, places = true, people = mapOf("uana" to ana))
                }
            }
        }
        compose.onNodeWithText("Seu dia").assertExists()
        compose.onNodeWithText("Você é da noite").assertExists()
        compose.onNodeWithText("Gaiteros de Pillopo · Paramore").assertExists()
        shot("dna-profile")
        val list = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
        list.performScrollToNode(hasText("Lugar 1"))
        list.performScrollToNode(hasText("Ana Lima"))
        shot("dna-profile-places-people")
    }

    @Test
    fun placesShowOnlyWithLembrarLugaresOnAndNothingBeforeThereIsListening() {
        compose.setContent {
            Host(dark = false) {
                androidx.compose.foundation.lazy.LazyColumn {
                    dnaSections(summary, places = false, people = emptyMap())
                    dnaSections(null, places = true, people = emptyMap())
                }
            }
        }
        compose.onNodeWithText("Sua semana").assertExists()
        compose.onNodeWithText("Seus lugares").assertDoesNotExist()
        compose.onNodeWithText("Com quem você ouve").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun Host(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val controller = androidx.navigation.compose.rememberNavController()
        val navigator = androidx.compose.runtime.remember(controller) { com.villamediana.sound.ui.nav.Navigator(controller) }
        SoundTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.villamediana.sound.ui.nav.LocalNavigator provides navigator,
                com.villamediana.sound.ui.theme.LocalBottomInset provides androidx.compose.ui.unit.Dp(24f),
            ) { content() }
        }
    }

    private fun shot(name: String) {
        if (System.getenv("SOUND_SHOTS") != "1") return
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
