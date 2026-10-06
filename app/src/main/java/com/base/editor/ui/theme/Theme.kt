package com.base.editor.ui.theme

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.base.editor.R
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Семантические цвета (рекомендации ui-ux-pro-max для тёмного редактора: OLED-тёмная гамма, холодные поверхности,
 * контраст текста ≥ 4.5:1). Бренд-акцент Cyan сохранён; рекомендованный навыком розовый — одна строка ниже.
 */
object BaseColors {
    val Cyan = Color(0xFF00CCDD)
    /** Альтернативный акцент по рекомендации ui-ux-pro-max (Video pink on dark); не используется, пока не выбран. */
    val AccentPink = Color(0xFFEC4899)
    val Ink = Color(0xFF111318)
    val Muted = Color(0xFF8A8F98)
    val Line = Color(0xFFE6E8EC)
    val SkyTop = Color(0xFFA9DBFF)
    val DarkBg = Color(0xFF0B1020)
    val DarkPanel = Color(0xFF161D30)
    val DarkSlot = Color(0xFF222B42)
    val TileEmpty = Color(0xFF2A3350)
    /** Вторичный текст на тёмном фоне (контраст ≈ 6:1). */
    val MutedOnDark = Color(0xFF94A3B8)
    val BorderOnDark = Color(0x14FFFFFF)
    val LightBg = Color(0xFFF7F9FC)
}

/** Inter — рекомендованная навыком гарнитура для «тёмных, точных» инструментов; вшита в приложение (кириллица есть). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val InterFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private fun interTypography(base: Typography = Typography()): Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = InterFamily), displayMedium = base.displayMedium.copy(fontFamily = InterFamily),
    displaySmall = base.displaySmall.copy(fontFamily = InterFamily), headlineLarge = base.headlineLarge.copy(fontFamily = InterFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = InterFamily), headlineSmall = base.headlineSmall.copy(fontFamily = InterFamily),
    titleLarge = base.titleLarge.copy(fontFamily = InterFamily), titleMedium = base.titleMedium.copy(fontFamily = InterFamily),
    titleSmall = base.titleSmall.copy(fontFamily = InterFamily), bodyLarge = base.bodyLarge.copy(fontFamily = InterFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = InterFamily), bodySmall = base.bodySmall.copy(fontFamily = InterFamily),
    labelLarge = base.labelLarge.copy(fontFamily = InterFamily), labelMedium = base.labelMedium.copy(fontFamily = InterFamily),
    labelSmall = base.labelSmall.copy(fontFamily = InterFamily),
)

@Composable
fun BaseTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = BaseColors.Cyan, onPrimary = Color.Black,
        background = BaseColors.DarkBg, onBackground = Color.White,
        surface = BaseColors.DarkPanel, onSurface = Color.White,
    ) else lightColorScheme(
        primary = BaseColors.Cyan, onPrimary = Color.Black,
        background = BaseColors.LightBg, onBackground = BaseColors.Ink,
        surface = Color.White, onSurface = BaseColors.Ink,
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = interTypography()) {
        // Text() без явной гарнитуры берёт стиль из LocalTextStyle — задаём Inter на всё приложение
        androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = InterFamily), content)
    }
}

fun soon(ctx: Context) = Toast.makeText(ctx, "Скоро появится", Toast.LENGTH_SHORT).show()
