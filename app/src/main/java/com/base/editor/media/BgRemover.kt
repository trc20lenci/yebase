package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * Удаление фона на устройстве: сегментация силуэта лёгкой TFLite-моделью (selfie segmenter, ≈250 КБ) через LiteRT по кадрам
 * исходника; маски (12 к/с) пишутся в файл и дальше применяются шейдером [com.base.editor.media.gl.ClipFxEffect].
 * Идея «маска → альфа» та же, что у backgroundremover; сама модель — лёгкая, только для людей.
 * Работает в фоне с прогрессом, отменяется вместе с корутиной.
 */
class BgRemover(private val context: Context, private val store: MaskStore = MaskStore(context)) {

    class Failure(message: String) : Exception(message)

    suspend fun process(clip: Clip, onProgress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        if (clip.type == MediaType.AUDIO) throw Failure("Для звука фон не удаляется")
        val spanMs = clip.srcSpanMs
        if (spanMs > MAX_SPAN_MS) throw Failure("Для удаления фона клип должен быть не длиннее ${MAX_SPAN_MS / 1000} с")
        val modelFile = ensureModel()
        val count = if (clip.type == MediaType.IMAGE) 1 else ceil(spanMs * FPS / 1000.0).toInt() + 1
        val frames = arrayOfNulls<ByteArray>(count)
        val done = AtomicInteger(0)
        // кадры считаются параллельно (по одному модельному интерпретатору и декодеру на поток): главное время уходит на декодирование видео
        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        var dims = IntArray(2)
        coroutineScope {
            (0 until workers).map { w ->
                async(Dispatchers.Default) {
                    val interpreter = Interpreter(directBuffer(modelFile), Interpreter.Options().setNumThreads(2))
                    val retriever = if (clip.type == MediaType.VIDEO) MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(clip.uri)) } else null
                    try {
                        // форма входа/выхода читается из самой модели: [1, H, W, 3] → [1, H, W, C]
                        val inShape = interpreter.getInputTensor(0).shape()
                        val outShape = interpreter.getOutputTensor(0).shape()
                        val inH = inShape[1]; val inW = inShape[2]
                        val outH = outShape[1]; val outW = outShape[2]; val outC = if (outShape.size > 3) outShape[3] else 1
                        if (w == 0) dims = intArrayOf(outW, outH)
                        val floatInput = interpreter.getInputTensor(0).dataType() == DataType.FLOAT32
                        val input = ByteBuffer.allocateDirect(inW * inH * 3 * (if (floatInput) 4 else 1)).order(ByteOrder.nativeOrder())
                        val output = ByteBuffer.allocateDirect(outW * outH * outC * 4).order(ByteOrder.nativeOrder())
                        val pixels = IntArray(inW * inH)
                        var i = w
                        while (i < count) {
                            ensureActive()
                            val srcMs = clip.srcInMs + (i * 1000L / FPS)
                            val bmp = frame(clip, retriever, srcMs) ?: throw Failure("Не удалось прочитать кадр")
                            val scaled = if (bmp.width == inW && bmp.height == inH) bmp else Bitmap.createScaledBitmap(bmp, inW, inH, false)
                            scaled.getPixels(pixels, 0, inW, 0, 0, inW, inH)
                            input.rewind()
                            for (px in pixels) {
                                val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
                                if (floatInput) { input.putFloat(r / 255f); input.putFloat(g / 255f); input.putFloat(b / 255f) }
                                else { input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte()) }
                            }
                            input.rewind(); output.rewind()
                            interpreter.run(input, output)
                            output.rewind()
                            val fb = output.asFloatBuffer()
                            val bytes = ByteArray(outW * outH)
                            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                            for (k in 0 until outW * outH * outC) { val v = fb.get(k); if (v < mn) mn = v; if (v > mx) mx = v }
                            val logits = mn < -0.01f || mx > 1.01f
                            for (k in bytes.indices) {
                                val v = if (outC == 1) fb.get(k) else {
                                    val bgV = fb.get(k * outC); val fgV = fb.get(k * outC + 1)       // канал 1 — «человек»
                                    if (logits) 1f / (1f + kotlin.math.exp(bgV - fgV)) else fgV
                                }
                                val p = if (outC == 1 && logits) 1f / (1f + kotlin.math.exp(-v)) else v
                                bytes[k] = (p.coerceIn(0f, 1f) * 255f).toInt().toByte()
                            }
                            frames[i] = bytes
                            onProgress(done.incrementAndGet().toFloat() / count)
                            i += workers
                        }
                    } finally { runCatching { retriever?.release() }; runCatching { interpreter.close() } }
                }
            }.awaitAll()
        }
        val (outW, outH) = dims[0] to dims[1]
        val first = frames[0] ?: throw Failure("Модель не вернула маску")
        // модель могла вернуть «фон» вместо «человека»: по краям кадра значения выше, чем в центре
        val invert = borderMean(first, outW, outH) > centerMean(first, outW, outH) + 20
        val part = File(store.file(clip.uri).path + ".part").apply { delete() }
        try {
            RandomAccessFile(part, "rw").use { out ->
                val header = ByteBuffer.allocate(MaskStore.HEADER.toInt()).order(ByteOrder.BIG_ENDIAN)
                header.putInt(MaskStore.MAGIC).putInt(outW).putInt(outH).putInt(FPS).putLong(clip.srcInMs).putInt(count)
                out.write(header.array())
                for (f in frames) {
                    val bytes = f ?: ByteArray(outW * outH) { 255.toByte() }
                    if (invert) for (k in bytes.indices) bytes[k] = (255 - (bytes[k].toInt() and 0xFF)).toByte()
                    out.write(bytes)
                }
            }
            val target = store.file(clip.uri); target.delete()
            if (!part.renameTo(target)) throw Failure("Не удалось сохранить маски")
        } finally { part.delete() }
    }

    private fun frame(clip: Clip, r: MediaMetadataRetriever?, srcMs: Long): Bitmap? =
        if (r != null) MediaFrames.scaledFrame(r, srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, MAX_SIDE)
        else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(clip.uri))) { dec, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > MAX_SIDE) dec.setTargetSampleSize(big / MAX_SIDE)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun borderMean(b: ByteArray, w: Int, h: Int): Float {
        var s = 0L; var n = 0
        for (x in 0 until w) { s += b[x].toInt() and 0xFF; s += b[(h - 1) * w + x].toInt() and 0xFF; n += 2 }
        for (y in 0 until h) { s += b[y * w].toInt() and 0xFF; s += b[y * w + w - 1].toInt() and 0xFF; n += 2 }
        return s.toFloat() / n
    }

    private fun centerMean(b: ByteArray, w: Int, h: Int): Float {
        var s = 0L; var n = 0
        for (y in h / 3 until 2 * h / 3) for (x in w / 3 until 2 * w / 3) { s += b[y * w + x].toInt() and 0xFF; n++ }
        return s.toFloat() / n.coerceAtLeast(1)
    }

    private fun directBuffer(f: File): ByteBuffer {
        val bytes = f.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.rewind() }
    }

    private fun ensureModel(): File {
        val f = File(File(context.applicationContext.filesDir, "models").apply { mkdirs() }, MODEL_NAME)
        if (f.exists() && isTflite(f)) return f
        val part = File(f.path + ".part").apply { delete() }
        var last: Exception? = null
        repeat(3) {
            try {
                val c = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    c.inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
                } finally { c.disconnect() }
                if (!isTflite(part)) throw IOException("файл модели повреждён")
                f.delete(); if (!part.renameTo(f)) throw IOException("не удалось сохранить модель")
                return f
            } catch (e: IOException) { last = e; Log.w(TAG, "загрузка модели", e); part.delete() }
        }
        throw Failure("Не удалось загрузить модель сегментации: ${last?.message}")
    }

    /** TFLite-файл: размер в разумных пределах и сигнатура «TFL3» со смещения 4. */
    private fun isTflite(f: File): Boolean {
        if (f.length() !in 50_000..20_000_000) return false
        return f.inputStream().use { i -> val h = ByteArray(8); i.read(h) == 8 && String(h, 4, 4, Charsets.ISO_8859_1) == "TFL3" }
    }

    private companion object {
        const val TAG = "BaseBg"
        const val FPS = 6
        const val MAX_SIDE = 256
        const val MAX_SPAN_MS = 120_000L
        const val MODEL_NAME = "selfie_segmenter.tflite"
        const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite"
    }
}
