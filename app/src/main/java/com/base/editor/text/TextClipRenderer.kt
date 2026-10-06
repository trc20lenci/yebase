package com.base.editor.text

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Рисовальщик текстовых слоёв — общий для превью и экспорта. Исключения наружу не выходят.
 */
object TextClipRenderer {
    /** Высота кадра (в «sp»), для которой fontSizeSp = реальный размер шрифта. */
    const val REFERENCE_HEIGHT = 640f

    /** Рамка текста в пикселях области: центр + размер до поворота. */
    class Box(val centerX: Float, val centerY: Float, val width: Float, val height: Float, val rotationDeg: Float) {
        /** Попадает ли точка в рамку (с учётом поворота) — для выбора текста тапом. */
        fun contains(x: Float, y: Float, slop: Float = 0f): Boolean {
            val r = Math.toRadians(-rotationDeg.toDouble())
            val dx = x - centerX; val dy = y - centerY
            val lx = (dx * Math.cos(r) - dy * Math.sin(r)).toFloat(); val ly = (dx * Math.sin(r) + dy * Math.cos(r)).toFloat()
            return kotlin.math.abs(lx) <= width / 2 + slop && kotlin.math.abs(ly) <= height / 2 + slop
        }
    }

    private class Laid(val layout: androidx.compose.ui.text.TextLayoutResult, val fontPx: Float, val padX: Float, val padY: Float, val left: Float, val top: Float, val boxW: Float, val boxH: Float)

    private fun DrawScope.lay(measurer: TextMeasurer, clip: TextClip, visibleChars: Int = Int.MAX_VALUE): Laid {
        val fontPx = max(6f, clip.fontSizeSp / REFERENCE_HEIGHT * size.height)
        val maxW = max(8f, size.width * 0.90f)
        val padX = if (clip.hasBackground) fontPx * 0.45f else 0f
        val padY = if (clip.hasBackground) fontPx * 0.25f else 0f
        val layout = measurer.measure(
            if (visibleChars >= clip.text.length) AnnotatedString(clip.text)
            else androidx.compose.ui.text.buildAnnotatedString {            // Typewriter: непропечатанная часть прозрачна, раскладка не прыгает
                append(clip.text)
                addStyle(androidx.compose.ui.text.SpanStyle(color = Color.Transparent), visibleChars.coerceAtLeast(0), clip.text.length)
            },
            TextStyle(fontSize = fontPx.toSp(), fontFamily = TextFonts.family(clip.fontId), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            softWrap = true,
            constraints = Constraints(maxWidth = max(1, (maxW - padX * 2).toInt())),
            layoutDirection = layoutDirection, density = this,
        )
        val boxW = layout.size.width + padX * 2; val boxH = layout.size.height + padY * 2
        // центр — в долях кадра; рамка свободно уезжает за край, чтобы жесты не «залипали» (вращение/масштаб)
        val cx = clip.positionX * size.width; val cy = clip.positionY * size.height
        return Laid(layout, fontPx, padX, padY, cx - boxW / 2, cy - boxH / 2, boxW, boxH)
    }

    fun DrawScope.measureBox(measurer: TextMeasurer, clip: TextClip): Box? = runCatching {
        if (clip.text.isBlank()) return null
        val l = lay(measurer, clip)
        Box(l.left + l.boxW / 2, l.top + l.boxH / 2, l.boxW, l.boxH, clip.rotationDeg)
    }.getOrNull()

    /**
     * [localMs] — время от начала слоя (для анимации появления); без него слой рисуется в конечном состоянии.
     * Слои: плашка → тень/свечение → контур (STROKE, скруглённые стыки — внутренние отверстия букв остаются чистыми) → заливка.
     */
    fun DrawScope.drawTextClip(measurer: TextMeasurer, clip: TextClip, localMs: Long = Long.MAX_VALUE) {
        if (clip.text.isBlank() || size.width < 8f || size.height < 8f) return
        try {
            val a = TextAnimator.frame(clip, localMs)
            if (a.alpha <= 0f) return
            val visible = if (a.visibleFraction >= 1f) Int.MAX_VALUE else kotlin.math.ceil(clip.text.length * a.visibleFraction).toInt()
            val l = lay(measurer, clip, visible)
            val pivot = Offset(l.left + l.boxW / 2, l.top + l.boxH / 2)
            fun Color.al() = copy(alpha = alpha * a.alpha)
            withTransform({
                translate(a.dx * size.width, 0f)
                if (a.scale != 1f) scale(a.scale, a.scale, pivot)
                rotate(clip.rotationDeg, pivot)
            }) {
                if (clip.hasBackground) drawRoundRect(Color(clip.backgroundColor).al(), Offset(l.left, l.top), Size(l.boxW, l.boxH), CornerRadius(l.fontPx * 0.3f))
                val at = Offset(l.left + l.padX, l.top + l.padY)
                val shadow = if (clip.shadowColor != 0L)
                    Shadow(Color(clip.shadowColor).al(), Offset(0f, clip.shadowDy * l.fontPx), blurRadius = clip.shadowBlur * l.fontPx)
                else null
                if (clip.strokeColor != 0L && clip.strokeWidth > 0f) {
                    // контур отдельным проходом под заливкой: толстая обводка со скруглёнными стыками, без размытия
                    drawText(l.layout, color = Color(clip.strokeColor).al(), topLeft = at, shadow = shadow,
                        drawStyle = Stroke(width = clip.strokeWidth * l.fontPx * 2f, join = StrokeJoin.Round, cap = StrokeCap.Round))
                    drawText(l.layout, color = Color(clip.textColor).al(), topLeft = at)
                } else drawText(l.layout, color = Color(clip.textColor).al(), topLeft = at, shadow = shadow)
            }
        } catch (_: Exception) {
            // zero-crash
        }
    }
}
