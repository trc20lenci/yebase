package com.base.editor.ui.editor

import com.base.editor.ui.theme.Lucide
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.BgMode
import com.base.editor.core.BgRemoval
import com.base.editor.core.ChromaKey
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val PANEL_BG = BaseColors.DarkPanel

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.pressable(onClick = onClose).size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(Lucide.Check, "Готово", tint = Color.White) }
    }
}

@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, Modifier.pressable(onClick = onClick).clip(RoundedCornerShape(12.dp)).background(animatedSelectColor(on)).padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
    )
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.pressable(onClick = onClick).size(36.dp).clip(CircleShape).background(Color(argb))
            .border(if (selected) 3.dp else 1.dp, if (selected) BaseColors.Primary else Color.White.copy(alpha = .3f), CircleShape),
    )
}

private val BG_COLORS = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF00B140.toInt(), 0xFF0047BB.toInt(), 0xFFFF2BD6.toInt(), 0xFFFFE600.toInt(), 0xFF1E1E1E.toInt())

// ───────────────────────── Скорость ─────────────────────────

private val SPEED_STOPS = floatArrayOf(0.1f, 0.5f, 1f, 2f, 5f, 10f)

/** Положение на линейке (0..1, точки равноудалены) → скорость: между точками — по логарифму, поэтому шаг ощущается ровным. */
private fun speedAt(t: Float): Float {
    val x = t.coerceIn(0f, 1f) * (SPEED_STOPS.size - 1)
    val i = x.toInt().coerceAtMost(SPEED_STOPS.size - 2)
    val f = x - i
    return exp(ln(SPEED_STOPS[i]) + f * (ln(SPEED_STOPS[i + 1]) - ln(SPEED_STOPS[i])))
}

private fun positionOf(speed: Float): Float {
    val s = speed.coerceIn(SPEED_STOPS.first(), SPEED_STOPS.last())
    var i = 0
    while (i < SPEED_STOPS.size - 2 && s > SPEED_STOPS[i + 1]) i++
    val f = (ln(s) - ln(SPEED_STOPS[i])) / (ln(SPEED_STOPS[i + 1]) - ln(SPEED_STOPS[i]))
    return (i + f) / (SPEED_STOPS.size - 1)
}

private fun speedLabel(v: Float) = if (v >= 10f) "10x" else if (abs(v - v.roundToInt()) < 0.005f) "${v.roundToInt()}x" else "%.1fx".format(v)

/**
 * Линейка скорости: засечки, точки 0.1x · 0.5x · 1x · 2x · 5x · 10x, плавное перетаскивание и быстрый тап по значению.
 * Пока палец движется, показывается живое значение; в клип (и на таймлайн, длина пересчитывается) скорость пишется при отпускании.
 */
