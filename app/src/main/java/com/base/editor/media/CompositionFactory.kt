package com.base.editor.media

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import android.graphics.Matrix
import com.base.editor.core.ClipTransform
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import com.base.editor.core.TransitionCatalog
import com.base.editor.domain.TimelineState
import com.base.editor.media.gl.TailCaptureEffect
import com.base.editor.media.gl.TransitionBridge
import com.base.editor.media.gl.TransitionEffect
import kotlin.math.abs
import kotlin.math.max

/** Параметры сборки одной композиции. */
data class CompositionRequest(
    val state: TimelineState,
    val canvas: Size,
    /** Отключить звук (экспорт); в превью громкость регулируется у плеера. */
    val removeAudio: Boolean = false,
    /** true — без шейдерных эффектов (аварийный режим после сбоя декодера/GL). */
    val safeMode: Boolean = false,
    val onTransitionFallback: (String) -> Unit = {},
    /** Субтитры «вжигаются» только в экспорт; в превью их рисует Compose-оверлей. */
    val captions: CaptionTrack? = null,
    /** Текстовые слои — тоже только в экспорт. */
    val texts: List<com.base.editor.text.TextClip> = emptyList(),
    /**
     * Превью: живой источник трансформации (clipId, локальное время клипа) → положение кадра. Матрица читает его на каждом
     * кадре, поэтому правка положения/ключей не требует пересборки композиции. null — экспорт: берётся снимок [state].
     */
    val liveTransforms: ((Long, Long) -> com.base.editor.core.ClipTransform)? = null,
    /** Экспорт: целевой FPS (24/30/60). null — превью (30). */
    val fps: Int? = null,
    /** Превью: живые параметры хромакея/удаления фона по clipId (слайдеры работают без пересборки). null — снимок [state]. */
    val liveFx: ((Long) -> com.base.editor.media.gl.FxParams)? = null,
)

/**
 * Единственное место, где модель таймлайна превращается в Composition Media3.
 * Её используют и плеер превью, и экспорт — картинка совпадает.
 *
 * Основная дорожка → одна последовательность EditedMediaItemSequence:
 *  • обрезка клипа — ClippingConfiguration (In/Out в исходнике);
 *  • зазоры между клипами — addGap (чёрный кадр + тишина);
 *  • каждый клип приводится к общему холсту (Presentation, вписывание с полосами);
 *  • на стыках — TailCaptureEffect у уходящего и TransitionEffect у входящего клипа.
 *
 * Аудиодорожка (музыка) → вторая, ПАРАЛЛЕЛЬНАЯ последовательность (только TRACK_TYPE_AUDIO):
 * Media3 микширует её со звуком основной дорожки и в превью (CompositionPlayer), и в экспорте.
 *
 * Ключевые кадры: если у клипа есть ключи, статическая трансформация заменяется на
 * MatrixTransformation с линейной интерполяцией по времени кадра (KeyframeTrack.at).
 */
@UnstableApi
class CompositionFactory(private val context: Context, private val catalog: TransitionCatalog) {

