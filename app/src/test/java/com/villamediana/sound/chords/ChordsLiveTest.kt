package com.villamediana.sound.chords

import com.villamediana.sound.online.NewPipeHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * The chords of real songs, end to end, on this machine (see tools/chords/README.md):
 * 1. `fetchesSongs` downloads their audio (SOUND_LIVE=1, SOUND_CHORDS_DIR=dir);
 * 2. ffmpeg decodes it (and `resamplesLikeThePhone` brings it to 22 kHz as the phone does);
 * 3. `hearsRealSongs` runs the app's hearing (Cqt, Btc, ChordReading) on it and reports how much of
 *    each song sits on the chords it is known to have.
 */
class ChordsLiveTest {
    private val dir = System.getProperty("sound.chords.dir").orEmpty()

    /** Songs whose chords every player knows (as they sound, no capo). */
    private val songs = listOf(
        Song("let-it-be", "Let It Be The Beatles", listOf("C", "G", "Am", "F", "Em", "Dm")),
        Song("stand-by-me", "Stand By Me Ben E. King", listOf("A", "F#m", "D", "E")),
        // Played Am G C F with a capo on the first fret.
        Song("riptide", "Riptide Vance Joy", listOf("Bbm", "Ab", "Db", "Gb", "Ebm", "Fm")),
        Song("perfect", "Perfect Ed Sheeran", listOf("Ab", "Fm", "Db", "Eb", "Cm", "Bbm")),
        // A sheet from ukulele-tabs.com that Miguel sent: intro and the three verses it writes out.
        Song(
            "si-usted-la-viera", "Si Usted la Viera Jorge Luis Chacin", listOf("Bb", "Bbm", "Eb", "Ebm", "F", "Gm7", "Gb7", "Gm", "Cm7", "Fm7"),
            sheet = "Bb Bbm Eb Ebm | Bb F Ebm Gm7 Gb7 Bb F | Bb F Gm Gm7 Gb7 Bb F | Bb F Gm Gm7 Cm7 Ebm F Bb Gm Fm7 Cm7 Eb F Bb",
        ),
    )

    /** [sheet]: the chords in order as a sheet writes them (bars "|" between sections), to compare one by one. */
    private data class Song(val slug: String, val query: String, val chords: List<String>, val sheet: String? = null)

    @Test fun fetchesSongs() {
        assumeTrue(System.getProperty("sound.live") == "1" && dir.isNotBlank())
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale("pt", "BR"))
        val yt = ServiceList.YouTube
        for (song in songs) {
            val out = File(dir, "${song.slug}.m4a")
            if (out.isFile && out.length() > 500_000) continue
            val handler = yt.searchQHFactory.fromQuery(song.query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
            val first = yt.getSearchExtractor(handler).also { it.fetchPage() }.initialPage.items.filterIsInstance<StreamInfoItem>().first()
            val info = StreamInfo.getInfo(yt, first.url)
            val stream = info.audioStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
                .maxBy { it.averageBitrate }
            val length = stream.itagItem?.contentLength?.takeIf { it > 0 } ?: error("no length for ${song.slug}")
            out.outputStream().use { sink ->
                var at = 0L
                while (at < length) {
                    val end = minOf(at + 1_000_000, length) - 1
                    val req = Request.Builder().url(stream.content).header("Range", "bytes=$at-$end").header("User-Agent", NewPipeHttp.USER_AGENT).build()
                    http.newCall(req).execute().use { r -> sink.write(r.body.bytes()) }
                    at = end + 1
                }
            }
            println("${song.slug}: ${first.name} — ${first.uploaderName}, ${out.length()} bytes")
        }
    }

