package com.base.editor.ui.editor

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.base.editor.text.TextClip
import com.base.editor.ui.theme.BaseColors

private val TextPalette = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFFFFEB3BL, 0xFFFF6D00L, 0xFFFF1744L, 0xFFFF2DF1L,
    0xFF7C4DFFL, 0xFF2979FFL, 0xFF00E5FFL, 0xFF00E676L,
)

/**
 * Строка ввода текста: небольшое окно у нижнего края (не модальное по касаниям выше него, без затемнения).
 * Клавиатура не пересчитывает размеры экрана редактора. Всё остальное — в нижней панели [TextContextPanel].
 */
@Composable
fun TextInputSheet(text: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0f)
                setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(BaseColors.DarkPanel)
                .imePadding().navigationBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text, onValueChange = onChange,
                modifier = Modifier.weight(1f).focusRequester(focus), placeholder = { Text("Введите текст") }, minLines = 1, maxLines = 3,
            )
            Text("Готово", Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).background(BaseColors.Cyan).clickable(onClick = onDone).padding(horizontal = 18.dp, vertical = 12.dp),
                color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Панель инструментов текстового слоя в слоте под таймлайном (как у медиа-клипа). Таймлайн остаётся живым.
 * Корневой ряд: «Назад / Текст / Разделить / Шрифты / Стили / Анимация / Цвет / Удалить»; разделы заменяют ряд
 * горизонтальной каруселью с кнопкой возврата. Размер, поворот и положение — жестами на холсте.
 */
@Composable
fun TextToolbar(
    clip: TextClip, sub: TextSub?, templates: List<com.base.editor.pag.PagTemplateStore.Template>,
    onBack: () -> Unit, onSub: (TextSub?) -> Unit, onEditText: () -> Unit, onSplit: () -> Unit, onDelete: () -> Unit,
    onChange: ((TextClip) -> TextClip) -> Unit, onStyle: (com.base.editor.text.TextStyles.Preset) -> Unit,
    onAnimation: (com.base.editor.text.TextAnimation) -> Unit, onImportPag: (android.net.Uri) -> Unit,
) {
    androidx.compose.animation.Crossfade(sub, animationSpec = androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f)), label = "textSub") { cur ->
        if (cur == null) {
            Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelAction("Назад", Icons.Rounded.ChevronLeft, onClick = onBack)
                PanelAction("Текст", Icons.Rounded.Edit, onClick = onEditText)
                PanelAction("Разделить", Icons.Rounded.VerticalSplit, onClick = onSplit)
                PanelAction("Шрифты", Icons.Rounded.TextFields) { onSub(TextSub.FONTS) }
                PanelAction("Стили", Icons.Rounded.AutoAwesome) { onSub(TextSub.STYLES) }
                PanelAction("Анимация", Icons.Rounded.Animation) { onSub(TextSub.ANIMATION) }
                PanelAction("Цвет", Icons.Rounded.Palette) { onSub(TextSub.COLOR) }
                PanelAction("Удалить", Icons.Rounded.DeleteOutline, onClick = onDelete)
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelAction("Назад", Icons.Rounded.ChevronLeft) { onSub(null) }
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (cur) {
                        TextSub.FONTS -> items(com.base.editor.text.TextFonts.all, key = { it.id }) { f ->
                            Chip(clip.fontId == f.id, { onChange { it.copy(fontId = f.id) } }) { Text("Аа Яя", fontSize = 18.sp, fontFamily = f.family, color = chipText(clip.fontId == f.id)) }
                        }
                        TextSub.STYLES -> items(com.base.editor.text.TextStyles.all, key = { it.id }) { p ->
                            val on = clip.styleId == p.id
                            Chip(on, { onStyle(p) }) { Text(p.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = chipText(on)) }
                        }
                        TextSub.ANIMATION -> {
                            items(com.base.editor.text.TextAnimation.entries.toList(), key = { it.id }) { a ->
                                val on = clip.animId == a.id
                                Chip(on, { onAnimation(a) }) { Text(a.label, fontSize = 13.sp, color = chipText(on)) }
                            }
                            items(templates, key = { it.ref }) { t -> Chip(clip.pagTemplate == t.ref, { onChange { it.copy(pagTemplate = if (clip.pagTemplate == t.ref) null else t.ref) } }) { Text(t.title, fontSize = 13.sp, color = chipText(clip.pagTemplate == t.ref)) } }
                            item {
                                val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImportPag(uri) }
                                Chip(false, { picker.launch(arrayOf("*/*")) }) { Text("+ .pag", fontSize = 13.sp, color = Color.White) }
                            }
                        }
                        TextSub.COLOR -> {
                            items(TEXT_COLORS, key = { it }) { c ->
                                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                    .then(if (clip.textColor == c) Modifier.border(3.dp, BaseColors.Cyan, CircleShape) else Modifier.border(1.dp, Color.White.copy(alpha = .35f), CircleShape))
                                    .clickable { onChange { it.copy(textColor = c) } })
                            }
                            item { Chip(clip.hasBackground, { onChange { it.copy(backgroundColor = if (clip.hasBackground) 0L else 0x99000000) } }) { Text("Плашка", fontSize = 13.sp, color = chipText(clip.hasBackground)) } }
                        }
                    }
                }
            }
        }
    }
}

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFE600, 0xFFFF3B30, 0xFFFF9500, 0xFF34C759, 0xFF00E5FF, 0xFF0A84FF, 0xFFFF2BD6, 0xFFB388FF)

private fun chipText(on: Boolean) = if (on) Color.Black else Color.White

@Composable
private fun Chip(on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) BaseColors.Cyan else BaseColors.DarkSlot)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PanelAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.width(62.dp).height(56.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White.copy(alpha = .85f), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun PagChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) BaseColors.Cyan else BaseColors.DarkSlot).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable private fun Label(t: String) = Text(t, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

@Composable
private fun ColorChips(selected: Long, onPick: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TextPalette) { c ->
            val sel = (c and 0xFFFFFF) == (selected and 0xFFFFFF)
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) BaseColors.Cyan else Color.White.copy(alpha = .25f)), CircleShape).clickable { onPick(c) })
        }
    }
}

