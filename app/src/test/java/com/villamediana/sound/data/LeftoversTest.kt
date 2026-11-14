package com.villamediana.sound.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The karaoke's files from 1.32.0 go away; nothing else is touched. */
class LeftoversTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the karaoke's model and songs are deleted, the rest stays`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        File(files, "karaoke-model").mkdirs()
        File(files, "karaoke-model/vocals.onnx").writeBytes(ByteArray(1_000))
        File(files, "karaoke-model/accompaniment.onnx.part").writeBytes(ByteArray(500))
        File(cache, "karaoke").mkdirs()
        File(cache, "karaoke/v_abc.masks").writeBytes(ByteArray(2_000))
        val kept = listOf(File(files, "chord-shifts.json"), File(files, "chords/v_abc.json"), File(cache, "audio/x"))
        kept.forEach { it.parentFile.mkdirs(); it.writeText("x") }

        assertEquals(3_500L, Leftovers.clear(files, cache))
        assertFalse(File(files, "karaoke-model").exists())
        assertFalse(File(cache, "karaoke").exists())
        assertTrue(kept.all { it.isFile })
        // The next time there is nothing to do.
        assertEquals(0L, Leftovers.clear(files, cache))
    }
}
