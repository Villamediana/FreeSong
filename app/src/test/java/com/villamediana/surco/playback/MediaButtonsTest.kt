package com.villamediana.surco.playback

import android.app.Application
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Headset play with the app closed: the service only starts when there is a queue to resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaButtonsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun pressPlay() {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        SurcoMediaButtonReceiver().onReceive(context, intent)
    }

    @Test fun nothingSavedDoesNotStartTheService() {
        ResumableQueue.set(context, false)
        pressPlay()
        assertNull(shadowOf(context).peekNextStartedService())
    }

    @Test fun aSavedQueueStartsThePlaybackService() {
        ResumableQueue.set(context, true)
        pressPlay()
        val started = shadowOf(context).peekNextStartedService()
        assertNotNull(started)
        assertEquals(PlaybackService::class.java.name, started!!.component!!.className)
    }
}
