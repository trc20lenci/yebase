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
import com.base.editor.text.TextAnimation
import com.base.editor.text.TextAnimator
import com.base.editor.text.TextClip
import com.base.editor.text.TextStyles
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
import kotlinx.coroutines.flow.first
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
    fun trimText(id: String, startMs: Long, endMs: Long)
    fun trimCaption(id: String, startMs: Long, endMs: Long)
}

/** Клип во время жеста: [baked] — положение, уже «запечённое» в показанной композиции; [current] — новое. */
data class LiveClip(val id: Long, val baked: ClipTransform, val current: ClipTransform)

/** Инструменты панели «Изменить» с отдельной панелью. */
enum class MediaTool { SPEED, VOLUME, CHROMA, BG }

/** Раздел нижней панели текста. */
enum class TextSub { FONTS, STYLES, ANIMATION, COLOR }

private const val SCRUB_OVERLAY_HOLD_MS = 350L

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
    /** Частота кадров экспорта: 24 / 30 / 60. */
    val exportFps = MutableStateFlow(30)
    fun setExportFps(f: Int) { exportFps.value = f.takeIf { it in listOf(24, 30, 60) } ?: 30 }
    val muted = MutableStateFlow(false)
    /** true, пока проект загружается: жесты перемотки в это время игнорируются. */
    val isLoading = MutableStateFlow(true)
    val events = MutableStateFlow<String?>(null)          // одноразовые сообщения для Toast
    val transitionFor = MutableStateFlow<Long?>(null)     // стык, для которого открыта панель переходов
    val transitionMaxMs = MutableStateFlow(0L)
    val exportState = MutableStateFlow<ExportState?>(null)
    val captionPanelOpen = MutableStateFlow(false)
    /** Текст, который сейчас редактируется (новый или существующий). */
    /** true — открыта строка ввода текста (клавиатура); false — показана нижняя панель инструментов текста. */
    val textInputOpen = MutableStateFlow(false)
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
    /**
     * Пропорции окна предпросмотра. Меняются только когда плеер показал композицию нового формата, поэтому окно
     * не «схлопывается» в промежутке пересборки; сам переход в интерфейсе плавный (animateFloatAsState).
     */
    val displayAspect = MutableStateFlow(9f / 16f)
    /** Пропорции выбранного формата — для рамки предпросмотра: меняются мгновенно, одним кадром. */
    val canvasAspect = MutableStateFlow(9f / 16f)
    val exportSheetOpen = MutableStateFlow(false)
    fun openExportSheet() { controller.pause(); exportSheetOpen.value = true }
    fun closeExportSheet() { exportSheetOpen.value = false }

    // ───────── фон холста ─────────
    val canvasBg = MutableStateFlow(com.base.editor.core.CanvasBg.BLACK)
    val bgBarOpen = MutableStateFlow(false)
    fun openBgBar() { controller.pause(); select(null); formatPanelOpen.value = false; captionPanelOpen.value = false; bgBarOpen.value = true }
    fun closeBgBar() { bgBarOpen.value = false }
    fun setCanvasBg(b: com.base.editor.core.CanvasBg) {
        if (b == canvasBg.value) return
        snapshotWhileRebuilding(); canvasBg.value = b; controller.setCanvasBg(b); persist()
    }

    private var snapshotJob: kotlinx.coroutines.Job? = null
    /**
     * Смена формата/фона требует пересборки композиции (несколько секунд). Чтобы картинка не «сжималась и приходила в
     * себя», на это время поверх плеера показывается текущий кадр (вписанный в НОВУЮ рамку), и снимается он, когда
     * плеер показал новую композицию.
     */
    private fun snapshotWhileRebuilding() {
        val t = playheadMs.value
        val clip = clips.value.firstOrNull { it.row == 0 && it.type != com.base.editor.core.MediaType.AUDIO && t >= it.startMs && t < it.endMs } ?: return
        val local = t - clip.startMs
        scrubHideJob?.cancel(); scrubOverlayOn.value = true; overlayCover.value = true
        scrubFrames.request(clip, local, true, controller.state.value.transformAt(clip.id, local), controller.cropOf(clip.id))
        val v0 = controller.appliedVersion.value
        snapshotJob?.cancel()
        snapshotJob = viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(9000) { controller.appliedVersion.first { it > v0 } }
            controller.awaitSeekSettled(2500)
            hideOverlay()
        }
    }

    // ───────── живой предпросмотр при скраббинге ─────────
    private val scrubFrames = com.base.editor.media.ScrubFrames(app, viewModelScope)
    val scrubFrame = scrubFrames.frame
    val scrubOverlayOn = MutableStateFlow(false)
    /** true — подмена закрывает плеер сразу (смена формата/фона), не дожидаясь кадра: иначе видно, как картинка «сплющивается». */
    val overlayCover = MutableStateFlow(false)
    private fun hideOverlay() { scrubHideJob?.cancel(); snapshotJob?.cancel(); scrubOverlayOn.value = false; overlayCover.value = false; scrubFrames.clear() }
    private var scrubHideJob: kotlinx.coroutines.Job? = null

    var onRequestAddMedia: (() -> Unit)? = null
    /** Открыть системный выбор аудиофайла (подставляет экран). */
    var onRequestAddAudio: (() -> Unit)? = null
    private var aspect = 9f / 16f
    private var originalAspect = 9f / 16f
    val format = MutableStateFlow(com.base.editor.core.CanvasFormat.ORIGINAL)
    val formatPanelOpen = MutableStateFlow(false)
    private var exportJob: Job? = null

    init {
        viewModelScope.launch { controller.appliedVersion.collect { displayAspect.value = videoAspect } }
        viewModelScope.launch {
            val saved = repo.loadTimeline(projectId)
            controller.load(saved)
            format.value = com.base.editor.core.CanvasFormat.of(repo.loadFormat(projectId))
            canvasBg.value = com.base.editor.core.CanvasBg.of(repo.loadBg(projectId)); controller.setCanvasBg(canvasBg.value)
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
    fun setFormat(f: com.base.editor.core.CanvasFormat) { snapshotWhileRebuilding(); format.value = f; applyCanvas(); persist() }
    fun openFormat() { controller.pause(); selectedId.value = null; transitionFor.value = null; captionPanelOpen.value = false; bgBarOpen.value = false; formatPanelOpen.value = true }
    fun closeFormat() { formatPanelOpen.value = false }

    private fun shortSide() = when (resolution.value) { "480p" -> 480; "1080p" -> 1080; else -> 720 }
    private fun applyCanvas() { aspect = format.value.aspect ?: originalAspect; canvasAspect.value = aspect; controller.setCanvas(CompositionFactory.canvasFor(aspect, shortSide())) }
    fun setResolution(r: String) { resolution.value = r; applyCanvas() }

    private fun persist() {
        val data = controller.serialize(); val clips = controller.state.value.clips
        val cap = captions.toJson(); val txt = controller.serializeTexts()
        val fmt = format.value.id
        persistScope.launch { repo.save(projectId, data, clips, cap, txt, fmt, canvasBg.value.id) }
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
        val t = snapToKeyframe(ms)
        controller.scrubTo(t)
        scrubPreview(t, exact = false)
    }

    override fun scrubEnd() {
        controller.scrubEnd()
        scrubPreview(playheadMs.value, exact = true)           // палец остановился — точный кадр
        scrubHideJob?.cancel()
        scrubHideJob = viewModelScope.launch {
            // оверлей держится, пока плеер догоняет позицию (BUFFERING), но не дольше нескольких секунд
            delay(SCRUB_OVERLAY_HOLD_MS)
            val deadline = System.currentTimeMillis() + 3000
            while (controller.isBuffering.value && System.currentTimeMillis() < deadline) delay(50)
            delay(150)
            hideOverlay()
        }
    }

    /** Кадр под курсором напрямую из файла: плеер на паузе догоняет позицию с задержкой, а этот кадр виден сразу. */
    private fun scrubPreview(t: Long, exact: Boolean) {
        val clip = clips.value.firstOrNull { it.row == 0 && it.type != com.base.editor.core.MediaType.AUDIO && t >= it.startMs && t < it.endMs } ?: return
        scrubHideJob?.cancel(); scrubOverlayOn.value = true
        val local = t - clip.startMs
        scrubFrames.request(clip, local, exact, controller.state.value.transformAt(clip.id, local), controller.cropOf(clip.id))
    }

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
    /** Выделение одно на весь редактор: клип/аудио, текстовый слой или карточка субтитров. null — снять всё. */
    override fun select(id: Long?) {
        selectedId.value = id; canvasTextId.value = null; selectedCaptionId.value = null; textSub.value = null
        bgBarOpen.value = false; pipetteOn.value = false
    }
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
    fun openCaptions() { controller.pause(); formatPanelOpen.value = false; selectedId.value = null; canvasTextId.value = null; selectedCaptionId.value = null; transitionFor.value = null; captionPanelOpen.value = true }
    fun closeCaptions() { captionPanelOpen.value = false; editingCaptionId.value = null }
    fun openCaptionItem(c: CaptionItem) { controller.pause(); controller.seekTo(c.startMs); editingCaptionId.value = c.id }
    fun addCaptionHere() { editingCaptionId.value = captions.addAt(playheadMs.value) }
    fun prefetchCaptionModel(lang: com.base.editor.captions.asr.SpeechLanguage) = captions.prefetch(lang)

    fun generateCaptions(lang: com.base.editor.captions.asr.SpeechLanguage) {
        if (controller.state.value.clips.none { it.type == com.base.editor.core.MediaType.VIDEO }) { events.value = "Нужен хотя бы один видеоклип со звуком"; return }
        controller.pause(); captions.generate(controller.state.value, lang)
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
                com.base.editor.media.MediaFrames.scaledFrame(r, (c.srcInMs + localMs) * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280)
            } finally { r.release() }
        } else {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val big = maxOf(info.size.width, info.size.height)
                if (big > 1600) dec.setTargetSampleSize(big / 1600)
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }

    // ───────── инструменты «Изменить»: скорость, громкость, хромакей, удаление фона ─────────
    val mediaTool = MutableStateFlow<MediaTool?>(null)
    val colorPickOpen = MutableStateFlow(false)
    val bgJob = MutableStateFlow<BgJobState?>(null)
    private var bgCoroutine: kotlinx.coroutines.Job? = null
    private val maskStore = com.base.editor.media.MaskStore(app)
    private val bgRemover = com.base.editor.media.BgRemover(app, maskStore)

    /** Плитка на главном экране: после загрузки клипов сразу открываем выбранный инструмент. */
    private fun runPendingTool() = viewModelScope.launch {
        val tool = com.base.editor.data.PendingTool.take() ?: return@launch
        val ready = kotlinx.coroutines.withTimeoutOrNull(6000) { controller.state.first { st -> st.clips.any { it.row == 0 } } } ?: return@launch
        val first = ready.clips.filter { it.row == 0 }.minByOrNull { it.startMs } ?: return@launch
        when (tool) {
            "subtitles" -> openCaptions()
            "text" -> openNewText()
            "music" -> addAudio()
            "bg" -> { select(first.id); openMediaTool(MediaTool.BG) }
            "chroma" -> { select(first.id); openMediaTool(MediaTool.CHROMA) }
            "speed" -> { select(first.id); openMediaTool(MediaTool.SPEED) }
        }
    }
    init {
        runPendingTool()
        viewModelScope.launch { controller.isPlaying.collect { if (it) hideOverlay() } }
        captions.prefetch(com.base.editor.captions.asr.SpeechLanguage.RU)
        com.base.editor.media.gl.FxDiagnostics.listener = { msg -> events.value = msg }
    }

    val selectedClip get() = selectedId.value?.let { id -> clips.value.firstOrNull { it.id == id } }

    fun openMediaTool(t: MediaTool) {
        val c = selectedClip ?: return
        if (c.type == com.base.editor.core.MediaType.IMAGE && (t == MediaTool.SPEED || t == MediaTool.VOLUME)) { events.value = "Для фото это недоступно"; return }
        if (c.type == com.base.editor.core.MediaType.AUDIO && (t == MediaTool.CHROMA || t == MediaTool.BG)) { events.value = "Для звука это недоступно"; return }
        controller.pause(); mediaTool.value = t
        if (t == MediaTool.CHROMA) startPipette() else pipetteOn.value = false
    }
    fun closeMediaTool() { controller.commitFxEdit(); mediaTool.value = null; colorPickOpen.value = false; pipetteOn.value = false }

    fun setClipSpeed(v: Float) { selectedId.value?.let { controller.setSpeed(it, v) } }
    fun setClipVolume(v: Float) { selectedId.value?.let { controller.setVolume(it, v) } }

    fun chromaOfSelected() = selectedId.value?.let { controller.state.value.chromas[it] }
    fun updateChroma(f: (com.base.editor.core.ChromaKey) -> com.base.editor.core.ChromaKey) {
        val id = selectedId.value ?: return
        val cur = controller.state.value.chromas[id] ?: return
        controller.setChroma(id, f(cur), rebuild = false)
    }
    fun resetChroma() {
        val id = selectedId.value ?: return
        controller.setChroma(id, null, rebuild = true)
        pipettePos.value = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
    }
    fun commitFx() = controller.commitFxEdit()

    // ───────── пипетка: кольцо на самом кадре, цвет берётся под его центром ─────────
    val pipetteOn = MutableStateFlow(false)
    val pipettePos = MutableStateFlow(androidx.compose.ui.geometry.Offset(0.5f, 0.5f))      // доли холста
    private var pipetteFrame: android.graphics.Bitmap? = null
    private var pipetteFrameClip = -1L

    fun startPipette() {
        val clip = selectedClip ?: return
        pipetteOn.value = true
        if (pipetteFrameClip == clip.id && pipetteFrame != null) return
        val local = (playheadMs.value - clip.startMs).coerceIn(0L, (clip.endMs - clip.startMs).coerceAtLeast(0L))
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching { loadFrame(clip, (local * clip.speed).toLong()) }.getOrNull() ?: return@launch
            val c = controller.cropOf(clip.id)
            pipetteFrame = if (c.isFull) bmp else android.graphics.Bitmap.createBitmap(bmp,
                (c.left * bmp.width).toInt().coerceIn(0, bmp.width - 1), (c.top * bmp.height).toInt().coerceIn(0, bmp.height - 1),
                (c.width * bmp.width).toInt().coerceIn(1, bmp.width), (c.height * bmp.height).toInt().coerceIn(1, bmp.height))
            pipetteFrameClip = clip.id
        }
    }
    fun stopPipette() { pipetteOn.value = false }

    /** Кольцо пипетки сдвинуто в [pos] (доли холста): берём цвет кадра под центром и сразу применяем как ключ. */
    fun movePipette(pos: androidx.compose.ui.geometry.Offset, canvasW: Float, canvasH: Float) {
        pipettePos.value = pos
        val clip = selectedClip ?: return
        val bmp = pipetteFrame ?: return
        val local = (playheadMs.value - clip.startMs).coerceAtLeast(0L)
        val t = controller.state.value.transformAt(clip.id, local)
        // обратное преобразование: холст → кадр (сдвиг, поворот, масштаб относительно центра, вписывание Fit)
        var cx = (pos.x - 0.5f) * canvasW - t.x * canvasW
        var cy = (pos.y - 0.5f) * canvasH - t.y * canvasH
        val rad = Math.toRadians(-t.rotationDeg.toDouble())
        val rx = ((cx * Math.cos(rad) - cy * Math.sin(rad)) / t.scale).toFloat()
        val ry = ((cx * Math.sin(rad) + cy * Math.cos(rad)) / t.scale).toFloat()
        val ac = canvasW / canvasH; val af = bmp.width.toFloat() / bmp.height
        val fw = if (af >= ac) canvasW else canvasH * af
        val fh = if (af >= ac) canvasW / af else canvasH
        val u = rx / fw + 0.5f; val v = ry / fh + 0.5f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return                       // кольцо над полем — цвет не берём
        val px = (u * bmp.width).toInt().coerceIn(0, bmp.width - 1); val py = (v * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val c = bmp.getPixel((px + dx).coerceIn(0, bmp.width - 1), (py + dy).coerceIn(0, bmp.height - 1))
            r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c); n++
        }
        val argb = android.graphics.Color.rgb(r / n, g / n, b / n)
        val id = clip.id
        val cur = controller.state.value.chromas[id]
        if (cur == null) controller.setChroma(id, com.base.editor.core.ChromaKey(color = argb), rebuild = true)       // включается при первом выборе цвета
        else controller.setChroma(id, cur.copy(color = argb), rebuild = false)
    }

    fun bgMaskReady() = selectedClip?.let { maskStore.has(it.uri) } == true
    fun bgOfSelected() = selectedId.value?.let { controller.state.value.bgs[it] }

    /** Удаление фона: сегментация идёт в фоне (прогресс в панели), интерфейс не блокируется. */
    fun startBg(recompute: Boolean) {
        val clip = selectedClip ?: return
        if (bgJob.value != null) return
        if (!recompute && maskStore.has(clip.uri)) { controller.setBg(clip.id, com.base.editor.core.BgRemoval(), rebuild = true); return }
        bgJob.value = BgJobState(clip.id, 0f)
        bgCoroutine = viewModelScope.launch {
            try {
                if (recompute) maskStore.delete(clip.uri)
                bgRemover.process(clip) { p -> bgJob.value = BgJobState(clip.id, p) }
                controller.setBg(clip.id, controller.state.value.bgs[clip.id] ?: com.base.editor.core.BgRemoval(), rebuild = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: com.base.editor.media.BgRemover.Failure) { events.value = e.message
            } catch (e: Exception) { android.util.Log.e("BaseBg", "удаление фона", e); events.value = "Не удалось удалить фон: ${e.javaClass.simpleName}: ${e.message?.take(90)}"
            } finally { bgJob.value = null }
        }
    }
    fun cancelBg() { bgCoroutine?.cancel(); bgJob.value = null }
    fun updateBg(f: (com.base.editor.core.BgRemoval) -> com.base.editor.core.BgRemoval) {
        val id = selectedId.value ?: return
        val cur = controller.state.value.bgs[id] ?: return
        controller.setBg(id, f(cur), rebuild = false)
    }
    fun disableBg() { selectedId.value?.let { controller.setBg(it, null, rebuild = true) } }

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
        if (liveClipTransform.value == null) {
            // начало жеста: показываем кадр клипа картинкой поверх плеера — она следует за пальцами в реальном времени
            val local = (playheadMs.value - clip.startMs).coerceAtLeast(0L)
            scrubHideJob?.cancel(); scrubOverlayOn.value = true
            scrubFrames.request(clip, local, true, baked, controller.cropOf(clipId))
        }
        liveClipTransform.value = LiveClip(clipId, baked, transform)
        if (hasKeys) {
            // автоключ как в CapCut: любое движение кадра пальцем фиксирует ключ на текущей миллисекунде
            controller.setKeyframeTransform(clipId, playheadMs.value - clip.startMs, transform)
        } else {
            controller.setClipTransform(clipId, transform)
        }
    }

    override fun onTextTransform(clip: TextClip, isDraft: Boolean) { controller.updateText(clip) }

    /** Конец жеста: фиксируем и пересобираем композицию; «живая» трансформация держится, пока не покажется новый кадр. */
    override fun onGestureEnd() {
        val live = liveClipTransform.value
        if (live != null) {
            val applied = controller.appliedVersion.value
            controller.commitTransformEdit()
            viewModelScope.launch {
                // кадр-подмена держится, пока плеер реально не покажет новое положение (а не фиксированное время)
                val deadline = System.currentTimeMillis() + 5000
                while (controller.appliedVersion.value == applied && System.currentTimeMillis() < deadline) delay(40)
                liveClipTransform.value = null
                hideOverlay()
            }
        } else controller.pause()
    }

    override fun onTapText(id: String) { openText(id) }

    override fun onTapVideo() {
        // тап по видео: выделение клипа под курсором; выделенный текст/субтитры — снимаются
        canvasTextId.value = null; selectedCaptionId.value = null; textSub.value = null
        val t = playheadMs.value
        val clip = clips.value.firstOrNull { it.row == 0 && t >= it.startMs && t < it.endMs }
        selectedId.value = if (clip != null && selectedId.value != clip.id) clip.id else null
    }

    // ───────── текст: правки сразу попадают в слой, отдельных «черновиков» и модальных окон нет ─────────
    val textSub = MutableStateFlow<TextSub?>(null)
    val captionInputOpen = MutableStateFlow(false)
    val selectedCaptionId = MutableStateFlow<String?>(null)

    /** Кнопка «Текст»: создаёт слой на позиции курсора, выделяет его и открывает строку ввода. */
    fun openNewText() {
        controller.pause()
        val clip = TextClip(text = "Текст", startMs = playheadMs.value).let { TextStyles.byId("plain")!!.apply(it) }
        controller.addText(clip)
        select(null); canvasTextId.value = clip.id
        textInputOpen.value = true
    }

    override fun moveText(id: String, startMs: Long) = controller.moveText(id, startMs)
    override fun moveCaption(id: String, startMs: Long) = captions.moveTo(id, startMs)
    override fun trimText(id: String, startMs: Long, endMs: Long) {
        controller.findText(id)?.let {
            val s0 = startMs.coerceAtLeast(0L); val e0 = maxOf(endMs, s0 + TextClip.MIN_DURATION_MS)
            controller.updateText(it.copy(startMs = s0, durationMs = (e0 - s0).coerceAtMost(TextClip.MAX_DURATION_MS)))
        }
    }
    override fun trimCaption(id: String, startMs: Long, endMs: Long) = captions.trim(id, startMs, endMs)

    /** Тап по текстовому блоку (таймлайн или холст): выделение → в слоте инструментов появляется панель текста. Плеер не трогаем. */
    override fun openText(id: String) {
        if (controller.findText(id) == null) return
        selectedId.value = null; selectedCaptionId.value = null; captionPanelOpen.value = false; textSub.value = null
        canvasTextId.value = id
    }

    /** Тап по блоку субтитров: выделение карточки; панель инструментов субтитров появляется в слоте. */
    override fun openCaption(id: String) {
        if (captions.items.value.none { it.id == id }) return
        selectedId.value = null; canvasTextId.value = null; textSub.value = null
        selectedCaptionId.value = id
    }

    val selectedTextClip get() = canvasTextId.value?.let { controller.findText(it) }

    fun editText(f: (TextClip) -> TextClip) {
        val c = selectedTextClip ?: return
        controller.updateText(f(c))
    }

    fun applyTextStyle(p: TextStyles.Preset) = editText { p.apply(it) }

    /** Выбор анимации: показываем её — курсор на начало слоя и короткое воспроизведение. */
    fun applyTextAnimation(anim: TextAnimation) {
        val c = selectedTextClip ?: return
        val updated = c.copy(animId = anim.id)
        controller.updateText(updated)
        if (anim != TextAnimation.NONE) controller.playRange(updated.startMs, minOf(updated.endMs, updated.startMs + TextAnimator.durationMs(updated) + 500))
    }

    fun setTextSub(v: TextSub?) { textSub.value = v }
    fun openTextInput() { textInputOpen.value = true }
    fun closeTextInput() { textInputOpen.value = false }
    fun setTextValue(t: String) = editText { it.copy(text = t) }

    /** «Разделить»: текстовый блок режется по курсору на две независимые части. */
    fun splitText() {
        val c = selectedTextClip ?: return
        val at = playheadMs.value
        if (at < c.startMs + TextClip.MIN_DURATION_MS || at > c.endMs - TextClip.MIN_DURATION_MS) {
            events.value = "Поставьте курсор внутрь блока"; return
        }
        controller.updateText(c.copy(durationMs = at - c.startMs))
        val right = c.copy(id = java.util.UUID.randomUUID().toString(), startMs = at, durationMs = c.endMs - at)
        controller.addText(right)
        canvasTextId.value = right.id
    }
    fun deleteText() { canvasTextId.value?.let(controller::removeText); canvasTextId.value = null; textSub.value = null }

    fun splitCaption() {
        val id = selectedCaptionId.value ?: return
        if (!captions.split(id, playheadMs.value)) events.value = "Поставьте курсор внутрь карточки"
    }
    fun deleteCaption() { selectedCaptionId.value?.let(captions::delete); selectedCaptionId.value = null }
    val selectedCaption get() = selectedCaptionId.value?.let { id -> captions.items.value.firstOrNull { it.id == id } }
    fun setCaptionValue(t: String) { selectedCaptionId.value?.let { captions.updateText(it, t) } }
    fun openCaptionInput() { captionInputOpen.value = true }
    fun closeCaptionInput() { captionInputOpen.value = false }

    fun importPag(uri: android.net.Uri) {
        viewModelScope.launch {
            val t = pagStore.import(uri)
            if (t == null) { events.value = "Не удалось открыть файл шаблона"; return@launch }
            pagTemplates.value = pagStore.list()
            editText { it.copy(pagTemplate = t.ref) }
        }
    }

    // ───────── экспорт ─────────
    private fun exportQuality() = when (resolution.value) { "480p" -> ExportQuality.P480; "1080p" -> ExportQuality.P1080; "2K/4K" -> ExportQuality.P1440; else -> ExportQuality.P720 }

    fun startExport(quality: ExportQuality = exportQuality()) {
        if (exportJob?.isActive == true) return
        controller.pause()
        val track = captions.items.value.takeIf { it.isNotEmpty() }?.let { CaptionTrack(it, captions.style.value) }
        val request = ExportRequest(controller.state.value, aspect, quality, fps = exportFps.value, canvasBg = canvasBg.value, removeAudio = muted.value, captions = track, texts = controller.texts.value)
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
        com.base.editor.media.gl.FxDiagnostics.listener = null
        scrubFrames.release()
        captions.cancelGeneration()
        persist()
        controller.release()
    }
}
