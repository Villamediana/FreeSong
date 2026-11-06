package com.villamediana.surco.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotTest {
    private val full = QueueSnapshot(
        ids = listOf(10, 11, 12, 13),
        index = 2,
        positionMs = 61_500,
        shuffle = true,
        order = listOf(2, 0, 3, 1),
        repeat = RepeatMode.ALL,
        source = QueueSnapshot.SavedSource(PlaySource.Type.ALBUM.name, "Clube da Esquina", "milton\u0001clube da esquina"),
        smart = true,
        savedAt = 1_773_144_000_000,
    )

    @Test
    fun `json round trip`() {
        assertEquals(full, QueueSnapshot.decode(full.encode()))
        val minimal = QueueSnapshot(ids = listOf(1), index = 0)
        assertEquals(minimal, QueueSnapshot.decode(minimal.encode()))
        assertEquals(
            PlaySource(PlaySource.Type.ALBUM, "Clube da Esquina", "milton\u0001clube da esquina"),
            full.source?.toPlaySource(),
        )
    }

    @Test
    fun `garbage and invalid snapshots decode to null`() {
        assertNull(QueueSnapshot.decode(null))
        assertNull(QueueSnapshot.decode(""))
        assertNull(QueueSnapshot.decode("{not json"))
        assertNull(QueueSnapshot.decode("""{"ids":[],"index":0}"""))
        assertNull(QueueSnapshot.decode("""{"ids":[1,2],"index":5}"""))
        // Unknown fields from a newer version are ignored.
        assertEquals(listOf(1L, 2L), QueueSnapshot.decode("""{"ids":[1,2],"index":1,"future":true}""")?.ids)
        assertNull(QueueSnapshot.SavedSource("NOPE", "x").toPlaySource())
    }

    @Test
    fun `retain drops deleted songs and remaps index and order`() {
        // 11 deleted: current (12) moves from index 2 to 1; order 2,0,3,1 → 1,0,2.
        val kept = full.retain(setOf(10, 12, 13))!!
        assertEquals(listOf(10L, 12L, 13L), kept.ids)
        assertEquals(1, kept.index)
        assertEquals(61_500, kept.positionMs)
        assertEquals(listOf(1, 0, 2), kept.order)

        // The current song was deleted: continue from the next one, from its start.
        val gone = full.retain(setOf(10, 11, 13))!!
        assertEquals(listOf(10L, 11L, 13L), gone.ids)
        assertEquals(2, gone.index)
        assertEquals(0, gone.positionMs)

        assertNull(full.retain(emptySet()))
        assertEquals(full, full.retain(setOf(10, 11, 12, 13)))
    }
}
