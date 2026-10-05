package com.base.editor.ui.editor

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionManager
import com.base.editor.data.PickedMediaInbox
import com.base.editor.pag.PagTemplateStore
import com.base.editor.text.TextClip
import com.base.editor.core.Clip
import com.base.editor.core.ClipTransform
import com.base.editor.core.PickedMedia
import com.base.editor.core.Transition
import com.base.editor.core.TransitionCatalog
import com.base.editor.data.MediaProbe
import com.base.editor.data.ProjectRepository
import com.base.editor.media.AppDispatchers
import com.base.editor.media.CaptionTrack
import com.base.editor.media.CompositionFactory
import com.base.editor.media.ExportQuality
import com.base.editor.media.ExportRequest
import com.base.editor.media.ExportState
import com.base.editor.media.TimelineController
import com.base.editor.media.VideoExportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Действия, которые жесты таймлайна вызывают у ViewModel. */
interface TimelineActions {
    fun scrubStart()
    fun scrubTo(ms: Long)
    fun scrubEnd()
    fun select(id: Long?)
    fun editBegin()
    fun moveClip(id: Long, startMs: Long)
    fun trimStart(id: Long, ms: Long)
    fun trimEnd(id: Long, ms: Long)
    fun editEnd()
    fun setZoom(pxPerSecDp: Float)
    fun addMedia()
    fun addAudio()
    fun addText()
    fun openTransitions(leftId: Long)
    fun openCaption(id: String)
    fun openText(id: String)
    fun moveText(id: String, startMs: Long)
    fun moveCaption(id: String, startMs: Long)
}

/** Клип во время жеста: [baked] — положение, уже «запечённое» в показанной композиции; [current] — новое. */
data class LiveClip(val id: Long, val baked: ClipTransform, val current: ClipTransform)

/** Редактируемый текст: [isNew] — ещё не добавлен на дорожку. */
data class TextDraft(val clip: TextClip, val isNew: Boolean)

@OptIn(FlowPreview::class)
@UnstableApi
class EditorViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app), TimelineActions, CanvasActions {
    private val projectId: String = checkNotNull(handle["projectId"])
    private val repo = ProjectRepository.get(app)
    private val dispatchers = AppDispatchers()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val catalog = TransitionCatalog(app)
    val controller = TimelineController(app, viewModelScope, catalog, dispatchers)
    val captions = CaptionManager(app, viewModelScope, dispatchers)
    private val exporter = VideoExportManager(app, CompositionFactory(app, catalog), dispatchers)

    // состояние таймлайна — прямо из контроллера
    val clips: StateFlow<List<Clip>> = controller.state.map { it.clips }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transitions: StateFlow<List<Transition>> = controller.state.map { it.transitions }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val totalMs: StateFlow<Long> = controller.state.map { it.totalMs }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    /** Ключевые кадры клипов (clipId → ключи) — для ромбиков на дорожке и кнопки. */
    val keyframes: StateFlow<Map<Long, List<com.base.editor.core.Keyframe>>> =
        controller.state.map { it.keyframes }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val playheadMs = controller.playheadMs
    val isPlaying = controller.isPlaying
    val canUndo = controller.canUndo
    val canRedo = controller.canRedo

    // состояние интерфейса
    val selectedId = MutableStateFlow<Long?>(null)
    val pxPerSec = MutableStateFlow(60f)                  // масштаб: dp на секунду
    val resolution = MutableStateFlow("720p")
    val muted = MutableStateFlow(false)
    /** true, пока проект загружается: жесты перемотки в это время игнорируются. */
    val isLoading = MutableStateFlow(true)
    val events = MutableStateFlow<String?>(null)          // одноразовые сообщения для Toast
    val transitionFor = MutableStateFlow<Long?>(null)     // стык, для которого открыта панель переходов
    val transitionMaxMs = MutableStateFlow(0L)
    val exportState = MutableStateFlow<ExportState?>(null)
    val captionPanelOpen = MutableStateFlow(false)
    /** Текст, который сейчас редактируется (новый или существующий). */
    val textDraft = MutableStateFlow<TextDraft?>(null)
    private val pagStore = PagTemplateStore(app)
    /** Шаблоны анимированных титров (.pag): встроенные и импортированные пользователем. */
    val pagTemplates = MutableStateFlow(pagStore.list())
    /** Текстовый слой, выбранный тапом на холсте (вне редактора). */
    val canvasTextId = MutableStateFlow<String?>(null)
    /** Положение кадра клипа во время жеста: показывается сразу, пока плеер ещё не пересобрался. */
    val liveClipTransform = MutableStateFlow<LiveClip?>(null)
    val texts get() = controller.texts
    val editingCaptionId = MutableStateFlow<String?>(null)
    val videoAspect get() = controller.canvas.let { it.width.toFloat() / it.height }

