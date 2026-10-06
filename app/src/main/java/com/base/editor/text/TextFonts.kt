package com.base.editor.text

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.base.editor.R
import com.base.editor.captions.CaptionFont
import com.base.editor.captions.CaptionFonts

/**
 * Подборка шрифтов для текстовых слоёв (все с кириллицей, лицензия OFL). Шрифты вшиты в приложение: в экспорте
 * текст рисуется вне UI, и ему нужны гарантированно готовые гарнитуры без сети и сервисов Google Play.
 */
@OptIn(ExperimentalTextApi::class)
object TextFonts {
    data class Entry(val id: String, val label: String, val family: FontFamily)

    private fun variable(res: Int, w: Int) = FontFamily(Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))))

    val all: List<Entry> by lazy {
        listOf(
            Entry("MONTSERRAT", "Montserrat", CaptionFonts.family(CaptionFont.MONTSERRAT, 700, false)),
            Entry("MONTSERRAT_BLACK", "Montserrat Black", CaptionFonts.family(CaptionFont.MONTSERRAT, 900, false)),
            Entry("OSWALD", "Oswald", CaptionFonts.family(CaptionFont.OSWALD, 600, false)),
            Entry("RUBIK", "Rubik", CaptionFonts.family(CaptionFont.RUBIK, 700, false)),
            Entry("INTER", "Inter", variable(R.font.inter, 800)),
            Entry("FIRA_COND", "Fira Condensed", FontFamily(Font(R.font.fira_condensed, FontWeight.ExtraBold))),
            Entry("CAVEAT", "Caveat", variable(R.font.caveat, 700)),
            Entry("RUSSO", "Russo One", CaptionFonts.family(CaptionFont.RUSSO, 400, false)),
            Entry("PACIFICO", "Pacifico", CaptionFonts.family(CaptionFont.PACIFICO, 400, false)),
        )
    }

    fun family(id: String): FontFamily = all.firstOrNull { it.id == id }?.family ?: all.first().family
}
