package com.base.editor.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Единые токены движения (по принципам Emil Kowalski): интерфейс отвечает на касание сразу, всё, что появляется,
 * идёт по сильной кривой ease-out, длительности короткие (UI ≤ 250 мс), ease-in не используется.
 */
object BaseMotion {
    /** Сильный ease-out для появления/исчезновения и ответа на касание. */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    const val PRESS_IN_MS = 90          // нажатие — мгновенный отклик
    const val PRESS_OUT_MS = 180        // отпускание — чуть медленнее
    const val ENTER_MS = 220
    const val EXIT_MS = 140
    const val STATE_MS = 160            // смена цвета/состояния чипа, иконки
    const val STAGGER_MS = 35           // шаг каскада в списках
    const val PRESS_SCALE = 0.96f
}

/** Система просит отключить анимации (Настройки → Специальные возможности / Для разработчиков). */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Нажимаемый элемент с откликом на касание: лёгкое сжатие и затемнение, возвращается по ease-out.
 * Ставится ПЕРВЫМ в цепочке модификаторов, чтобы масштабировалось всё — фон и содержимое вместе.
 * Стандартная «рябь» отключена: собственный отклик заметнее и не перекрывается фоном.
 */
fun Modifier.pressable(
    enabled: Boolean = true, role: Role? = null, pressedScale: Float = BaseMotion.PRESS_SCALE, onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduced) pressedScale else 1f,
        tween(if (pressed) BaseMotion.PRESS_IN_MS else BaseMotion.PRESS_OUT_MS, easing = BaseMotion.EaseOut), label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .alpha(if (pressed && enabled) 0.88f else 1f)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

private const val STAGGER_MAX_ITEMS = 10

/** Цвет выбранного/невыбранного чипа с плавной сменой (STATE_MS, ease-out). */
@Composable
fun animatedSelectColor(on: Boolean, onColor: androidx.compose.ui.graphics.Color = BaseColors.Primary, offColor: androidx.compose.ui.graphics.Color = BaseColors.DarkSlot): androidx.compose.ui.graphics.Color =
    androidx.compose.animation.animateColorAsState(if (on) onColor else offColor, tween(BaseMotion.STATE_MS, easing = BaseMotion.EaseOut), label = "chip").value

/**
 * Каскадное появление элемента списка: прозрачность + небольшой подъём, элементы стартуют с шагом [BaseMotion.STAGGER_MS].
 * Играет один раз при первом появлении; при отключённых анимациях элемент сразу виден.
 */
fun Modifier.staggeredEnter(index: Int): Modifier = composed {
    val reduced = rememberReducedMotion()
    val skip = reduced || index >= STAGGER_MAX_ITEMS          // длинные списки при прокрутке не переигрываются
    val progress = remember { Animatable(if (skip) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!skip) {
            delay(min(index, 8) * BaseMotion.STAGGER_MS.toLong())
            progress.animateTo(1f, tween(BaseMotion.ENTER_MS, easing = BaseMotion.EaseOut))
        }
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
    }
}
