package com.base.editor.text

import java.util.UUID

/**
 * Текстовый слой на дорожке «Текст». Неизменяемая модель: правка = copy(), поэтому состояние
 * безопасно раздаётся через StateFlow.
 *
 * @param positionX/positionY центр текста в долях кадра (0.5 — середина).
 * @param fontSizeSp размер относительно эталонной высоты кадра [TextClipRenderer.REFERENCE_HEIGHT]:
 *   в превью и в экспорте текст занимает одну и ту же долю кадра.
 * @param textColor / backgroundColor — ARGB (прозрачный фон — alpha = 0).
 */
data class TextClip(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startMs: Long,
    val durationMs: Long = 3000L,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val fontSizeSp: Float = 24f,
    val textColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000,
    /** Поворот по часовой стрелке, градусы. */
    val rotationDeg: Float = 0f,
    /** Имя анимированного шаблона титра (.pag) или null — обычный текст. */
    val pagTemplate: String? = null,
    /** Шрифт из [TextFonts]. */
    val fontId: String = "MONTSERRAT",
    /** Контур: цвет ARGB (0 — нет) и толщина в долях размера шрифта. */
    val strokeColor: Long = 0,
    val strokeWidth: Float = 0f,
    /** Тень/свечение: цвет ARGB (0 — нет), размытие и смещение по Y в долях размера шрифта. */
    val shadowColor: Long = 0,
    val shadowBlur: Float = 0f,
    val shadowDy: Float = 0f,
    /** Пресет стиля (для подсветки в карусели) и анимация появления из [TextAnimation]. */
    val styleId: String = "plain",
    val animId: String = "none",
) {
    val endMs get() = startMs + durationMs
    val hasBackground get() = (backgroundColor ushr 24) > 0L

    fun isVisibleAt(timeMs: Long) = timeMs >= startMs && timeMs < startMs + durationMs

    companion object {
        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 30_000L
    }
}
