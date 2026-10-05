package com.base.editor.captions

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Единственный рисовальщик субтитров BASE: им пользуются и оверлей превью (Canvas),
 * и экспорт (рисование в Bitmap). Поэтому в готовом видео субтитры выглядят так же, как в редакторе.
 *
 * Всё считается в пикселях области рисования; размеры стиля — доли высоты кадра [DrawScope.size].
 * Никаких исключений наружу: любой сбой разметки превращается в пропуск кадра.
 */
object CaptionRenderer {
    private class Placed(val layout: TextLayoutResult, val index: Int, var x: Float = 0f, var y: Float = 0f)
    private class Line(val words: MutableList<Placed> = mutableListOf(), var width: Float = 0f, var height: Float = 0f)

    /**
     * @param centerY вертикальный центр блока в локальных координатах (px).
     * @param viewportHeight высота полного кадра — от неё считается размер шрифта
     *   (нужна, когда рисуем только «полосу» кадра).
     */
    fun DrawScope.drawCaption(
        measurer: TextMeasurer,
        item: CaptionItem,
        style: CaptionStyle,
        timeMs: Long,
        centerY: Float = size.height * style.positionY,
        viewportHeight: Float = size.height,
    ) {
        if (size.width < 8f || size.height < 8f || item.words.isEmpty()) return
        try {
            drawInternal(measurer, item, style, timeMs, centerY, viewportHeight)
        } catch (_: Exception) {
            // zero-crash: субтитры не должны ронять ни превью, ни экспорт
        }
    }

    private fun DrawScope.drawInternal(measurer: TextMeasurer, item: CaptionItem, style: CaptionStyle, timeMs: Long, centerY: Float, viewportHeight: Float) {
        val maxW = max(8f, size.width * style.maxWidthFrac.coerceIn(0.3f, 1f))
        var fontPx = max(6f, style.sizeFrac * viewportHeight)
        var lines = layout(measurer, item, style, fontPx, maxW)
        // подгонка под ширину: если фраза не влезает в одну строку — сначала уменьшаем шрифт (до 55%), потом переносим
        val oneLine = lines.sumOf { it.width.toDouble() }.toFloat() + fontPx * 0.28f * (item.words.size - 1)
        if (oneLine > maxW) {
            fontPx = max(fontPx * 0.55f, fontPx * maxW / oneLine)
            lines = layout(measurer, item, style, fontPx, maxW)
        }
        if (lines.isEmpty()) return

        val pad = if (style.hasBackground) style.backgroundPaddingEm * fontPx else 0f
        val lineGap = fontPx * 0.08f + pad * 0.6f
        val blockH = lines.sumOf { it.height.toDouble() }.toFloat() + lineGap * (lines.size - 1)
        // блок целиком внутри области (без coerceIn с перевёрнутыми границами)
        var top = centerY - blockH / 2
        top = min(top, size.height - blockH - 2f)
        top = max(top, 2f)

        // появление карточки: масштаб 0.8 → 1 и сдвиг снизу (как в коротких видео)
        val enter = easeOut(((timeMs - item.startMs) / ENTER_MS).coerceIn(0f, 1f))
        val enterScale = 0.8f + 0.2f * enter
        val enterDy = (1f - enter) * viewportHeight * 0.026f
        val pivot = Offset(size.width / 2f, top + blockH / 2f)

        withTransform({ translate(0f, enterDy); scale(enterScale, enterScale, pivot) }) {
            var y = top
            for (line in lines) {
                val left = (size.width - line.width) / 2
                if (style.hasBackground) {
                    drawRoundRect(
                        color = Color(style.backgroundColor),
                        topLeft = Offset(left - pad, y - pad * 0.5f),
                        size = Size(line.width + pad * 2, line.height + pad),
                        cornerRadius = CornerRadius(style.backgroundCornerEm * fontPx),
                    )
                }
                for (p in line.words) {
                    val w = item.words[p.index]
                    val active = timeMs >= w.startMs && timeMs < w.endMs
                    val past = timeMs >= w.endMs
                    val color = when (style.animation) {
                        WordAnimation.KARAOKE -> if (past || active) style.activeColor else style.textColor
                        else -> if (active) style.activeColor else style.textColor
                    }
                    var scale = 1f
                    if (style.animation == WordAnimation.POP) {
                        // пружина с перелётом при начале слова, плавный возврат после его конца
                        val rise = Spring.step((timeMs - w.startMs) / 1000f)
                        val release = if (timeMs >= w.endMs) 1f - easeOut(((timeMs - w.endMs) / RELEASE_MS).coerceIn(0f, 1f)) else 1f
                        scale = 1f + (style.activeScale - 1f) * rise * release
                    }
                    val topLeft = Offset(left + p.x, y + (line.height - p.layout.size.height) / 2)
                    val wp = Offset(topLeft.x + p.layout.size.width / 2f, topLeft.y + p.layout.size.height / 2f)
                    withTransform({ scale(scale, scale, wp) }) { drawWord(p.layout, style, color, topLeft, fontPx) }
                }
                y += line.height + lineGap
            }
        }
    }

