package com.base.editor.captions.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Распознавание на устройстве через k2-fsa/sherpa-onnx (OfflineRecognizer + Whisper ONNX int8).
 * [language]: "ru" / "en" — язык задаётся принудительно, "" — автоопределение. Токены приходят с метками времени.
 * Один экземпляр на серию окон; не потокобезопасен.
 */
class SherpaWhisper(files: SherpaModelStore.Files, language: String, threads: Int = 4) : AutoCloseable {
    class Decoded(val text: String, val tokens: Array<String>, val timestampsSec: FloatArray)

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.encoder.absolutePath, decoder = files.decoder.absolutePath,
                    language = language, task = "transcribe", enableTokenTimestamps = true,
                ),
                tokens = files.tokens.absolutePath, numThreads = threads, modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(samples: FloatArray): Decoded {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return Decoded(r.text, r.tokens, r.timestamps)
        } finally { stream.release() }
    }

    override fun close() { recognizer.release() }

    private companion object { const val SAMPLE_RATE = 16_000 }
}
