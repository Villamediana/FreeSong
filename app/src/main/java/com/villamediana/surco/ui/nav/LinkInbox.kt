package com.villamediana.surco.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A YouTube/YouTube Music link shared to Surco (or opened from the browser), waiting for the
 * Search screen to resolve it. The Search screen calls [consume] once it has taken it.
 */
object LinkInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun post(text: String) { _pending.value = text }
    fun consume(): String? = _pending.value.also { _pending.value = null }
}
