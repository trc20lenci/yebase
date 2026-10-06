package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.base.editor.core.Clip
import com.base.editor.core.ClipTransform
import com.base.editor.core.CropRect
import com.base.editor.core.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Быстрый предпросмотр кадров при ведении пальцем по таймлайну. Тяжёлый seek CompositionPlayer на паузе занимает
 * секунды, поэтому пока палец двигается, кадр под курсором берётся напрямую из файла (MediaMetadataRetriever,
 * ближайший опорный кадр — быстро) и показывается поверх плеера; когда палец остановился, подгружается точный кадр.
 * Работает по принципу «последний запрос побеждает»: очередь не копится.
 */
class ScrubFrames(private val context: Context, private val scope: CoroutineScope) {
    /** Кадр + положение, с которым он должен быть показан (трансформация клипа на этой позиции). */
    class Frame(val image: ImageBitmap, val transform: ClipTransform)

    private class Req(val clip: Clip, val localMs: Long, val exact: Boolean, val transform: ClipTransform, val crop: CropRect)

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    @Volatile private var target: Req? = null
    private var job: Job? = null
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: String? = null
    private var stillUri: String? = null
    private var still: Bitmap? = null

    fun request(clip: Clip, localMs: Long, exact: Boolean, transform: ClipTransform, crop: CropRect) {
        target = Req(clip, localMs, exact, transform, crop)
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val r = target ?: break
                target = null
                val bmp = runCatching { decode(r) }.onFailure { Log.w(TAG, "кадр скраббинга", it) }.getOrNull() ?: continue
                _frame.value = Frame(bmp.asImageBitmap(), r.transform)
            }
        }
    }

    fun clear() { target = null; _frame.value = null }

    fun release() {
        job?.cancel(); clear()
        runCatching { retriever?.release() }; retriever = null; retrieverUri = null; still = null
    }

    private fun decode(r: Req): Bitmap? {
        val src = if (r.clip.type == MediaType.VIDEO) videoFrame(r) else imageFrame(r.clip.uri)
        return src?.let { crop(it, r.crop) }
    }

    private fun videoFrame(r: Req): Bitmap? {
        val uri = r.clip.uri
        if (retriever == null || retrieverUri != uri) {
            runCatching { retriever?.release() }
            retriever = MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(uri)) }
            retrieverUri = uri
        }
        val option = if (r.exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return retriever?.getScaledFrameAtTime((r.clip.srcInMs + r.localMs) * 1000, option, MAX_SIDE, MAX_SIDE)
    }

    private fun imageFrame(uri: String): Bitmap? {
        if (stillUri == uri && still != null) return still
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { dec, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > MAX_SIDE) dec.setTargetSampleSize(big / MAX_SIDE)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        stillUri = uri; still = bmp
        return bmp
    }

    private fun crop(b: Bitmap, c: CropRect): Bitmap {
        if (c.isFull) return b
        val x = (c.left * b.width).toInt().coerceIn(0, b.width - 1); val y = (c.top * b.height).toInt().coerceIn(0, b.height - 1)
        val w = (c.width * b.width).toInt().coerceIn(1, b.width - x); val h = (c.height * b.height).toInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    private companion object { const val TAG = "BaseScrub"; const val MAX_SIDE = 720 }
}
