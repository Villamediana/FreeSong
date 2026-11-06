package com.villamediana.sound.smart

import com.villamediana.sound.smart.TestLibrary.Companion.BASE_TIME
import com.villamediana.sound.smart.TestLibrary.Companion.DAY_MS
import com.villamediana.sound.smart.TestLibrary.Companion.library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A big phone library must still feel instant: these run on the player's thread pool. */
class SmartPerformanceTest {
    private val genres = listOf("Pop", "Rock", "MPB", "Reggaeton", "Hip-Hop/Rap", "Electronic", "Jazz", "Sertanejo", "R&B/Soul", "Indie")
    private val r = Random(1)
    private val big = library {
        repeat(5_000) { i ->
            val artist = "Artist ${r.nextInt(600)}"
            add(
                artist,
                genre = genres[r.nextInt(genres.size)],
                album = "Album ${r.nextInt(3)}",
                year = 1960 + r.nextInt(65),
                liked = r.nextInt(8) == 0,
                playCount = r.nextInt(20),
                skipCount = r.nextInt(5),
                lastPlayedAt = BASE_TIME - r.nextLong(200) * DAY_MS,
                addedAt = BASE_TIME - r.nextLong(400) * DAY_MS,
            )
        }
    }
    private val taste = Taste(recentPlays = big.filter { r.nextInt(5) == 0 }.associate { it.id to r.nextInt(1, 9) }, now = BASE_TIME)

    private fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `five thousand songs`() {
        timed { SmartQueue.smartShuffle(big, taste, 0) } // warm-up
        val shuffleMs = timed {
            val out = SmartQueue.smartShuffle(big, taste, 1)
            assertEquals(big.size, out.size)
            assertEquals(0, out.artistRepeats())
        }
        val radioMs = timed { SmartQueue.radio(big[10], big, taste, 50, 1) }
        val genreMs = timed { SmartQueue.genre("Rock", big, taste, 100, 1) }
        val contMs = timed { SmartQueue.continuation(big.take(300), big, taste, 25, 1) }
        val mixesMs = timed { assertTrue(SmartMixes.build(big, taste, MixLabelsForTests).size >= 10) }
        println("smartShuffle=${shuffleMs}ms radio=${radioMs}ms genre=${genreMs}ms continuation=${contMs}ms mixes=${mixesMs}ms")
        assertTrue("smartShuffle $shuffleMs ms", shuffleMs < 3_000)
        assertTrue("mixes $mixesMs ms", mixesMs < 5_000)
        assertTrue("radio $radioMs ms", radioMs < 1_000)
        assertTrue("genre $genreMs ms", genreMs < 1_000)
        assertTrue("continuation $contMs ms", contMs < 1_000)
    }
}

val MixLabelsForTests = MixLabels(
    genreMix = { "Mix $it" }, genreMixSubtitle = { it }, liked = "L", likedSubtitle = "", onRepeat = "R",
    onRepeatSubtitle = "", rediscover = "D", rediscoverSubtitle = "", fresh = "F", freshSubtitle = "",
    decade = { "Anos $it" }, decadeSubtitle = "", artistMix = { "Mix de $it" }, daily = "Mix do dia", dailySubtitle = "",
)
