package com.base.editor.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import com.base.editor.media.ExportState
import com.base.editor.R
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.base.editor.captions.CaptionOps
import androidx.compose.material.icons.rounded.ClosedCaption
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.soon
import kotlin.math.roundToInt

@UnstableApi
@Composable
fun EditorScreen(onClose: () -> Unit, onAddMedia: () -> Unit, vm: EditorViewModel = viewModel()) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val clips by vm.clips.collectAsStateWithLifecycle()
    val formatPanel by vm.formatPanelOpen.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val playhead by vm.playheadMs.collectAsStateWithLifecycle()
    val total by vm.totalMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val zoom by vm.pxPerSec.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val transitions by vm.transitions.collectAsStateWithLifecycle()
    val transitionFor by vm.transitionFor.collectAsStateWithLifecycle()
    val transitionMax by vm.transitionMaxMs.collectAsStateWithLifecycle()
    val captionItems by vm.captions.items.collectAsStateWithLifecycle()
    val captionStyle by vm.captions.style.collectAsStateWithLifecycle()
    val captionGen by vm.captions.generation.collectAsStateWithLifecycle()
    val captionPanel by vm.captionPanelOpen.collectAsStateWithLifecycle()
    val editingCaption by vm.editingCaptionId.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val draft by vm.textDraft.collectAsStateWithLifecycle()
    val liveClip by vm.liveClipTransform.collectAsStateWithLifecycle()
    val keyframes by vm.keyframes.collectAsStateWithLifecycle()

    SideEffect { vm.onRequestAddMedia = onAddMedia }
    // выбор музыки с устройства (MIME audio/*) — результат уходит во ViewModel
    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::onAudioPicked) }
    SideEffect { vm.onRequestAddAudio = { audioPicker.launch(arrayOf("audio/*")) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.scrubStart(); vm.saveNow() }
    val export by vm.exportState.collectAsStateWithLifecycle()
    export?.let { ExportDialog(it, onCancel = vm::cancelExport, onDismiss = vm::dismissExport) }
    LaunchedEffect(event) { event?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.events.value = null } }
    fun close() { vm.saveNow(); onClose() }
    BackHandler {
        when {
            draft != null -> vm.cancelText()
            formatPanel -> vm.closeFormat()
            captionPanel -> vm.closeCaptions()
            transitionFor != null -> vm.closeTransitions()
            selected != null -> vm.select(null)
            else -> close()
        }
    }
    val pagTemplates by vm.pagTemplates.collectAsStateWithLifecycle()
    draft?.let { d -> TextEditorSheet(d.clip, d.isNew, onChange = vm::updateTextDraft, templates = pagTemplates, onImportPag = vm::importPag, onDone = vm::commitText, onDelete = vm::deleteText, onCancel = vm::cancelText) }

    Column(Modifier.fillMaxSize().background(BaseColors.DarkBg).systemBarsPadding()) {
        // верхняя панель
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.Close, "Выйти", ::close)
            Spacer(Modifier.width(8.dp))
            Image(painterResource(R.drawable.logo_base_white), "BASE", Modifier.height(20.dp))
            Spacer(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(BaseColors.DarkPanel).clickable { menu = true }.padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(resolution, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White)
                }
                DropdownMenu(menu, { menu = false }) {
                    listOf("480p", "720p", "1080p", "2K/4K").forEach { r -> DropdownMenuItem(text = { Text(r) }, onClick = { vm.setResolution(r); menu = false }) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("Экспорт", Modifier.clip(RoundedCornerShape(12.dp)).background(BaseColors.Cyan).clickable { vm.startExport() }.padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        // плеер: занимает всё, что осталось над фиксированной нижней областью, поэтому его размер не меняется
        // ни при открытии панелей, ни при смене инструментов
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
          // рамка выбранного формата: всё, что выходит за её границы, аппаратно отсекается
          Box(Modifier.aspectRatio(vm.videoAspect.coerceIn(0.2f, 5f)).clipToBounds()) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        player = vm.controller.player
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // пока плеер пересобирается после жеста, показываем разницу между новым и «запечённым» положением
                    liveClip?.let { l ->
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f + l.baked.x, 0.5f + l.baked.y)
                        translationX = (l.current.x - l.baked.x) * size.width; translationY = (l.current.y - l.baked.y) * size.height
                        scaleX = l.current.scale / l.baked.scale; scaleY = scaleX
                        rotationZ = l.current.rotationDeg - l.baked.rotationDeg
                    }
                },
            )
            val visibleTexts = texts.filter { it.isVisibleAt(playhead) && it.id != draft?.clip?.id } + listOfNotNull(draft?.clip)
            TextOverlay(visibleTexts.filter { it.pagTemplate == null }, vm.videoAspect)
            visibleTexts.filter { it.pagTemplate != null }.forEach { PagTitleOverlay(it, playhead, vm.videoAspect) }
            CaptionOverlay(CaptionOps.captionAt(captionItems, playhead), captionStyle, playhead, vm.videoAspect)
            // свободные жесты: перемещение / масштаб / поворот выделенного клипа или текста
            val canvasText by vm.canvasTextId.collectAsStateWithLifecycle()
            val clipAspects by vm.clipAspects.collectAsStateWithLifecycle()
            val target = remember(selected, canvasText, draft, texts, liveClip, playhead, clips, clipAspects) { vm.canvasTarget() }
            CanvasTransformOverlay(vm.videoAspect, target, visibleTexts, vm)
          }
        }

        // время / play / undo-redo
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.align(Alignment.CenterStart)) {
                Text(Format.duration(playhead), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("  /  ${Format.duration(total)}", color = Color.White.copy(alpha = .5f), fontSize = 14.sp)
            }
            RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Воспроизвести", vm::togglePlay, size = 52.dp)
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                // ромбик ключевого кадра: только когда выбран клип основной дорожки (режим «Изменить»)
                val selMain = clips.firstOrNull { it.id == selected && it.row == 0 }
                if (selMain != null) {
                    val keyAtCursor = vm.hasKeyframeAtCursor()
                    KeyframeButton(hasKey = keyAtCursor, onClick = vm::toggleKeyframe)
                    Spacer(Modifier.width(4.dp))
                }
                RoundIcon(Icons.Rounded.Undo, "Отменить", vm::undo, enabled = canUndo)
                RoundIcon(Icons.Rounded.Redo, "Повторить", vm::redo, enabled = canRedo)
            }
        }

        // Нижняя область ФИКСИРОВАННОЙ высоты: таймлайн + панель инструментов.
        // Панели (субтитры, переходы) выезжают поверх неё и не меняют размеры соседей.
        val panelOpen = captionPanel || transitionFor != null || formatPanel
        Box(Modifier.fillMaxWidth().height((TL_HEIGHT_DP + TOOLBAR_HEIGHT_DP).dp).background(BaseColors.DarkBg)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(TL_HEIGHT_DP.dp)) {
                    TimelineView(clips, transitions, captionItems, texts, keyframes, selected, playhead, total, zoom, vm, Modifier.fillMaxWidth())
                    // кнопка «звук клипа» слева от нулевой отметки — уезжает вместе со шкалой
                    val scrollPx = playhead * zoom * density.density / 1000f
                    Column(
                        Modifier.offset { IntOffset(-scrollPx.roundToInt(), with(density) { 36.dp.roundToPx() }) }.padding(start = 12.dp).width(64.dp)
                            .clip(RoundedCornerShape(10.dp)).clickable(onClick = vm::toggleMute).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Text(if (muted) "Вкл. звук клипа" else "Выкл. звук клипа", color = Color.White.copy(alpha = .8f), fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp)
                    }
                    // «+» закреплён у правого края на уровне основной дорожки — доступен при любой прокрутке и зуме
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 46.dp, end = 8.dp).size(44.dp)
                            .shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White).clickable(onClick = vm::addMedia),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, "Добавить видео или фото", tint = Color.Black, modifier = Modifier.size(28.dp)) }
                }
                Crossfade(selected != null, label = "toolbar", modifier = Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT_DP.dp).background(BaseColors.DarkPanel)) { hasSel ->
                    if (!hasSel) {
                        ToolRow {
                            ToolButton(Icons.Rounded.ContentCut, "Изменить") { vm.selectAtPlayhead() }
                            ToolButton(Icons.Rounded.MusicNote, "Звук", onClick = vm::addAudio)
                            ToolButton(Icons.Rounded.TextFields, "Текст", onClick = vm::openNewText)
                            ToolButton(Icons.Rounded.ClosedCaption, "Субтитры", onClick = vm::openCaptions)
                            ToolButton(Icons.Rounded.AspectRatio, "Формат", onClick = vm::openFormat)
                            ToolButton(Icons.Rounded.Layers, "Наложение") { soon(ctx) }
                        }
                    } else {
                        ToolRow {
                            ToolButton(Icons.Rounded.ChevronLeft, "Назад") { vm.select(null) }
                            ToolButton(Icons.Rounded.VerticalSplit, "Разделить", onClick = vm::split)
                            ToolButton(Icons.Rounded.Animation, "Анимации") { soon(ctx) }
                            ToolButton(Icons.Rounded.DeleteOutline, "Удалить", onClick = vm::deleteSelected)
                        }
                    }
                }
            }

            // пока открыта панель — всё, что под ней, не реагирует на касания
            if (panelOpen) Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            })

            BottomPanel(visible = captionPanel) {
                CaptionPanel(
                    items = captionItems, style = captionStyle, generation = captionGen, playheadMs = playhead, editingId = editingCaption,
                    onGenerate = vm::generateCaptions, onDismissError = vm.captions::dismissError,
                    onOpenItem = vm::openCaptionItem, onCloseEdit = { vm.editingCaptionId.value = null },
                    onUpdateText = vm.captions::updateText, onUpdateTiming = vm.captions::updateTiming, onDelete = vm.captions::delete,
                    onAdd = vm::addCaptionHere, onClearAll = vm.captions::clearAll,
                    onPreset = vm.captions::applyPreset, onStyle = vm.captions::updateStyle, onClose = vm::closeCaptions,
                )
            }
            val curFormat by vm.format.collectAsStateWithLifecycle()
            BottomPanel(visible = formatPanel) { FormatPanel(curFormat, vm::setFormat, vm::closeFormat) }
            val tf = transitionFor
            BottomPanel(visible = tf != null && !captionPanel) {
                if (tf != null) TransitionPanel(
                    current = vm.currentTransition(tf), maxMs = transitionMax, items = vm.catalog.items,
                    onPick = { id, dur -> vm.applyTransition(tf, id, dur) },
                    onDuration = { dur -> vm.currentTransition(tf)?.let { vm.applyTransition(tf, it.shaderId, dur) } },
                    onClose = vm::closeTransitions,
                )
            }
        }
    }
}

