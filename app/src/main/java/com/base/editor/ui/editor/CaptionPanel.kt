package com.base.editor.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.captions.CaptionFont
import com.base.editor.captions.CaptionFonts
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionPresets
import com.base.editor.captions.CaptionStyle
import com.base.editor.captions.GenerationState
import com.base.editor.captions.WordAnimation
import com.base.editor.captions.WordTimestamp
import androidx.compose.foundation.layout.height
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable

private val Palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF6D00, 0xFFFF1744, 0xFFFF2DF1,
    0xFF7C4DFF, 0xFF2979FF, 0xFF00E5FF, 0xFF00E676, 0xFFB2FF59, 0xFFBDBDBD,
).map { it.toInt() }

/** Панель «Субтитры»: создание, список с правкой текста/таймингов, стили. */
@Composable
fun CaptionPanel(
    items: List<CaptionItem>,
    style: CaptionStyle,
    generation: GenerationState,
    playheadMs: Long,
    editingId: String?,
    language: com.base.editor.captions.CaptionLanguage,
    onLanguage: (com.base.editor.captions.CaptionLanguage) -> Unit,
    onGenerate: () -> Unit,
    onDismissError: () -> Unit,
    onOpenItem: (CaptionItem) -> Unit,
    onCloseEdit: () -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTiming: (String, Long, Long) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onClearAll: () -> Unit,
    onPreset: (String) -> Unit,
    onStyle: ((CaptionStyle) -> CaptionStyle) -> Unit,
    onClose: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxWidth().background(BaseColors.DarkPanel).padding(top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Текст", "Стиль").forEachIndexed { i, t ->
                Text(t, Modifier.pressable { tab = i }.clip(RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (tab == i) BaseColors.Cyan else Color.White.copy(alpha = .7f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.pressable(onClick = onClose).size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, "Готово", tint = Color.White) }
        }
        Box(Modifier.heightIn(max = 250.dp).fillMaxWidth()) {
            if (tab == 0) TextTab(items, generation, playheadMs, language, onLanguage, onGenerate, onDismissError, onOpenItem, onAdd, onClearAll)
            else StyleTab(style, onPreset, onStyle)
        }
    }

    items.firstOrNull { it.id == editingId }?.let { EditDialog(it, onCloseEdit, onUpdateText, onUpdateTiming, onDelete) }
}

