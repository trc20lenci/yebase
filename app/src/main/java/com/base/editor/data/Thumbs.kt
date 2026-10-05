package com.base.editor.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.base.editor.core.MediaType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class Thumb(val bitmap: Bitmap) { val image: ImageBitmap = bitmap.asImageBitmap() }

/** Кэш миниатюр: кадры видео для таймлайна и постеры для галереи. */
object Thumbs {
    private val cache = object : LruCache<String, Thumb>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(96 * 1024 * 1024)
    ) { override fun sizeOf(key: String, value: Thumb) = value.bitmap.byteCount }

    // MediaMetadataRetriever не любит параллельность: один поток + маленький пул ретриверов
    private val videoDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
            if (size > 3) { runCatching { eldest.value.release() }; return true }
            return false
        }
    }

    fun key(uri: String, bucketMs: Long, h: Int) = "$uri|$bucketMs|$h"
    fun peek(key: String): Thumb? = cache.get(key)

    suspend fun load(ctx: Context, uri: String, type: MediaType, bucketMs: Long, h: Int): Thumb? {
        val k = key(uri, bucketMs, h)
        cache.get(k)?.let { return it }
        val bmp = if (type == MediaType.VIDEO) videoFrame(ctx, uri, bucketMs, h) else imageThumb(ctx, Uri.parse(uri), h * 2)
        return bmp?.let { Thumb(it).also { t -> cache.put(k, t) } }
    }

    /** Квадратный постер для сетки галереи. */
    suspend fun poster(ctx: Context, uri: Uri, px: Int): Thumb? {
        val k = key(uri.toString(), -1, px)
        cache.get(k)?.let { return it }
        return imageThumb(ctx, uri, px)?.let { Thumb(it).also { t -> cache.put(k, t) } }
    }

    /** Кадр для обложки проекта (сохраняется в JPEG). */
    suspend fun projectCover(ctx: Context, uri: String, type: MediaType, atMs: Long): Bitmap? =
        if (type == MediaType.VIDEO) videoFrame(ctx, uri, atMs, 240) else imageThumb(ctx, Uri.parse(uri), 240)

    private suspend fun imageThumb(ctx: Context, uri: Uri, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }

    private suspend fun videoFrame(ctx: Context, uri: String, atMs: Long, h: Int): Bitmap? = withContext(videoDispatcher) {
        runCatching {
            val r = retrievers.getOrPut(uri) { MediaMetadataRetriever().apply { setDataSource(ctx, Uri.parse(uri)) } }
            val src = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
            if (src.height <= h) src else {
                val w = (src.width * h.toFloat() / src.height).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
            }
        }.getOrNull()
    }
}

/** Размер кадра с учётом поворота — по нему выбираем пропорции окна превью. */
object MediaProbe {
    fun displaySize(ctx: Context, uri: String, type: MediaType): Pair<Int, Int>? = runCatching {
        if (type == MediaType.VIDEO) {
            val r = MediaMetadataRetriever().apply { setDataSource(ctx, Uri.parse(uri)) }
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r.release()
            if (rot % 180 == 90) h to w else w to h
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            o.outWidth to o.outHeight
        }
    }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

    /** Длительность аудиофайла (мс) — для блока музыки на аудиодорожке. */
    fun audioDurationMs(ctx: Context, uri: String): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(uri))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    }.getOrNull()?.takeIf { it > 0 }
}