private const val TOOLBAR_HEIGHT_DP = 68

/** Панель «Формат»: пресеты пропорций холста. */
@Composable
private fun FormatPanel(current: com.base.editor.core.CanvasFormat, onPick: (com.base.editor.core.CanvasFormat) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(BaseColors.DarkPanel).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Формат", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Rounded.Check, "Готово", onClose)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.base.editor.core.CanvasFormat.entries.forEach { f ->
                val on = f == current
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (on) BaseColors.Cyan else Color.White.copy(alpha = .1f))
                        .clickable { onPick(f) }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(f.label, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
            }
        }
    }
}

/** Панель, выезжающая снизу поверх фиксированной области (не влияет на размеры соседей). */
@Composable
private fun BoxScope.BottomPanel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) { content() }
}

/** Строка инструментов: каждая вкладка получает ровно равную долю ширины и центрируется. */
@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, enabled: Boolean = true) {
    Box(Modifier.size(size).clip(CircleShape).alpha(if (enabled) 1f else .35f).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(if (size > 48.dp) 34.dp else 26.dp))
    }
}

/**
 * Кнопка ключевого кадра (как в CapCut): ромбик с плюсом — ключа на курсоре нет (тап добавит),
 * ромбик с минусом — курсор стоит на существующем ключе (тап удалит).
 */
