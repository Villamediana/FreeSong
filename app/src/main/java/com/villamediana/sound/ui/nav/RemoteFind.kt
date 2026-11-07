package com.villamediana.sound.ui.nav

import com.villamediana.sound.online.CollectionKind

/**
 * An online page opened by name (the artist or album of the song playing): the route carries a
 * placeholder url and the page finds the real one on YouTube Music while it shows its skeleton.
 */
object RemoteFind {
    private const val PREFIX = "find:"

    fun url(kind: CollectionKind, title: String, author: String?): String = PREFIX + kind.name + ":" + author.orEmpty() + "/" + title

    fun isFind(url: String): Boolean = url.startsWith(PREFIX)
}
