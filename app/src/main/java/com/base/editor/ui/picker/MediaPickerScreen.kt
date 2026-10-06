package com.base.editor.ui.picker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.base.editor.core.PickedMedia
import com.base.editor.data.DeviceMedia
import com.base.editor.data.Format
import com.base.editor.data.MediaRepository
import com.base.editor.data.Thumbs
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.pressable

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun MediaPickerScreen(startOnPhotos: Boolean, onClose: () -> Unit, onConfirm: (List<PickedMedia>) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAccess(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasAccess(ctx) }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(mediaPermissions()) }

    var tab by rememberSaveable { mutableStateOf(if (startOnPhotos) 1 else 0) }   // ровно 2 вкладки
    var items by remember { mutableStateOf<List<DeviceMedia>>(emptyList()) }
    LaunchedEffect(granted, tab) { if (granted) items = MediaRepository.query(ctx, video = tab == 0) }
    val selected = remember { mutableStateListOf<DeviceMedia>() }   // порядок выбора = порядок на таймлайне

    Column(Modifier.fillMaxSize().background(BaseColors.DarkBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.pressable(onClick = onClose).size(48.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Видео", "Фото").forEachIndexed { i, label ->
                Column(Modifier.pressable { tab = i }.weight(1f).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (tab == i) BaseColors.Cyan else Color.White.copy(alpha = .6f))
                    Box(Modifier.padding(top = 8.dp).height(3.dp).size(width = 44.dp, height = 3.dp).background(if (tab == i) BaseColors.Cyan else Color.Transparent))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!granted) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Разрешите доступ к видео и фото, чтобы выбрать материалы для монтажа.", color = Color.White.copy(alpha = .8f), fontSize = 16.sp)
                    Button({ launcher.launch(mediaPermissions()) }, Modifier.padding(top = 16.dp), colors = ButtonDefaults.buttonColors(BaseColors.Cyan, Color.Black)) { Text("Разрешить доступ") }
                }
            } else if (items.isEmpty()) {
                Text(if (tab == 0) "Видео не найдены" else "Фото не найдены", Modifier.align(Alignment.Center), color = Color.White.copy(alpha = .6f))
            } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items, key = { it.uri.toString() }) { m ->
                    val order = selected.indexOfFirst { it.uri == m.uri }
                    MediaCell(m, order) { if (order >= 0) selected.removeAt(order) else selected.add(m) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onConfirm(selected.map { PickedMedia(it.uri.toString(), it.type, it.durationMs) }) },
                enabled = selected.isNotEmpty(), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(BaseColors.Cyan, Color.Black, BaseColors.DarkSlot, Color.White.copy(alpha = .4f)),
            ) { Text(if (selected.isEmpty()) "Добавить" else "Добавить (${selected.size})", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
        }
    }
}

@Composable
private fun MediaCell(m: DeviceMedia, order: Int, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<com.base.editor.data.Thumb?>(null, m.uri) { value = Thumbs.poster(ctx, m.uri, 320) }
    Box(Modifier.pressable(onClick = onToggle).fillMaxWidth().aspectRatio(1f).background(BaseColors.TileEmpty)) {
        thumb?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (m.durationMs > 0) Text(Format.duration(m.durationMs), Modifier.align(Alignment.BottomEnd).padding(6.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                .then(if (order >= 0) Modifier.background(BaseColors.Cyan) else Modifier.border(BorderStroke(2.dp, Color.White), CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (order >= 0) Text("${order + 1}", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun hasAccess(ctx: android.content.Context): Boolean {
    fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> ok(Manifest.permission.READ_MEDIA_VIDEO) || ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> ok(Manifest.permission.READ_MEDIA_VIDEO) || ok(Manifest.permission.READ_MEDIA_IMAGES)
        else -> ok(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