    var onRequestAddMedia: (() -> Unit)? = null
    /** Открыть системный выбор аудиофайла (подставляет экран). */
    var onRequestAddAudio: (() -> Unit)? = null
    private var aspect = 9f / 16f
    private var originalAspect = 9f / 16f
    val format = MutableStateFlow(com.base.editor.core.CanvasFormat.ORIGINAL)
    val formatPanelOpen = MutableStateFlow(false)
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = repo.loadTimeline(projectId)
            controller.load(saved)
            format.value = com.base.editor.core.CanvasFormat.of(repo.loadFormat(projectId))
            captions.load(repo.loadCaptions(projectId))
            controller.loadTexts(repo.loadTexts(projectId))
            withContext(dispatchers.default) { detectAspect() }      // чтение метаданных — не на главном потоке
            applyCanvas()
            isLoading.value = false
        }
        viewModelScope.launch { controller.events.collect { events.value = it } }
        viewModelScope.launch { controller.committed.debounce(600).collect { persist() } }
        viewModelScope.launch { captions.committed.debounce(600).collect { persist() } }
        // файлы, выбранные в галерее по кнопке «+», приходят через почтовый ящик проекта
        viewModelScope.launch {
            for (items in PickedMediaInbox.channel(projectId)) controller.addMedia(items)
        }
        // выбранный клип / панель переходов не должны ссылаться на исчезнувшие клипы
        viewModelScope.launch {
            controller.state.collect { s ->
                if (selectedId.value != null && s.clips.none { it.id == selectedId.value }) selectedId.value = null
                transitionFor.value?.let { id ->
                    val max = controller.maxTransitionMs(id)
                    if (s.clips.none { it.id == id } || max <= 0) transitionFor.value = null else transitionMaxMs.value = max
                }
            }
        }
    }

    // ───────── проект ─────────
    private suspend fun detectAspect() {
        val first = controller.state.value.clips.filter { it.row == 0 }.minByOrNull { it.startMs } ?: return
        MediaProbe.displaySize(getApplication(), first.uri, first.type)?.let { (w, h) -> originalAspect = w.toFloat() / h }
    }

    /** Формат холста: пресет или пропорции первого клипа («Оригинал»). Влияет и на превью, и на экспорт (aspect → canvasFor). */
    fun setFormat(f: com.base.editor.core.CanvasFormat) { format.value = f; applyCanvas(); persist() }
    fun openFormat() { controller.pause(); selectedId.value = null; transitionFor.value = null; captionPanelOpen.value = false; formatPanelOpen.value = true }
    fun closeFormat() { formatPanelOpen.value = false }

    private fun shortSide() = when (resolution.value) { "480p" -> 480; "1080p" -> 1080; else -> 720 }
    private fun applyCanvas() { aspect = format.value.aspect ?: originalAspect; controller.setCanvas(CompositionFactory.canvasFor(aspect, shortSide())) }
    fun setResolution(r: String) { resolution.value = r; applyCanvas() }

    private fun persist() {
        val data = controller.serialize(); val clips = controller.state.value.clips
        val cap = captions.toJson(); val txt = controller.serializeTexts()
        val fmt = format.value.id
        persistScope.launch { repo.save(projectId, data, clips, cap, txt, fmt) }
    }
    fun saveNow() = persist()

    // ───────── воспроизведение ─────────
    fun togglePlay() = controller.toggle()
    fun toggleMute() { muted.value = !muted.value; controller.setMuted(muted.value) }
    fun undo() = controller.undo()
    fun redo() = controller.redo()

    // ───────── TimelineActions ─────────
    override fun scrubStart() = controller.pause()
    override fun scrubTo(ms: Long) {
        if (isLoading.value || controller.state.value.clips.isEmpty()) return   // нет композиции — нечего перематывать
        controller.scrubTo(snapToKeyframe(ms))
    }
    override fun scrubEnd() = controller.scrubEnd()

    /** Курсор «считывает» ромбики выбранного клипа: рядом с ключом (±8 px шкалы) скраб прилипает к нему. */
    private fun snapToKeyframe(ms: Long): Long {
        val id = selectedId.value ?: return ms
        val clip = clips.value.firstOrNull { it.id == id } ?: return ms
        val keys = keyframes.value[id].orEmpty()
        if (keys.isEmpty()) return ms
        val threshold = (8f / pxPerSec.value * 1000).toLong()
        val hit = keys.minByOrNull { kotlin.math.abs(it.timeMs + clip.startMs - ms) } ?: return ms
        val at = hit.timeMs + clip.startMs
        return if (kotlin.math.abs(at - ms) <= threshold) at else ms
    }
    override fun select(id: Long?) { selectedId.value = id; if (id != null) canvasTextId.value = null }
    override fun editBegin() = controller.beginEdit()
    override fun moveClip(id: Long, startMs: Long) = controller.move(id, startMs, (8f / pxPerSec.value * 1000).toLong())
    override fun trimStart(id: Long, ms: Long) = controller.trimStart(id, ms)
    override fun trimEnd(id: Long, ms: Long) = controller.trimEnd(id, ms)
    override fun editEnd() = controller.commitEdit()
    override fun setZoom(pxPerSecDp: Float) { pxPerSec.value = pxPerSecDp.coerceIn(12f, 400f) }
    override fun addMedia() { controller.pause(); onRequestAddMedia?.invoke() }
    override fun addAudio() { onRequestAddAudio?.invoke() }
    override fun addText() { events.value = "Текстовые слои появятся на следующем этапе" }

    /** URI аудиофайла из системного выбора аудио: проба длительности вне главного потока, затем блок на дорожке. */
    fun onAudioPicked(uri: android.net.Uri) {
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch {
            val dur = withContext(dispatchers.default) { MediaProbe.audioDurationMs(getApplication(), uri.toString()) }
            if (dur == null) events.value = "Не удалось прочитать аудиофайл" else controller.addAudio(uri.toString(), dur)
        }
    }

    // ───────── ключевые кадры ─────────
    /** Клип под курсором = выбранный клип ОСНОВНОЙ дорожки, и курсор внутри него (иначе ромбик недоступен). */
    private fun selectedClipAtPlayhead(): Clip? {
        val id = selectedId.value ?: return null
        val t = playheadMs.value
        return clips.value.firstOrNull { it.id == id && it.row == 0 && t >= it.startMs && t < it.endMs }
    }

    /** Есть ли ключ ровно под курсором плеера (для иконки «ромбик с минусом»). */
    fun hasKeyframeAtCursor(): Boolean {
        val clip = selectedClipAtPlayhead() ?: return false
        return controller.keyframeAt(clip.id, playheadMs.value - clip.startMs) != null
    }

    /** Ромбик: нет ключа на этой миллисекунде — добавить; курсор стоит на ключе — удалить. */
    fun toggleKeyframe() {
        val clip = selectedClipAtPlayhead() ?: return
        controller.toggleKeyframe(clip.id, playheadMs.value - clip.startMs)
    }

    fun split() {
        val id = selectedId.value ?: return
        if (!controller.split(id)) events.value = "Поставьте курсор внутрь клипа"
    }

    fun deleteSelected() { selectedId.value?.let { controller.remove(it); selectedId.value = null } }

    fun selectAtPlayhead() {
        val t = playheadMs.value
        selectedId.value = clips.value.firstOrNull { it.row == 0 && t >= it.startMs && t < it.endMs }?.id
    }

    // ───────── переходы ─────────
    override fun openTransitions(leftId: Long) {
        controller.pause()
        selectedId.value = null
        transitionMaxMs.value = controller.maxTransitionMs(leftId)
        transitionFor.value = leftId
        clips.value.firstOrNull { it.id == leftId }?.let { controller.seekTo(it.endMs) }   // курсор на стык
    }

    fun closeTransitions() { transitionFor.value = null }
    fun currentTransition(leftId: Long): Transition? = transitions.value.firstOrNull { it.leftId == leftId }

    /** shaderId == null — убрать переход. Сразу проигрывает окно перехода в плеере. */
    fun applyTransition(leftId: Long, shaderId: String?, durationMs: Long) {
        if (controller.setTransition(leftId, shaderId, durationMs) && shaderId != null) controller.previewTransition(leftId)
    }

    // ───────── субтитры ─────────
    fun openCaptions() { controller.pause(); formatPanelOpen.value = false; selectedId.value = null; transitionFor.value = null; captionPanelOpen.value = true }
    fun closeCaptions() { captionPanelOpen.value = false; editingCaptionId.value = null }
    override fun openCaption(id: String) {
        openCaptions()
        captions.items.value.firstOrNull { it.id == id }?.let { controller.seekTo(it.startMs); editingCaptionId.value = id }
    }
    fun openCaptionItem(c: CaptionItem) { controller.pause(); controller.seekTo(c.startMs); editingCaptionId.value = c.id }
    fun addCaptionHere() { editingCaptionId.value = captions.addAt(playheadMs.value) }
    fun generateCaptions() {
        if (controller.state.value.clips.none { it.type == com.base.editor.core.MediaType.VIDEO }) { events.value = "Нужен хотя бы один видеоклип со звуком"; return }
        controller.pause(); captions.generate(controller.state.value)
    }

    // ───────── кадрирование ─────────
    val cropClipId = MutableStateFlow<Long?>(null)
    val cropFrame = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val cropInitial = MutableStateFlow(com.base.editor.core.CropRect())

    fun openCrop() {
        val clip = selectedClipAtPlayhead() ?: return
        if (clip.type == com.base.editor.core.MediaType.AUDIO) return
        controller.pause()
        cropInitial.value = controller.cropOf(clip.id)
        cropFrame.value = null; cropClipId.value = clip.id
        // кадр показываем БЕЗ уже применённой обрезки, чтобы рамку можно было расширить обратно
        val local = (playheadMs.value - clip.startMs).coerceIn(0L, (clip.endMs - clip.startMs).coerceAtLeast(0L))
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching { loadFrame(clip, local) }.getOrNull()
            cropFrame.value = bmp?.asImageBitmap()
        }
    }
    fun applyCrop(r: com.base.editor.core.CropRect?) {
        cropClipId.value?.let { controller.setCrop(it, r) }
        cropClipId.value = null; cropFrame.value = null
    }
    fun cancelCrop() { cropClipId.value = null; cropFrame.value = null }

    private fun loadFrame(c: Clip, localMs: Long): android.graphics.Bitmap? {
        val ctx = getApplication<Application>()
        val uri = android.net.Uri.parse(c.uri)
        return if (c.type == com.base.editor.core.MediaType.VIDEO) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                r.getScaledFrameAtTime((c.srcInMs + localMs) * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
            } finally { r.release() }
        } else {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val big = maxOf(info.size.width, info.size.height)
                if (big > 1600) dec.setTargetSampleSize(big / 1600)
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }

    // ───────── жесты на холсте ─────────
    /** Реальные пропорции исходников (по uri); рамка выделения должна совпадать с вписанным кадром. */
    val clipAspects = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val aspectRequested = HashSet<String>()
    private fun loadClipAspect(c: Clip) {
        if (!aspectRequested.add(c.uri)) return
        viewModelScope.launch(Dispatchers.IO) {
            MediaProbe.displaySize(getApplication(), c.uri, c.type)?.let { (w, h) ->
                clipAspects.update { it + (c.uri to w.toFloat() / h) }
            }
        }
    }

    /** Что выделено на холсте: слой текста (черновик имеет приоритет) или клип под курсором. */
    fun canvasTarget(): CanvasTarget? {
        textDraft.value?.let { return CanvasTarget.Text(it.clip, isDraft = true) }
        canvasTextId.value?.let { id -> controller.findText(id)?.let { return CanvasTarget.Text(it, isDraft = false) } }
        val clip = selectedClipAtPlayhead() ?: return null
        val live = liveClipTransform.value?.takeIf { it.id == clip.id }?.current
        val crop = controller.cropOf(clip.id)
        val aspect = (clipAspects.value[clip.uri] ?: videoAspect.also { loadClipAspect(clip) }) * (crop.width / crop.height)
        return CanvasTarget.Clip(clip.id, live ?: effectiveTransform(clip), aspect)
    }

    /** Текущее положение кадра клипа: интерполяция по ключам на курсоре, иначе статическая трансформация. */
    private fun effectiveTransform(clip: Clip): ClipTransform =
        controller.state.value.transformAt(clip.id, playheadMs.value - clip.startMs)

    override fun onGestureStart() { controller.beginEdit() }

    override fun onClipTransform(clipId: Long, transform: ClipTransform) {
        val clip = clips.value.firstOrNull { it.id == clipId } ?: return
        val hasKeys = keyframes.value[clipId].orEmpty().isNotEmpty()
        val baked = liveClipTransform.value?.takeIf { it.id == clipId }?.baked ?: effectiveTransform(clip)
        liveClipTransform.value = LiveClip(clipId, baked, transform)
        if (hasKeys) {
            // автоключ как в CapCut: любое движение кадра пальцем фиксирует ключ на текущей миллисекунде
            controller.setKeyframeTransform(clipId, playheadMs.value - clip.startMs, transform)
        } else {
            controller.setClipTransform(clipId, transform)
        }
    }

    override fun onTextTransform(clip: TextClip, isDraft: Boolean) {
        if (isDraft) updateTextDraft { clip } else controller.updateText(clip)
    }

    /** Конец жеста: фиксируем и пересобираем композицию; «живая» трансформация держится, пока не покажется новый кадр. */
    override fun onGestureEnd() {
        val live = liveClipTransform.value
        if (live != null) {
            val applied = controller.appliedVersion.value
            controller.commitTransformEdit()
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 1000
                while (controller.appliedVersion.value == applied && System.currentTimeMillis() < deadline) delay(40)
                liveClipTransform.value = null
            }
        } else controller.pause()
    }

    override fun onTapText(id: String) {
        if (canvasTextId.value == id && textDraft.value == null) openText(id)        // второй тап — редактор
        else { canvasTextId.value = id; selectedId.value = null }
    }

    override fun onTapVideo() {
        canvasTextId.value = null
        if (textDraft.value != null) return
        val t = playheadMs.value
        val clip = clips.value.firstOrNull { it.row == 0 && t >= it.startMs && t < it.endMs }
        selectedId.value = if (clip != null && selectedId.value != clip.id) clip.id else null
    }

    // ───────── текст ─────────
    /** Кнопка «Текст»: открывает редактор нового слоя на позиции курсора. */
    fun openNewText() {
        controller.pause(); selectedId.value = null; transitionFor.value = null; captionPanelOpen.value = false
        textDraft.value = TextDraft(TextClip(text = "", startMs = playheadMs.value), isNew = true)
    }

    override fun moveText(id: String, startMs: Long) = controller.moveText(id, startMs)
    override fun moveCaption(id: String, startMs: Long) = captions.moveTo(id, startMs)

    override fun openText(id: String) {
        val clip = controller.findText(id) ?: return
        controller.pause(); controller.seekTo(clip.startMs)
        canvasTextId.value = id
        textDraft.value = TextDraft(clip, isNew = false)
    }

    fun updateTextDraft(f: (TextClip) -> TextClip) { textDraft.update { d -> d?.copy(clip = f(d.clip)) } }

    /** «Готово»: пустой новый текст не создаётся. */
    fun commitText() {
        val d = textDraft.value ?: return
        textDraft.value = null
        if (d.clip.text.isBlank()) { if (!d.isNew) controller.removeText(d.clip.id); return }
        if (d.isNew) controller.addText(d.clip) else controller.updateText(d.clip)
    }

    fun importPag(uri: android.net.Uri) {
        viewModelScope.launch {
            val t = pagStore.import(uri)
            if (t == null) { events.value = "Не удалось открыть файл шаблона"; return@launch }
            pagTemplates.value = pagStore.list()
            updateTextDraft { it.copy(pagTemplate = t.ref) }
        }
    }

    fun cancelText() { textDraft.value = null }
    fun deleteText() { textDraft.value?.let { if (!it.isNew) controller.removeText(it.clip.id) }; textDraft.value = null; canvasTextId.value = null }

    // ───────── экспорт ─────────
    fun startExport(quality: ExportQuality = ExportQuality.P1080) {
        if (exportJob?.isActive == true) return
        controller.pause()
        val track = captions.items.value.takeIf { it.isNotEmpty() }?.let { CaptionTrack(it, captions.style.value) }
        val request = ExportRequest(controller.state.value, aspect, quality, removeAudio = muted.value, captions = track, texts = controller.texts.value)
        exportJob = viewModelScope.launch {
            exporter.export(request).collect { s ->
                exportState.value = s
                if (s is ExportState.Done) {
                    runCatching { exporter.saveToGallery(s.file) }
                        .onFailure { exportState.value = ExportState.Failed("Не удалось сохранить в галерею", it) }
                        .onSuccess { events.value = "Видео сохранено в Movies/BASE" }
                }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel(); exportState.value = null }
    fun dismissExport() { exportState.value = null }

    override fun onCleared() {
        captions.cancelGeneration()
        persist()
        controller.release()
    }
}
