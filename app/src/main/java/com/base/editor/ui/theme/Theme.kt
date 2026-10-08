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
 * Акцент — прежний бирюзовый (по просьбе); остальные роли по ui-ux-pro-max: Accent #2563EB, Destructive #DC2626. Светлые экраны остаются белыми, как были; редактор — тёмный, как был.
 */
object BaseColors {
    val Primary = Color(0xFF00CCDD)
    val OnPrimary = Color(0xFF000000)
    val Secondary = Color(0xFF00A3B3)
    /** Синий акцент: таймлайн и вторичные CTA. */
    val Accent = Color(0xFF2563EB)
    val Destructive = Color(0xFFDC2626)

    // светлые экраны (Дом / Проекты / Я)
    val Ink = Color(0xFF111318)
    val Muted = Color(0xFF8A8F98)
    val Line = Color(0xFFE6E8EC)
    val SkyTop = Color(0xFFA9DBFF)
    val LightBg = Color.White

    // тёмный редактор
    val DarkBg = Color(0xFF0F0F11)
    val DarkPanel = Color(0xFF1B1C20)
    val DarkSlot = Color(0xFF26272C)
    val TileEmpty = Color(0xFF2E2F35)
    val MutedOnDark = Color(0xFF94A3B8)
    val BorderOnDark = Color(0x14FFFFFF)
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
    val scheme = if (dark) darkColorScheme(
        primary = BaseColors.Primary, onPrimary = BaseColors.OnPrimary, secondary = BaseColors.Secondary, tertiary = BaseColors.Accent,
        background = BaseColors.DarkBg, onBackground = Color.White,
        surface = BaseColors.DarkPanel, onSurface = Color.White, error = BaseColors.Destructive,
    ) else lightColorScheme(
        primary = BaseColors.Primary, onPrimary = BaseColors.OnPrimary, secondary = BaseColors.Secondary, tertiary = BaseColors.Accent,
        background = BaseColors.LightBg, onBackground = BaseColors.Ink,
        surface = Color.White, onSurface = BaseColors.Ink, error = BaseColors.Destructive,
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    // текст чуть мельче по всему приложению
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * TEXT_SCALE),
    ) {
        MaterialTheme(colorScheme = scheme, typography = interTypography()) {
            // Text() без явной гарнитуры берёт стиль из LocalTextStyle — задаём Inter на всё приложение
            androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = InterFamily), content)
        }
    }
}

private const val TEXT_SCALE = 0.92f

fun soon(ctx: Context) = Toast.makeText(ctx, "Скоро появится", Toast.LENGTH_SHORT).show()
