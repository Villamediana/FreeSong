package com.villamediana.surco.ui.equalizer

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.villamediana.surco.R
import com.villamediana.surco.playback.EqBand
import com.villamediana.surco.playback.EqPreset
import com.villamediana.surco.playback.EqState
import com.villamediana.surco.ui.theme.SurcoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = context.getString(id)

    private val bands = listOf(60, 230, 910, 3_600, 14_000).mapIndexed { i, hz -> EqBand(i, hz, 0) }

    private class Calls {
        val enabled = mutableListOf<Boolean>()
        val presets = mutableListOf<EqPreset>()
        val bands = mutableListOf<Pair<Int, Int>>()
        val bass = mutableListOf<Int>()
        val virtualizer = mutableListOf<Int>()
        var resets = 0
    }

    private fun show(initial: EqState, hasMedia: Boolean = false): Pair<Calls, (EqState) -> Unit> {
        val calls = Calls()
        var state by mutableStateOf(initial)
        rule.setContent {
            SurcoTheme {
                EqualizerContent(
                    state = state,
                    hasMedia = hasMedia,
                    onBack = {},
                    onEnabled = { calls.enabled += it },
                    onPreset = { calls.presets += it },
                    onBand = { b, l -> calls.bands += b to l },
                    onBassBoost = { calls.bass += it },
                    onVirtualizer = { calls.virtualizer += it },
                    onReset = { calls.resets++ },
                )
            }
        }
        return calls to { s: EqState -> state = s }
    }

    private val full = EqState(
        available = true, enabled = true, bands = bands, preset = EqPreset.FLAT,
        bassBoostAvailable = true, virtualizerAvailable = true,
    )

    @Test
    fun theWholeScreenReactsToTouches() {
        val (calls, update) = show(full)
        rule.onNodeWithText(str(R.string.eq_title)).assertExists()
        rule.onNodeWithTag("eq_switch").assertIsOn()
        (0 until 5).forEach { rule.onNodeWithTag("eq_band_$it").assertExists() }

        // A preset pill.
        rule.onNodeWithText(str(R.string.eq_preset_bass)).performClick()
        assertEquals(listOf(EqPreset.BASS), calls.presets)

        // Dragging the 230 Hz band up raises it.
        rule.onNodeWithTag("eq_band_1").performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        rule.waitForIdle()
        assertTrue(calls.bands.isNotEmpty())
        assertTrue(calls.bands.all { it.first == 1 })
        assertTrue(calls.bands.last().second > 0)

        // Accessibility: set a band directly.
        rule.onNodeWithTag("eq_band_4").performSemanticsAction(SemanticsActions.SetProgress) { it(-600f) }
        assertEquals(4 to -600, calls.bands.last())

        // Knobs.
        rule.onNodeWithTag("eq_bass_boost").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(503f) }
        assertEquals(listOf(500), calls.bass)
        rule.onNodeWithTag("eq_virtualizer").performSemanticsAction(SemanticsActions.SetProgress) { it(1000f) }
        assertEquals(listOf(1000), calls.virtualizer)

        // A custom curve shows "Personalizado" and makes "Restaurar" useful.
        update(full.copy(preset = null, bands = bands.map { it.copy(levelMb = if (it.index == 1) 450 else 0) }))
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(str(R.string.eq_preset_custom)).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(str(R.string.eq_reset)).performClick()
        assertEquals(1, calls.resets)

        // The switch.
        rule.onNodeWithTag("eq_switch").performClick()
        assertEquals(listOf(false), calls.enabled)
    }

    @Test
    fun switchedOffNothingMoves() {
        val (calls, _) = show(full.copy(enabled = false, preset = EqPreset.ROCK))
        rule.onNodeWithTag("eq_switch").assertIsOff()
        rule.onNodeWithText(str(R.string.eq_preset_pop)).performClick()
        rule.onNodeWithTag("eq_band_2").performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        rule.waitForIdle()
        assertTrue(calls.presets.isEmpty())
        assertTrue(calls.bands.isEmpty())
        rule.onNodeWithTag("eq_switch").performClick()
        assertEquals(listOf(true), calls.enabled)
    }

    @Test
    fun withoutAnEqualizerItSaysWhy() {
        show(EqState(available = false, enabled = false))
        rule.onNodeWithText(str(R.string.eq_unavailable_idle)).assertExists()
        rule.onNodeWithTag("eq_band_0").assertDoesNotExist()
        rule.onNodeWithTag("eq_bass_boost").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.eq_effects).uppercase()).assertDoesNotExist()
    }

    @Test
    fun withMusicLoadedTheMissingEqualizerIsThePhones() {
        show(EqState(available = false, bassBoostAvailable = true), hasMedia = true)
        rule.onNodeWithText(str(R.string.eq_unavailable_device)).assertExists()
        rule.onNodeWithTag("eq_bass_boost").assertExists()
        rule.onNodeWithTag("eq_virtualizer").assertDoesNotExist()
    }

    @Test
    fun screenshots() {
        val levels = listOf(450, 120, -100, 250, 420)
        val (_, update) = show(full.copy(preset = EqPreset.ROCK, bands = bands.mapIndexed { i, b -> b.copy(levelMb = levels[i]) }, bassBoost = 450, virtualizer = 200))
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        screenshot("equalizer_rock")
        // Mid-drag: the handle grows and its value floats above it.
        rule.onNodeWithTag("eq_band_3").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -40f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, -60f))
        }
        rule.mainClock.advanceTimeBy(1_000)
        screenshot("equalizer_drag")
        rule.onNodeWithTag("eq_band_3").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000)
        update(full.copy(enabled = false, preset = EqPreset.ROCK, bands = bands.mapIndexed { i, b -> b.copy(levelMb = levels[i]) }, bassBoost = 450, virtualizer = 200))
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        screenshot("equalizer_off")
    }

    /** Best effort PNG into build/screenshots (only with native graphics). */
    private fun screenshot(name: String) {
        runCatching {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }
}
