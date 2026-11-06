package com.villamediana.surco.ui.nav

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore

/** What an intent from outside (the share sheet, the browser, the Assistant) asks of Surco. */
internal sealed interface ExternalRequest {
    /** A YouTube / YouTube Music link (or text with one) for the Search screen to resolve. */
    data class OpenLink(val text: String) : ExternalRequest

    /** "Ok Google, tocar … no Surco" (MEDIA_PLAY_FROM_SEARCH); an empty query means "play something". */
    data class PlayFromSearch(val query: String) : ExternalRequest

    companion object {
        fun of(intent: Intent?): ExternalRequest? {
            if (intent == null) return null
            // Android 8–11 reopen a finished task from Recents by replaying the intent that created
            // it: an old share would be opened again (an old voice request played again).
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
                return PlayFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty().trim())
            }
            val text = when (intent.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.dataString
                else -> null
            }
            return text?.takeIf { it.isNotBlank() }?.let(::OpenLink)
        }
    }
}