@Composable
fun SpeedPanel(speed: Float, onCommit: (Float) -> Unit, onClose: () -> Unit) {
    var live by remember(speed) { mutableFloatStateOf(speed) }
    Column(Modifier.fillMaxSize().background(PANEL_BG).padding(horizontal = 16.dp, vertical = 8.dp)) {
        PanelHeader("Скорость", onClose)
        Text(speedLabel(live), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(72.dp)
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        // быстрый тап: ближайшая точка 0.1x … 10x
                        val padPx = 20.dp.toPx(); val w = size.width - 2 * padPx
                        val idx = ((p.x - padPx) / w * (SPEED_STOPS.size - 1)).roundToInt().coerceIn(0, SPEED_STOPS.size - 1)
                        live = SPEED_STOPS[idx]; onCommit(live)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            val padPx = 20.dp.toPx(); val w = size.width - 2 * padPx
                            live = speedAt((change.position.x - padPx) / w)
                        },
                        onDragEnd = {
                            // у точки — прилипание, чтобы 1x выставлялся точно
                            val near = SPEED_STOPS.minByOrNull { abs(ln(it) - ln(live)) }!!
                            if (abs(ln(near) - ln(live)) < 0.06f) live = near
                            onCommit(live)
                        },
                    )
                },
        ) {
            val pad = 20.dp.toPx(); val w = size.width - 2 * pad; val y = 26.dp.toPx()
            drawLine(Color.White.copy(alpha = .25f), Offset(pad, y), Offset(pad + w, y), 3.dp.toPx())
            val tNow = positionOf(live)
            drawLine(BaseColors.Primary, Offset(pad, y), Offset(pad + w * tNow, y), 3.dp.toPx())
            // мелкие засечки между точками
            for (seg in 0 until SPEED_STOPS.size - 1) for (k in 1..4) {
                val x = pad + w * (seg + k / 5f) / (SPEED_STOPS.size - 1)
                drawLine(Color.White.copy(alpha = .35f), Offset(x, y - 5.dp.toPx()), Offset(x, y + 5.dp.toPx()), 1.dp.toPx())
            }
            // крупные точки с подписями
            SPEED_STOPS.forEachIndexed { i, v ->
                val x = pad + w * i / (SPEED_STOPS.size - 1)
                val on = abs(ln(v) - ln(live)) < 0.02f
                drawCircle(if (on) BaseColors.Primary else Color.White.copy(alpha = .8f), if (on) 7.dp.toPx() else 5.dp.toPx(), Offset(x, y))
                drawContext.canvas.nativeCanvas.drawText(
                    speedLabel(v), x, y + 34.dp.toPx(),
                    android.graphics.Paint().apply { color = if (on) android.graphics.Color.WHITE else 0x99FFFFFF.toInt(); textSize = 13.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = on },
                )
            }
            drawCircle(Color.White, 11.dp.toPx(), Offset(pad + w * tNow, y))
            drawCircle(BaseColors.Primary, 7.dp.toPx(), Offset(pad + w * tNow, y))
        }
        Text("Тон звука сохраняется. Длина клипа на таймлайне пересчитывается.", color = BaseColors.MutedOnDark, fontSize = 11.sp)
    }
}

// ───────────────────────── Громкость ─────────────────────────

