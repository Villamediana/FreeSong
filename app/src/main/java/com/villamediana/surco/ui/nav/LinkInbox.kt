package com.villamediana.surco.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Something handed to Surco from outside that the Search screen takes care of. */
sealed interface Inbound {
    /** A YouTube/YouTube Music link shared to Surco (or opened from the browser), to resolve. */
    data class Link(val text: String) : Inbound

    /** A voice request ("tocar … no Surco") with nothing in the library for it, to search online. */
    data class Query(val text: String) : Inbound
}

/**
 * What came from outside, waiting for the Search screen. The Search screen calls [consume] once
 * it has taken it.
 */
object LinkInbox {
    private val _pending = MutableStateFlow<Inbound?>(null)
    val pending: StateFlow<Inbound?> = _pending

    fun post(item: Inbound) { _pending.value = item }
    fun consume(): Inbound? = _pending.value.also { _pending.value = null }
}
