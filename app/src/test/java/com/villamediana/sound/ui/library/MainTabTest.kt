package com.villamediana.sound.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The "Aba Início" setting: which pills show and where the app opens. */
class MainTabTest {
    @Test fun withInicioOnItIsThereAndTheAppOpensOnIt() {
        assertEquals(MainTab.entries, MainTab.visible(showHome = true))
        assertEquals(MainTab.HOME, MainTab.start(showHome = true))
    }

    @Test fun withInicioOffThePillIsGoneAndTheAppOpensOnTheSongs() {
        assertFalse(MainTab.HOME in MainTab.visible(showHome = false))
        assertEquals(MainTab.SONGS, MainTab.visible(showHome = false).first())
        assertEquals(MainTab.SONGS, MainTab.start(showHome = false))
    }
}
