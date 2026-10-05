package com.base.editor.captions.asr

import android.content.Context
import android.util.Log
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionLanguage
import com.base.editor.captions.CaptionSegmenter
import com.base.editor.captions.WordTimestamp
import com.base.editor.core.MediaType
import com.base.editor.domain.TimelineState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class NoSpeechException : Exception("Речь не найдена")

/**
 * Автосубтитры: звук каждого видеоклипа (с учётом обрезки) → участки с голосом → окна до 12 с →
 * распознавание на устройстве → слова с таймингами → карточки.
 * Всё выполняется на фоновых диспетчерах; модель при необходимости подготавливается автоматически.
 */
class AutoCaptionGenerator(
    private val context: Context,
    private val store: SherpaModelStore = SherpaModelStore(context),
    private val extractor: AudioPcmExtractor = AudioPcmExtractor(context),
    private val segmenter: CaptionSegmenter = CaptionSegmenter(),
    private val default: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun generate(timeline: TimelineState, language: CaptionLanguage = CaptionLanguage.AUTO): List<CaptionItem> {
        val clips = timeline.clips.filter { it.row == 0 && it.type == MediaType.VIDEO }.sortedBy { it.startMs }
        if (clips.isEmpty()) throw NoSpeechException()

        val files = store.ensure()
        return withContext(default) {
            val words = mutableListOf<WordTimestamp>()
            SherpaWhisper(files, language.code).use { recognizer ->
                for (clip in clips) {
                    coroutineContext.ensureActive()
                    val pcm = readPcm(clip.uri, clip.srcInMs, clip.srcInMs + clip.lengthMs) ?: continue
                    val energies = SpeechActivity.frameEnergies(pcm)
                    val voiced = SpeechActivity.detect(energies)
                    for (w in SpeechActivity.windows(voiced, energies, clip.lengthMs)) {
                        coroutineContext.ensureActive()
                        val r = recognizer.transcribe(SpeechActivity.toFloats(pcm, w.startMs, w.endMs))
                        if (clean(r.text).isEmpty()) continue
                        // тайминги слов — из токенов модели (миллисекунды от начала окна), без подгонки по энергии звука
                        val timed = WordTimings.build(r.text, r.tokens, r.timestampsSec, w.endMs - w.startMs)
                        dedupe(timed).forEach {
                            val s = clip.startMs + w.startMs + it.startMs
                            val e = minOf(clip.startMs + w.startMs + it.endMs, clip.startMs + clip.lengthMs)
                            if (e > s) words += it.copy(startMs = s, endMs = e)
                        }
                    }
                }
            }
            if (words.isEmpty()) throw NoSpeechException()
            segmenter.segment(words)
        }
    }

    /** Убирает зацикленные повторы («галлюцинации» на музыке): больше двух одинаковых слов подряд. */
    private fun dedupe(ws: List<WordTimestamp>): List<WordTimestamp> {
        val out = ArrayList<WordTimestamp>(); var run = 0
        for (w in ws) {
            if (out.isNotEmpty() && out.last().word.equals(w.word, ignoreCase = true)) { if (++run >= 3) continue } else run = 0
            out += w
        }
        return out
    }

    private suspend fun readPcm(uri: String, fromMs: Long, toMs: Long): ShortArray? {
        var buf = ShortArray(16_000 * 60); var n = 0
        val ok = extractor.stream(uri, fromMs, toMs) { chunk, len ->
            if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
            System.arraycopy(chunk, 0, buf, n, len); n += len
        }
        return if (ok && n > 0) buf.copyOf(n) else null
    }

    /** Отбрасывает «галлюцинации» на тишине/музыке: пустые, из одних знаков, с зацикленными словами. */
    private fun clean(raw: String): String {
        val t = raw.replace(Regex("[♪♫\\[\\]()*]"), " ").trim()
        if (t.none { it.isLetterOrDigit() }) return ""
        val out = mutableListOf<String>()
        var run = 0
        for (w in t.split(Regex("\\s+"))) {
            if (out.isNotEmpty() && out.last().equals(w, ignoreCase = true)) { if (++run >= 3) continue } else run = 0
            out += w
        }
        Log.d(TAG, "распознано: ${out.size} слов")
        return out.joinToString(" ")
    }

    private companion object { const val TAG = "BaseCaptions" }
}
