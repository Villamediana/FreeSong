package com.villamediana.sound.ui.equalizer

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.R
import com.villamediana.sound.playback.EqBand
import com.villamediana.sound.playback.EqPreset
import com.villamediana.sound.playback.EqState
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var customs = 0
        val bands = mutableListOf<Pair<Int, Int>>()
        val bass = mutableListOf<Int>()
        val virtualizer = mutableListOf<Int>()
        var resets = 0
        val crossfades = mutableListOf<Int>()
    }

    private fun show(initial: EqState, hasMedia: Boolean = false): Pair<Calls, (EqState) -> Unit> {
        val calls = Calls()
        var state by mutableStateOf(initial)
        rule.setContent {
            SoundTheme {
                EqualizerContent(
                    state = state,
                    hasMedia = hasMedia,
                    onBack = {},
                    onEnabled = { calls.enabled += it },
                    onPreset = { calls.presets += it },
                    onCustom = { calls.customs++ },
                    onBand = { b, l -> calls.bands += b to l },
                    onBassBoost = { calls.bass += it },
                    onVirtualizer = { calls.virtualizer += it },
                    onReset = { calls.resets++ },
                    crossfadeSeconds = 5,
                    onCrossfade = { calls.crossfades += it },
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
        rule.onNodeWithText(str(R.string.eq_preset_bass)).performScrollTo().performClick()
        assertEquals(listOf(EqPreset.BASS), calls.presets)

        // Dragging the 230 Hz band up raises it.
        rule.onNodeWithTag("eq_band_1").performScrollTo().performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
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
        rule.onNodeWithText(str(R.string.eq_reset)).performScrollTo().performClick()
        assertEquals(1, calls.resets)

        // The switch.
        rule.onNodeWithTag("eq_switch").performScrollTo().performClick()
        assertEquals(listOf(false), calls.enabled)
    }

    /** A band of [s] at [levelMb]. */
    private fun withBand(s: EqState, band: Int, levelMb: Int) =
        s.copy(bands = s.bands.map { if (it.index == band) it.copy(levelMb = levelMb) else it })

    @Test
    fun aDraggedBandStaysWhereTheFingerLeftIt() {
        // The screen the way the app runs it: every move of the finger is the new state.
        var state by mutableStateOf(full)
        rule.setContent {
            SoundTheme {
                EqualizerContent(
                    state = state,
                    hasMedia = true,
                    onBack = {},
                    onEnabled = {},
                    onPreset = {},
                    onCustom = {},
                    onBand = { b, l -> state = withBand(state, b, l).copy(preset = null) },
                    onBassBoost = {},
                    onVirtualizer = {},
                    onReset = {},
                    crossfadeSeconds = 5,
                    onCrossfade = {},
                )
            }
        }
        val slider = rule.onNodeWithTag("eq_band_1")
        // The finger moves (the screen redrawing between moves), rests, and only then lets go — as on the phone.
        slider.performScrollTo().performTouchInput { down(center) }
        slider.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -80f)) }
        rule.mainClock.advanceTimeBy(100)
        slider.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -60f)) }
        rule.mainClock.advanceTimeBy(500)
        slider.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val level = state.bands[1].levelMb
        assertTrue(level > 0)
        val afterDrag = slider.captureToImage().asAndroidBitmap()

        // Back to flat and up to the same level from outside (as a preset lands): that is how the
        // bar looks at that level — and how it must look right after the finger let go.
        state = withBand(state, 1, 0)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val flat = slider.captureToImage().asAndroidBitmap()
        state = withBand(state, 1, level)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val settled = slider.captureToImage().asAndroidBitmap()

        assertFalse(flat.sameAs(settled))
        assertTrue(afterDrag.sameAs(settled))
    }

    @Test
    fun afterAPresetPersonalizadoBringsTheCustomCurveBack() {
        val (calls, update) = show(full.copy(preset = EqPreset.ROCK, hasCustomCurve = true))
        val custom = rule.onNode(hasText(str(R.string.eq_preset_custom)) and isSelectable())
        val rock = rule.onNode(hasText(str(R.string.eq_preset_rock)) and isSelectable())
        // TalkBack hears which one is on. The row opens on Rock; "Personalizado" leads it.
        rock.assertIsSelected()
        custom.performScrollTo()
        custom.assertIsNotSelected()
        custom.performClick()
        assertEquals(1, calls.customs)

        // On the custom curve the pill is the selected one, and tapping it again does nothing.
        update(full.copy(preset = null, hasCustomCurve = true))
        rule.waitForIdle()
        custom.assertIsSelected()
        custom.performClick()
        assertEquals(1, calls.customs)
        rock.performScrollTo()
        rock.assertIsNotSelected()

        // Without a saved curve there is no pill to go back to.
        update(full.copy(preset = EqPreset.ROCK, hasCustomCurve = false))
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.eq_preset_custom)).assertDoesNotExist()
    }

    @Test
    fun switchedOffNothingMoves() {
        val (calls, _) = show(full.copy(enabled = false, preset = EqPreset.ROCK, hasCustomCurve = true))
        rule.onNodeWithTag("eq_switch").assertIsOff()
        // Disabled pills: no click (and so no press shrink).
        rule.onNodeWithText(str(R.string.eq_preset_pop)).performScrollTo().assertIsNotEnabled().performClick()
        rule.onNodeWithText(str(R.string.eq_preset_custom)).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, calls.customs)
        rule.onNodeWithTag("eq_band_2").performScrollTo().performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        rule.waitForIdle()
        assertTrue(calls.presets.isEmpty())
        assertTrue(calls.bands.isEmpty())
        rule.onNodeWithTag("eq_switch").performScrollTo().performClick()
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

    @Test
    fun crossfadeSecondsLiveWithTheSound() {
        show(full)
        rule.onNodeWithText("Crossfade").performScrollTo()
        rule.onNodeWithText("5 s").assertExists()
        screenshot("equalizer_crossfade")
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
