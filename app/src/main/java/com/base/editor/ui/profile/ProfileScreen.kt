package com.base.editor.ui.profile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.ProjectMeta
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.pressable
import com.base.editor.ui.theme.soon

private data class Row_(val label: String, val icon: ImageVector)

@Composable
fun ProfileScreen(projects: List<ProjectMeta>) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to BaseColors.DarkPanel, 0.4f to BaseColors.DarkBg)).statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            listOf(Icons.Rounded.NotificationsNone to "Уведомления", Icons.Rounded.Settings to "Настройки").forEach { (i, d) ->
                Box(Modifier.pressable { soon(ctx) }.size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(i, d, tint = BaseColors.OnBg) }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(BaseColors.Primary), contentAlignment = Alignment.Center) {
                Text("B", color = Color.Black, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Text("Мой BASE", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = BaseColors.OnBg)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(BaseColors.DarkPanel).padding(vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(projects.size.toString(), "Проекты")
            Stat(Format.duration(projects.sumOf { it.durationMs }), "Всего видео")
            Stat(Format.size(projects.sumOf { it.sizeBytes }), "Занято места")
        }
        Spacer(Modifier.height(20.dp))
        listOf(Row_("События", Icons.Rounded.EmojiEvents), Row_("Избранное", Icons.Rounded.Bookmark), Row_("История", Icons.Rounded.History), Row_("Справочный центр", Icons.Rounded.HelpOutline)).forEach { r ->
            Row(Modifier.pressable { soon(ctx) }.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(r.icon, null, tint = BaseColors.OnBg)
                Text(r.label, Modifier.weight(1f).padding(start = 16.dp), fontSize = 18.sp, color = BaseColors.OnBg)
                Icon(Icons.Rounded.ChevronRight, null, tint = BaseColors.Muted)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = BaseColors.OnBg)
        Text(label, fontSize = 13.sp, color = BaseColors.Muted, textAlign = TextAlign.Center)
    }
}