    /**
     * The audio as the phone gets it: [AudioDecoder] hands over the decoder's own rate, and
     * [MonoResampler] brings it to 22 kHz. From `<song>.44100.f32` (ffmpeg -ac 1, native rate) to
     * `<song>.phone.f32`, and `<song>.nofilter.f32` without the low-pass, to compare
     * (SOUND_CHORDS_VARIANT=.phone for hearsRealSongs).
     */
    @Test fun resamplesLikeThePhone() {
        assumeTrue(dir.isNotBlank())
        for (song in songs) {
            val native = File(dir, "${song.slug}.44100.f32").takeIf { it.isFile } ?: continue
            val audio = floats(native)
            for ((name, filter) in listOf("phone" to true, "nofilter" to false)) {
                val r = MonoResampler(44_100, Cqt.RATE, antiAlias = filter)
                val out = java.io.ByteArrayOutputStream()
                // In decoder-sized blocks, as the phone does.
                var at = 0
                while (at < audio.size) {
                    val n = minOf(2048, audio.size - at)
                    val res = r.push(audio.copyOfRange(at, at + n))
                    val bb = ByteBuffer.allocate(res.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(res)
                    out.write(bb.array())
                    at += n
                }
                File(dir, "${song.slug}.$name.f32").writeBytes(out.toByteArray())
            }
        }
    }

    @Test fun hearsRealSongs() {
        assumeTrue(dir.isNotBlank())
        val variant = System.getProperty("sound.chords.variant").orEmpty()
        val model = Btc(ByteBuffer.wrap(File("src/main/assets/chords/btc_voca.bin").readBytes()))
        for (song in songs) {
            val pcm = File(dir, "${song.slug}$variant.f32").takeIf { it.isFile } ?: continue
            val audio = floats(pcm)
            val t0 = System.nanoTime()
            val beats = BeatTracker.track(audio)
            val t1 = System.nanoTime()
            val features = Cqt.features(audio)
            val logp = model.logProbs(features)
            val t2 = System.nanoTime()
            val heard = ChordReading.chords(logp)
            val key = ChordReading.key(heard)
            val spelling = Spelling.forKey(key)
            val known = song.chords.map { Chord.parse(it)!! }.toSet()
            val total = heard.sumOf { it.endMs - it.startMs }.toDouble()
            val onKnown = heard.filter { it.chord in known }.sumOf { it.endMs - it.startMs } / total
            // Same root and kind (a 7 or m7 where the triad is known counts too).
            val loose = heard.filter { h -> known.any { it.root == h.root && minor(it) == minor(h.chord) } }.sumOf { it.endMs - it.startMs } / total
            println(
                "\n== ${song.slug}: key ${key?.name()} · ${"%.1f".format(beats.bpm)} BPM · ${heard.size} chords · " +
                    "beats ${(t1 - t0) / 1_000_000} ms, chords ${(t2 - t1) / 1_000_000} ms",
            )
            println("   on its chords: ${"%.0f".format(onKnown * 100)}% exact, ${"%.0f".format(loose * 100)}% same root and mode")
            println("   " + heard.joinToString(" ") { "${it.chord.name(spelling)}@${"%.1f".format(it.startMs / 1000.0)}" })
            song.sheet?.let { compare(it, heard, spelling) }
        }
    }

    /**
     * The sheet's chords against what was heard, one by one: aligned in order (Needleman–Wunsch;
     * what is heard after the sheet's part is free), exact, same root and mode (Gm7 for Gm), or not.
     */
    private fun compare(sheet: String, heard: List<TimedChord>, spelling: Spelling) {
        val want = sheet.split(" ").filter { it.isNotBlank() && it != "|" }.map { Chord.parse(it)!! }
        val got = heard.map { it.chord }
        fun score(a: Chord, b: Chord) = when {
            a == b -> 2
            a.root == b.root && minor(a) == minor(b) -> 1
            else -> -1
        }
        val gap = -1
        val n = want.size
        val m = got.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) dp[i][0] = dp[i - 1][0] + gap
        for (j in 1..m) dp[0][j] = dp[0][j - 1] + gap
        for (i in 1..n) for (j in 1..m) dp[i][j] = maxOf(dp[i - 1][j - 1] + score(want[i - 1], got[j - 1]), dp[i - 1][j] + gap, dp[i][j - 1] + gap)
        // The sheet covers the start of the song: it may end anywhere in what was heard.
        var j = (0..m).maxBy { dp[n][it] }
        var i = n
        val rows = ArrayList<String>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + score(want[i - 1], got[j - 1])) {
                val a = want[i - 1]; val b = heard[j - 1]
                val mark = when (score(a, b.chord)) { 2 -> "✓ igual"; 1 -> "≈ mesma base"; else -> "✗ outro" }
                rows += "%-5s  %-5s @%5.1fs  %s".format(a.name(spelling), b.chord.name(spelling), b.startMs / 1000.0, mark)
                i--; j--
            } else if (i > 0 && (j == 0 || dp[i][j] == dp[i - 1][j] + gap)) {
                rows += "%-5s  %-13s  %s".format(want[i - 1].name(spelling), "—", "✗ não ouvido")
                i--
            } else {
                val b = heard[j - 1]
                rows += "%-5s  %-5s @%5.1fs  %s".format("—", b.chord.name(spelling), b.startMs / 1000.0, "+ a mais")
                j--
            }
        }
        rows.reverse()
        println("   cifra  ouvido   ")
        rows.forEach { println("   $it") }
        val exact = rows.count { "✓" in it }
        val close = rows.count { "≈" in it }
        println("   ${want.size} acordes da cifra: $exact iguais, $close mesma base, ${want.size - exact - close} diferentes ou não ouvidos; ${rows.count { "+ a mais" in it }} a mais no meio")
    }

    private fun minor(c: Chord) = c.quality in setOf(ChordQuality.MINOR, ChordQuality.MIN7, ChordQuality.MIN6, ChordQuality.MIN_MAJ7, ChordQuality.MIN_ADD9, ChordQuality.DIM, ChordQuality.DIM7, ChordQuality.HALF_DIM)

    private fun floats(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(bb.remaining()).also { bb.get(it) }
    }
}
