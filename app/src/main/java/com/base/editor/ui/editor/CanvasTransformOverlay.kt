package com.base.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.base.editor.core.ClipTransform
import com.base.editor.text.TextClip
import com.base.editor.text.TextClipRenderer.measureBox
import kotlin.math.abs

/** Что сейчас выделено на холсте. */
sealed interface CanvasTarget {
    /** Видео/фото клип: его кадр можно двигать, масштабировать и вращать. */
    data class Clip(val id: Long, val transform: ClipTransform, val aspect: Float) : CanvasTarget
    data class Text(val clip: TextClip, val isDraft: Boolean) : CanvasTarget
}

/** Действия жестов; вызываются на главном потоке. */
interface CanvasActions {
    fun onGestureStart()
    fun onClipTransform(clipId: Long, transform: ClipTransform)
    fun onTextTransform(clip: TextClip, isDraft: Boolean)
    fun onGestureEnd()
    /** Тап по слою текста (id), по пустому месту видео (null). */
    fun onTapText(id: String)
    fun onTapVideo()
}

/**
 * Интерактивный слой поверх плеера. Логика жестов перенесена из PhotoEditor
 * (MultiTouchListener.java, MIT): в начале касания запоминается исходная трансформация,
 * дальше pan / pinch / rotate считаются как ПОЛНОЕ смещение жеста от этой точки
 * (translate = base + pan, scale = base * zoom, rotation = base + angle) и применяются
 * относительно ЦЕНТРА вписанного в кадр клипа — контейнер никогда не растягивается,
 * пропорции исходника жёстко соблюдаются (ContentScale.Fit / Presentation.LAYOUT_SCALE_TO_FIT).
 * Работает для выделенного клипа и для текстовых слоёв.
 * Выделенный элемент обводится рамкой с маркерами по углам.
 */
@Composable
fun CanvasTransformOverlay(
    videoAspect: Float,
    target: CanvasTarget?,
    texts: List<TextClip>,
    actions: CanvasActions,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val slop = LocalViewConfiguration.current.touchSlop
    val currentTarget by rememberUpdatedState(target)
    val currentTexts by rememberUpdatedState(texts)
    val act by rememberUpdatedState(actions)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.aspectRatio(videoAspect.coerceIn(0.2f, 5f)).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var transforming = false
                    var accPan = Offset.Zero; var accZoom = 1f; var accRot = 0f
                    var base: CanvasTarget? = null                 // состояние элемента на момент начала жеста
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan(); val zoom = event.calculateZoom(); val rot = event.calculateRotation()
                        accPan += pan; accZoom *= zoom; accRot += rot
                        val t = currentTarget
                        if (!transforming && t != null &&
                            (accPan.getDistance() > slop || abs(accZoom - 1f) > 0.04f || abs(accRot) > 3f)) {
                            transforming = true; base = t; act.onGestureStart()
                        }
                        val b = base
                        if (transforming && b != null && event.changes.any { it.pressed }) {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            when (b) {
                                // как в MultiTouchListener: от исходного состояния + суммарная дельта жеста
                                is CanvasTarget.Clip -> act.onClipTransform(b.id, b.transform.copy(
                                    x = b.transform.x + accPan.x / w, y = b.transform.y + accPan.y / h,
                                    scale = b.transform.scale * accZoom, rotationDeg = b.transform.rotationDeg + accRot,
                                ).sane())
                                is CanvasTarget.Text -> act.onTextTransform(b.clip.copy(
                                    positionX = (b.clip.positionX + accPan.x / w).coerceIn(-0.2f, 1.2f),
                                    positionY = (b.clip.positionY + accPan.y / h).coerceIn(-0.2f, 1.2f),
                                    fontSizeSp = (b.clip.fontSizeSp * accZoom).coerceIn(8f, 160f),
                                    rotationDeg = b.clip.rotationDeg + accRot,
                                ), b.isDraft)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (transforming) act.onGestureEnd()
                    else {
                        // тап: сверху вниз ищем текстовый слой под пальцем, иначе — видео
                        val hit = hitText(measurer, currentTexts, down.position, Size(size.width.toFloat(), size.height.toFloat()), this)
                        if (hit != null) act.onTapText(hit) else act.onTapVideo()
                    }
                }
            },
        ) {
            val t = target ?: return@Canvas
            when (t) {
                is CanvasTarget.Text -> measureBox(measurer, t.clip)?.let { b ->
                    drawSelection(Offset(b.centerX, b.centerY), b.width, b.height, b.rotationDeg)
                }
                is CanvasTarget.Clip -> {
                    // рамка строго по вписанному кадру клипа (ContentScale.Fit), а не по всему холсту;
                    // масштаб и поворот идут вокруг центра кадра, как в MultiTouchListener (PhotoEditor)
                    val c = t.transform
                    val (fw, fh) = fitSize(size.width, size.height, t.aspect)
                    drawSelection(Offset(size.width / 2 + c.x * size.width, size.height / 2 + c.y * size.height), fw * c.scale, fh * c.scale, c.rotationDeg)
                }
            }
        }
    }
}

/** Размер кадра с соотношением [aspect], вписанного в холст w×h без растяжения (ContentScale.Fit). */
internal fun fitSize(w: Float, h: Float, aspect: Float): Pair<Float, Float> {
    val a = aspect.coerceIn(0.05f, 20f)
    return if (a >= w / h) w to w / a else h * a to h
}

private fun hitText(measurer: TextMeasurer, texts: List<TextClip>, p: Offset, size: Size, density: androidx.compose.ui.unit.Density): String? {
    val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
    var found: String? = null
    val bmp = androidx.compose.ui.graphics.ImageBitmap(1, 1)
    scope.draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), size) {
        for (t in texts.asReversed()) {                          // верхний слой — последний
            val b = measureBox(measurer, t) ?: continue
            if (b.contains(p.x, p.y, slop = 12.dp.toPx())) { found = t.id; break }
        }
    }
    return found
}

/** Тонкая рамка с угловыми маркерами; поворачивается вместе с элементом. */
private fun DrawScope.drawSelection(center: Offset, w: Float, h: Float, rotationDeg: Float) {
    withTransform({ rotate(rotationDeg, center) }) {
        val tl = Offset(center.x - w / 2, center.y - h / 2)
        drawRect(Color.White, tl, Size(w, h), style = Stroke(1.5.dp.toPx()))
        val r = 5.dp.toPx()
        listOf(tl, Offset(tl.x + w, tl.y), Offset(tl.x, tl.y + h), Offset(tl.x + w, tl.y + h)).forEach {
            drawCircle(Color.White, r, it); drawCircle(Color(0xFF00CCDD), r * 0.55f, it)
        }
    }
}
