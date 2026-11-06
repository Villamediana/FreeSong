package com.villamediana.surco.ui.nav

import android.content.Intent

/** What an intent from outside (the share sheet, the browser) asks of Surco. */
internal sealed interface ExternalRequest {
    /** A YouTube / YouTube Music link (or text with one) for the Search screen to resolve. */
    data class OpenLink(val text: String) : ExternalRequest

    companion object {
        fun of(intent: Intent?): ExternalRequest? {
            if (intent == null) return null
            // Android 8–11 reopen a finished task from Recents by replaying the intent that created
            // it: an old share would be opened again.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val text = when (intent.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.dataString
                else -> null
            }
            return text?.takeIf { it.isNotBlank() }?.let(::OpenLink)
        }
    }
}
