package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.base.editor.text.TextClip
import com.base.editor.text.TextClipRenderer.drawTextClip
import kotlin.math.max

/**
 * Текстовые слои в экспорте: на каждом кадре рисуются активные слои (тем же рисовальщиком, что и в превью)
 * в прозрачный Bitmap размером в кадр. Текст статичный, поэтому картинка перерисовывается только когда
 * меняется набор видимых слоёв.
 */
@UnstableApi
class TextBitmapOverlay(context: Context, private val clips: List<TextClip>, canvas: android.util.Size) : BitmapOverlay() {
    private val w = max(2, canvas.width)
    private val h = max(2, canvas.height)
    private val measurer = TextMeasurer(createFontFamilyResolver(context.applicationContext), Density(1f), LayoutDirection.Ltr, 32)
    private val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val scope = CanvasDrawScope()
    private val composeCanvas = Canvas(bitmap.asImageBitmap())
    private var lastKey: List<String>? = null
    private val settings: OverlaySettings = StaticOverlaySettings.Builder().setOverlayFrameAnchor(0f, 0f).setBackgroundFrameAnchor(0f, 0f).build()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1000
        val active = clips.filter { it.isVisibleAt(t) }
        if (active.isEmpty()) { lastKey = null; return blank }
        // ключ кэша: набор слоёв + фаза анимации появления (пока она идёт, картинка обновляется ~30 раз/с)
        val key = active.map { "${it.id}:${com.base.editor.text.TextAnimator.phase(it, t - it.startMs)}" }
        if (key != lastKey) {
            lastKey = key
            bitmap.eraseColor(Color.TRANSPARENT)
            scope.draw(Density(1f), LayoutDirection.Ltr, composeCanvas, Size(w.toFloat(), h.toFloat())) {
                active.forEach { drawTextClip(measurer, it, t - it.startMs) }
            }
        }
        return bitmap
    }
}
