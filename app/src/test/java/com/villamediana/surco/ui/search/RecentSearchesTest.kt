package com.villamediana.surco.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test
    fun newestFirstWithoutRepeats() {
        var list = emptyList<String>()
        list = RecentSearches.add(list, "soda stereo")
        list = RecentSearches.add(list, "Cerati")
        list = RecentSearches.add(list, "  Soda   Stereo ")
        assertEquals(listOf("Soda Stereo", "Cerati"), list)
    }

    @Test
    fun blankIsIgnored() {
        assertEquals(listOf("a"), RecentSearches.add(listOf("a"), "   "))
    }

    @Test
    fun keepsAtMostTwelve() {
        var list = emptyList<String>()
        (1..20).forEach { list = RecentSearches.add(list, "q$it") }
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("q20", list.first())
        assertEquals("q9", list.last())
    }

    @Test
    fun removeIgnoresCase() {
        assertEquals(listOf("b"), RecentSearches.remove(listOf("Alpha", "b"), "alpha "))
    }

    @Test
    fun jsonRoundTripAndGarbage() {
        val list = listOf("Mercedes Sosa", "\"aspas\" e acentuação", "日本")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
        assertEquals(emptyList<String>(), RecentSearches.decode(""))
        assertEquals(emptyList<String>(), RecentSearches.decode("{not json"))
    }
}