@Composable
fun VolumePanel(volume: Float, onChange: (Float) -> Unit, onClose: () -> Unit) {
    var live by remember(volume) { mutableFloatStateOf(volume) }
    var lastNonZero by remember { mutableFloatStateOf(if (volume > 0f) volume else 1f) }
    Column(Modifier.fillMaxSize().background(PANEL_BG).padding(horizontal = 16.dp, vertical = 8.dp)) {
        PanelHeader("Громкость", onClose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${(live * 100).roundToInt()}%", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(
                Modifier.pressable {
                    if (live > 0f) { lastNonZero = live; live = 0f } else live = lastNonZero
                    onChange(live)
                }.clip(RoundedCornerShape(12.dp)).background(animatedSelectColor(live == 0f, onColor = BaseColors.Destructive)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.VolumeX, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (live == 0f) "Звук выкл." else "Mute", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Slider(
            value = live, onValueChange = { v -> live = if (abs(v - 1f) < 0.04f) 1f else v },     // у 100% лёгкое прилипание
            onValueChangeFinished = { onChange(live) }, valueRange = 0f..2f,
            colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary, inactiveTrackColor = Color.White.copy(alpha = .2f)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0% — тишина", color = BaseColors.MutedOnDark, fontSize = 11.sp)
            Text("100% — оригинал", color = BaseColors.MutedOnDark, fontSize = 11.sp)
            Text("200% — +6 дБ", color = BaseColors.MutedOnDark, fontSize = 11.sp)
        }
    }
}

// ───────────────────────── Хромакей ─────────────────────────

/**
 * Хромакей: пипетка — кольцо прямо на кадре (двигается пальцем, цвет под центром берётся сразу), два слайдера:
 * «Интенсивность» (порог схожести цвета) и «Тени» (мягкость края и подавление цветового подсвета).
 */
@Composable
fun ChromaPanel(
    key: ChromaKey?, pipetteOn: Boolean, onPipette: () -> Unit, onReset: () -> Unit,
    onChange: ((ChromaKey) -> ChromaKey) -> Unit, onDone: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(BaseColors.DarkPanel).padding(horizontal = 16.dp, vertical = 6.dp).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Row(
                Modifier.align(Alignment.CenterStart).pressable(onClick = onReset).clip(RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.RotateCcw, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text("Сброс", color = Color.White, fontSize = 14.sp)
            }
            Text("Хромакей", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).pressable(onClick = onClose).size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Icon(Lucide.Check, "Готово", tint = Color.White)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.pressable(onClick = onPipette).size(60.dp).clip(CircleShape).background(BaseColors.DarkSlot)
                    .border(if (pipetteOn) 2.dp else 1.dp, if (pipetteOn) BaseColors.Primary else Color.White.copy(alpha = .15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Pipette, null, tint = BaseColors.Primary, modifier = Modifier.size(26.dp)) }
            Text("Пипетка", color = BaseColors.Primary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (key == null) Text("Перетащите кольцо на цвет, который нужно убрать", color = BaseColors.MutedOnDark, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        SideSlider("Интенсивность", key?.similarity ?: 0f, key != null, { v -> onChange { it.copy(similarity = v) } }, onDone)
        SideSlider("Тени", key?.smoothness ?: 0f, key != null, { v -> onChange { it.copy(smoothness = v) } }, onDone)
    }
}

/** Подпись слева, ползунок справа — как в панелях CapCut. */
@Composable
private fun SideSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else .5f), fontSize = 13.sp, modifier = Modifier.width(104.dp))
        Slider(
            value, onChange, onValueChangeFinished = onDone, enabled = enabled, valueRange = 0f..1f, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BaseColors.Primary, inactiveTrackColor = Color.White.copy(alpha = .18f),
                disabledThumbColor = Color.White.copy(alpha = .6f), disabledActiveTrackColor = Color.White.copy(alpha = .18f), disabledInactiveTrackColor = Color.White.copy(alpha = .18f)),
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", color = BaseColors.MutedOnDark, fontSize = 13.sp)
        }
        Slider(value, onChange, onValueChangeFinished = onDone, valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary, inactiveTrackColor = Color.White.copy(alpha = .2f)))
    }
}

// ───────────────────────── Удаление фона ─────────────────────────

/** Состояние фоновой обработки: [progress] 0..1. */
class BgJobState(val clipId: Long, val progress: Float)

@Composable
fun BgPanel(
    hasMask: Boolean, bg: BgRemoval?, job: BgJobState?, onStart: (Boolean) -> Unit, onCancel: () -> Unit,
    onChange: ((BgRemoval) -> BgRemoval) -> Unit, onDone: () -> Unit, onDisable: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PANEL_BG).padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
        PanelHeader("Удалить фон", onClose)
        when {
            job != null -> {
                // работа идёт в фоне: интерфейс не блокируется, можно закрыть панель и продолжать монтаж
                Text("Обработка… ${(job.progress * 100).roundToInt()}%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = BaseColors.Primary, trackColor = Color.White.copy(alpha = .15f))
                Spacer(Modifier.height(10.dp))
                Pill("Отмена", false, onCancel)
            }
            bg == null || !hasMask -> {
                Text(
                    "Силуэт человека выделяется моделью прямо на устройстве. При первом запуске скачивается лёгкая модель (≈0,3 МБ). " +
                        "Обработка идёт в фоне, для длинных клипов — до нескольких минут.",
                    color = BaseColors.MutedOnDark, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Pill(if (hasMask) "Применить" else "Удалить фон", true) { onStart(false) }
            }
            else -> {
                Text("Фон", color = BaseColors.MutedOnDark, fontSize = 12.sp)
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Цвет", bg.mode == BgMode.COLOR) { onChange { it.copy(mode = BgMode.COLOR) }; onDone() }
                    Pill("Размытие", bg.mode == BgMode.BLUR) { onChange { it.copy(mode = BgMode.BLUR) }; onDone() }
                }
                if (bg.mode == BgMode.COLOR) {
                    Text("Прозрачная область заливается цветом (под основной дорожкой слоёв нет)", color = BaseColors.MutedOnDark, fontSize = 11.sp)
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BG_COLORS.forEach { c -> Swatch(c, bg.bgColor == c) { onChange { it.copy(bgColor = c) }; onDone() } }
                    }
                } else LabeledSlider("Сила размытия", bg.blur, { v -> onChange { it.copy(blur = v) } }, onDone)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Обводка контура", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(bg.outline, { on -> onChange { it.copy(outline = on) }; onDone() }, colors = SwitchDefaults.colors(checkedTrackColor = BaseColors.Primary, checkedThumbColor = Color.Black))
                }
                if (bg.outline) {
                    LabeledSlider("Толщина обводки", bg.outlineWidth, { v -> onChange { it.copy(outlineWidth = v) } }, onDone)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BG_COLORS.forEach { c -> Swatch(c, bg.outlineColor == c) { onChange { it.copy(outlineColor = c) }; onDone() } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Пересчитать", false) { onStart(true) }
                    Pill("Отключить", false, onDisable)
                }
            }
        }
    }
}

