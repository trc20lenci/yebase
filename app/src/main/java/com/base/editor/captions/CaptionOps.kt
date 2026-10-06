package com.base.editor.captions

import kotlin.math.max
import kotlin.math.min

/** Чистая логика правки субтитров: без Android, легко тестируется. */
object CaptionOps {
    const val MIN_DURATION_MS = 200L

    /**
     * Индекс слова, звучащего в момент [timeMs]: последнее слово, начавшееся не позже этого момента
     * (в паузах между словами остаётся подсвеченным предыдущее). −1 — ещё ни одно слово не началось.
     */
    fun activeWordIndex(item: CaptionItem, timeMs: Long): Int {
        var idx = -1
        for (i in item.words.indices) if (item.words[i].startMs <= timeMs) idx = i else break
        return idx
    }

    /** Субтитр, который виден в момент [timeMs] (при перекрытии — начавшийся позже). */
    fun captionAt(items: List<CaptionItem>, timeMs: Long): CaptionItem? =
        items.lastOrNull { timeMs >= it.startMs && timeMs < it.endMs }

    /**
     * Меняет текст. Если число слов не изменилось — тайминги слов сохраняются,
     * иначе слова заново раскладываются по длительности пропорционально числу букв.
     */
    fun retext(item: CaptionItem, newText: String): CaptionItem {
        val tokens = newText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return item.copy(text = "", words = emptyList())
        val words = if (tokens.size == item.words.size) {
            item.words.mapIndexed { i, w -> w.copy(word = tokens[i]) }
        } else distribute(tokens, item.startMs, item.endMs)
        return item.copy(text = tokens.joinToString(" "), words = words)
    }

    /** Новые границы карточки; тайминги слов линейно переносятся на новый интервал. */
    fun retime(item: CaptionItem, newStartMs: Long, newEndMs: Long): CaptionItem {
        val start = max(0, newStartMs)
        val end = max(start + MIN_DURATION_MS, newEndMs)
        val oldLen = max(1L, item.endMs - item.startMs).toDouble()
        val k = (end - start) / oldLen
        fun map(t: Long) = (start + (t - item.startMs) * k).toLong().coerceIn(start, end)
        return item.copy(startMs = start, endMs = end, words = item.words.map { it.copy(startMs = map(it.startMs), endMs = map(it.endMs)) })
    }

    /** Подрезка края карточки (ручки на таймлайне): окно меняется, тайминги слов остаются прежними. */
    fun trim(item: CaptionItem, newStartMs: Long, newEndMs: Long): CaptionItem {
        val start = max(0, newStartMs)
        val end = max(start + MIN_DURATION_MS, newEndMs)
        return item.copy(startMs = start, endMs = end)
    }

    /** Делит карточку по времени на две независимые: слово уходит в ту часть, куда попадает его середина. */
    fun split(item: CaptionItem, atMs: Long): Pair<CaptionItem, CaptionItem>? {
        if (atMs <= item.startMs + MIN_DURATION_MS || atMs >= item.endMs - MIN_DURATION_MS) return null
        val left = item.words.filter { (it.startMs + it.endMs) / 2 < atMs }
        val right = item.words.filter { (it.startMs + it.endMs) / 2 >= atMs }
        if (left.isEmpty() || right.isEmpty()) return null
        fun make(id: String, s: Long, e: Long, ws: List<WordTimestamp>) =
            CaptionItem(id, s, e, ws.joinToString(" ") { it.word }, ws.map { it.copy(startMs = it.startMs.coerceIn(s, e), endMs = it.endMs.coerceIn(s, e)) })
        return make(item.id, item.startMs, atMs, left) to make(java.util.UUID.randomUUID().toString(), atMs, item.endMs, right)
    }

    /** Сдвиг карточки целиком (длительность и пословные тайминги сохраняются). */
    fun shift(item: CaptionItem, newStartMs: Long): CaptionItem {
        val d = max(0, newStartMs) - item.startMs
        if (d == 0L) return item
        return item.copy(startMs = item.startMs + d, endMs = item.endMs + d,
            words = item.words.map { it.copy(startMs = it.startMs + d, endMs = it.endMs + d) })
    }

    /** Равномерная раскладка слов (вес — длина слова) по интервалу. */
    fun distribute(tokens: List<String>, startMs: Long, endMs: Long): List<WordTimestamp> {
        val total = max(1, tokens.sumOf { it.length + 1 }).toDouble()
        val span = max(tokens.size.toLong(), endMs - startMs).toDouble()
        var cursor = startMs.toDouble()
        return tokens.map { t ->
            val len = span * (t.length + 1) / total
            val w = WordTimestamp(t, cursor.toLong(), min(endMs.toDouble(), cursor + len).toLong())
            cursor += len; w
        }
    }

    /** Ручной субтитр: 2 секунды на указанной позиции. */
    fun manual(id: String, atMs: Long, text: String = "Текст"): CaptionItem =
        CaptionItem(id, atMs, atMs + 2000, text, distribute(listOf(text), atMs, atMs + 2000))
}
