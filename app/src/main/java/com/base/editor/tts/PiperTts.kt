package com.base.editor.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Текст в речь: голоса Piper (ONNX) через sherpa-onnx. Результат — WAV 16 бит, моно, в filesDir/tts-out. */
class PiperTts(context: Context, private val store: TtsModelStore = TtsModelStore(context)) {
    private val outDir = File(context.applicationContext.filesDir, "tts-out").apply { mkdirs() }

    class Result(val file: File, val durationMs: Long)

    fun isReady(v: TtsVoice) = store.isReady(v)
    suspend fun prefetch(v: TtsVoice) { if (!store.isReady(v)) store.ensure(v) }

    suspend fun synthesize(voice: TtsVoice, text: String, speed: Float = 1f): Result = withContext(Dispatchers.Default) {
        val clean = text.replace(Regex("[\\u0000-\\u001F]+"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)
        require(clean.isNotEmpty()) { "пустой текст" }
        val out = File(outDir, "v-" + sha1("${voice.id}|$speed|$clean") + ".wav")
        if (out.exists() && out.length() > 44) return@withContext Result(out, wavDurationMs(out))
        val f = store.ensure(voice)
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = f.model.absolutePath, tokens = f.tokens.absolutePath, dataDir = f.dataDir.absolutePath),
            numThreads = 2, debug = false, provider = "cpu",
        )))
        try {
            val audio = tts.generate(text = clean, sid = 0, speed = speed)
            if (audio.samples.isEmpty()) throw IllegalStateException("голос не вернул звук")
            writeWav(out, audio.samples, audio.sampleRate)
            Result(out, audio.samples.size * 1000L / audio.sampleRate)
        } finally { tts.release() }
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val part = File(file.path + ".part")
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(0)
            val dataLen = samples.size * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray()).put("fmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(dataLen)
            raf.write(h.array())
            val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) body.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            raf.write(body.array())
        }
        file.delete(); part.renameTo(file)
    }

    private fun wavDurationMs(f: File): Long = ((f.length() - 44) / 2) * 1000L / readRate(f)
    private fun readRate(f: File): Int = RandomAccessFile(f, "r").use { it.seek(24); val b = ByteArray(4); it.readFully(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.coerceAtLeast(8000) }
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_CHARS = 1500 }
}
