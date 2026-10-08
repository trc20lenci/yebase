package com.base.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.CanvasBg
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.Lucide
import com.base.editor.ui.theme.pressable

/**
 * Панель «Фон» в слоте инструментов: чем заполнять поля вокруг кадра. Каждый вариант показан превью —
 * размытое видео нарисовано мягким градиентом, цвета — заливкой. Таймлайн остаётся живым.
 */
@Composable
fun BackgroundsBar(current: CanvasBg, onPick: (CanvasBg) -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.pressable(onClick = onBack).width(56.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Lucide.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text("Назад", color = Color.White, fontSize = 10.sp, maxLines = 1)
        }
        LazyRow(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            items(CanvasBg.entries.toList(), key = { it.id }) { bg ->
                val on = bg == current
                Column(
                    Modifier.pressable { onPick(bg) }.width(66.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(preview(bg))
                            .border(if (on) 2.5.dp else 1.dp, if (on) BaseColors.Primary else Color.White.copy(alpha = .25f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bg == CanvasBg.BLUR) {
                            // «размытое видео»: два мягких светлых пятна поверх градиента
                            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White.copy(alpha = .30f)))
                        }
                    }
                    Text(bg.label, color = if (on) BaseColors.Primary else Color.White, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

private fun preview(bg: CanvasBg): Brush = when {
    bg == CanvasBg.BLUR -> Brush.linearGradient(listOf(Color(0xFF6C8CFF), Color(0xFFFF8FB1), Color(0xFFFFD08A)))
    else -> Brush.linearGradient(listOf(Color(bg.argb!!), Color(bg.argb)))
}
