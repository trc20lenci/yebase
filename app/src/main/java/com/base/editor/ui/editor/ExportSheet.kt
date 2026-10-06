package com.base.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.media.ExportQuality
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.pressable
import kotlin.math.roundToInt

private class Res(val key: String, val label: String, val quality: ExportQuality)

private val RESOLUTIONS = listOf(
    Res("480p", "480p", ExportQuality.P480), Res("720p", "720p", ExportQuality.P720),
    Res("1080p", "1080p (FHD)", ExportQuality.P1080), Res("2K/4K", "2K/4K", ExportQuality.P1440),
)
private val FPS_OPTIONS = listOf(24, 30, 60)

/** Лист параметров рендера: дискретные слайдеры разрешения и частоты кадров, оценка веса файла, «Начать рендер». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(durationMs: Long, initialResolution: String, initialFps: Int, onDismiss: () -> Unit, onStart: (String, Int) -> Unit) {
    var resIdx by remember { mutableIntStateOf(RESOLUTIONS.indexOfFirst { it.key == initialResolution }.takeIf { it >= 0 } ?: 1) }
    var fpsIdx by remember { mutableIntStateOf(FPS_OPTIONS.indexOf(initialFps).takeIf { it >= 0 } ?: 1) }
    // оценка: видеопоток + звук 128 кбит/с; битрейт задаётся качеством, поэтому частота кадров на вес почти не влияет
    val mb = (RESOLUTIONS[resIdx].quality.bitrate + 128_000L) * (durationMs / 1000.0) / 8.0 / 1_048_576.0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BaseColors.DarkPanel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Экспорт", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            DiscreteSetting("Разрешение", RESOLUTIONS.map { it.label }, resIdx) { resIdx = it }
            Spacer(Modifier.height(14.dp))
            DiscreteSetting("Частота кадров", FPS_OPTIONS.map { "$it fps" }, fpsIdx) { fpsIdx = it }
            if (FPS_OPTIONS[fpsIdx] == 60) Text("60 fps не добавляет кадры у видео с меньшей частотой", color = Color.White.copy(alpha = .55f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.pressable { onStart(RESOLUTIONS[resIdx].key, FPS_OPTIONS[fpsIdx]) }.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BaseColors.Cyan)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Начать рендер", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("  ·  ≈ ${formatSize(mb)}", color = Color.Black.copy(alpha = .65f), fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatSize(mb: Double) = if (mb < 1) "<1 МБ" else if (mb < 10) "%.1f МБ".format(mb) else "${mb.roundToInt()} МБ"

/** Дискретный слайдер с делениями и подписями под ними. */
@Composable
private fun DiscreteSetting(title: String, labels: List<String>, index: Int, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White.copy(alpha = .7f), fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(labels[index], color = BaseColors.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = index.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(0, labels.lastIndex)) },
            valueRange = 0f..labels.lastIndex.toFloat(), steps = labels.size - 2,
            colors = SliderDefaults.colors(thumbColor = BaseColors.Cyan, activeTrackColor = BaseColors.Cyan, inactiveTrackColor = Color.White.copy(alpha = .2f),
                activeTickColor = Color.Black.copy(alpha = .6f), inactiveTickColor = Color.White.copy(alpha = .5f)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { i, l -> Text(l, color = Color.White.copy(alpha = if (i == index) 1f else .5f), fontSize = 11.sp, maxLines = 1) }
        }
    }
}
