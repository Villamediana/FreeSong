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
 * 2. ffmpeg decodes it to mono 22 kHz and basic_pitch_frames.py runs the app's model;
 * 3. `hearsRealSongs` runs the app's beat tracker, note creation and chord hearing on it and
 *    reports how much of each song sits on the chords it is known to have.
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
    )

    private data class Song(val slug: String, val query: String, val chords: List<String>)

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
                val r = MonoResampler(44_100, BasicPitch.RATE, antiAlias = filter)
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
        for (song in songs) {
            val pcm = File(dir, "${song.slug}$variant.f32").takeIf { it.isFile } ?: continue
            val audio = floats(pcm)
            val frames = matrix(File(dir, "${song.slug}$variant.frames.f32"))
            val onsets = matrix(File(dir, "${song.slug}$variant.onsets.f32"))
            val t0 = System.nanoTime()
            val beats = BeatTracker.track(audio)
            val t1 = System.nanoTime()
            val options = BasicPitch.Options(minNoteMs = 130f)
            val notes = BasicPitch.eventsToNotes(BasicPitch.notes(frames, onsets, options, BasicPitch.minLen(options)), options)
            val t2 = System.nanoTime()
            val heard = ChordHearing.hear(notes, beats.timesMs)
            val t3 = System.nanoTime()
            val spelling = Spelling.forKey(heard.key)
            val known = song.chords.map { Chord.parse(it)!! }.toSet()
            val total = heard.chords.sumOf { it.endMs - it.startMs }.toDouble()
            val onKnown = heard.chords.filter { it.chord in known }.sumOf { it.endMs - it.startMs } / total
            // Same root and kind (a 7 or m7 where the triad is known counts too).
            val loose = heard.chords.filter { h -> known.any { it.root == h.chord.root && minor(it) == minor(h.chord) } }.sumOf { it.endMs - it.startMs } / total
            println(
                "\n== ${song.slug}: key ${heard.key?.name()} · ${"%.1f".format(beats.bpm)} BPM · ${beats.timesMs.size} beats · ${notes.size} notes · " +
                    "beats ${(t1 - t0) / 1_000_000} ms, notes ${(t2 - t1) / 1_000_000} ms, chords ${(t3 - t2) / 1_000_000} ms",
            )
            println("   on its chords: ${"%.0f".format(onKnown * 100)}% exact, ${"%.0f".format(loose * 100)}% same root and mode")
            println("   " + heard.chords.joinToString(" ") { "${it.chord.name(spelling)}@${"%.1f".format(it.startMs / 1000.0)}" })
        }
    }

    private fun minor(c: Chord) = c.quality == ChordQuality.MINOR || c.quality == ChordQuality.MIN7

    private fun floats(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(bb.remaining()).also { bb.get(it) }
    }

    private fun matrix(f: File): Array<FloatArray> {
        val all = floats(f)
        return Array(all.size / 88) { r -> all.copyOfRange(r * 88, r * 88 + 88) }
    }
}
