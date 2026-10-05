package com.base.editor.captions

import android.content.Context
import android.util.Log
import com.base.editor.captions.asr.AutoCaptionGenerator
import com.base.editor.captions.asr.NoSpeechException
import com.base.editor.domain.TimelineState
import com.base.editor.media.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface GenerationState {
    data object Idle : GenerationState
    data object Generating : GenerationState
    data class Failed(val message: String) : GenerationState
}

/**
 * Владелец субтитров проекта: карточки, стиль, генерация, ручная правка.
 * Состояние меняется на главном потоке (дёшево), распознавание — в фоне.
 */
class CaptionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val generator: AutoCaptionGenerator = AutoCaptionGenerator(context, default = dispatchers.default),
) {
    private val _items = MutableStateFlow<List<CaptionItem>>(emptyList())
    val items: StateFlow<List<CaptionItem>> = _items.asStateFlow()
    private val _style = MutableStateFlow(CaptionPresets.default)
    val style: StateFlow<CaptionStyle> = _style.asStateFlow()
    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generation: StateFlow<GenerationState> = _generation.asStateFlow()
    private val _committed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Срабатывает после каждой правки — по нему проект сохраняется. */
    val committed: SharedFlow<Unit> = _committed.asSharedFlow()

    private var job: Job? = null

    // ───────── сохранение ─────────
    fun load(json: String?) {
        val (items, style) = CaptionJson.decode(json)
        _items.value = items.sortedBy { it.startMs }; _style.value = style
    }

    fun toJson(): String = CaptionJson.encode(_items.value, _style.value)
    val hasData get() = _items.value.isNotEmpty()

    // ───────── генерация ─────────
    fun generate(timeline: TimelineState, language: CaptionLanguage = CaptionLanguage.AUTO) {
        job?.cancel()
        job = scope.launch(dispatchers.default) {
            _generation.value = GenerationState.Generating
            try {
                _items.value = generator.generate(timeline, language)
                _generation.value = GenerationState.Idle
                _committed.tryEmit(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _generation.value = GenerationState.Idle; throw e
            } catch (e: NoSpeechException) {
                _generation.value = GenerationState.Failed("Речь в видео не найдена")
            } catch (e: java.io.IOException) {
                Log.e(TAG, "сеть/файлы", e)
                _generation.value = GenerationState.Failed("Не удалось создать субтитры. Проверьте подключение к интернету и повторите.")
            } catch (e: Throwable) {
                Log.e(TAG, "распознавание", e)
                _generation.value = GenerationState.Failed("Не удалось создать субтитры на этом устройстве")
            }
        }
    }

    fun cancelGeneration() { job?.cancel(); _generation.value = GenerationState.Idle }
    fun dismissError() { if (_generation.value is GenerationState.Failed) _generation.value = GenerationState.Idle }

    // ───────── правка ─────────
    fun updateText(id: String, text: String) = edit(id) { CaptionOps.retext(it, text) }
    fun updateTiming(id: String, startMs: Long, endMs: Long) = edit(id) { CaptionOps.retime(it, startMs, endMs) }
    /** Перетаскивание карточки по шкале (долгое нажатие на таймлайне). */
    fun moveTo(id: String, startMs: Long) = edit(id) { CaptionOps.shift(it, startMs) }

    fun delete(id: String) { _items.update { l -> l.filterNot { it.id == id } }; changed() }

    /** Добавляет пустую карточку на позиции курсора и возвращает её id. */
    fun addAt(timeMs: Long): String {
        val item = CaptionOps.manual(UUID.randomUUID().toString(), timeMs)
        _items.update { (it + item).sortedBy { c -> c.startMs } }; changed()
        return item.id
    }

    fun clearAll() { _items.value = emptyList(); changed() }

    private fun edit(id: String, f: (CaptionItem) -> CaptionItem) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it }.sortedBy { it.startMs } }; changed()
    }

    // ───────── стиль ─────────
    fun applyPreset(id: String) { CaptionPresets.byId(id)?.let { p -> _style.update { p.copy(positionY = it.positionY) }; changed() } }
    fun updateStyle(f: (CaptionStyle) -> CaptionStyle) { _style.update(f); changed() }

    fun captionAt(timeMs: Long) = CaptionOps.captionAt(_items.value, timeMs)

    private fun changed() { _committed.tryEmit(Unit) }

    private companion object { const val TAG = "BaseCaptions" }
}
