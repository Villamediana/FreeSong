package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Track
import com.villamediana.sound.smart.TestLibrary.Companion.BASE_TIME
import com.villamediana.sound.smart.TestLibrary.Companion.library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartQueueTest {
    private val taste = neutralTaste()

    /** Random library where no artist has more than half the songs (so no repeats are needed). */
    private fun balancedLibrary(seed: Int, size: Int): List<Track> {
        val r = Random(seed)
        val artists = (1..r.nextInt(3, 12)).map { "Artist $it" }
        return library {
            var left = size
            var i = 0
            while (left > 0) {
                val artist = artists[i % artists.size]
                val albums = listOf("A", "B", null)
                add(artist, genre = listOf("Pop", "Rock", "MPB").random(r), album = albums.random(r), year = 1980 + r.nextInt(40))
                left--
                i++
            }
        }.shuffled(r)
    }

    // ------------------------------------------------------------------ smart shuffle

    @Test
    fun `smart shuffle is a permutation`() {
        repeat(50) { s ->
            val lib = balancedLibrary(s, 1 + s % 40)
            val out = SmartQueue.smartShuffle(lib, taste, s.toLong())
            assertEquals(lib.map { it.id }.sorted(), out.map { it.id }.sorted())
        }
    }

    @Test
    fun `smart shuffle keeps duplicates exactly as many times`() {
        val lib = library { repeat(6) { add("A$it") } }
        val withDupes = lib + lib.take(2)
        val out = SmartQueue.smartShuffle(withDupes, taste, 7)
        assertEquals(withDupes.map { it.id }.sorted(), out.map { it.id }.sorted())
    }

    @Test
    fun `no same artist back to back when avoidable`() {
        repeat(200) { s ->
            val lib = balancedLibrary(s, 6 + s % 60)
            val out = SmartQueue.smartShuffle(lib, taste, s * 31L)
            assertEquals("seed $s", 0, out.artistRepeats())
        }
    }

    @Test
    fun `a dominant artist is interleaved with the minimum possible repeats`() {
        val lib = library {
            repeat(12) { add("Djavan", genre = "MPB") }
            repeat(4) { add("Other $it", genre = "MPB") }
        }
        repeat(30) { s ->
            val out = SmartQueue.smartShuffle(lib, taste, s.toLong())
            // 12 vs 4: at best D o D o D o D o D D D D D D D D → 12 - 4 - 1 = 7 repeats.
            assertEquals(7, out.artistRepeats())
        }
    }

    @Test
    fun `albums are spread apart`() {
        val lib = library {
            listOf("Hits 1", "Hits 2", "Hits 3").forEach { album ->
                repeat(6) { add("Artist $album $it", album = album, albumArtist = "Various Artists") }
            }
        }
        repeat(30) { s ->
            val out = SmartQueue.smartShuffle(lib, taste, s.toLong())
            assertEquals("seed $s", 0, out.albumRepeats())
        }
    }

    @Test
    fun `deterministic by seed and independent of input order`() {
        val lib = balancedLibrary(3, 40)
        val a = SmartQueue.smartShuffle(lib, taste, 99)
        val b = SmartQueue.smartShuffle(lib.reversed(), taste, 99)
        val c = SmartQueue.smartShuffle(lib, taste, 100)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertNotEquals(a.map { it.id }, c.map { it.id })
    }

    @Test
    fun `liked and often finished songs come earlier, skipped and just played later`() {
        val lib = library {
            repeat(40) { i ->
                add(
                    "Artist $i",
                    liked = i < 10,
                    playCount = if (i in 10 until 20) 1 else 0,
                    skipCount = if (i in 20 until 30) 8 else 0,
                )
            }
        }
        val justPlayed = lib.filter { it.id in 31L..40L }.map { it.id }.toSet()
        val t = Taste(recentPlays = lib.filter { it.id in 11L..20L }.associate { it.id to 6 }, justPlayed = justPlayed, now = BASE_TIME)
        val sums = DoubleArray(4)
        repeat(400) { s ->
            val out = SmartQueue.smartShuffle(lib, t, s.toLong())
            out.forEachIndexed { pos, track -> sums[((track.id - 1) / 10).toInt()] += pos.toDouble() }
        }
        val liked = sums[0]
        val finished = sums[1]
        val skipped = sums[2]
        val recent = sums[3]
        assertTrue("liked $liked < skipped $skipped", liked < skipped)
        assertTrue("finished $finished < skipped $skipped", finished < skipped)
        assertTrue("liked $liked < just played $recent", liked < recent)
        assertTrue("finished $finished < just played $recent", finished < recent)
    }

    @Test
    fun `the songs of this moment come earlier, the ones of another later`() {
        val lib = library { repeat(30) { i -> add("Artist $i") } }
        // The first ten are this moment's, the next ten another one's, the rest as always.
        val t = Taste(now = BASE_TIME, fit = lib.take(10).associate { it.id to 2.5 } + lib.subList(10, 20).associate { it.id to 0.4 })
        val sums = DoubleArray(3)
        repeat(400) { s ->
            SmartQueue.smartShuffle(lib, t, s.toLong()).forEachIndexed { pos, track -> sums[((track.id - 1) / 10).toInt()] += pos.toDouble() }
        }
        assertTrue("now ${sums[0]} < always ${sums[2]} < another ${sums[1]}", sums[0] < sums[2] && sums[2] < sums[1])
        // Without it nothing changes.
        assertEquals(SmartQueue.smartShuffle(lib, Taste(now = BASE_TIME), 7), SmartQueue.smartShuffle(lib, Taste(now = BASE_TIME, fit = emptyMap()), 7))
    }

    @Test
    fun `tiny inputs`() {
        assertEquals(emptyList<Track>(), SmartQueue.smartShuffle(emptyList(), taste, 1))
        val one = library { add("A") }
        assertEquals(one, SmartQueue.smartShuffle(one, taste, 1))
    }

    // ------------------------------------------------------------------ radio

    private val rockLibrary = library {
        add("Soda Stereo", genre = "Rock en Español", year = 1988, title = "Seed")
        repeat(5) { add("Soda Stereo", genre = "Rock en Español", year = 1986 + it) }
        repeat(6) { add("Rock Band $it", genre = "Rock", year = 1990 + it) }
        repeat(4) { add("Alt Band $it", genre = "Alternative", year = 2000 + it) }
        add("Gustavo Cerati feat. Soda Stereo", genre = "Rock", year = 1999)
        repeat(10) { add("Reggaeton $it", genre = "Reggaeton", year = 2020) }
        repeat(10) { add("Orchestra $it", genre = "Classical", year = 1800 + it) }
    }

    @Test
    fun `radio starts with the seed, never repeats it, and holds only what is like it`() {
        val seed = rockLibrary.first()
        val related = rockLibrary.filter { Genres.family(it.genre) == GenreFamily.ROCK }.map { it.id }.toSet()
        repeat(20) { s ->
            val radio = SmartQueue.radio(seed, rockLibrary, taste, limit = 50, seedValue = s.toLong())
            assertEquals(seed.id, radio.first().id)
            assertEquals(1, radio.count { it.id == seed.id })
            assertEquals(radio.size, radio.map { it.id }.distinct().size)
            // Every song of the rock family and nothing else: a radio is not filled with the rest of the library.
            assertEquals(related, radio.map { it.id }.toSet())
        }
    }

    @Test
    fun `radio respects the limit and keeps artists varied`() {
        val seed = rockLibrary.first()
        val radio = SmartQueue.radio(seed, rockLibrary, taste, limit = 10, seedValue = 3)
        assertEquals(10, radio.size)
        assertEquals(0, radio.artistRepeats())
    }

    @Test
    fun `radio never plays the same song twice even if downloaded twice`() {
        val lib = library {
            add("Sade", genre = "R&B/Soul", title = "Smooth Operator")
            add("Sade", genre = "R&B/Soul", title = "Smooth Operator")
            add("Sade", genre = "R&B/Soul", title = "By Your Side")
        }
        val radio = SmartQueue.radio(lib[0], lib, taste)
        assertEquals(listOf(lib[0].id, lib[2].id), radio.map { it.id })
    }

    @Test
    fun `radio is deterministic by seed value`() {
        val seed = rockLibrary.first()
        assertEquals(
            SmartQueue.radio(seed, rockLibrary, taste, seedValue = 5).map { it.id },
            SmartQueue.radio(seed, rockLibrary.shuffled(Random(1)), taste, seedValue = 5).map { it.id },
        )
    }

    // ------------------------------------------------------------------ genre

    @Test
    fun `genre session plays the genre, then its family, then neighbours`() {
        val lib = library {
            repeat(5) { add("Pop $it", genre = "Pop") }
            repeat(3) { add("Dance $it", genre = "Dance Pop") }
            repeat(3) { add("House $it", genre = "House") }
            repeat(3) { add("Orchestra $it", genre = "Classical") }
            repeat(3) { add("Jazz $it", genre = "Jazz") }
        }
        repeat(10) { s ->
            val out = SmartQueue.genre("pop", lib, taste, limit = 80, seedValue = s.toLong())
            assertEquals(11, out.size)
            assertTrue(out.take(5).all { it.genre == "Pop" })
            assertTrue(out.drop(5).take(3).all { it.genre == "Dance Pop" })
            assertTrue(out.drop(8).all { it.genre == "House" })
        }
    }

    @Test
    fun `genre matches regardless of accents and case and honours the limit`() {
        val lib = library { repeat(10) { add("Artist $it", genre = "Reggaetón") } }
        assertEquals(10, SmartQueue.genre("reggaeton", lib, taste).size)
        assertEquals(4, SmartQueue.genre("REGGAETÓN", lib, taste, limit = 4).size)
        assertTrue(SmartQueue.genre("", lib, taste).isEmpty())
    }

    // ------------------------------------------------------------------ continuation

    @Test
    fun `continuation fits the end of the queue and skips what is queued`() {
        val queue = rockLibrary.take(3)
        val out = SmartQueue.continuation(queue, rockLibrary, taste, limit = 10, seedValue = 1)
        assertEquals(10, out.size)
        assertTrue(out.none { t -> queue.any { it.id == t.id } })
        assertTrue(out.all { Genres.family(it.genre) == GenreFamily.ROCK })
        assertNotEquals("no same artist right after the queue", artistKey(queue.last().artist), artistKey(out.first().artist))
    }

    @Test
    fun `continuation of an empty queue is a smart shuffle and runs out gracefully`() {
        assertEquals(5, SmartQueue.continuation(emptyList(), rockLibrary, taste, limit = 5).size)
        assertTrue(SmartQueue.continuation(rockLibrary, rockLibrary, taste).isEmpty())
        assertTrue(SmartQueue.continuation(rockLibrary, emptyList(), taste).isEmpty())
    }

    @Test
    fun `continuation without fill brings only what is like the end of the queue`() {
        val rock = rockLibrary.filter { Genres.family(it.genre) == GenreFamily.ROCK }
        val queue = rock.take(3)
        val filled = SmartQueue.continuation(queue, rockLibrary, taste, limit = 60, seedValue = 1)
        val close = SmartQueue.continuation(queue, rockLibrary, taste, limit = 60, seedValue = 1, fill = false)
        // Filling takes the rest of the library when the related run out; without it, the list is shorter.
        assertTrue(filled.any { Genres.family(it.genre) != GenreFamily.ROCK })
        assertTrue(close.isNotEmpty() && close.size < filled.size)
        assertTrue(close.all { Genres.family(it.genre) == GenreFamily.ROCK })
    }
}
