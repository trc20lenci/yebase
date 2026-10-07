package com.base.editor.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlin.math.max
import kotlin.math.roundToInt

/** Кадр видео в нужном размере С СОХРАНЕНИЕМ пропорций (учитывая поворот): getScaledFrameAtTime сам размер не вписывает. */
object MediaFrames {
    fun scaledFrame(r: MediaMetadataRetriever, timeUs: Long, option: Int, maxSide: Int): Bitmap? {
        val w0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return r.getFrameAtTime(timeUs, option)
        val h0 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return r.getFrameAtTime(timeUs, option)
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val w = if (rot == 90 || rot == 270) h0 else w0
        val h = if (rot == 90 || rot == 270) w0 else h0
        val k = maxSide.toFloat() / max(w, h)
        if (k >= 1f) return r.getFrameAtTime(timeUs, option)
        return r.getScaledFrameAtTime(timeUs, option, max(2, (w * k).roundToInt()), max(2, (h * k).roundToInt()))
    }
}