    private fun DrawScope.layout(measurer: TextMeasurer, item: CaptionItem, style: CaptionStyle, fontPx: Float, maxW: Float): MutableList<Line> {
        val base = TextStyle(
            fontFamily = CaptionFonts.family(style.font, style.fontWeight, style.italic),
            fontSize = fontPx.toSp(),
            letterSpacing = (style.letterSpacingEm * fontPx).toSp(),
        )
        val spaceW = fontPx * 0.28f
        val lines = mutableListOf(Line())
        item.words.forEachIndexed { i, w ->
            val text = if (style.uppercase) w.word.uppercase() else w.word
            if (text.isBlank()) return@forEachIndexed
            val layout = measurer.measure(AnnotatedString(text), base, softWrap = false, maxLines = 1,
                constraints = Constraints(), layoutDirection = layoutDirection, density = this)
            var line = lines.last()
            val needed = layout.size.width + if (line.words.isEmpty()) 0f else spaceW
            if (line.words.isNotEmpty() && line.width + needed > maxW) { line = Line(); lines += line }
            val x = line.width + if (line.words.isEmpty()) 0f else spaceW
            line.words += Placed(layout, i, x)
            line.width = x + layout.size.width
            line.height = max(line.height, layout.size.height.toFloat())
        }
        lines.removeAll { it.words.isEmpty() }
        return lines
    }

    private fun DrawScope.drawWord(layout: TextLayoutResult, style: CaptionStyle, fill: Int, topLeft: Offset, fontPx: Float) {
        // Рендеринг строго в два слоя (как TextLayer в Lottie): контур, затем чистая заливка.
        // Никаких BlurMaskFilter и размытий: внутренние отверстия букв («о», «е», «а», «в») остаются чистыми.
        // 1) контур: STROKE с круглыми стыками и окончаниями, толщина строго 2.5 dp
        if (style.strokeEm > 0f) {
            drawText(layout, color = Color(style.strokeColor), topLeft = topLeft,
                drawStyle = Stroke(width = STROKE_DP.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
        // 2) заливка основным цветом поверх контура + фиксированная тень (offset 2 dp, чёрный 40%), без пересвета
        val shadow = if ((style.shadowColor ushr 24) > 0)
            Shadow(Color(style.shadowColor), Offset(0f, SHADOW_DY_DP.dp.toPx()), blurRadius = 0f) else null
        drawText(layout, color = Color(fill), topLeft = topLeft, shadow = shadow)
    }

    private const val ENTER_MS = 170f
    private const val RELEASE_MS = 140f
    // геометрия контура и тени — фиксированная (в dp), как в эталонной отрисовке Lottie TextLayer
    private const val STROKE_DP = 2.5f
    private const val SHADOW_DY_DP = 2f
    private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
}

/** Пружина с перелётом (затухающие колебания), 0 → 1: «выпрыгивание» слова. */
internal object Spring {
    private const val DAMPING = 0.45f
    private const val OMEGA = 30f                                   // рад/с: полный цикл ≈ 0.2 с

    fun step(tSeconds: Float): Float {
        if (tSeconds <= 0f) return 0f
        val wd = OMEGA * sqrt(1f - DAMPING * DAMPING)
        val decay = exp(-DAMPING * OMEGA * tSeconds)
        return 1f - decay * (cos(wd * tSeconds) + DAMPING * OMEGA / wd * sin(wd * tSeconds))
    }
}
