package com.base.editor.text

import androidx.compose.ui.text.font.FontFamily
import com.base.editor.captions.CaptionFont
import com.base.editor.captions.CaptionFonts

/** Подборка шрифтов для текстовых слоёв: встроенные (кириллица есть у всех) + системные. */
object TextFonts {
    data class Entry(val id: String, val label: String, val family: FontFamily)

    val all: List<Entry> by lazy {
        listOf(
            Entry("MONTSERRAT", "Montserrat", CaptionFonts.family(CaptionFont.MONTSERRAT, 700, false)),
            Entry("OSWALD", "Oswald", CaptionFonts.family(CaptionFont.OSWALD, 600, false)),
            Entry("RUBIK", "Rubik", CaptionFonts.family(CaptionFont.RUBIK, 700, false)),
            Entry("RUSSO", "Russo One", CaptionFonts.family(CaptionFont.RUSSO, 400, false)),
            Entry("PACIFICO", "Pacifico", CaptionFonts.family(CaptionFont.PACIFICO, 400, false)),
            Entry("SERIF", "Serif", FontFamily.Serif),
            Entry("CURSIVE", "Рукописный", FontFamily.Cursive),
            Entry("MONO", "Mono", FontFamily.Monospace),
        )
    }

    fun family(id: String): FontFamily = all.firstOrNull { it.id == id }?.family ?: all.first().family
}
