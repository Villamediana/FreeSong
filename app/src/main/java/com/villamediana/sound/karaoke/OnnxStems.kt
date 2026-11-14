package com.villamediana.sound.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Spleeter's two stems run by ONNX Runtime on the phone: one network for the voice, one for the
 * band (the files [KaraokeModel] keeps). A stretch of twelve seconds takes a fraction of a second.
 */
class OnnxStems(voice: File, band: File, threads: Int = defaultThreads()) : StemModel {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Threads that wait for work sleep instead of spinning: nothing burns between stretches.
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }
    private val voiceNet = env.createSession(voice.path, options)
    private val bandNet = env.createSession(band.path, options)
    private val shape = longArrayOf(2, 1, VocalRemover.WINDOW.toLong(), VoiceMasks.BINS.toLong())

    override fun stems(mix: FloatArray): StemModel.Stems = StemModel.Stems(band = run(bandNet, mix), voice = run(voiceNet, mix))

    private fun run(net: OrtSession, mix: FloatArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), shape).use { input ->
            net.run(mapOf(net.inputNames.first() to input)).use { result ->
                val out = FloatArray(mix.size)
                (result[0] as OnnxTensor).floatBuffer.get(out)
                out
            }
        }

    override fun close() {
        runCatching { voiceNet.close() }
        runCatching { bandNet.close() }
        runCatching { options.close() }
    }

    companion object {
        /** The big cores, and no more: the song goes on playing meanwhile. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}
