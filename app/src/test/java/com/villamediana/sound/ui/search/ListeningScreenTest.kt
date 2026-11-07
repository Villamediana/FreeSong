package com.villamediana.sound.ui.search

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The listening screen: listening, then not found with "try again". */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) = runCatching {
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun listeningThenNotFoundOffersToTryAgain() {
        var hearing by mutableStateOf<SearchViewModel.Hearing>(SearchViewModel.Hearing.Listening(5f))
        var retries = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SoundTheme { ListeningScreen(hearing = hearing, level = 0.6f, onCancel = {}, onRetry = { retries++ }) }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Ouvindo…").assertExists()
        shot("listening")
        compose.runOnIdle { hearing = SearchViewModel.Hearing.NotFound }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Não reconheci essa").assertExists()
        shot("listening_not_found")
        compose.onNodeWithText("Tentar de novo").performClick()
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, retries)
        compose.mainClock.autoAdvance = true
    }
}