// ───────────────────────── Пипетка ─────────────────────────

/** Выбор цвета прямо на кадре видео: касание или движение пальцем берёт цвет (среднее по маленькой области). */
@Composable
fun ColorPickDialog(frame: androidx.compose.ui.graphics.ImageBitmap?, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var picked by remember { mutableStateOf<Int?>(null) }
        var marker by remember { mutableStateOf<Offset?>(null) }
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Отмена", Modifier.pressable(onClick = onCancel).padding(10.dp), color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                picked?.let { Box(Modifier.size(32.dp).clip(CircleShape).background(Color(it)).border(2.dp, Color.White, CircleShape)); Spacer(Modifier.width(10.dp)) }
                Text("Готово", Modifier.pressable(enabled = picked != null) { picked?.let(onPick) }.clip(RoundedCornerShape(10.dp))
                    .background(if (picked != null) BaseColors.Primary else Color.White.copy(alpha = .15f)).padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (picked != null) Color.Black else Color.White.copy(alpha = .5f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Коснитесь цвета, который нужно убрать", color = BaseColors.MutedOnDark, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (frame == null) Text("Загрузка кадра…", color = Color.White.copy(alpha = .6f))
                else {
                    val bmp = remember(frame) { frame.asAndroidBitmap() }
                    fun sample(p: Offset, w: Int, h: Int) {
                        val cx = (p.x / w * bmp.width).toInt().coerceIn(0, bmp.width - 1); val cy = (p.y / h * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                        var r = 0; var g = 0; var b = 0; var n = 0
                        for (dy in -2..2) for (dx in -2..2) {
                            val px = bmp.getPixel((cx + dx).coerceIn(0, bmp.width - 1), (cy + dy).coerceIn(0, bmp.height - 1))
                            r += android.graphics.Color.red(px); g += android.graphics.Color.green(px); b += android.graphics.Color.blue(px); n++
                        }
                        picked = android.graphics.Color.rgb(r / n, g / n, b / n); marker = p
                    }
                    Box(
                        Modifier.aspectRatio(frame.width.toFloat() / frame.height)
                            .pointerInput(frame) { detectTapGestures { p -> sample(p, size.width, size.height) } }
                            .pointerInput(frame) { detectDragGestures { change, _ -> change.consume(); sample(change.position, size.width, size.height) } },
                    ) {
                        androidx.compose.foundation.Image(frame, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            marker?.let { m ->
                                drawCircle(Color.White, 14.dp.toPx(), m, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                drawCircle(Color.Black, 16.dp.toPx(), m, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}
