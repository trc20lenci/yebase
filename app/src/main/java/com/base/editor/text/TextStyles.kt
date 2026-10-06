package com.base.editor.text

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Готовые стили текста (карусель в нижней панели). Применяются поверх шрифта/позиции слоя. */
object TextStyles {
    class Preset(val id: String, val label: String, val apply: (TextClip) -> TextClip)

    val all: List<Preset> = listOf(
        Preset("plain", "Обычный") { it.copy(styleId = "plain", textColor = 0xFFFFFFFF, backgroundColor = 0, strokeColor = 0, strokeWidth = 0f, shadowColor = 0x66000000, shadowBlur = 0f, shadowDy = 0.06f) },
        // Reels / TikTok: белый текст на аккуратной полупрозрачной чёрной скруглённой плашке
        Preset("reels", "Reels") { it.copy(styleId = "reels", textColor = 0xFFFFFFFF, backgroundColor = 0x99000000, strokeColor = 0, strokeWidth = 0f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f) },
        // Blogger / YouTube: жёлтый жирный + чёрный контур + лёгкая тень
        Preset("blogger", "Blogger") { it.copy(styleId = "blogger", textColor = 0xFFFFE600, backgroundColor = 0, strokeColor = 0xFF000000, strokeWidth = 0.09f, shadowColor = 0x66000000, shadowBlur = 0f, shadowDy = 0.07f, fontId = "MONTSERRAT_BLACK") },
        // Neon: яркий циан + свечение (мягкая тень с радиусом)
        Preset("neon", "Neon") { it.copy(styleId = "neon", textColor = 0xFF7DF9FF, backgroundColor = 0, strokeColor = 0, strokeWidth = 0f, shadowColor = 0xFF00E5FF, shadowBlur = 0.35f, shadowDy = 0f) },
        Preset("neon_pink", "Neon pink") { it.copy(styleId = "neon_pink", textColor = 0xFFFF9AF5, backgroundColor = 0, strokeColor = 0, strokeWidth = 0f, shadowColor = 0xFFFF2BD6, shadowBlur = 0.35f, shadowDy = 0f) },
        // Cinema: минималистичный чистый белый с глубокой тенью
        Preset("cinema", "Cinema") { it.copy(styleId = "cinema", textColor = 0xFFFFFFFF, backgroundColor = 0, strokeColor = 0, strokeWidth = 0f, shadowColor = 0xB3000000, shadowBlur = 0.22f, shadowDy = 0.1f, fontId = "INTER") },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Анимации появления текста. */
enum class TextAnimation(val id: String, val label: String) {
    NONE("none", "Без анимации"), FADE("fade", "Fade In"), POP("pop", "Scale Pop"), TYPEWRITER("typewriter", "Typewriter"), SLIDE("slide", "Slide In");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: NONE }
}

/** Состояние кадра анимации: прозрачность, масштаб, сдвиг по X (в долях ширины кадра), доля видимых символов. */
class AnimFrame(val alpha: Float, val scale: Float, val dx: Float, val visibleFraction: Float) {
    companion object { val FULL = AnimFrame(1f, 1f, 0f, 1f) }
}

object TextAnimator {
    const val BASE_MS = 600L

    fun durationMs(clip: TextClip): Long = when (TextAnimation.of(clip.animId)) {
        TextAnimation.NONE -> 0L
        TextAnimation.TYPEWRITER -> (clip.text.length * 55L).coerceIn(BASE_MS, 2500L)
        else -> BASE_MS
    }

    /** [localMs] — время от начала слоя. Для статичного кадра передайте [Long.MAX_VALUE]. */
    fun frame(clip: TextClip, localMs: Long): AnimFrame {
        val d = durationMs(clip)
        if (d == 0L || localMs >= d) return AnimFrame.FULL
        val p = (localMs.coerceAtLeast(0L).toFloat() / d).coerceIn(0f, 1f)
        return when (TextAnimation.of(clip.animId)) {
            TextAnimation.FADE -> AnimFrame(p, 1f, 0f, 1f)
            TextAnimation.POP -> AnimFrame(min(1f, p * 2.5f), 0.3f + 0.7f * easeOutBack(p), 0f, 1f)
            TextAnimation.TYPEWRITER -> AnimFrame(1f, 1f, 0f, p)
            TextAnimation.SLIDE -> AnimFrame(min(1f, p * 2f), 1f, -0.3f * (1f - easeOutCubic(p)), 1f)
            TextAnimation.NONE -> AnimFrame.FULL
        }
    }

    /** Фаза для кэша экспорта: пока идёт анимация, кадр меняется (шаг ≈ 33 мс), потом — константа. */
    fun phase(clip: TextClip, localMs: Long): Int {
        val d = durationMs(clip)
        return if (d == 0L || localMs >= d) -1 else (max(0L, localMs) / 33L).toInt()
    }

    private fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)
    private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }
}