    /** null — на основной дорожке нет клипов. Безопасно вызывать с фонового потока. */
    fun build(req: CompositionRequest): Composition? {
        val main = req.state.clips.filter { it.row == 0 }.sortedBy { it.startMs }
        if (main.isEmpty()) return null

        val bridges = HashMap<Long, TransitionBridge>()          // ключ — id левого клипа стыка
        val inbound = req.state.transitions.associateBy { it.rightId }
        val outbound = req.state.transitions.associateBy { it.leftId }

        val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
        var cursorMs = 0L
        for (clip in main) {
            if (clip.startMs > cursorMs) seq.addGap((clip.startMs - cursorMs) * 1000)

            val effects = mutableListOf<Effect>()
            // хромакей / удаление фона — до кадрирования: маска посчитана по полному исходному кадру
            if (!req.safeMode && (req.state.chromas.containsKey(clip.id) || req.state.bgs.containsKey(clip.id))) {
                val id = clip.id
                val maskReader = if (req.state.bgs.containsKey(id)) com.base.editor.media.MaskStore(context).reader(clip.uri) else null
                val provider = req.liveFx ?: { _ -> com.base.editor.media.gl.FxParams(req.state.chromas[id], req.state.bgs[id]) }
                effects += com.base.editor.media.gl.ClipFxEffect({ provider(id) }, maskReader, clip.startMs, clip.srcInMs, clip.speed)
            }
            // кадрирование — первым, до вписывания в холст; Crop принимает границы в NDC (−1..1, Y вверх)
            req.state.crops[clip.id]?.takeIf { !it.isFull }?.let { c ->
                effects += Crop(-1f + 2f * c.left, -1f + 2f * c.right, 1f - 2f * c.bottom, 1f - 2f * c.top)
            }
            effects += (Presentation.createForWidthAndHeight(req.canvas.width, req.canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            val live = req.liveTransforms
            val keys = req.state.keyframes[clip.id].orEmpty()
            if (live != null) {
                val startMs = clip.startMs; val id = clip.id
                effects += MatrixTransformation { us -> transformMatrix(live(id, (us / 1000 - startMs).coerceAtLeast(0L)), req.canvas) }
            }
            else if (keys.isNotEmpty()) effects += keyframeTransformEffect(keys, clip.startMs, req.canvas)
            else req.state.transforms[clip.id]?.takeIf { !it.isIdentity }?.let { effects += clipTransformEffect(it, req.canvas) }
            if (!req.safeMode) {
                inbound[clip.id]?.let { t ->
                    catalog.spec(t.shaderId)?.let { spec ->
                        effects += TransitionEffect(spec, bridges.getOrPut(t.leftId) { TransitionBridge() }, t.durationMs * 1000, req.onTransitionFallback)
                    } ?: Log.w(TAG, "переход ${t.shaderId} не найден в каталоге")
                }
                // захват хвоста — после перехода, чтобы в мост попал финальный кадр клипа
                outbound[clip.id]?.let { effects += TailCaptureEffect(bridges.getOrPut(clip.id) { TransitionBridge() }) }
            }
            seq.addItem(editedItem(clip, effects, req.removeAudio, req.fps ?: FRAME_RATE))
            cursorMs = clip.endMs
        }

        // музыка: параллельная последовательность только со звуком (видео у неё снято)
        val music = req.state.clips.filter { it.row != 0 && it.type == MediaType.AUDIO }.sortedBy { it.startMs }
        val builder = if (music.isEmpty()) Composition.Builder(seq.build()) else {
            val audioSeq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            var cur = 0L
            for (c in music) {
                if (c.startMs > cur) audioSeq.addGap((c.startMs - cur) * 1000)
                audioSeq.addItem(editedItem(c, emptyList(), removeAudio = false, fps = req.fps ?: FRAME_RATE))
                cur = c.endMs
            }
            Composition.Builder(seq.build(), audioSeq.build())
        }
        // эффект уровня композиции: время кадров — время всего проекта, слои совпадают с таймлайном
        val overlays = buildList<androidx.media3.effect.TextureOverlay> {
            req.captions?.takeIf { it.items.isNotEmpty() }?.let { add(CaptionBitmapOverlay(context, it, req.canvas)) }
            val plain = req.texts.filter { it.pagTemplate == null }
            val animated = req.texts.filter { it.pagTemplate != null }
            if (plain.isNotEmpty()) add(TextBitmapOverlay(context, plain, req.canvas))
            if (animated.isNotEmpty()) add(PagBitmapOverlay(context, animated, req.canvas))
        }
        // эффекты уровня композиции: наложения + ограничение FPS (24/30; для 60 кадры не добавляются —
        // частота не может превысить частоту исходника, у фото она задаётся через setFrameRate)
        val compEffects = buildList<Effect> {
            if (overlays.isNotEmpty()) add(OverlayEffect(overlays))
            req.fps?.takeIf { it < 60 }?.let { add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(it.toFloat())) }
        }
        if (compEffects.isNotEmpty()) builder.setEffects(Effects(emptyList(), compEffects))
        return builder.build()
    }

    /**
     * Положение кадра на холсте. Поворот считается в «квадратных» единицах (поправка на пропорции кадра),
     * иначе картинка перекашивалась бы. Размер кадра не меняется: лишнее обрезается, пустое заливается чёрным.
     */
    private fun clipTransformEffect(t: ClipTransform, canvas: Size): Effect =
        MatrixTransformation { transformMatrix(t, canvas) }

    /**
     * Анимированная трансформация по ключевым кадрам: матрица вычисляется для каждого кадра.
     * presentationTimeUs — время внутри последовательности; оно совпадает со шкалой таймлайна
     * (основная дорожка собирается без ведущего зазора), поэтому локальное время клипа = t − clipStartMs.
     * Интерполяция — линейная, как в Lottie BaseKeyframeAnimation: V1 + (V2 - V1) * progress.
     */
    private fun keyframeTransformEffect(keys: List<com.base.editor.core.Keyframe>, clipStartMs: Long, canvas: Size): Effect =
        MatrixTransformation { presentationTimeUs ->
            val localMs = (presentationTimeUs / 1000 - clipStartMs).coerceAtLeast(0L)
            transformMatrix(com.base.editor.domain.KeyframeTrack.at(keys, localMs), canvas)
        }

    private fun transformMatrix(t: ClipTransform, canvas: Size): Matrix {
        val aspect = canvas.width.toFloat() / canvas.height
        return Matrix().apply {
            postScale(aspect, 1f)
            postScale(t.scale, t.scale)
            postRotate(-t.rotationDeg)                      // экранный поворот по часовой = против часовой в системе с осью Y вверх
            postScale(1f / aspect, 1f)
            postTranslate(t.x * 2f, -t.y * 2f)              // доля кадра → нормализованные координаты (ось Y вверх)
        }
    }

    private fun editedItem(c: Clip, videoEffects: List<Effect>, removeAudio: Boolean, fps: Int = FRAME_RATE): EditedMediaItem {
        val item = MediaItem.Builder().setUri(c.uri)
        val edited: EditedMediaItem.Builder
        if (c.type == MediaType.IMAGE) {
            item.setImageDurationMs(c.lengthMs)
            edited = EditedMediaItem.Builder(item.build()).setDurationUs(c.lengthMs * 1000).setFrameRate(fps)
        } else {
            // отрезок ИСХОДНИКА = длина на таймлайне × скорость (при 2× клип в 10 с занимает 20 с исходника)
            val srcSpan = c.srcSpanMs
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.srcInMs)
                    .setEndPositionMs(c.srcInMs + srcSpan)
                    .build(),
            )
            // CompositionPlayer не читает длительность из файла: ему нужна ПОЛНАЯ длительность исходника
            // заранее (из неё он сам вычитает обрезку). Без этого — IllegalStateException в setComposition.
            val sourceMs = max(c.srcDurMs, c.srcInMs + srcSpan)
            edited = EditedMediaItem.Builder(item.build()).setDurationUs(sourceMs * 1000).setFrameRate(fps)
            // скорость: SpeedProvider меняет темп и видео, и звука; звук растягивается с сохранением тона (Sonic)
            if (abs(c.speed - 1f) > 1e-3f) edited.setSpeed(ConstantSpeed(c.speed))
        }
        // громкость: 0 — тишина, 1 — оригинал, 2 — усиление ×2 (+6 дБ)
        val audioFx: List<androidx.media3.common.audio.AudioProcessor> =
            if (abs(c.volume - 1f) > 1e-3f) listOf(volumeProcessor(c.volume)) else emptyList()
        return edited.setEffects(Effects(audioFx, videoEffects))
            .setRemoveAudio(removeAudio)
            .setRemoveVideo(c.type == MediaType.AUDIO)      // музыка идёт отдельной звуковой последовательностью
            .build()
    }

    private fun volumeProcessor(v: Float) = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix(1, 1, floatArrayOf(v)))
        putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix(2, 2, floatArrayOf(v, 0f, 0f, v)))
    }

    private class ConstantSpeed(private val speed: Float) : androidx.media3.common.audio.SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
    }

    companion object {
        private const val TAG = "BaseComposition"
        const val FRAME_RATE = 30

        /** Холст по пропорциям первого клипа; short — длина короткой стороны (480/720/1080). */
        fun canvasFor(aspect: Float, short: Int): Size {
            fun even(v: Int) = (v and 1.inv()).coerceAtLeast(2)
            return if (aspect >= 1f) Size(even((short * aspect).toInt()), even(short)) else Size(even(short), even((short / aspect).toInt()))
        }
    }
}
