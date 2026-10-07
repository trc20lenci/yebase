package com.base.editor.media

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import com.base.editor.core.IMAGE_DEFAULT_MS
import com.base.editor.core.MediaType
import com.base.editor.core.PickedMedia
import com.base.editor.core.TransitionCatalog
import com.base.editor.core.ClipTransform
import com.base.editor.domain.TimelineModel
import com.base.editor.domain.TimelineState
import com.base.editor.text.TextClip
import com.base.editor.text.TextJson
import com.base.editor.text.TextTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Управляет дорожками, нарезкой, позиционированием и воспроизведением.
 *
 *  • Модель ([TimelineModel]) меняется мгновенно на главном потоке (это чистая арифметика).
 *  • Тяжёлое — сборка Composition — идёт на Dispatchers.Default; запросы пересборки «склеиваются»
 *    (CONFLATED), в плеер уходит только последняя версия.
 *  • Декодирование, GL-эффекты и вывод кадров выполняет Media3 на собственных потоках;
 *    на главном потоке остаются только неблокирующие команды плееру.
 *
 * Все публичные методы — с главного потока (требование плеера).
 */
@UnstableApi
class TimelineController(
    context: Context,
    private val scope: CoroutineScope,
    catalog: TransitionCatalog,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : Player.Listener {

    private val model = TimelineModel()
    private val textTrack = TextTrack()
    private val factory = CompositionFactory(context.applicationContext, catalog)

    val player: CompositionPlayer = CompositionPlayer.Builder(context.applicationContext).build().also { it.addListener(this) }

    private val _state = MutableStateFlow(TimelineState(emptyList(), emptyList()))
    val state: StateFlow<TimelineState> = _state.asStateFlow()
    private val _playhead = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playhead.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _playing.asStateFlow()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()
    private val _texts = MutableStateFlow<List<TextClip>>(emptyList())
    /** Текстовые слои дорожки «Текст» (отдельно от видеодорожки). */
    val texts: StateFlow<List<TextClip>> = _texts.asStateFlow()

    /** Срабатывает после каждой завершённой правки — по нему проект сохраняется. */
    private val _committed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val committed: SharedFlow<Unit> = _committed.asSharedFlow()

    var canvas: Size = Size(720, 1280)
        private set
    private var muted = false
    private var safeMode = false
    private var stopAtMs = -1L
    private val rebuildRequests = Channel<Unit>(Channel.CONFLATED)
    /** true — плеер подготовил композицию и принимает seek/play. */
    private var playerReady = false
    private val _applied = MutableStateFlow(0)
    /** Растёт каждый раз, когда новая композиция подготовлена и показана. */
    val appliedVersion: StateFlow<Int> = _applied.asStateFlow()
    private var pendingSeekMs = -1L
    /** Play нажат, пока плеер ещё готовился. */
    private var playWhenReady = false
    private val _buffering = MutableStateFlow(false)
    /** true — плеер ждёт данные (BUFFERING) или готовит композицию. */
    val isBuffering: StateFlow<Boolean> = _buffering.asStateFlow()

    init {
        scope.launch(dispatchers.default) {                 // конвейер пересборки
            for (ignored in rebuildRequests) {
                try {
                    val snapshot = _state.value
                    val request = CompositionRequest(snapshot, canvas, safeMode = safeMode, onTransitionFallback = { _events.tryEmit(it) },
                        liveTransforms = { id, localMs -> _state.value.transformAt(id, localMs) },
                        liveFx = { id -> com.base.editor.media.gl.FxParams(_state.value.chromas[id], _state.value.bgs[id]) })
                    val composition = runCatching { factory.build(request) }
                        .onFailure { Log.e(TAG, "не удалось собрать композицию", it); _events.tryEmit("Не удалось подготовить предпросмотр") }
                        .getOrNull()
                    // Плеер Media3 — строго главный поток
                    withContext(dispatchers.main) { applyComposition(composition) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // один сбой не должен убивать ни приложение, ни сам конвейер пересборки
                    Log.e(TAG, "сбой пересборки", e)
                    _events.tryEmit("Не удалось подготовить предпросмотр")
                }
            }
        }
        scope.launch {                                      // опрос позиции только пока играет
            while (isActive) {
                delay(POLL_MS)
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    _playhead.value = pos
                    if (stopAtMs in 0..pos) { stopAtMs = -1; pause() }
                }
            }
        }
    }

    // ───────── загрузка ─────────
    fun load(serialized: String?) {
        if (serialized != null) model.load(serialized)
        publish(); requestRebuild()
    }

    fun serialize(): String = model.serialize()

    // ───────── дорожка «Текст» ─────────
    fun loadTexts(json: String?) { textTrack.load(TextJson.decode(json)); _texts.value = textTrack.all() }
    fun serializeTexts(): String = TextJson.encode(textTrack.all())

    fun addText(clip: TextClip) { textTrack.add(clip); textsChanged() }
    fun updateText(clip: TextClip) { if (textTrack.update(clip)) textsChanged() }
    fun removeText(id: String) { if (textTrack.remove(id)) textsChanged() }
    fun findText(id: String) = textTrack.find(id)
    /** Перетаскивание текстового блока по шкале (долгое нажатие). */
    fun moveText(id: String, startMs: Long) { textTrack.find(id)?.let { updateText(it.copy(startMs = startMs.coerceAtLeast(0))) } }
    private fun textsChanged() { _texts.value = textTrack.all(); _committed.tryEmit(Unit) }

    /** Размер кадра превью/экспорта. Меняется редко (смена пропорций или качества). */
    fun setCanvas(size: Size) {
        if (size == canvas) return
        canvas = size
        requestRebuild()
    }

    // ───────── жесты и правки ─────────
    /** Начало жеста/операции: пауза + точка отката. */
    fun beginEdit() { pause(); model.checkpoint() }

    /** Промежуточные шаги жеста: обновляют только интерфейс, плеер не трогают. */
    fun move(id: Long, startMs: Long, snapMs: Long) { model.moveClip(id, startMs, snapMs, _playhead.value); publish() }
    fun trimStart(id: Long, ms: Long) { model.trimStart(id, ms); publish() }
    fun trimEnd(id: Long, ms: Long) { model.trimEnd(id, ms); publish() }

    /** Конец жеста: фиксируем и пересобираем композицию. */
    fun commitEdit() { model.discardCheckpointIfNoop(); afterStructuralEdit() }

    /** Положение кадра клипа на холсте: между beginEdit() и commitEdit() — без пересборки плеера. */
    fun setClipTransform(id: Long, t: ClipTransform) { if (model.setTransform(id, t)) publish() }

    /** Кадрирование клипа (null — снять). Пересобирает композицию: Crop Effect идёт и в превью, и в экспорт. */
    fun setCrop(id: Long, r: com.base.editor.core.CropRect?) {
        pause(); model.checkpoint()
        if (!model.setCrop(id, r)) { model.discardCheckpointIfNoop(); return }
        model.discardCheckpointIfNoop(); afterStructuralEdit()
    }
    fun cropOf(id: Long) = model.state().cropOf(id)

    /** Скорость клипа: длина на таймлайне пересчитывается, композиция пересобирается (темп аудио — с сохранением тона). */
    fun setSpeed(id: Long, speed: Float) {
        pause(); model.checkpoint()
        model.setSpeed(id, speed)
        model.discardCheckpointIfNoop(); afterStructuralEdit()
    }

    fun setVolume(id: Long, volume: Float) {
        pause(); model.checkpoint()
        model.setVolume(id, volume)
        model.discardCheckpointIfNoop(); afterStructuralEdit()
    }

    /**
     * Хромакей. [rebuild] — только при включении/выключении эффекта (меняется цепочка эффектов); правка параметров идёт
     * вживую: эффект читает их каждый кадр, достаточно перерисовать текущий кадр.
     */
    fun setChroma(id: Long, k: com.base.editor.core.ChromaKey?, rebuild: Boolean) {
        if (rebuild) { pause(); model.checkpoint() }
        model.setChroma(id, k)
        if (rebuild) { model.discardCheckpointIfNoop(); afterStructuralEdit() } else refreshFrameLive()
    }

    fun setBg(id: Long, b: com.base.editor.core.BgRemoval?, rebuild: Boolean) {
        if (rebuild) { pause(); model.checkpoint() }
        model.setBg(id, b)
        if (rebuild) { model.discardCheckpointIfNoop(); afterStructuralEdit() } else refreshFrameLive()
    }

    private var lastLiveRefresh = 0L
    /** Перерисовка текущего кадра при движении слайдера: публикуем состояние и просим плеер обновить кадр (не чаще ~30 раз/с). */
    private fun refreshFrameLive() {
        publish()
        val now = SystemClock.uptimeMillis()
        if (now - lastLiveRefresh < 33 || !playerReady) return
        lastLiveRefresh = now
        seekPlayerSafely(player.currentPosition)
    }

    /** Слайдер отпущен: сохранить и перерисовать кадр точно. */
    fun commitFxEdit() { model.discardCheckpointIfNoop(); publish(); _committed.tryEmit(Unit); if (playerReady) seekPlayerSafely(player.currentPosition) }

    // ───────── ключевые кадры ─────────
    /** Ключ под курсором (в локальном времени клипа) или null. */
    fun keyframeAt(clipId: Long, localMs: Long) = model.keyframeAt(clipId, localMs)

    /** Ромбик: ключа нет — добавить с текущим значением; курсор на ключе — удалить его. Возвращает true, если ключ добавлен. */
    fun toggleKeyframe(clipId: Long, localMs: Long): Boolean {
        pause(); model.checkpoint()
        val added = model.toggleKeyframe(clipId, localMs)
        model.discardCheckpointIfNoop()
        commitTransformEdit()
        return added
    }

    /**
     * Конец правки положения/ключей кадра. Матрица эффекта читает [state] на каждом кадре, поэтому композицию
     * НЕ пересобираем: достаточно перерисовать текущий кадр (seek в ту же позицию) — результат виден сразу, а не через секунды.
     * Сигнал appliedVersion подаётся после перерисовки, по нему интерфейс снимает «живую» подмену.
     */
    fun commitTransformEdit() {
        model.discardCheckpointIfNoop()
        publish(); _committed.tryEmit(Unit)
        if (playerReady) seekPlayerSafely(player.currentPosition)
        scope.launch { delay(FRAME_REFRESH_MS); _applied.value++ }
    }

    /** Автоключ во время жеста трансформации: фиксирует новые координаты на текущей миллисекунде (без пересборки). */
    fun setKeyframeTransform(clipId: Long, localMs: Long, t: ClipTransform) { if (model.setKeyframe(clipId, localMs, t)) publish() }

    fun split(id: Long): Boolean {
        pause(); model.checkpoint()
        if (model.split(id, _playhead.value) < 0) { model.discardCheckpointIfNoop(); return false }
        afterStructuralEdit(); return true
    }

    fun remove(id: Long) { pause(); model.checkpoint(); model.remove(id); afterStructuralEdit() }

    fun addMedia(items: List<PickedMedia>) {
        if (items.isEmpty()) return
        pause(); model.checkpoint()
        val firstStart = model.totalMs
        items.forEach { m ->
            val video = m.type == MediaType.VIDEO
            model.addClip(0, m.type, m.uri, if (video) m.durationMs else 0, if (video) m.durationMs else IMAGE_DEFAULT_MS)
        }
        afterStructuralEdit()
        seekTo(firstStart)
    }

    /** Музыка из файлов устройства: блок на аудиодорожке (row 1), старт — от курсора. */
    fun addAudio(uri: String, srcDurMs: Long) {
        if (srcDurMs <= 0) { _events.tryEmit("Не удалось прочитать аудиофайл"); return }
        pause(); model.checkpoint()
        val start = _playhead.value.coerceIn(0L, model.totalMs)
        val id = model.addClip(AUDIO_ROW, MediaType.AUDIO, uri, srcDurMs, srcDurMs)
        // блок ставится от курсора, а не в конец дорожки
        if (model.state().clips.firstOrNull { it.id == id }?.startMs != start) model.moveClip(id, start, 0, -1)
        afterStructuralEdit()
    }

    fun undo() { if (model.undo()) afterStructuralEdit() }
    fun redo() { if (model.redo()) afterStructuralEdit() }

    // ───────── переходы ─────────
    fun maxTransitionMs(leftId: Long) = model.maxTransitionMs(leftId)
    fun transitionAfter(leftId: Long) = _state.value.transitions.firstOrNull { it.leftId == leftId }

    /** shaderId == null — снять переход. */
    fun setTransition(leftId: Long, shaderId: String?, durationMs: Long): Boolean {
        pause(); model.checkpoint()
        if (!model.setTransition(leftId, shaderId, durationMs)) { model.discardCheckpointIfNoop(); return false }
        model.discardCheckpointIfNoop()
        afterStructuralEdit()
        return true
    }

    /** Проигрывает окно перехода (с запасом 0.4 с до и после) — «моментальный предпросмотр». */
    fun previewTransition(leftId: Long) {
        val t = transitionAfter(leftId) ?: return
        val junction = _state.value.clips.firstOrNull { it.id == t.rightId }?.startMs ?: return
        seekTo((junction - PREVIEW_PAD_MS).coerceAtLeast(0))
        stopAtMs = junction + t.durationMs + PREVIEW_PAD_MS
        if (playerReady) resumePlayer()
    }

    // ───────── воспроизведение ─────────
    fun play() {
        if (_state.value.clips.none { it.row == 0 }) return
        stopAtMs = -1
        scrubEnd()                                                // доставить последнюю позицию перемотки
        if (_playhead.value >= _state.value.totalMs - 50) seekTo(0)
        if (!playerReady) { playWhenReady = true; return }        // композиция ещё готовится: стартуем, как только будет READY
        if (scrubbedSincePlay) {
            // после частой перемотки декодер/поверхность могут залипнуть (звук идёт, кадр стоит): переподключаем
            // поверхность и перемещаем плеер в ту же позицию (flush декодера), и только потом запускаем воспроизведение
            scrubbedSincePlay = false
            refreshVideoSurface()
            scope.launch {
                delay(SURFACE_REBIND_MS)
                if (playerReady) { seekPlayerSafely(player.currentPosition); resumePlayer() } else playWhenReady = true
            }
            return
        }
        resumePlayer()
    }

    /** Просит интерфейс заново привязать поверхность видео к плееру. */
    fun refreshVideoSurface() { _surfaceReset.value++ }

    /** Play в любом состоянии: из IDLE — prepare, из ENDED — с начала, из BUFFERING — стартует сам по готовности. */
    private fun resumePlayer() {
        try {
            when (player.playbackState) {
                Player.STATE_IDLE -> { player.prepare(); player.play() }
                Player.STATE_ENDED -> { player.seekTo(0); player.play() }
                else -> player.play()
            }
        } catch (e: Exception) { Log.w(TAG, "play не удался", e); _events.tryEmit("Не удалось запустить воспроизведение") }
    }

    fun pause() { playWhenReady = false; runCatching { player.pause() }.onFailure { Log.w(TAG, "pause до подготовки плеера", it) } }
    fun toggle() { if (player.isPlaying) pause() else play() }

    /**
     * Перемотка. CompositionPlayer создаёт внутреннее состояние только после prepare(); перемотка раньше
     * этого момента падает в handleSeekInternal (NPE). Поэтому: позиция всегда зажимается в [0, длина],
     * а пока плеер не готов — запоминается в [pendingSeekMs] и выполняется на первом STATE_READY.
     */
    fun seekTo(ms: Long) {
        cancelScrub()
        val total = _state.value.totalMs
        val v = if (total > 0) ms.coerceIn(0L, total) else 0L
        _playhead.value = v
        if (total <= 0) return                                   // композиции ещё нет — перематывать нечего
        if (!playerReady || player.playbackState == Player.STATE_IDLE) { pendingSeekMs = v; return }
        seekPlayerSafely(v)
    }

    // ───────── живая перемотка пальцем ─────────
    private var scrubTarget = -1L
    @Volatile private var seekInFlight = false
    private var lastSeekAt = 0L
    private var scrubbedSincePlay = false
    private var bufferingJob: Job? = null
    private val _surfaceReset = MutableStateFlow(0)
    /** Счётчик «переподключить поверхность видео»: интерфейс пересоздаёт привязку PlayerView к плееру. */
    val surfaceReset: StateFlow<Int> = _surfaceReset.asStateFlow()
    private var scrubJob: Job? = null

    /**
     * Перемотка при ведении пальцем по таймлайну (десятки вызовов в секунду). Курсор в интерфейсе двигается сразу,
     * а в плеер уходит не больше одного seek за [SCRUB_MS] (≈30 к/с) и всегда с самой свежей позицией:
     * промежуточные цели отбрасываются, очередь не копится, главный поток не забивается командами.
     */
    fun scrubTo(ms: Long) {
        val total = _state.value.totalMs
        if (total <= 0) return
        val v = ms.coerceIn(0L, total)
        _playhead.value = v
        scrubbedSincePlay = true
        scrubTarget = v
        if (scrubJob?.isActive == true) return
        scrubJob = scope.launch {
            while (isActive && scrubTarget >= 0) {
                // не заваливаем декодер: пока предыдущий seek не обработан, новый не отправляем (но не дольше SEEK_INFLIGHT_MAX_MS)
                if (seekInFlight && SystemClock.uptimeMillis() - lastSeekAt < SEEK_INFLIGHT_MAX_MS) { delay(12); continue }
                val t = scrubTarget; scrubTarget = -1
                if (!playerReady || player.playbackState == Player.STATE_IDLE) { pendingSeekMs = t } else seekPlayerSafely(t)
                delay(SCRUB_MS)
            }
        }
    }

    /** Короткое воспроизведение фрагмента (предпросмотр анимации текста): с [fromMs], остановка на [toMs]. */
    fun playRange(fromMs: Long, toMs: Long) {
        pause(); seekTo(fromMs)
        stopAtMs = toMs
        if (playerReady) resumePlayer() else playWhenReady = true
    }

    /** Сбрасывает недоставленные цели перемотки (резкий свайп, play, явный seek). */
    private fun cancelScrub() { scrubTarget = -1; scrubJob?.cancel(); scrubJob = null }

    /** Палец отпущен: доставить последнюю позицию немедленно, не дожидаясь следующего тика. */
    fun scrubEnd() {
        val t = scrubTarget
        cancelScrub()
        if (t >= 0) { if (playerReady) seekPlayerSafely(t) else pendingSeekMs = t }
    }

    private fun seekPlayerSafely(v: Long) {
        try { player.seekTo(v); pendingSeekMs = -1; seekInFlight = true; lastSeekAt = SystemClock.uptimeMillis() } catch (e: Exception) {
            Log.w(TAG, "seekTo отложен: плеер не готов", e)
            pendingSeekMs = v
        }
    }

    fun setMuted(m: Boolean) { muted = m; player.volume = if (m) 0f else 1f }
    val isMuted get() = muted

    fun release() {
        rebuildRequests.close()
        player.removeListener(this)
        player.release()
    }

    // ───────── внутреннее ─────────
    private fun publish() {
        _state.value = model.state()
        _canUndo.value = model.canUndo
        _canRedo.value = model.canRedo
        _playhead.value = _playhead.value.coerceIn(0, _state.value.totalMs)
    }

    private fun afterStructuralEdit() {
        publish(); requestRebuild(); _committed.tryEmit(Unit)
    }

    private fun requestRebuild() { rebuildRequests.trySend(Unit) }

    /** Только главный поток. Никогда не бросает: повреждённый файл даёт сообщение, а не падение процесса. */
    private fun applyComposition(composition: Composition?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Плеер можно трогать только с главного потока" }
        try {
            playerReady = false                                  // пока новая композиция готовится — seek откладывается
            if (composition == null) { player.stop(); return }
            pendingSeekMs = _playhead.value
            player.setComposition(composition, _playhead.value)
            player.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "setComposition не удался", e)
            _events.tryEmit("Не удалось открыть файл: возможно, он повреждён или не поддерживается")
            runCatching { player.stop() }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) seekInFlight = false
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        _buffering.value = playbackState == Player.STATE_BUFFERING
        if (playbackState == Player.STATE_READY) seekInFlight = false
        // сторожок: если буферизация затянулась (декодер завис), перемещаем плеер в ту же позицию и переподключаем поверхность
        bufferingJob?.cancel()
        if (playbackState == Player.STATE_BUFFERING) bufferingJob = scope.launch {
            delay(BUFFERING_WATCHDOG_MS)
            if (player.playbackState == Player.STATE_BUFFERING) {
                Log.w(TAG, "буферизация затянулась — сброс декодера")
                refreshVideoSurface(); delay(SURFACE_REBIND_MS); seekPlayerSafely(player.currentPosition)
            }
        }
        when (playbackState) {
            Player.STATE_IDLE -> playerReady = false
            Player.STATE_READY, Player.STATE_ENDED -> {
                if (!playerReady) _applied.value++
                playerReady = true
                val p = pendingSeekMs
                if (p >= 0) {                                   // отложенная перемотка (скраб до готовности)
                    val total = _state.value.totalMs
                    if (total > 0 && p != player.currentPosition) seekPlayerSafely(p.coerceIn(0L, total)) else pendingSeekMs = -1
                }
                if (playbackState == Player.STATE_ENDED) { _playing.value = false; _playhead.value = _state.value.totalMs }
                else if (playWhenReady) { playWhenReady = false; runCatching { player.play() } }   // отложенный Play
            }
            else -> Unit
        }
    }

    /**
     * Сбои MediaCodec/GL: первый раз пересобираем композицию без шейдерных эффектов (аварийный режим),
     * повторный — сообщаем пользователю.
     */
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "ошибка плеера: ${error.errorCodeName}", error)
        val codecRelated = error.errorCode in CODEC_ERRORS
        if (codecRelated && !safeMode) {
            safeMode = true
            _events.tryEmit("Проблема с декодером или GPU — переходы временно отключены")
            requestRebuild()
        } else {
            _events.tryEmit("Не удалось воспроизвести: ${error.errorCodeName}")
        }
    }

    private companion object {
        const val TAG = "BaseTimeline"
        const val POLL_MS = 33L
        const val PREVIEW_PAD_MS = 400L
        const val FRAME_REFRESH_MS = 120L
        const val SCRUB_MS = 33L
        const val SEEK_INFLIGHT_MAX_MS = 250L
        const val SURFACE_REBIND_MS = 70L
        const val BUFFERING_WATCHDOG_MS = 4000L
        const val AUDIO_ROW = 1
        val CODEC_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
    }
}
