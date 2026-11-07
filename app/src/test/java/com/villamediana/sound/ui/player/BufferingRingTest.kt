package com.villamediana.sound.ui.player

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The player's loading ring takes the glyph's color over a see-through button — never black. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BufferingRingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theRingIsTheGlyphColorNotBlack() {
        val mint = Color(0xFFA88BFF)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SoundTheme {
                Box(Modifier.background(Color(0xFF3A4A58)).padding(16.dp)) {
                    MorphPlayPause(playing = true, onClick = {}, size = 84.dp, container = Color.Transparent, content = mint, buffering = true)
                }
            }
        }
        compose.mainClock.advanceTimeBy(700)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        runCatching {
            File("build/screenshots").apply { mkdirs() }.resolve("buffering_ring.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var dark = 0
        var tinted = 0
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
            val p = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(p); val g = android.graphics.Color.green(p); val b = android.graphics.Color.blue(p)
            if (r < 30 && g < 30 && b < 30) dark++
            if (b > 200 && r > 140) tinted++
        }
        assertTrue("no black ring ($dark dark px)", dark < 20)
        assertTrue("the ring shows in the glyph color ($tinted px)", tinted > 200)
        compose.mainClock.autoAdvance = true
    }
}
