package com.villamediana.sound.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * The fingerprint Shazam matches songs by, computed on the phone.
 *
 * A port of the open signature generator (SongRec, and shazamio's Python of
 * it): 16 kHz mono audio, an FFT every 128 samples over the last 2048, the
 * spectrum spread in frequency and time, and the peaks that stand out kept in
 * four frequency bands. The peaks, packed in Shazam's binary format, are all
 * that is sent — never the audio.
 */
object ShazamSignature {

    const val RATE = 16_000
    private const val WINDOW = 2048
    private const val BINS = 1025
    private const val RING = 256

    /** Hanning over 2050 points with the zero ends dropped, as the reference does. */
    private val HANNING = DoubleArray(WINDOW) { i -> 0.5 - 0.5 * cos(2 * PI * (i + 1) / (WINDOW + 1)) }

    data class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    data class Signature(val numberSamples: Int, val bands: Map<Int, List<Peak>>) {
        val sampleMs: Int get() = (numberSamples.toLong() * 1000 / RATE).toInt()
        val peakCount: Int get() = bands.values.sumOf { it.size }
    }

    /**
     * The signature of [pcm] (16 kHz mono), from [from] samples in: at least
     * [maxSeconds] of audio or 255 peaks, whichever is reached last.
     */
    fun generate(pcm: ShortArray, from: Int = 0, maxSeconds: Double = 12.0): Signature {
        val ring = DoubleArray(WINDOW)
        var ringPos = 0
        val fft = Array(RING) { DoubleArray(BINS) }
        var fftPos = 0
        var fftWritten = 0
        val spread = Array(RING) { DoubleArray(BINS) }
        var spreadPos = 0
        var spreadWritten = 0
        val bands = sortedMapOf<Int, MutableList<Peak>>()
        var samples = 0
        var processed = from.coerceIn(0, pcm.size)

        val re = DoubleArray(WINDOW)
        val im = DoubleArray(WINDOW)

        fun peaks() = bands.values.sumOf { it.size }

        while (pcm.size - processed >= 128 && (samples.toDouble() / RATE < maxSeconds || peaks() < 255)) {
            // --- FFT of the last 2048 samples ---
            for (k in 0 until 128) {
                ring[ringPos] = pcm[processed + k].toDouble()
                ringPos = (ringPos + 1) % WINDOW
            }
            samples += 128
            processed += 128
            for (i in 0 until WINDOW) {
                re[i] = ring[(ringPos + i) % WINDOW] * HANNING[i]
                im[i] = 0.0
            }
            Fft.transform(re, im)
            val out = fft[fftPos]
            for (b in 0 until BINS) {
                out[b] = max((re[b] * re[b] + im[b] * im[b]) / (1 shl 17), 1e-10)
            }
            fftPos = (fftPos + 1) % RING
            fftWritten++

            // --- spreading in frequency, then in time ---
            val last = fft[Math.floorMod(fftPos - 1, RING)]
            val spreadNow = DoubleArray(BINS)
            for (b in 0 until BINS - 3) spreadNow[b] = max(last[b], max(last[b + 1], last[b + 2]))
            for (b in BINS - 3 until BINS) spreadNow[b] = last[b]
            val i1 = Math.floorMod(spreadPos - 1, RING)
            val i2 = Math.floorMod(spreadPos - 3, RING)
            val i3 = Math.floorMod(spreadPos - 6, RING)
            val a1 = spread[i1]
            val a2 = spread[i2]
            val a3 = spread[i3]
            for (b in 0 until BINS) {
                val m1 = max(spreadNow[b], a1[b])
                val m2 = max(m1, a2[b])
                val m3 = max(m2, a3[b])
                a1[b] = m1
                a2[b] = m2
                a3[b] = m3
            }
            spread[spreadPos] = spreadNow
            spreadPos = (spreadPos + 1) % RING
            spreadWritten++

            if (spreadWritten >= 46) recognise(fft, fftPos, spread, spreadPos, spreadWritten, bands)
        }
        return Signature(samples, bands)
    }

