package com.villamediana.sound.ui.social

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
class SwipeTabsTest {
    @get:Rule val compose = createComposeRule()

    private var tab by mutableStateOf("Amigos")

    private fun show() {
        compose.setContent {
            SwipeTabsList(
                tabs = listOf("Amigos", "Descobrir"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxSize(),
                header = { item(key = "header") { Text("Header", Modifier.fillMaxWidth().height(300.dp)) } },
            ) { page ->
                items(40) { i -> Text("$page $i", Modifier.fillMaxWidth().height(56.dp)) }
            }
        }
    }

    @Test fun aSidewaysSwipeOnTheListGoesToTheOtherTabAndBack() {
        show()
        compose.onNodeWithText("Amigos 0").assertIsDisplayed()
        compose.onNodeWithTag("swipe-tabs-pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("Descobrir", tab)
        compose.onNodeWithText("Descobrir 0").assertIsDisplayed()
        compose.onNodeWithTag("swipe-tabs-pages").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("Amigos", tab)
        compose.onNodeWithText("Amigos 0").assertIsDisplayed()
    }

    @Test fun aTabChosenElsewhereBringsItsPage() {
        show()
        tab = "Descobrir"
        compose.waitForIdle()
        compose.onNodeWithText("Descobrir 0").assertIsDisplayed()
    }

    @Test fun scrollingUpTheHeaderGoesAwayBeforeTheList() {
        show()
        // A slow drag up (no fling) shorter than the header.
        compose.onNodeWithTag("swipe-tabs-pages").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -100f), delayMillis = 50) }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("swipe-tabs-pages").performTouchInput { up() }
        compose.waitForIdle()
        // The header moved up; the tab's list is still at its first row.
        assertTrue(compose.onNodeWithText("Header").getUnclippedBoundsInRoot().top < (-100).dp)
        compose.onNodeWithText("Amigos 0").assertIsDisplayed()
    }
}
