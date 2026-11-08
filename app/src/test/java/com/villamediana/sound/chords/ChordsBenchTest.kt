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
import kotlin.math.abs

/**
 * The chord benchmark (tools/chords/bench/README.md): songs of the McGill Billboard dataset, whose
 * chords musicians marked second by second, heard the way the phone hears them.
 * 1. `fetches` downloads each song of manifest.tsv (SOUND_LIVE=1, SOUND_BENCH_DIR=dir), the
 *    recording whose length matches the annotated one;
 * 2. ffmpeg decodes it at its own rate, `resamples` brings it to 22 kHz like the phone;
 * 3. `hears` writes what the app hears as `<id>.ours.lab`, for evaluate.py to score.
 */
class ChordsBenchTest {
    private val dir = System.getProperty("sound.bench.dir").orEmpty()

    @Test fun fetches() {
        assumeTrue(System.getProperty("sound.live") == "1" && dir.isNotBlank())
        val http = OkHttpClient()
        NewPipeHttp.init(http, Locale.forLanguageTag("en-US"))
        val yt = ServiceList.YouTube
        val log = File(dir, "fetched.tsv")
        for (line in File(dir, "manifest.tsv").readLines()) {
            val (id, title, artist, seconds) = line.split("\t")
            val out = File(dir, "$id.m4a")
            if (out.isFile && out.length() > 300_000) continue
            if (File(dir, "$id.missing").isFile) continue
            runCatching {
                val handler = yt.searchQHFactory.fromQuery("$title ${artist.replace(",", " ")}", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
                val items = yt.getSearchExtractor(handler).also { it.fetchPage() }.initialPage.items.filterIsInstance<StreamInfoItem>().take(6)
                // The same recording: the length the annotation has, give or take a few seconds.
                val item = items.firstOrNull { abs(it.duration - seconds.toDouble()) <= 4.0 }
                if (item == null) {
                    File(dir, "$id.missing").writeText(items.joinToString("\n") { "${it.name} — ${it.uploaderName} ${it.duration}s" })
                    println("$id $title: no recording of ${seconds}s")
                    return@runCatching
                }
                val info = StreamInfo.getInfo(yt, item.url)
                val stream = info.audioStreams
                    .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
                    .maxBy { it.averageBitrate }
                val length = stream.itagItem?.contentLength?.takeIf { it > 0 } ?: error("no length")
                out.outputStream().use { sink ->
                    var at = 0L
                    while (at < length) {
                        val end = minOf(at + 1_000_000, length) - 1
                        val req = Request.Builder().url(stream.content).header("Range", "bytes=$at-$end").header("User-Agent", NewPipeHttp.USER_AGENT).build()
                        http.newCall(req).execute().use { r -> sink.write(r.body.bytes()) }
                        at = end + 1
                    }
                }
                log.appendText("$id\t${item.name}\t${item.uploaderName}\t${item.duration}\n")
                println("$id $title: ${item.name} — ${item.uploaderName} (${item.duration}s)")
            }.onFailure {
                out.delete()
                println("$id $title: failed ${it.message}")
            }
        }
    }

    /** `<id>.44100.f32` (ffmpeg -ac 1 at the file's own rate, 44.1 kHz) to `<id>.phone.f32`, as the phone resamples. */
    @Test fun resamples() {
        assumeTrue(dir.isNotBlank())
        for (native in File(dir).listFiles { f -> f.name.endsWith(".44100.f32") }.orEmpty()) {
            val target = File(dir, native.name.replace(".44100.f32", ".phone.f32"))
            if (target.isFile) continue
            val audio = floats(native)
            val r = MonoResampler(44_100, Cqt.RATE)
            target.outputStream().buffered().use { sink ->
                var at = 0
                while (at < audio.size) {
                    val n = minOf(2048, audio.size - at)
                    val res = r.push(audio.copyOfRange(at, at + n))
                    val bb = ByteBuffer.allocate(res.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(res)
                    sink.write(bb.array())
                    at += n
                }
            }
        }
    }

    /** What the app hears in each song (its own code: Cqt, Btc, ChordReading), as a lab file. */
    @Test fun hears() {
        assumeTrue(dir.isNotBlank())
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val labels = mapOf(
            ChordQuality.MAJOR to "maj", ChordQuality.MINOR to "min", ChordQuality.DIM to "dim", ChordQuality.AUG to "aug",
            ChordQuality.MIN6 to "min6", ChordQuality.SIX to "maj6", ChordQuality.MIN7 to "min7", ChordQuality.MIN_MAJ7 to "minmaj7",
            ChordQuality.MAJ7 to "maj7", ChordQuality.DOM7 to "7", ChordQuality.DIM7 to "dim7", ChordQuality.HALF_DIM to "hdim7",
            ChordQuality.SUS2 to "sus2", ChordQuality.SUS4 to "sus4",
        )
        val model = Btc(ByteBuffer.wrap(File("src/main/assets/chords/btc_voca.bin").readBytes()))
        var seconds = 0.0
        var spent = 0L
        for (pcm in File(dir).listFiles { f -> f.name.endsWith(".phone.f32") }.orEmpty().sortedBy { it.name }) {
            val id = pcm.name.removeSuffix(".phone.f32")
            val audio = floats(pcm)
            val t0 = System.nanoTime()
            val chords = ChordReading.chords(model.logProbs(Cqt.features(audio)))
            spent += System.nanoTime() - t0
            seconds += audio.size / 22_050.0
            File(dir, "$id.ours.lab").writeText(
                chords.joinToString("") { c -> "%.3f\t%.3f\t%s:%s\n".format(Locale.ROOT, c.startMs / 1000.0, c.endMs / 1000.0, names[c.root], labels.getValue(c.quality)) },
            )
        }
        println("BENCH heard %.0f s of music in %.1f s (%.1f s per 4-minute song)".format(seconds, spent / 1e9, spent / 1e9 / seconds * 240))
    }

    private fun floats(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(bb.remaining()).also { bb.get(it) }
    }
}
