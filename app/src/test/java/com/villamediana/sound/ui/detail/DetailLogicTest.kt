package com.villamediana.sound.ui.detail

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.data.model.TrackSort
import com.villamediana.sound.playback.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class DetailLogicTest {
    private fun track(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist",
        album: String? = null,
        plays: Int = 0,
        durationMs: Long = 180_000,
        addedAt: Long = 0,
    ) = Track(id = id, title = title, artist = artist, album = album, filePath = "/x/$id.m4a", playCount = plays, durationMs = durationMs, addedAt = addedAt)

    // ------------------------------------------------------------ meta line

    @Test fun metaJoinSkipsBlanksAndTrims() {
        assertEquals("Soda Stereo · 1990 · 12 músicas, 47 min", MetaFormat.join("Soda Stereo", "1990", null, " ", "12 músicas, 47 min"))
        assertEquals("", MetaFormat.join(null, ""))
        assertEquals("Só", MetaFormat.join("  Só  "))
    }

    @Test fun durationReadsNaturally() {
        assertEquals(null, MetaFormat.duration(0))
        assertEquals("0:45", MetaFormat.duration(45_000))
        assertEquals("47 min", MetaFormat.duration(47 * 60_000L + 20_000))
        assertEquals("2 h 14 min", MetaFormat.duration((2 * 60 + 14) * 60_000L))
    }

    @Test fun countAndDurationDropsZeroDuration() {
        assertEquals("12 músicas, 47 min", MetaFormat.countAndDuration("12 músicas", 47 * 60_000L))
        assertEquals("0 músicas", MetaFormat.countAndDuration("0 músicas", 0))
    }

    @Test fun albumHeaderMetaLine() {
        val meta = MetaFormat.join("1990", MetaFormat.countAndDuration("12 músicas", 47 * 60_000L))
        assertEquals("Soda Stereo · 1990 · 12 músicas, 47 min", MetaFormat.join("Soda Stereo", meta))
    }

    @Test fun topArtistsByFrequencyThenName() {
        val tracks = listOf(
            track(1, artist = "Cerati"), track(2, artist = "Soda Stereo"), track(3, artist = "Soda Stereo"),
            track(4, artist = "Babasónicos"), track(5, artist = "Cerati"), track(6, artist = "Soda Stereo"),
        )
        assertEquals(listOf("Soda Stereo", "Cerati"), MetaFormat.topArtists(tracks))
        assertEquals(listOf("Soda Stereo", "Cerati", "Babasónicos"), MetaFormat.topArtists(tracks, 5))
    }

    @Test fun decadeAsPeopleSayIt() {
        assertEquals("90", MetaFormat.decade(1990))
        assertEquals("60", MetaFormat.decade(1960))
        assertEquals("2000", MetaFormat.decade(2000))
        assertEquals("2010", MetaFormat.decade(2010))
        assertEquals("80", MetaFormat.decade(80))
        assertEquals("1900", MetaFormat.decade(1900)) // "Anos 00" would read as the 2000s
    }

    // ------------------------------------------------------------ A–Z index

    @Test fun letterFoldsAccentsAndPunctuation() {
        assertEquals("A", ScrollIndex.letter("Ánimo"))
        assertEquals("H", ScrollIndex.letter("¡Hola!"))
        assertEquals("E", ScrollIndex.letter("  él"))
        assertEquals("N", ScrollIndex.letter("Ñandú"))
        assertEquals("#", ScrollIndex.letter("1979"))
        assertEquals("#", ScrollIndex.letter("Ωmega"))
        assertEquals("#", ScrollIndex.letter(""))
        assertEquals("#", ScrollIndex.letter(null))
        assertEquals("#", ScrollIndex.letter("..."))
    }

    @Test fun bubbleLabelFollowsTheSort() {
        val t = track(1, title = "Zoom", artist = "Soda Stereo", album = "Sueño Stereo", plays = 12, durationMs = 252_000,
            addedAt = LocalDate.of(2026, 11, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val utc = ZoneId.of("UTC")
        assertEquals("Z", ScrollIndex.label(t, TrackSort.TITLE, Locale.ROOT, utc))
        assertEquals("S", ScrollIndex.label(t, TrackSort.ARTIST, Locale.ROOT, utc))
        assertEquals("S", ScrollIndex.label(t, TrackSort.ALBUM, Locale.ROOT, utc))
        assertEquals("#", ScrollIndex.label(t.copy(album = null), TrackSort.ALBUM, Locale.ROOT, utc))
        assertEquals("12×", ScrollIndex.label(t, TrackSort.MOST_PLAYED, Locale.ROOT, utc))
        assertEquals("4:12", ScrollIndex.label(t, TrackSort.DURATION, Locale.ROOT, utc))
        val month = ScrollIndex.label(t, TrackSort.RECENT, Locale.forLanguageTag("es"), utc)
        assertTrue(month, month.startsWith("nov") && month.endsWith("2026"))
    }

    @Test fun firstIndexByLabel() {
        val labels = listOf("#", "A", "A", "B", "D", "D", "D", "Z")
        assertEquals(mapOf("#" to 0, "A" to 1, "B" to 3, "D" to 4, "Z" to 7), ScrollIndex.firstIndexByLabel(labels))
        assertEquals(listOf("#", "A", "B", "D", "Z"), ScrollIndex.firstIndexByLabel(labels).keys.toList())
    }

    @Test fun thumbFractionAndIndexAreInverse() {
        assertEquals(0, ScrollIndex.indexAt(0f, 100))
        assertEquals(99, ScrollIndex.indexAt(1f, 100))
        assertEquals(50, ScrollIndex.indexAt(0.505f, 100))
        assertEquals(0, ScrollIndex.indexAt(-3f, 100))
        assertEquals(99, ScrollIndex.indexAt(7f, 100))
        assertEquals(0, ScrollIndex.indexAt(0.5f, 0))
        assertEquals(0f, ScrollIndex.fractionOf(0, 100, 10), 0f)
        assertEquals(1f, ScrollIndex.fractionOf(90, 100, 10), 0f)
        assertEquals(0.5f, ScrollIndex.fractionOf(45, 100, 10), 0.0001f)
        assertEquals(0f, ScrollIndex.fractionOf(3, 8, 10), 0f) // everything fits
    }

    // ------------------------------------------------------------ header collapse

    @Test fun scrolledIsInfiniteOnceHeaderLeaves() {
        assertEquals(120f, HeaderMath.scrolled(0, 120), 0f)
        assertTrue(HeaderMath.scrolled(1, 0).isInfinite())
        assertEquals(0f, HeaderMath.scrolled(0, -5), 0f)
    }

    @Test fun fractionClampsAndHandlesEmptyRange() {
        assertEquals(0f, HeaderMath.fraction(0f, 400f), 0f)
        assertEquals(0.5f, HeaderMath.fraction(200f, 400f), 0f)
        assertEquals(1f, HeaderMath.fraction(900f, 400f), 0f)
        assertEquals(1f, HeaderMath.fraction(Float.POSITIVE_INFINITY, 400f), 0f)
        assertEquals(0f, HeaderMath.fraction(0f, 0f), 0f)
        assertEquals(1f, HeaderMath.fraction(1f, 0f), 0f)
    }

    @Test fun artParallaxScaleAndFade() {
        assertEquals(50f, HeaderMath.artTranslation(100f, 300f), 0f)
        assertEquals(300f, HeaderMath.artTranslation(10_000f, 300f), 0f)
        assertEquals(300f, HeaderMath.artTranslation(Float.POSITIVE_INFINITY, 300f), 0f)
        assertEquals(1f, HeaderMath.artScale(0f), 0f)
        assertEquals(0.86f, HeaderMath.artScale(1f), 0.0001f)
        assertEquals(1f, HeaderMath.artAlpha(0f), 0f)
        assertEquals(0f, HeaderMath.artAlpha(1f), 0f)
        assertTrue(HeaderMath.artAlpha(0.5f) in 0.4f..0.5f)
    }

    @Test fun barFadesInBeforeTheTitleSlidesUnder() {
        // Title bottom at 700 px in the header, bar bottom at 200 px → solid once scrolled 500 px.
        val lead = 100f
        assertEquals(0f, HeaderMath.barAlpha(0f, 700f, 200f, lead), 0f)
        assertEquals(0f, HeaderMath.barAlpha(400f, 700f, 200f, lead), 0f)
        assertEquals(0.5f, HeaderMath.barAlpha(450f, 700f, 200f, lead), 0.0001f)
        assertEquals(1f, HeaderMath.barAlpha(500f, 700f, 200f, lead), 0f)
        assertEquals(1f, HeaderMath.barAlpha(Float.POSITIVE_INFINITY, 700f, 200f, lead), 0f)
        assertEquals(0f, HeaderMath.barAlpha(300f, Float.POSITIVE_INFINITY, 200f, lead), 0f) // not measured yet
    }

    @Test fun compactTitleAppearsAfterTheBigOneIsCovered() {
        val fade = 40f
        assertEquals(0f, HeaderMath.titleAlpha(499f, 700f, 200f, fade), 0f)
        assertEquals(0.5f, HeaderMath.titleAlpha(520f, 700f, 200f, fade), 0.0001f)
        assertEquals(1f, HeaderMath.titleAlpha(600f, 700f, 200f, fade), 0f)
        assertEquals(1f, HeaderMath.titleAlpha(Float.POSITIVE_INFINITY, 700f, 200f, fade), 0f)
        assertEquals(0f, HeaderMath.titleAlpha(10_000f, Float.POSITIVE_INFINITY, 200f, fade), 0f)
    }

    // ------------------------------------------------------------ sources

    @Test fun sameCollectionIsRecognised() {
        assertTrue(Sources.matches(Sources.album("Soda Stereo", "Canción Animal"), Sources.album("soda stereo ", "canción animal")))
        assertFalse(Sources.matches(Sources.album("Soda Stereo", "Signos"), Sources.album("Soda Stereo", "Doble Vida")))
        assertTrue(Sources.matches(Sources.playlist(7, "Viaje"), Sources.playlist(7, "Viaje renombrada")))
        assertFalse(Sources.matches(Sources.playlist(7, "Viaje"), Sources.playlist(8, "Viaje")))
        assertFalse(Sources.matches(Sources.artist("Cerati"), Sources.genre("Cerati")))
        assertFalse(Sources.matches(null, Sources.liked("Curtidas")))
    }

    @Test fun smartSessionsMatchByTitleToo() {
        // The player may label a genre session with its own id format; the title still matches.
        assertTrue(Sources.matches(PlaySource(PlaySource.Type.GENRE, "Rock", "genre:rock"), Sources.genre("rock")))
        assertTrue(Sources.matches(PlaySource(PlaySource.Type.GENRE, "Rock", null), Sources.genre("Rock")))
        assertTrue(Sources.matches(PlaySource(PlaySource.Type.MIX, "Mix do dia", "daily"), Sources.mix("daily", "Mix do dia")))
        assertTrue(Sources.matches(PlaySource(PlaySource.Type.MIX, "Mix do dia", null), Sources.mix("daily", "Mix do dia")))
        // …but albums with different ids don't, even with the same title.
        assertFalse(Sources.matches(PlaySource(PlaySource.Type.ALBUM, "Greatest Hits", "a\u0001greatest hits"), Sources.album("B", "Greatest Hits")))
    }

    @Test fun aCollectionIsWhatPlaysOnlyOnOneOfItsSongsAsItIsNow() {
        val mix = Sources.mix("dna:moment", "Pra agora")
        val today = listOf(track(1), track(2))
        // Playing from it, on one of its songs: its Play pauses/resumes.
        assertTrue(Sources.playingFrom(track(2), mix, mix, today))
        // The same downloaded song by its YouTube id counts too.
        assertTrue(Sources.playingFrom(track(9).copy(videoId = "yt1"), mix, mix, listOf(track(1).copy(videoId = "yt1"))))
        // Remade since (the moment changed), a queue kept from before the app closed, or the songs
        // after it ended: the song isn't one of its songs now → its Play starts it.
        assertFalse(Sources.playingFrom(track(7), mix, mix, today))
        // Another collection, or nothing playing.
        assertFalse(Sources.playingFrom(track(1), Sources.mix("daily", "Mix do dia"), mix, today))
        assertFalse(Sources.playingFrom(null, mix, mix, today))
    }

    // ------------------------------------------------------------ ordering

    @Test fun artistPlayOrderIsTopFirstWithoutRepeats() {
        val all = (1L..6L).map { track(it) }
        val top = listOf(all[4], all[1])
        assertEquals(listOf(5L, 2L, 1L, 3L, 4L, 6L), PlayOrder.artist(top, all).map { it.id })
    }

    @Test fun moveOneElement() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PlayOrder.move(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PlayOrder.move(l, 3, 0))
        assertEquals(l, PlayOrder.move(l, 1, 1))
        assertEquals(l, PlayOrder.move(l, -1, 2))
        assertEquals(l, PlayOrder.move(l, 0, 9))
    }

    @Test fun applyLocalOrderKeepsUnknownsAtTheEnd() {
        val items = listOf(10L, 20L, 30L, 40L)
        assertEquals(items, PlayOrder.applyOrder(items, null) { it })
        assertEquals(listOf(30L, 10L, 40L, 20L), PlayOrder.applyOrder(items, listOf(30L, 10L, 40L, 20L)) { it })
        // 50 is new (not in the local order yet) and 20 was removed from the order: both trail, in source order.
        assertEquals(listOf(40L, 10L, 30L, 20L, 50L), PlayOrder.applyOrder(items + 50L, listOf(40L, 10L, 30L)) { it })
    }

    @Test fun reorderTargetCrossesHalfARow() {
        // Rows of 192 px; the dragged row takes the next slot once it passed half of it.
        assertEquals(0, ReorderMath.targetIndex(0, 95f, 192f, 4))
        assertEquals(1, ReorderMath.targetIndex(0, 97f, 192f, 4))
        assertEquals(2, ReorderMath.targetIndex(0, 396f, 192f, 4))
        assertEquals(3, ReorderMath.targetIndex(0, 5_000f, 192f, 4))
        assertEquals(0, ReorderMath.targetIndex(2, -500f, 192f, 4))
        assertEquals(1, ReorderMath.targetIndex(3, -400f, 192f, 4))
        assertEquals(2, ReorderMath.targetIndex(2, 10f, 0f, 4))
        assertEquals(0, ReorderMath.targetIndex(0, 10f, 192f, 0))
    }

    @Test fun shuffleStartIsStablePerSeedAndInRange() {
        repeat(50) { seed ->
            val i = PlayOrder.shuffleStart(12, seed.toLong())
            assertTrue(i in 0 until 12)
            assertEquals(i, PlayOrder.shuffleStart(12, seed.toLong()))
        }
        assertEquals(0, PlayOrder.shuffleStart(0, 1))
    }
}
