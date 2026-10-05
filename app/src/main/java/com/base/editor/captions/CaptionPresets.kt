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
    )

    val default: CaptionStyle = all.first()
    fun byId(id: String): CaptionStyle? = all.firstOrNull { it.id == id }
}
