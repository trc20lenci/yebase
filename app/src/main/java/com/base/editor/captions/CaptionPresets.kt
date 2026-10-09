package com.base.editor.captions

/**
 * Стили BASE для коротких вертикальных видео: ЗАГЛАВНЫЕ жирные буквы, чёткий чёрный контур
 * (фиксированные 2.5 dp, круглые стыки — отрисовка как в Lottie TextLayer), аккуратная
 * фиксированная тень (смещение 2 dp, чёрный 40%) БЕЗ размытия и белого пересвета,
 * неоновый акцент для звучащего слова и пружинное «выпрыгивание» 1.0 → 1.15.
 */
object CaptionPresets {
    private fun c(v: Long) = v.toInt()
    const val NEON_YELLOW = 0xFFFFE600L
    const val NEON_GREEN = 0xFF00FF66L

    private val base = CaptionStyle(
        font = CaptionFont.MONTSERRAT, fontWeight = 900, uppercase = true, sizeFrac = 0.0625f,
        textColor = c(0xFFFFFFFF), strokeColor = c(0xFF000000), strokeEm = 0.09f,
        shadowColor = c(0x66000000), shadowBlurEm = 0f, shadowDyEm = 0f,   // тень 40% без размытия — читаемость без свечения
        activeScale = 1.15f, maxWidthFrac = 0.90f, positionY = 0.78f,
    )

