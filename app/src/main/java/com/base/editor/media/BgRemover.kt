package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.Dispatchers
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
 * Удаление фона на устройстве: сегментация силуэта моделью MediaPipe (selfie segmenter, TFLite, ≈250 КБ) по кадрам
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
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(directBuffer(modelFile)).build())
            .setRunningMode(RunningMode.IMAGE).setOutputConfidenceMasks(true).setOutputCategoryMask(false).build()
        val segmenter = ImageSegmenter.createFromOptions(context, opts)
        val retriever = if (clip.type == MediaType.VIDEO) MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(clip.uri)) } else null
        val part = File(store.file(clip.uri).path + ".part").apply { delete() }
        try {
            val count = if (clip.type == MediaType.IMAGE) 1 else ceil(spanMs * FPS / 1000.0).toInt() + 1
            var w = 0; var h = 0; var invert: Boolean? = null
            RandomAccessFile(part, "rw").use { out ->
                for (i in 0 until count) {
                    coroutineContext.ensureActive()
                    val srcMs = clip.srcInMs + (i * 1000L / FPS)
                    val bmp = frame(clip, retriever, srcMs) ?: throw Failure("Не удалось прочитать кадр")
                    val res = segmenter.segment(BitmapImageBuilder(bmp).build())
                    val masks = res.confidenceMasks().orElse(null) ?: throw Failure("Модель не вернула маску")
                    val m = masks[if (masks.size >= 2) 1 else 0]
                    if (i == 0) {
                        w = m.width; h = m.height
                        out.write(ByteArray(MaskStore.HEADER.toInt()))                       // заголовок допишем в конце
                    }
                    val fb = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val bytes = ByteArray(w * h)
                    for (k in bytes.indices) bytes[k] = (fb.get(k).coerceIn(0f, 1f) * 255f).toInt().toByte()
                    if (invert == null) invert = borderMean(bytes, w, h) > centerMean(bytes, w, h) + 20     // модель вернула «фон», а не «человек»
                    if (invert == true) for (k in bytes.indices) bytes[k] = (255 - (bytes[k].toInt() and 0xFF)).toByte()
                    out.write(bytes)
                    onProgress((i + 1f) / count)
                }
                val header = ByteBuffer.allocate(MaskStore.HEADER.toInt()).order(ByteOrder.BIG_ENDIAN)
                header.putInt(MaskStore.MAGIC).putInt(w).putInt(h).putInt(FPS).putLong(clip.srcInMs).putInt(count)
                out.seek(0); out.write(header.array())
            }
            val target = store.file(clip.uri); target.delete()
            if (!part.renameTo(target)) throw Failure("Не удалось сохранить маски")
        } finally {
            part.delete(); runCatching { retriever?.release() }; runCatching { segmenter.close() }
        }
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
        const val FPS = 12
        const val MAX_SIDE = 320
        const val MAX_SPAN_MS = 120_000L
        const val MODEL_NAME = "selfie_segmenter.tflite"
        const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite"
    }
}
