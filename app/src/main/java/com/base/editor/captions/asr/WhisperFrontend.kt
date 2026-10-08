package com.base.editor.captions.asr

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Подготовка звука и разбор ответа для модели Whisper (TFLite): лог-мел-спектрограмма и словарь.
 * Чистый Kotlin без Android — проверяется на JVM.
 *
 * Модель принимает ровно 30 с звука 16 кГц: вход [1, 80, 3000] float32, выход — токены int32 [1, 448].
 */
class WhisperFrontend private constructor(private val filters: FloatArray, private val vocab: Array<ByteArray>) {

    /** Лог-мел-спектрограмма окна до 30 с (остаток добивается тишиной). Результат: [80][3000], построчно. */
    fun melSpectrogram(samples: FloatArray, length: Int = samples.size, threads: Int = 1): FloatArray {
        val n = min(length, N_SAMPLES)
        val out = FloatArray(N_MEL * N_FRAMES)
        val activeFrames = min(N_FRAMES, (n + HOP - 1) / HOP + 1)           // дальше — чистая тишина
        val work = { from: Int, to: Int -> frames(samples, n, from, to, out) }
        if (threads <= 1) work(0, activeFrames) else {
            val step = (activeFrames + threads - 1) / threads
            (0 until threads).map { t -> Thread { work(t * step, min(activeFrames, (t + 1) * step)) }.also { it.start() } }.forEach { it.join() }
        }
        // кадры тишины: мощность 0 → log10(1e-10)
        for (f in activeFrames until N_FRAMES) for (m in 0 until N_MEL) out[m * N_FRAMES + f] = SILENCE_LOG
        // как в эталоне: обрезка снизу на (max − 8) и нормализация
        var mx = -1e20f
        for (v in out) if (v > mx) mx = v
        val floor = mx - 8f
        for (i in out.indices) out[i] = (max(out[i], floor) + 4f) / 4f
        return out
    }

    private fun frames(samples: FloatArray, n: Int, from: Int, to: Int, out: FloatArray) {
        val re = FloatArray(N_FFT / 2 + 1); val im = FloatArray(N_FFT / 2 + 1)
        val windowed = FloatArray(N_FFT)
        val power = FloatArray(N_FFT / 2 + 1)
        for (f in from until to) {
            val offset = f * HOP
            for (j in 0 until N_FFT) windowed[j] = if (offset + j < n) HANN[j] * samples[offset + j] else 0f
            for (k in 0..N_FFT / 2) {
                var r = 0f; var i = 0f
                val step = k
                var idx = 0
                for (j in 0 until N_FFT) {
                    r += windowed[j] * COS[idx]; i -= windowed[j] * SIN[idx]
                    idx += step; if (idx >= N_FFT) idx -= N_FFT
                }
                re[k] = r; im[k] = i
            }
            for (k in 0..N_FFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            // отражённая половина спектра складывается (как в эталонной реализации)
            for (k in 1 until N_FFT / 2) power[k] *= 2f
            for (m in 0 until N_MEL) {
                var sum = 0.0
                val base = m * (N_FFT / 2 + 1)
                for (k in 0..N_FFT / 2) sum += power[k] * filters[base + k]
                if (sum < 1e-10) sum = 1e-10
                out[m * N_FRAMES + f] = (ln(sum) / LN10).toFloat()
            }
        }
    }

    /** Результат декодирования одного окна. */
    data class Decoded(val text: String, val languageToken: Int)

    /** Токены → текст. Слова собираются из байтов целиком, чтобы кириллица не рассыпалась. */
    fun decode(tokens: IntArray): Decoded {
        val bytes = java.io.ByteArrayOutputStream()
        var lang = -1
        var repeat = 0; var prev = -1
        for (t in tokens) {
            if (t == TOKEN_EOT || t < 0) break
            if (t in LANG_FIRST..LANG_LAST && lang < 0) { lang = t; continue }
            if (t >= TOKEN_EOT) continue                    // служебные токены
            if (t == prev) { if (++repeat > MAX_REPEAT) continue } else { repeat = 0; prev = t }
            if (t < vocab.size) bytes.write(vocab[t])
        }
        return Decoded(String(bytes.toByteArray(), Charsets.UTF_8).trim(), lang)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_SAMPLES = 30 * SAMPLE_RATE
        const val N_MEL = 80
        const val N_FRAMES = 3000
        private const val N_FFT = 400
        private const val HOP = 160
        private const val TOKEN_EOT = 50257          // мультиязычный словарь
        private const val LANG_FIRST = 50259
        private const val LANG_LAST = 50357
        private const val MAX_REPEAT = 6
        private val LN10 = ln(10.0)
        private val SILENCE_LOG = -10f
        private val HANN = FloatArray(N_FFT) { (0.5 * (1.0 - cos(2.0 * PI * it / N_FFT))).toFloat() }
        private val COS = FloatArray(N_FFT) { cos(2.0 * PI * it / N_FFT).toFloat() }
        private val SIN = FloatArray(N_FFT) { sin(2.0 * PI * it / N_FFT).toFloat() }

        fun load(file: File): WhisperFrontend = file.inputStream().use(::load)

        /** Формат файла: magic, n_mel, n_fft, фильтры float32, число токенов, затем [длина, байты] на токен. */
        fun load(input: InputStream): WhisperFrontend {
            val buf = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.nativeOrder())
            require(buf.getInt() == 0x5553454e) { "Неверный файл словаря" }
            val nMel = buf.getInt(); val nFft = buf.getInt()
            require(nMel == N_MEL && nFft == N_FFT / 2 + 1) { "Неподдерживаемые фильтры" }
            val filters = FloatArray(nMel * nFft) { buf.getFloat() }
            val n = buf.getInt()
            val vocab = Array(n) { ByteArray(buf.getInt()).also { b -> buf.get(b) } }
            return WhisperFrontend(filters, vocab)
        }
    }
}
