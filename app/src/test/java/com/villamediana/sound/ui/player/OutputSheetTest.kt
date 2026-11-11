package com.villamediana.sound.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.villamediana.sound.playback.Outputs
import com.villamediana.sound.ui.sheets.SheetHeader
import com.villamediana.sound.ui.components.Cover
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.theme.SoundTheme
import com.villamediana.sound.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
class OutputSheetTest {
    @get:Rule val compose = createComposeRule()

    private val philips = Outputs.Option(7, Outputs.Kind.BLUETOOTH, "Philips TAT1209")
    private val phone = Outputs.Option(1, Outputs.Kind.PHONE, null)

    private fun show(dark: Boolean = true): Triple<() -> Outputs.Option?, () -> Int, () -> Int> {
        var picked by mutableStateOf<Outputs.Option?>(null)
        var level by mutableIntStateOf(9)
        var connects by mutableIntStateOf(0)
        compose.setContent {
            SoundTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Column(Modifier.fillMaxWidth().background(SoundColors.Surface2).padding(top = 16.dp)) {
                    SheetHeader(title = "Mi Primer Millon", subtitle = "Bacilos", art = { Cover(null, Modifier.fillMaxSize(), seed = "Bacilos") })
                    val options = listOf(philips, phone)
                    OutputContent(
                        outputs = OutputsState(options, current = options.firstOrNull { it.id == (picked?.id ?: 7) }, systemDefault = 7),
                        volume = MediaVolume(level, 15) { level = it },
                        onPick = { picked = it },
                        onConnect = { connects++ },
                    )
                }
            }
        }
        return Triple({ picked }, { level }, { connects })
    }

    @Test fun theOutputsTheOnePlayingAndConnectingAnother() {
        val (picked, _, connects) = show()
        compose.onNodeWithText("Philips TAT1209").assertExists()
        compose.onNodeWithText("Este celular").assertExists()
        compose.onNodeWithText("Conectar dispositivo").assertExists()
        compose.onNode(hasText("Philips TAT1209") and androidx.compose.ui.test.isSelectable()).assertIsSelected()
        compose.onNode(hasText("Este celular") and androidx.compose.ui.test.isSelectable()).assertIsNotSelected()
        shot("output-sheet-dark")

        compose.onNodeWithText("Este celular").performClick()
        assertEquals(phone, picked())
        compose.onNode(hasText("Este celular") and androidx.compose.ui.test.isSelectable()).assertIsSelected()

        compose.onNodeWithText("Conectar dispositivo").performClick()
        assertEquals(1, connects())
    }

    @Test fun theVolumeFollowsTheFingerAndTalkBack() {
        val (_, level, _) = show(dark = false)
        shot("output-sheet-light")
        compose.onNodeWithTag("output-volume").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        assertEquals(3, level())
        // A tap near the end: almost all the way up.
        compose.onNodeWithTag("output-volume").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.95f, centerY)) }
        compose.waitForIdle()
        assertTrue(level() >= 14)
        // Dragged to the start: silence.
        compose.onNodeWithTag("output-volume").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * 0.5f, centerY))
            moveTo(androidx.compose.ui.geometry.Offset(width * 0.3f, centerY))
            moveTo(androidx.compose.ui.geometry.Offset(0f, centerY))
            up()
        }
        compose.waitForIdle()
        assertEquals(0, level())
    }

    /** Best effort PNG into build/screenshots (only with native graphics). */
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }
}
