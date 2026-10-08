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
import kotlin.coroutines.coroutineContext

class NoSpeechException : Exception("Речь не найдена")

/**
 * Автосубтитры: звук каждого видеоклипа (с учётом обрезки) → участки с голосом → окна до 12 с →
 * распознавание на устройстве → слова с таймингами → карточки.
 * Всё выполняется на фоновых диспетчерах; модель при необходимости подготавливается автоматически.
 */
class AutoCaptionGenerator(
    private val context: Context,
    private val store: WhisperModelStore = WhisperModelStore(context),
    private val extractor: AudioPcmExtractor = AudioPcmExtractor(context),
    private val segmenter: CaptionSegmenter = CaptionSegmenter(),
    private val default: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun generate(timeline: TimelineState): List<CaptionItem> {
        val clips = timeline.clips.filter { it.row == 0 && it.type == MediaType.VIDEO }.sortedBy { it.startMs }
        if (clips.isEmpty()) throw NoSpeechException()

        val modelFile = store.ensure()
        return withContext(default) {
            val frontend = context.assets.open("asr/filters_vocab_multilingual.bin").use(WhisperFrontend::load)
            val words = mutableListOf<WordTimestamp>()
            WhisperTranscriber(modelFile, frontend).use { recognizer ->
                for (clip in clips) {
                    coroutineContext.ensureActive()
                    val pcm = readPcm(clip.uri, clip.srcInMs, clip.srcInMs + clip.lengthMs) ?: continue
                    val energies = SpeechActivity.frameEnergies(pcm)
                    val voiced = SpeechActivity.detect(energies)
                    for (w in SpeechActivity.windows(voiced, energies, clip.lengthMs)) {
                        coroutineContext.ensureActive()
                        val text = clean(recognizer.transcribe(SpeechActivity.toFloats(pcm, w.startMs, w.endMs)).text)
                        if (text.isEmpty()) continue
                        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        SpeechActivity.assignWordTimes(tokens, w.voiced, Span(w.startMs, w.endMs), energies).forEach {
                            words += it.copy(startMs = clip.startMs + it.startMs, endMs = clip.startMs + it.endMs)
                        }
                    }
                }
            }
            if (words.isEmpty()) throw NoSpeechException()
            segmenter.segment(words)
        }
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
