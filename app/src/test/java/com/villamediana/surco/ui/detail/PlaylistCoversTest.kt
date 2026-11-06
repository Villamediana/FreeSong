package com.villamediana.surco.ui.detail

import com.villamediana.surco.data.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCoversTest {
    @Test fun ownedCoversAreRecognisedByName() {
        val name = Storage.safe(PlaylistCovers.fileName(12, 1_730_000_000_000)) + ".jpg"
        assertTrue(PlaylistCovers.isOwned("/data/Music/surco/covers/$name", 12))
        assertFalse(PlaylistCovers.isOwned("/data/Music/surco/covers/$name", 1))
        assertFalse(PlaylistCovers.isOwned("/data/Music/surco/covers/abc123.jpg", 12))
        assertFalse(PlaylistCovers.isOwned(null, 12))
    }

    @Test fun choicesPutTheCurrentCustomCoverFirst() {
        assertEquals(listOf("a", "b"), PlaylistCovers.choices(null, listOf("a", "b", "a")))
        assertEquals(listOf("a", "b"), PlaylistCovers.choices("b", listOf("a", "b")))
        assertEquals(listOf("custom", "a", "b"), PlaylistCovers.choices("custom", listOf("a", "b")))
    }
}