@Composable
private fun TextTab(
    items: List<CaptionItem>, generation: GenerationState, playheadMs: Long,
    language: com.base.editor.captions.CaptionLanguage, onLanguage: (com.base.editor.captions.CaptionLanguage) -> Unit,
    onGenerate: () -> Unit, onDismissError: () -> Unit,
    onOpen: (CaptionItem) -> Unit, onAdd: () -> Unit, onClearAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        when (generation) {
            GenerationState.Generating -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = BaseColors.Cyan, strokeWidth = 2.5.dp)
                Text("Создание субтитров...", color = Color.White.copy(alpha = .9f), fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
            }
            else -> {
                if (generation is GenerationState.Failed) {
                    Text(generation.message, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.pressable(onClick = onDismissError).padding(vertical = 4.dp))
                }
                // язык речи: «Русский» принудительно включает русский режим модели, «Авто» — определение по звуку
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.base.editor.captions.CaptionLanguage.entries.forEach { l ->
                        val on = l == language
                        Text(l.label, Modifier.pressable { onLanguage(l) }.clip(RoundedCornerShape(10.dp)).background(animatedSelectColor(on))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(if (items.isEmpty()) "Создать субтитры" else "Создать заново", Modifier.pressable(onClick = onGenerate).clip(RoundedCornerShape(10.dp)).background(BaseColors.Cyan)
                        .padding(horizontal = 16.dp, vertical = 9.dp), color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.pressable(onClick = onAdd).clip(RoundedCornerShape(10.dp)).background(BaseColors.DarkSlot).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp)); Text(" Добавить здесь", color = Color.White, fontSize = 13.sp)
            }
            if (items.isNotEmpty()) Text("Удалить все", Modifier.pressable(onClick = onClearAll).clip(RoundedCornerShape(10.dp)).background(BaseColors.DarkSlot).padding(horizontal = 12.dp, vertical = 8.dp), color = Color(0xFFFF8A80), fontSize = 13.sp)
        }
        LazyColumn(Modifier.padding(top = 6.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
            items(items, key = { it.id }) { c ->
                val now = playheadMs >= c.startMs && playheadMs < c.endMs
                Row(Modifier.pressable { onOpen(c) }.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (now) BaseColors.Cyan.copy(alpha = .18f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(Format.duration(c.startMs), color = Color.White.copy(alpha = .5f), fontSize = 12.sp, modifier = Modifier.width(48.dp))
                    Text(c.text.ifBlank { "(пусто)" }, color = Color.White, fontSize = 14.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun StyleTab(style: CaptionStyle, onPreset: (String) -> Unit, onStyle: ((CaptionStyle) -> CaptionStyle) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Label("Готовые стили")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CaptionPresets.all, key = { it.id }) { p ->
                val sel = style.id == p.id
                Column(Modifier.pressable { onPreset(p.id) }.width(92.dp).clip(RoundedCornerShape(12.dp)).border(BorderStroke(if (sel) 2.dp else 0.dp, if (sel) BaseColors.Cyan else Color.Transparent), RoundedCornerShape(12.dp))
                    .background(Color(0xFF3A3B40)), horizontalAlignment = Alignment.CenterHorizontally) {
                    StylePreviewCard(p, Modifier.fillMaxWidth().height(56.dp))
                    Text(p.name, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(vertical = 5.dp))
                }
            }
        }

        Label("Шрифт")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CaptionFont.entries.toList()) { f -> Chip(f.label, style.font == f) { onStyle { it.copy(font = f, fontWeight = if (CaptionFonts.weightRange(f) == null) 400 else it.fontWeight.coerceIn(CaptionFonts.weightRange(f)!!)) } } }
        }
        CaptionFonts.weightRange(style.font)?.let { r ->
            SliderRow("Насыщенность", style.fontWeight.toFloat(), r.first.toFloat()..r.last.toFloat(), "${style.fontWeight}") { v -> onStyle { it.copy(fontWeight = (v / 100).toInt() * 100) } }
        }
        SliderRow("Размер", style.sizeFrac, 0.03f..0.09f, "${(style.sizeFrac * 1000).toInt()}") { v -> onStyle { it.copy(sizeFrac = v) } }
        SliderRow("Положение", style.positionY, 0.1f..0.9f, "${(style.positionY * 100).toInt()}%") { v -> onStyle { it.copy(positionY = v) } }
        ToggleRow("Все заглавные", style.uppercase) { v -> onStyle { it.copy(uppercase = v) } }

        Label("Анимация слова")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WordAnimation.entries.toList()) { a -> Chip(a.label, style.animation == a) { onStyle { it.copy(animation = a) } } }
        }

        Label("Цвет текста"); ColorRow(style.textColor) { c -> onStyle { it.copy(textColor = c) } }
        Label("Цвет активного слова"); ColorRow(style.activeColor) { c -> onStyle { it.copy(activeColor = c) } }
        Label("Обводка"); ColorRow(style.strokeColor) { c -> onStyle { it.copy(strokeColor = c) } }
        SliderRow("Толщина обводки", style.strokeEm, 0f..0.25f, "${(style.strokeEm * 100).toInt()}") { v -> onStyle { it.copy(strokeEm = v) } }

        ToggleRow("Фоновая плашка", style.hasBackground) { on -> onStyle { it.copy(backgroundColor = if (on) 0xCC000000.toInt() else 0) } }
        if (style.hasBackground) ColorRow(style.backgroundColor or 0xFF000000.toInt()) { c -> onStyle { it.copy(backgroundColor = (0xCC shl 24) or (c and 0xFFFFFF)) } }

        val glow = (style.shadowColor ushr 24) > 0 && style.shadowBlurEm >= 0.3f
        ToggleRow("Свечение", glow) { on ->
            onStyle { if (on) it.copy(shadowColor = it.activeColor or 0xFF000000.toInt(), shadowBlurEm = 0.45f, shadowDyEm = 0f) else it.copy(shadowColor = 0x80000000.toInt(), shadowBlurEm = 0.10f, shadowDyEm = 0.06f) }
        }
        Spacer(Modifier.size(10.dp))
    }
}

