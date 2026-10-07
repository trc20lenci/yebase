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
 * Палитра ровно по рекомендации ui-ux-pro-max для видеоредактора (стиль Dark Mode OLED, «video pink on dark + timeline blue»):
 * Primary #EC4899 (текст на нём #000000), Secondary #DB2777, Accent/CTA #2563EB, Background #0F172A, Card #192134,
 * Muted #201A32, Muted Foreground #94A3B8, Border rgba(255,255,255,.08), Destructive #DC2626.
 */
object BaseColors {
    val Primary = Color(0xFFEC4899)
    val OnPrimary = Color(0xFF000000)
    val Secondary = Color(0xFFDB2777)
    /** Синий акцент: таймлайн и вторичные CTA. */
    val Accent = Color(0xFF2563EB)
    val DarkBg = Color(0xFF0F172A)
    /** Card. */
    val DarkPanel = Color(0xFF192134)
    /** Muted. */
    val DarkSlot = Color(0xFF201A32)
    val TileEmpty = Color(0xFF201A32)
    val MutedOnDark = Color(0xFF94A3B8)
    val Muted = MutedOnDark
    val BorderOnDark = Color(0x14FFFFFF)
    val Line = BorderOnDark
    val Destructive = Color(0xFFDC2626)
    /** Основной текст на тёмном фоне. */
    val OnBg = Color(0xFFFFFFFF)
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
    // по рекомендации навыка светлая тема для этого стиля не используется: приложение всегда тёмное
    val scheme = darkColorScheme(
        primary = BaseColors.Primary, onPrimary = BaseColors.OnPrimary,
        secondary = BaseColors.Secondary, onSecondary = Color.White,
        tertiary = BaseColors.Accent, onTertiary = Color.White,
        background = BaseColors.DarkBg, onBackground = Color.White,
        surface = BaseColors.DarkPanel, onSurface = Color.White,
        surfaceVariant = BaseColors.DarkSlot, onSurfaceVariant = BaseColors.MutedOnDark,
        outline = BaseColors.MutedOnDark, outlineVariant = BaseColors.BorderOnDark,
        error = BaseColors.Destructive, onError = Color.White,
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = interTypography()) {
        // Text() без явной гарнитуры берёт стиль из LocalTextStyle — задаём Inter на всё приложение
        androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = InterFamily), content)
    }
}

fun soon(ctx: Context) = Toast.makeText(ctx, "Скоро появится", Toast.LENGTH_SHORT).show()
