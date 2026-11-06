package com.villamediana.surco.playback

import com.villamediana.surco.smart.MixLabels
import com.villamediana.surco.smart.SmartMixes
import com.villamediana.surco.smart.TestLibrary.Companion.library
import com.villamediana.surco.smart.neutralTaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlansTest {
    private val labels = MixLabels(
        genreMix = { "Mix $it" }, genreMixSubtitle = { it }, liked = "L", likedSubtitle = "", onRepeat = "R",
        onRepeatSubtitle = "", rediscover = "D", rediscoverSubtitle = "", fresh = "F", freshSubtitle = "",
        decade = { "Anos $it" }, decadeSubtitle = "", artistMix = { "Mix de $it" }, daily = "Mix do dia", dailySubtitle = "",
    )
    private val strings = PlaybackStrings(labels, "Aleatório inteligente", { "Rádio de $it" }, "Curtidas", "Recentes", { "“$it”" })
    private val lib = library {
        repeat(30) { add("Pop ${it % 6}", genre = "Pop", year = 2015) }
        repeat(12) { add("Rock ${it % 4}", genre = "Rock", year = 1995) }
        repeat(8) { add("Samba ${it % 3}", genre = "Samba", year = 1975) }
    }
    private val taste = neutralTaste()

    @Test
    fun `radio plan starts at the seed and says where it came from`() {
        val seed = lib[35]
        val plan = SmartPlans.plan(SmartSeed.Radio(seed), lib, taste, strings, 1)!!
        assertEquals(seed.id, plan.tracks.first().id)
        assertEquals(0, plan.startIndex)
        assertEquals(PlaySource(PlaySource.Type.RADIO, "Rádio de ${seed.title}", seed.id.toString()), plan.source)
        assertTrue(plan.smart)
        assertTrue(plan.tracks.size <= SmartPlans.RADIO_LIMIT)
    }

    @Test
    fun `library, genre and mix plans`() {
        val all = SmartPlans.plan(SmartSeed.Library, lib, taste, strings, 2)!!
        assertEquals(lib.size, all.tracks.size)
        assertEquals(PlaySource.Type.SMART_SHUFFLE, all.source?.type)

        val genre = SmartPlans.plan(SmartSeed.Genre("Rock"), lib, taste, strings, 2)!!
        assertTrue(genre.tracks.take(12).all { it.genre == "Rock" })
        assertEquals(PlaySource(PlaySource.Type.GENRE, "Rock", "Rock"), genre.source)

        val mix = SmartPlans.plan(SmartSeed.Mix("genre:pop"), lib, taste, strings, 2)!!
        assertEquals(SmartMixes.byId("genre:pop", lib, taste, labels)!!.tracks, mix.tracks)
        assertEquals(PlaySource(PlaySource.Type.MIX, "Mix Pop", "genre:pop"), mix.source)

        assertNull(SmartPlans.plan(SmartSeed.Mix("genre:jazz"), lib, taste, strings, 2))
        assertNull(SmartPlans.plan(SmartSeed.Library, emptyList(), taste, strings, 2))
    }

    @Test
    fun `extension never repeats the queue and keeps the session's character`() {
        val queue = lib.filter { it.genre == "Rock" }.take(5)
        val smartShuffle = SmartPlans.extension(PlaySource(PlaySource.Type.SMART_SHUFFLE, ""), queue, lib, taste, 3)
        assertTrue(smartShuffle.isNotEmpty())
        assertTrue(smartShuffle.none { t -> queue.any { it.id == t.id } })

        val genre = SmartPlans.extension(PlaySource(PlaySource.Type.GENRE, "Rock", "Rock"), queue, lib, taste, 3)
        assertTrue(genre.take(7).all { it.genre == "Rock" })
        assertTrue(genre.none { t -> queue.any { it.id == t.id } })

        val album = SmartPlans.extension(PlaySource(PlaySource.Type.ALBUM, "x"), queue, lib, taste, 3)
        assertEquals(SmartPlans.EXTEND, album.size)
        // The 7 rock songs left come first (the last one may need a separator to avoid an artist repeat).
        assertTrue(album.take(6).all { it.genre == "Rock" })
    }

    @Test
    fun `decade labels as people say them`() {
        assertEquals("90", decadeLabel(1990))
        assertEquals("00", decadeLabel(1900))
        assertEquals("2000", decadeLabel(2000))
        assertEquals("2010", decadeLabel(2010))
    }
}
