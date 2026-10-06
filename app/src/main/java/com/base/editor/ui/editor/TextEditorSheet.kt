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

private enum class TextSub { FONTS, STYLE }

/**
 * Контекстная нижняя панель текстового слоя (как у медиафайлов): «Текст», «Разделить», «Шрифты», «Стиль/Цвет», «Удалить».
 * Размер, положение и поворот меняются жестами на холсте, длительность — краями блока на таймлайне, поэтому числовых полей нет.
 */
@Composable
fun TextContextPanel(
    clip: TextClip, isNew: Boolean,
    onChange: ((TextClip) -> TextClip) -> Unit,
    templates: List<com.base.editor.pag.PagTemplateStore.Template>, onImportPag: (android.net.Uri) -> Unit,
    onEditText: () -> Unit, onSplit: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit,
) {
    var sub by remember { mutableStateOf<TextSub?>(null) }
    Column(Modifier.fillMaxWidth().background(BaseColors.DarkPanel).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when (sub) { TextSub.FONTS -> "Шрифты"; TextSub.STYLE -> "Стиль и цвет"; null -> "Текст" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (sub != null) Text("Назад", Modifier.clip(RoundedCornerShape(10.dp)).clickable { sub = null }.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White.copy(alpha = .8f), fontSize = 14.sp)
            Text("Готово", Modifier.clip(RoundedCornerShape(10.dp)).background(BaseColors.Cyan).clickable(onClick = onDone).padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        when (sub) {
            null -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                PanelAction("Текст", Icons.Rounded.Edit, onClick = onEditText)
                PanelAction("Разделить", Icons.Rounded.VerticalSplit, enabled = !isNew, onClick = onSplit)
                PanelAction("Шрифты", Icons.Rounded.TextFields) { sub = TextSub.FONTS }
                PanelAction("Стиль/Цвет", Icons.Rounded.Palette) { sub = TextSub.STYLE }
                PanelAction("Удалить", Icons.Rounded.DeleteOutline, onClick = onDelete)
            }
            TextSub.FONTS -> LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.base.editor.text.TextFonts.all, key = { it.id }) { f ->
                    val on = clip.fontId == f.id
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) BaseColors.Cyan else BaseColors.DarkSlot)
                            .clickable { onChange { it.copy(fontId = f.id) } }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) { Text("Аа Яя", color = if (on) Color.Black else Color.White, fontSize = 20.sp, fontFamily = f.family) }
                }
            }
            TextSub.STYLE -> Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                Label("Цвет текста")
                ColorChips(clip.textColor) { c -> onChange { it.copy(textColor = c) } }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Фон под текстом", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(clip.hasBackground, { on -> onChange { it.copy(backgroundColor = if (on) 0xCC000000 else 0L) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = BaseColors.Cyan, checkedThumbColor = Color.Black))
                }
                if (clip.hasBackground) ColorChips(clip.backgroundColor or 0xFF000000) { c -> onChange { it.copy(backgroundColor = 0xCC000000 or (c and 0xFFFFFF)) } }
                Label("Анимация титра")
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImportPag(uri) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PagChip("Без анимации", clip.pagTemplate == null) { onChange { it.copy(pagTemplate = null) } } }
                    items(templates, key = { it.ref }) { t -> PagChip(t.title, clip.pagTemplate == t.ref) { onChange { it.copy(pagTemplate = t.ref) } } }
                    item { PagChip("+ Свой .pag", false) { picker.launch(arrayOf("*/*")) } }
                }
            }
        }
    }
}

@Composable
private fun PanelAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else .35f), modifier = Modifier.size(26.dp))
        Text(label, color = Color.White.copy(alpha = if (enabled) .85f else .35f), fontSize = 11.sp, maxLines = 1)
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

