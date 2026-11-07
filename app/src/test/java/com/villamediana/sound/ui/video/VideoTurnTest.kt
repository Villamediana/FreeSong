package com.villamediana.sound.ui.video

import android.app.Activity
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The video opens sideways, and the rotate button turns it the other way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoTurnTest {
    @Test
    fun opensSidewaysAndTheButtonTurnsItUpright() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val turn = VideoTurn(activity)
        turn.start()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)

        // On screen upright (the phone not turned yet): the button asks for sideways again;
        // shown sideways, it asks for upright.
        activity.resources.configuration.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        turn.flip()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        activity.resources.configuration.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
        turn.flip()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        turn.stop()
    }
}