    val all: List<CaptionStyle> = listOf(
        base.copy(id = "yellow", name = "Жёлтый", activeColor = c(NEON_YELLOW), animation = WordAnimation.POP),
        base.copy(id = "green", name = "Зелёный", activeColor = c(NEON_GREEN), animation = WordAnimation.POP),
        base.copy(id = "karaoke", name = "Караоке", activeColor = c(NEON_YELLOW), animation = WordAnimation.KARAOKE),
        base.copy(id = "contrast", name = "Контраст", activeColor = c(NEON_GREEN), strokeEm = 0.11f, sizeFrac = 0.058f, animation = WordAnimation.HIGHLIGHT),

        // классика и спокойные
        base.copy(id = "classic", name = "Классика", fontWeight = 800, uppercase = false, activeColor = c(0xFFFFFFFF), strokeEm = 0.08f, animation = WordAnimation.HIGHLIGHT),
        base.copy(id = "minimal", name = "Минимализм", font = CaptionFont.RUBIK, fontWeight = 500, uppercase = false, sizeFrac = 0.052f, strokeEm = 0f,
            shadowColor = c(0x80000000), shadowBlurEm = 0.10f, shadowDyEm = 0.05f, activeColor = c(0xFF00CCDD), animation = WordAnimation.HIGHLIGHT, activeScale = 1.06f),
        base.copy(id = "cinema", name = "Кино", fontWeight = 600, uppercase = false, sizeFrac = 0.05f, strokeEm = 0f, positionY = 0.86f,
            shadowColor = c(0xB3000000), shadowBlurEm = 0.22f, shadowDyEm = 0.10f, activeColor = c(0xFFFFFFFF), animation = WordAnimation.KARAOKE, activeScale = 1.0f),
        base.copy(id = "thin", name = "Тонкий контур", fontWeight = 700, strokeEm = 0.05f, activeColor = c(0xFF00CCDD), animation = WordAnimation.POP),

        // плашки
        base.copy(id = "plate_black", name = "Чёрная плашка", fontWeight = 800, uppercase = false, strokeEm = 0f, backgroundColor = c(0xCC000000),
            shadowColor = c(0), activeColor = c(NEON_YELLOW), animation = WordAnimation.HIGHLIGHT, activeScale = 1.05f),
        base.copy(id = "plate_white", name = "Белая плашка", fontWeight = 800, uppercase = false, textColor = c(0xFF111318), strokeEm = 0f, backgroundColor = c(0xF2FFFFFF),
            shadowColor = c(0x33000000), shadowBlurEm = 0.10f, shadowDyEm = 0.05f, activeColor = c(0xFFEC4899), animation = WordAnimation.HIGHLIGHT, activeScale = 1.05f),
        base.copy(id = "plate_glass", name = "Стекло", fontWeight = 700, uppercase = false, strokeEm = 0f, backgroundColor = c(0x66000000),
            shadowColor = c(0), activeColor = c(NEON_GREEN), animation = WordAnimation.POP),
        base.copy(id = "plate_pink", name = "Розовая плашка", fontWeight = 800, uppercase = false, strokeEm = 0f, backgroundColor = c(0xE6EC4899),
            shadowColor = c(0), activeColor = c(0xFFFFFFFF), animation = WordAnimation.KARAOKE, activeScale = 1.0f),

        // неон и цвета
        base.copy(id = "neon_cyan", name = "Неон бирюзовый", strokeEm = 0f, textColor = c(0xFF7DF9FF), shadowColor = c(0xFF00E5FF), shadowBlurEm = 0.35f, shadowDyEm = 0f,
            activeColor = c(0xFFFFFFFF), animation = WordAnimation.POP),
        base.copy(id = "neon_pink", name = "Неон розовый", strokeEm = 0f, textColor = c(0xFFFF9AF5), shadowColor = c(0xFFFF2BD6), shadowBlurEm = 0.35f, shadowDyEm = 0f,
            activeColor = c(0xFFFFFFFF), animation = WordAnimation.POP),
        base.copy(id = "neon_green", name = "Неон зелёный", strokeEm = 0f, textColor = c(0xFFB6FFCE), shadowColor = c(0xFF00FF66), shadowBlurEm = 0.32f, shadowDyEm = 0f,
            activeColor = c(0xFFFFFFFF), animation = WordAnimation.HIGHLIGHT),
        base.copy(id = "red", name = "Красный акцент", activeColor = c(0xFFFF1744), animation = WordAnimation.POP),
        base.copy(id = "blue", name = "Синий акцент", activeColor = c(0xFF2979FF), animation = WordAnimation.POP),
        base.copy(id = "violet", name = "Фиолетовый", activeColor = c(0xFFB388FF), strokeColor = c(0xFF1A0B3B), animation = WordAnimation.KARAOKE),
        base.copy(id = "orange", name = "Оранжевый", font = CaptionFont.OSWALD, fontWeight = 700, activeColor = c(0xFFFF9100), strokeEm = 0.10f, animation = WordAnimation.HIGHLIGHT),

        // выразительные шрифты
        base.copy(id = "comic", name = "Комикс", font = CaptionFont.RUSSO, fontWeight = 400, textColor = c(0xFFFFE600), strokeEm = 0.14f, activeColor = c(0xFFFF1744), animation = WordAnimation.POP, activeScale = 1.2f),
        base.copy(id = "game", name = "Игровой", font = CaptionFont.RUSSO, fontWeight = 400, textColor = c(0xFF00FF66), strokeColor = c(0xFF04210F), strokeEm = 0.12f, activeColor = c(NEON_YELLOW), animation = WordAnimation.HIGHLIGHT),
        base.copy(id = "script", name = "Рукописный", font = CaptionFont.PACIFICO, fontWeight = 400, uppercase = false, sizeFrac = 0.062f, strokeColor = c(0xFFEC4899), strokeEm = 0.07f,
            activeColor = c(0xFFFFE600), animation = WordAnimation.POP),
        base.copy(id = "tall", name = "Узкий", font = CaptionFont.OSWALD, fontWeight = 600, sizeFrac = 0.068f, strokeEm = 0.08f, activeColor = c(NEON_YELLOW), animation = WordAnimation.KARAOKE),
        base.copy(id = "big", name = "Крупный", sizeFrac = 0.078f, strokeEm = 0.10f, activeColor = c(NEON_YELLOW), animation = WordAnimation.POP, activeScale = 1.18f),
        base.copy(id = "italic", name = "Курсив", italic = true, fontWeight = 800, uppercase = false, activeColor = c(NEON_GREEN), animation = WordAnimation.HIGHLIGHT),
    )

    val default: CaptionStyle = all.first()
    fun byId(id: String): CaptionStyle? = all.firstOrNull { it.id == id }
}