    private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHERS = intArrayOf(-53, -45) + (165..200 step 7).toList().toIntArray() + (214..249 step 7).toList().toIntArray()

    private fun magnitude(v: Double) = ln(max(1.0 / 64, v)) * 1477.3 + 6144

    private fun recognise(
        fft: Array<DoubleArray>,
        fftPos: Int,
        spread: Array<DoubleArray>,
        spreadPos: Int,
        spreadWritten: Int,
        bands: MutableMap<Int, MutableList<Peak>>,
    ) {
        val f46 = fft[Math.floorMod(fftPos - 46, RING)]
        val s49 = spread[Math.floorMod(spreadPos - 49, RING)]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1.0 / 64 || v < s49[bin - 1]) continue
            var nearMax = 0.0
            for (o in NEIGHBOURS) nearMax = max(s49[bin + o], nearMax)
            if (v <= nearMax) continue
            var otherMax = nearMax
            for (o in OTHERS) otherMax = max(spread[Math.floorMod(spreadPos + o, RING)][bin - 1], otherMax)
            if (v <= otherMax) continue

            val pass = spreadWritten - 46
            val mag = magnitude(v)
            val before = magnitude(f46[bin - 1])
            val after = magnitude(f46[bin + 1])
            val variation1 = mag * 2 - before - after
            if (variation1 <= 0) continue
            val variation2 = (after - before) * 32 / variation1
            val corrected = bin * 64 + variation2
            val hz = corrected * (RATE / 2.0 / 1024 / 64)
            // The reference never files the 3.5–5.5 kHz band (its test can
            // never pass), and Shazam matches without it: the same here, so
            // the signature is the one the service is used to.
            val band = when {
                hz > 250 && hz < 520 -> 0
                hz > 520 && hz < 1450 -> 1
                hz > 1450 && hz < 3500 -> 2
                else -> continue
            }
            bands.getOrPut(band) { mutableListOf() } += Peak(pass, mag.toInt(), corrected.toInt())
        }
    }

    /** Shazam's binary signature: a 48-byte header, then a block of peaks per band. */
    fun encode(sig: Signature): ByteArray {
        val contents = java.io.ByteArrayOutputStream()
        for ((band, peaks) in sig.bands.toSortedMap()) {
            val buf = java.io.ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p.pass - pass >= 255) {
                    buf.write(0xFF)
                    buf.write(le32(p.pass))
                    pass = p.pass
                }
                buf.write(p.pass - pass)
                buf.write(le16(p.magnitude))
                buf.write(le16(p.bin))
                pass = p.pass
            }
            val bytes = buf.toByteArray()
            contents.write(le32(0x60030040 + band))
            contents.write(le32(bytes.size))
            contents.write(bytes)
            repeat(Math.floorMod(-bytes.size, 4)) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val out = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xCAFE2580.toInt())
        out.putInt(0) // CRC, filled below
        out.putInt(body.size + 8)
        out.putInt(0x94119C00.toInt())
        repeat(3) { out.putInt(0) }
        out.putInt(3 shl 27) // 16 kHz
        repeat(2) { out.putInt(0) }
        out.putInt(sig.numberSamples + (RATE * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000)
        out.putInt(body.size + 8)
        out.put(body)
        val bytes = out.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        return bytes
    }

    fun uri(sig: Signature): String =
        "data:audio/vnd.shazam.sig;base64," + java.util.Base64.getEncoder().encodeToString(encode(sig))

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}

/** An in-place radix-2 FFT. */
object Fft {
    private val tables = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

    @Synchronized
    private fun table(n: Int) = tables.getOrPut(n) {
        DoubleArray(n / 2) { cos(2 * PI * it / n) } to DoubleArray(n / 2) { -kotlin.math.sin(2 * PI * it / n) }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val (cosT, sinT) = table(n)
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len = len shl 1
        }
    }
}
