package com.base.editor.captions.asr

import com.base.editor.captions.WordTimestamp

/**
 * Слова с таймингами из токенов Whisper (sherpa-onnx, enableTokenTimestamps). Время — мс от начала окна.
 * Токен с пробелом в начале открывает новое слово; знаки препинания прилипают к предыдущему.
 * Если токены непригодны (размеры не совпали, обрезанные UTF-8-последовательности) — слова равномерно
 * по длине распределяются по окну: тайминги приблизительные, но подпись не теряется.
 */
object WordTimings {
    private const val MAX_WORD_MS = 1200L
    private const val MIN_WORD_MS = 60L

    fun build(text: String, tokens: Array<String>, timestampsSec: FloatArray, windowMs: Long): List<WordTimestamp> {
        if (windowMs <= 0) return emptyList()
        val byToken = if (tokens.isNotEmpty() && tokens.size == timestampsSec.size) fromTokens(tokens, timestampsSec, windowMs) else null
        return byToken ?: proportional(text, windowMs)
    }

    private fun fromTokens(tokens: Array<String>, ts: FloatArray, windowMs: Long): List<WordTimestamp>? {
        val words = ArrayList<StringBuilder>()
        val starts = ArrayList<Long>()
        var open = true                                        // следующий токен начинает слово
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.startsWith("<|")) continue                   // служебные токены Whisper
            if ('\uFFFD' in t) return null                     // разорванный многобайтный символ
            if (t.isBlank()) { open = true; continue }
            val ms = (ts[i] * 1000f).toLong().coerceIn(0L, windowMs)
            if (open || t.startsWith(" ") || words.isEmpty()) {
                words += StringBuilder(t.trim()); starts += maxOf(ms, starts.lastOrNull() ?: 0L)
            } else words.last().append(t)
            open = false
        }
        if (words.isEmpty()) return null
        return words.indices.map { i ->
            val start = starts[i]
            val next = if (i + 1 < words.size) starts[i + 1] else windowMs
            val end = minOf(next, start + MAX_WORD_MS, windowMs).let { if (it < start + MIN_WORD_MS) minOf(start + MIN_WORD_MS, windowMs) else it }
            WordTimestamp(words[i].toString(), start, maxOf(end, start))
        }
    }

    private fun proportional(text: String, windowMs: Long): List<WordTimestamp> {
        val ws = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (ws.isEmpty()) return emptyList()
        val total = ws.sumOf { it.length }.toDouble()
        var acc = 0.0
        return ws.map { w ->
            val s = (acc / total * windowMs).toLong(); acc += w.length
            WordTimestamp(w, s, (acc / total * windowMs).toLong())
        }
    }
}
