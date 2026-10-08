package com.base.editor.captions.asr

import android.content.Context
import android.util.Log
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionSegmenter
import com.base.editor.captions.WordTimestamp
import com.base.editor.core.MediaType
import com.base.editor.domain.TimelineState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.coroutines.coroutineContext

sealed interface GenerationProgress {
    data class DownloadingModel(val fraction: Float) : GenerationProgress
    data class Recognizing(val fraction: Float) : GenerationProgress
}

class NoSpeechException : Exception("Речь не найдена")

/**
 * Автосубтитры: для каждого видеоклипа основной дорожки декодируем звук (с учётом обрезки In/Out),
 * распознаём речь офлайн с пословными таймингами и переводим время клипа во время проекта.
 * Работает целиком на фоновых диспетчерах.
 */
class AutoCaptionGenerator(
    context: Context,
    private val models: SpeechModelStore = SpeechModelStore(context),
    private val extractor: AudioPcmExtractor = AudioPcmExtractor(context),
    private val segmenter: CaptionSegmenter = CaptionSegmenter(),
    private val default: CoroutineDispatcher = Dispatchers.Default,
) {
    fun isModelReady(lang: SpeechLanguage) = models.isReady(lang)

    suspend fun generate(
        timeline: TimelineState,
        lang: SpeechLanguage,
        onProgress: (GenerationProgress) -> Unit,
    ): List<CaptionItem> {
        val clips = timeline.clips.filter { it.row == 0 && it.type == MediaType.VIDEO }.sortedBy { it.startMs }
        if (clips.isEmpty()) throw NoSpeechException()

        val modelDir = models.ensure(lang) { onProgress(GenerationProgress.DownloadingModel(it)) }
        return withContext(default) {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val words = mutableListOf<WordTimestamp>()
            Model(modelDir.absolutePath).use { model ->
                val totalMs = clips.sumOf { it.lengthMs }.coerceAtLeast(1)
                var doneMs = 0L
                for (clip in clips) {
                    coroutineContext.ensureActive()
                    Recognizer(model, 16_000f).use { rec ->
                        rec.setWords(true)
                        val clipWords = mutableListOf<WordTimestamp>()
                        var fedSamples = 0L
                        val ok = extractor.stream(clip.uri, clip.srcInMs, clip.srcInMs + clip.lengthMs) { pcm, n ->
                            if (rec.acceptWaveForm(pcm, n)) collect(rec.result, clip.startMs, clip.lengthMs, clipWords)
                            fedSamples += n
                            onProgress(GenerationProgress.Recognizing(((doneMs + fedSamples / 16L).toFloat() / totalMs).coerceIn(0f, 1f)))
                        }
                        if (ok) collect(rec.finalResult, clip.startMs, clip.lengthMs, clipWords)
                        words += clipWords
                    }
                    doneMs += clip.lengthMs
                }
            }
            if (words.isEmpty()) throw NoSpeechException()
            segmenter.segment(words)
        }
    }

    /** Разбирает JSON результата: время слов — секунды от начала участка → мс на шкале проекта. */
    private fun collect(json: String, clipStartMs: Long, clipLenMs: Long, into: MutableList<WordTimestamp>) {
        runCatching {
            val arr = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = (o.getDouble("start") * 1000).toLong().coerceIn(0, clipLenMs)
                val e = (o.getDouble("end") * 1000).toLong().coerceIn(s, clipLenMs)
                into += WordTimestamp(o.getString("word"), clipStartMs + s, clipStartMs + e)
            }
        }.onFailure { Log.w("BaseCaptions", "не удалось разобрать результат распознавания", it) }
    }
}