@Composable
private fun KeyframeButton(hasKey: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
            val w = size.width; val h = size.height
            val cx = w / 2f; val cy = h / 2f
            val r = w * 0.30f
            val diamond = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }
            drawPath(diamond, if (hasKey) BaseColors.Cyan else Color.White)
            // плюс или минус внутри ромбика
            val ink = Color(0xFF111318)
            val lw = w * 0.075f; val arm = r * 0.48f
            drawLine(ink, androidx.compose.ui.geometry.Offset(cx - arm, cy), androidx.compose.ui.geometry.Offset(cx + arm, cy), lw)
            if (!hasKey) drawLine(ink, androidx.compose.ui.geometry.Offset(cx, cy - arm), androidx.compose.ui.geometry.Offset(cx, cy + arm), lw)
        }
    }
}

@Composable
private fun RowScope.ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TransitionPanel(
    current: com.base.editor.core.Transition?, maxMs: Long, items: List<com.base.editor.core.TransitionInfo>,
    onPick: (String?, Long) -> Unit, onDuration: (Long) -> Unit, onClose: () -> Unit,
) {
    val minMs = 200L
    val hi = maxMs.coerceAtLeast(minMs + 1).toFloat()
    var dur by remember(current?.leftId, maxMs) { mutableStateOf((current?.durationMs ?: 500L).coerceIn(minMs, maxMs.coerceAtLeast(minMs)).toFloat()) }
    Column(Modifier.fillMaxWidth().background(BaseColors.DarkPanel).padding(top = 8.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Переходы", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            RoundIcon(Icons.Rounded.Check, "Готово", onClose, size = 40.dp)
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TransitionTile("Нет", Icons.Rounded.Block, selected = current == null) { onPick(null, dur.toLong()) }
            }
            items(items, key = { it.id }) { t ->
                TransitionTile(t.label, Icons.Rounded.AutoAwesome, selected = current?.shaderId == t.id, previewAsset = "shaders/transitions/previews/${t.id}.webp") { onPick(t.id, dur.toLong()) }
            }
        }
        if (current != null && maxMs > minMs) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Длительность", color = Color.White.copy(alpha = .7f), fontSize = 13.sp)
                Slider(
                    value = dur, onValueChange = { dur = it }, valueRange = minMs.toFloat()..hi,
                    onValueChangeFinished = { onDuration(dur.toLong()) },
                    colors = SliderDefaults.colors(thumbColor = BaseColors.Cyan, activeTrackColor = BaseColors.Cyan),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("%.1f с".format(dur / 1000f), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TransitionTile(label: String, icon: ImageVector, selected: Boolean, previewAsset: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(if (selected) 2.dp else 0.dp, if (selected) BaseColors.Cyan else Color.Transparent), RoundedCornerShape(12.dp))
            .background(BaseColors.DarkSlot).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(54.dp).background(Color(0xFF15161A)), contentAlignment = Alignment.Center) {
            if (previewAsset != null) LiveTransitionPreview(previewAsset, Modifier.fillMaxSize())
            else Icon(icon, null, tint = if (selected) BaseColors.Cyan else Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp))
    }
}

