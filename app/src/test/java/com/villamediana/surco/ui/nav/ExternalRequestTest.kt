package com.villamediana.surco.ui.nav

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What MainActivity takes from the intents that open it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalRequestTest {
    private val shared = "Olha essa https://music.youtube.com/watch?v=dQw4w9WgXcQ"

    private fun share(text: String?) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    @Test
    fun aSharedOrOpenedLinkGoesToSearch() {
        assertEquals(ExternalRequest.OpenLink(shared), ExternalRequest.of(share(shared)))
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(ExternalRequest.OpenLink("https://youtu.be/dQw4w9WgXcQ"), ExternalRequest.of(view))
    }

    @Test
    fun theLauncherAndEmptySharesAskNothing() {
        assertNull(ExternalRequest.of(null))
        assertNull(ExternalRequest.of(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertNull(ExternalRequest.of(share(" ")))
        assertNull(ExternalRequest.of(share(null)))
    }

    @Test
    fun reopeningFromRecentsDoesNotReplayTheOldShare() {
        // Android 8–11: the task's first intent (the share) comes back with this flag.
        assertNull(ExternalRequest.of(share(shared).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }
}
