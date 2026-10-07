package com.base.editor.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.base.editor.ui.theme.staggeredEnter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.R
import com.base.editor.core.ProjectMeta
import com.base.editor.ui.projects.ProjectThumb
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.pressable
import com.base.editor.ui.theme.soon

private data class Tool(val label: String, val icon: ImageVector)

private val tools = listOf(
    Tool("Автоулучшение", Icons.Rounded.AutoFixHigh), Tool("Субтитры", Icons.Rounded.ClosedCaption),
    Tool("Ретушь", Icons.Rounded.Face), Tool("Умное освещение", Icons.Rounded.WbSunny),
    Tool("Захват кадра", Icons.Rounded.CameraAlt), Tool("Инструменты для фото", Icons.Rounded.PhotoFilter),
)

@Composable
fun HomeScreen(
    projects: List<ProjectMeta>,
    onNewVideo: () -> Unit,
    onEditPhoto: () -> Unit,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to BaseColors.DarkPanel, 0.45f to BaseColors.DarkBg))
            .statusBarsPadding().verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_base_black), "BASE", Modifier.height(28.dp))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.pressable(onClick = onSeeAll).size(44.dp).clip(RoundedCornerShape(22.dp)).background(BaseColors.DarkSlot),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Search, "Поиск", tint = BaseColors.OnBg) }
        }
        Spacer(Modifier.height(36.dp))
        Text("Редактирование видео", Modifier.padding(horizontal = 20.dp), color = BaseColors.OnBg.copy(alpha = .6f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Начать работу", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = BaseColors.OnBg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction("Новое видео", Icons.Rounded.Add, Modifier.weight(1.3f), onNewVideo)
            BigAction("Редактировать фото", Icons.Rounded.Photo, Modifier.weight(1f), onEditPhoto)
        }
        Spacer(Modifier.height(20.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(projects.take(6), key = { _, p -> p.id }) { i, p ->
                Box(Modifier.staggeredEnter(i).pressable { onOpen(p.id) }.size(88.dp).clip(RoundedCornerShape(14.dp))) {
                    ProjectThumb(p, Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ContentCut, null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(p.name, color = Color.White, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            if (projects.size > 6) item {
                Box(Modifier.pressable(onClick = onSeeAll).size(88.dp).clip(RoundedCornerShape(14.dp)).background(BaseColors.Line), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ChevronRight, "Все проекты")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        tools.chunked(3).forEach { rowTools ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                rowTools.forEach { t ->
                    Column(Modifier.pressable { soon(ctx) }.weight(1f).clip(RoundedCornerShape(12.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(t.icon, null, Modifier.size(30.dp), tint = BaseColors.OnBg.copy(alpha = .8f))
                        Spacer(Modifier.height(8.dp))
                        Text(t.label, fontSize = 13.sp, color = BaseColors.OnBg.copy(alpha = .75f), textAlign = TextAlign.Center, minLines = 2, maxLines = 2)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BigAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.pressable(onClick = onClick).height(120.dp).clip(RoundedCornerShape(22.dp)).background(BaseColors.DarkPanel).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(BaseColors.Primary), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.Black)
        }
        Spacer(Modifier.height(10.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = BaseColors.OnBg)
    }
}
