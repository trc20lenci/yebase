package com.base.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import com.base.editor.text.TextClip
import com.base.editor.text.TextClipRenderer.drawTextClip

/** Текстовые слои поверх видео; область рисования повторяет границы кадра, как и в экспорте. */
@Composable
fun TextOverlay(clips: List<TextClip>, videoAspect: Float, playheadMs: Long, modifier: Modifier = Modifier) {
    if (clips.isEmpty()) return
    val measurer = rememberTextMeasurer(cacheSize = 32)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.aspectRatio(videoAspect.coerceIn(0.2f, 5f))) { clips.forEach { drawTextClip(measurer, it, playheadMs - it.startMs) } }
    }
}
