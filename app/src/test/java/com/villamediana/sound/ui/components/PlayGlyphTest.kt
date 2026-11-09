package com.villamediana.sound.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The app's play/pause: soft like the skip arrows beside it, and the round button keeps its label. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h300dp-xxhdpi")
class PlayGlyphTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun playAndPauseSitBesideTheArrowsAndTheButtonSaysWhatItDoes() {
        compose.setContent {
            Row(Modifier.background(Color(0xFF14161D)).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(56.dp))
                PlayPauseGlyph(playing = false, tint = Color.White, modifier = Modifier.size(56.dp))
                PlayPauseGlyph(playing = true, tint = Color.White, modifier = Modifier.size(56.dp))
                Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(56.dp))
                PlayPauseButton(playing = false, onClick = {}, contentDescription = "Tocar")
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, compose.onAllNodesWithContentDescription("Tocar").fetchSemanticsNodes().size)
        if (System.getenv("SOUND_SHOTS") == "1") {
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
            File(dir, "play-glyph.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