/** Зацикленная анимация перехода (WebP из assets). Рисуется системным декодером, без сторонних библиотек. */
@Composable
private fun LiveTransitionPreview(asset: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(ctx.assets, asset))
            }.getOrNull()
        }
    }
    drawable?.let { d ->
        AndroidView(
            factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
            update = { v ->
                v.setImageDrawable(d)
                (d as? android.graphics.drawable.AnimatedImageDrawable)?.apply { repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; start() }
            },
            onRelease = { v -> (v.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop() },
            modifier = modifier,
        )
    }
}

@Composable
private fun ExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val finished = state is ExportState.Done || state is ExportState.Failed
    AlertDialog(
        onDismissRequest = { if (finished) onDismiss() },
        containerColor = BaseColors.DarkPanel,
        title = { Text(when (state) { is ExportState.Done -> "Готово"; is ExportState.Failed -> "Ошибка экспорта"; else -> "Экспорт видео" }, color = Color.White) },
        text = {
            Column {
                when (state) {
                    ExportState.Preparing -> { Text("Подготовка…", color = Color.White.copy(alpha = .8f)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = BaseColors.Cyan) }
                    is ExportState.Progress -> {
                        Text("${state.percent}%" + if (state.attempt > 0) " (упрощённый режим)" else "", color = Color.White.copy(alpha = .8f))
                        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = BaseColors.Cyan)
                    }
                    is ExportState.Retrying -> Text("Устройство не справилось с первой попыткой — повторяем без эффектов и с меньшим разрешением.", color = Color.White.copy(alpha = .8f))
                    is ExportState.Done -> Text("Видео сохранено в галерею: Movies/BASE.", color = Color.White.copy(alpha = .8f))
                    is ExportState.Failed -> Text(state.message, color = Color.White.copy(alpha = .8f))
                }
            }
        },
        confirmButton = { if (finished) TextButton(onDismiss) { Text("OK", color = BaseColors.Cyan) } },
        dismissButton = { if (!finished) TextButton(onCancel) { Text("Отмена", color = Color.White) } },
    )
}
