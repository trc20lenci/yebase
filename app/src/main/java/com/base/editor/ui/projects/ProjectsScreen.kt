package com.base.editor.ui.projects

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.base.editor.ui.theme.staggeredEnter
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.ProjectMeta
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Filter(val label: String) { All("Все"), Video("Видео"), Photo("Фото") }
private enum class Sort(val label: String) { Date("Сначала новые"), Name("По названию") }

@Composable
fun ProjectsScreen(projects: List<ProjectMeta>, onCreate: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var sort by rememberSaveable { mutableStateOf(Sort.Date) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }   // null = поиск закрыт
    var sortMenu by remember { mutableStateOf(false) }

    val shown = projects
        .filter { when (filter) { Filter.All -> true; Filter.Video -> it.hasVideo; Filter.Photo -> !it.hasVideo } }
        .filter { query.isNullOrBlank() || it.name.contains(query!!, ignoreCase = true) }
        .let { if (sort == Sort.Name) it.sortedBy { p -> p.name } else it }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (query == null) {
                    Text("Проекты", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = BaseColors.Ink, modifier = Modifier.weight(1f))
                } else {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color(0xFFF1F2F4)).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (query.isNullOrEmpty()) Text("Поиск по названию", color = BaseColors.Muted, fontSize = 16.sp)
                            BasicTextField(query.orEmpty(), { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = BaseColors.Ink), cursorBrush = SolidColor(BaseColors.Ink))
                        }
                        Icon(Icons.Rounded.Close, "Закрыть поиск", Modifier.pressable { query = null }.size(20.dp))
                    }
                }
                if (query == null) IconBox(Icons.Rounded.Search, "Поиск") { query = "" }
                Box {
                    IconBox(Icons.Rounded.SwapVert, "Сортировка") { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Color.White) {
                        Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Filter.entries) { f ->
                    val sel = f == filter
                    Text(f.label, Modifier.pressable { filter = f }.clip(RoundedCornerShape(18.dp)).border(1.5.dp, if (sel) BaseColors.Ink else BaseColors.Line, RoundedCornerShape(18.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                        fontSize = 16.sp, color = BaseColors.Ink, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            Text(Format.projects(shown.size), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BaseColors.Ink)
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
                    Text(if (projects.isEmpty()) "Проектов пока нет. Нажмите «Создать», чтобы выбрать видео или фото." else "Ничего не найдено.", color = BaseColors.Muted, fontSize = 15.sp)
                }
            } else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 12.dp, bottom = 100.dp)) {
                itemsIndexed(shown, key = { _, p -> p.id }) { i, p -> Box(Modifier.staggeredEnter(i)) { ProjectRow(p, onOpen = { onOpen(p.id) }, onDelete = { onDelete(p.id) }) } }
            }
        }
        Row(
            Modifier.pressable(onClick = onCreate).align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).clip(RoundedCornerShape(30.dp)).background(BaseColors.Cyan)
                .padding(horizontal = 26.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Создать", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
        }
    }
}

@Composable
private fun IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.pressable(onClick = onClick).size(44.dp).clip(RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = BaseColors.Ink)
    }
}

@Composable
private fun ProjectRow(p: ProjectMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.pressable(onClick = onOpen).fillMaxWidth().clip(RoundedCornerShape(14.dp)).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProjectThumb(p, Modifier.size(80.dp).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = BaseColors.Ink)
            Text(Format.date(p.modifiedAt), fontSize = 14.sp, color = BaseColors.Muted, modifier = Modifier.padding(top = 2.dp))
            Text("${Format.duration(p.durationMs)}  |  ${Format.size(p.sizeBytes)}", fontSize = 14.sp, color = BaseColors.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Box {
            IconBox(Icons.Rounded.MoreHoriz, "Меню") { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = Color.White) {
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Обложка проекта (JPEG из filesDir) либо серая заглушка. */
@Composable
fun ProjectThumb(p: ProjectMeta, modifier: Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.thumbPath, p.modifiedAt) {
        value = p.thumbPath?.let { path -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() } }
    }
    Box(modifier.background(Color(0xFF1B1C20))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