@Composable private fun Label(t: String) = Text(t, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, Modifier.pressable(onClick = onClick).clip(RoundedCornerShape(16.dp)).background(animatedSelectColor(selected)).padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Palette) { c ->
            Box(Modifier.pressable { onPick(c) }.size(36.dp).clip(CircleShape).background(Color(c)).border(BorderStroke(if (c == selected) 3.dp else 1.dp, if (c == selected) BaseColors.Cyan else Color.White.copy(alpha = .25f)), CircleShape))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = BaseColors.Cyan, activeTrackColor = BaseColors.Cyan))
        Text(shown, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = BaseColors.Cyan, checkedThumbColor = Color.Black))
    }
}

/** Диалог правки: текст и границы по времени (шаг 0,1 с). */
@Composable
private fun EditDialog(
    item: CaptionItem, onClose: () -> Unit, onText: (String, String) -> Unit, onTiming: (String, Long, Long) -> Unit, onDelete: (String) -> Unit,
) {
    var text by remember(item.id) { mutableStateOf(item.text) }
    AlertDialog(
        onDismissRequest = { onText(item.id, text); onClose() },
        containerColor = BaseColors.DarkPanel,
        title = { Text("Субтитр", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                TimeStepper("Начало", item.startMs) { onTiming(item.id, it, item.endMs) }
                TimeStepper("Конец", item.endMs) { onTiming(item.id, item.startMs, it) }
            }
        },
        confirmButton = { TextButton({ onText(item.id, text); onClose() }) { Text("Готово", color = BaseColors.Cyan) } },
        dismissButton = { TextButton({ onDelete(item.id); onClose() }) { Text("Удалить", color = Color(0xFFFF8A80)) } },
    )
}

@Composable
private fun TimeStepper(label: String, valueMs: Long, onChange: (Long) -> Unit) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = .7f), modifier = Modifier.width(70.dp), fontSize = 14.sp)
        listOf("−0.1" to -100L, "+0.1" to 100L).forEachIndexed { i, (t, d) ->
            if (i == 1) Text("%.1f с".format(valueMs / 1000f), color = Color.White, modifier = Modifier.width(64.dp).padding(horizontal = 6.dp), fontSize = 14.sp)
            Text(t, Modifier.pressable { onChange((valueMs + d).coerceAtLeast(0)) }.clip(RoundedCornerShape(8.dp)).background(BaseColors.DarkSlot).padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, fontSize = 13.sp)
        }
    }
}

private val previewItem = CaptionItem("preview", 0, 1000, "Аа Аа", listOf(WordTimestamp("Аа", 0, 400), WordTimestamp("Аа", 400, 1000)))

/** Мини-карточка стиля: рисуется тем же рисовальщиком, что и субтитры в плеере (векторный текст, без картинок). */
@Composable
private fun StylePreviewCard(style: CaptionStyle, modifier: Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 8)
    androidx.compose.foundation.Canvas(modifier) {
        // «кадр» условной высоты 300 dp: размер шрифта получается ≈ 19 dp, но рисуем на маленькой карточке по центру
        val viewport = 300.dp.toPx()
        with(com.base.editor.captions.CaptionRenderer) {
            drawCaption(measurer, previewItem, style.copy(maxWidthFrac = 1f, uppercase = false), timeMs = 700, centerY = size.height / 2, viewportHeight = viewport)
        }
    }
}
