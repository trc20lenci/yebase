package com.base.editor.domain

import com.base.editor.core.Clip
import com.base.editor.core.ClipTransform
import com.base.editor.core.CropRect
import com.base.editor.core.Keyframe
import com.base.editor.core.MediaType
import com.base.editor.core.Transition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TimelineState(
    val clips: List<Clip>,
    val transitions: List<Transition>,
    val transforms: Map<Long, ClipTransform> = emptyMap(),
    val keyframes: Map<Long, List<Keyframe>> = emptyMap(),
    val crops: Map<Long, CropRect> = emptyMap(),
) {
    fun cropOf(clipId: Long) = crops[clipId] ?: CropRect()
    fun transformOf(clipId: Long) = transforms[clipId] ?: ClipTransform()
    fun keyframesOf(clipId: Long) = keyframes[clipId] ?: emptyList()
    /** Трансформация кадра клипа в момент [localMs] (от начала клипа): интерполяция по ключам или статика. */
    fun transformAt(clipId: Long, localMs: Long): ClipTransform {
        val keys = keyframes[clipId]
        return if (!keys.isNullOrEmpty()) KeyframeTrack.at(keys, localMs) else transformOf(clipId)
    }
    val totalMs: Long get() = clips.maxOfOrNull { it.endMs } ?: 0L
}

/**
 * Модель таймлайна: дорожки → клипы, переходы на стыках, undo/redo.
 * Чистый Kotlin без Android-зависимостей. Время — миллисекунды.
 * Не потокобезопасна: владелец (TimelineController) обращается к ней с одного потока.
 */
class TimelineModel {
    private var clips = mutableListOf<Clip>()
    private var transitions = mutableListOf<Transition>()
    private var transforms = mutableMapOf<Long, ClipTransform>()
    private var keyframes = mutableMapOf<Long, List<Keyframe>>()
    private var crops = mutableMapOf<Long, CropRect>()
    private var nextId = 1L
    private val undoStack = ArrayDeque<TimelineState>()
    private val redoStack = ArrayDeque<TimelineState>()
    /** Состояние на начало текущего жеста (его позиции — точка отсчёта для move/trim, пока палец не отпущен). */
    private var gestureBase: TimelineState? = null

    fun state() = TimelineState(clips.toList(), transitions.toList(), transforms.toMap(), keyframes.toMap(), crops.toMap())
    val totalMs get() = state().totalMs
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ───────── операции ─────────
    /** Добавляет клип в конец дорожки. */
    fun addClip(row: Int, type: MediaType, uri: String, srcDurMs: Long, lengthMs: Long): Long {
        val start = clips.filter { it.row == row }.maxOfOrNull { it.endMs } ?: 0L
        val c = Clip(nextId++, row, type, start, start + max(lengthMs, MIN_CLIP_MS), 0, srcDurMs, uri)
        clips += c
        return c.id
    }

    /**
     * newStartMs — «сырая» позиция от начала жеста. Магнит к краям/нулю/extraSnapMs (курсор, −1 = нет),
     * затем ближайший свободный промежуток нужной длины: клипы можно «перепрыгивать».
     */
    fun moveClip(id: Long, newStartMs: Long, snapThresholdMs: Long, extraSnapMs: Long): Boolean {
        val c = find(id) ?: return false
        if (c.row == 0) return moveMain(c, newStartMs)       // основная дорожка — магнитная: только перестановка
        val len = c.lengthMs
        val others = clips.filter { it.row == c.row && it.id != id }.sortedBy { it.startMs }

        var desired = newStartMs
        var best = snapThresholdMs + 1
        var snapped = newStartMs
        fun trySnap(target: Long) {
            val d = abs(desired - target)
            if (d <= snapThresholdMs && d < best) { best = d; snapped = target }
        }
        trySnap(0)
        if (extraSnapMs >= 0) { trySnap(extraSnapMs); trySnap(extraSnapMs - len) }
        others.forEach { trySnap(it.endMs); trySnap(it.startMs - len) }
        if (best <= snapThresholdMs) desired = snapped

        var result = -1L
        var dist = Long.MAX_VALUE
        var gapLo = 0L
        fun consider(lo: Long, hi: Long) {
            if (hi - lo < len) return
            val cand = desired.coerceIn(lo, hi - len)
            val d = abs(cand - desired)
            if (d < dist) { dist = d; result = cand }
        }
        others.forEach { consider(gapLo, it.startMs); gapLo = max(gapLo, it.endMs) }
        consider(gapLo, INF)
        if (result < 0) return false
        replace(c.copy(startMs = result, endMs = result + len))
        sanitize()
        return true
    }

    fun trimStart(id: Long, newStartMs: Long): Boolean {
        val c = find(id) ?: return false
        if (c.row == 0) return trimMainStart(c, newStartMs)
        val prevEnd = clips.filter { it.row == c.row && it.id != id && it.endMs <= c.startMs }.maxOfOrNull { it.endMs } ?: 0L
        val bounded = c.srcDurMs > 0
        var lo = prevEnd
        if (bounded) lo = max(lo, c.startMs - c.srcInMs)
        val hi = c.endMs - MIN_CLIP_MS
        val v = newStartMs.coerceIn(lo, max(lo, hi))
        replace(c.copy(startMs = v, srcInMs = if (bounded) c.srcInMs + (v - c.startMs) else c.srcInMs))
        sanitize()
        return true
    }

    fun trimEnd(id: Long, newEndMs: Long): Boolean {
        val c = find(id) ?: return false
        if (c.row == 0) return trimMainEnd(c, newEndMs)
        val nextStart = clips.filter { it.row == c.row && it.id != id && it.startMs >= c.endMs }.minOfOrNull { it.startMs } ?: INF
        var hi = nextStart
        if (c.srcDurMs > 0) hi = min(hi, c.startMs + (c.srcDurMs - c.srcInMs))
        val lo = c.startMs + MIN_CLIP_MS
        replace(c.copy(endMs = newEndMs.coerceIn(lo, max(lo, hi))))
        sanitize()
        return true
    }

    /** Возвращает id правой половины или −1. */
    fun split(id: Long, atMs: Long): Long {
        val c = find(id) ?: return -1
        if (atMs < c.startMs + MIN_CLIP_MS || atMs > c.endMs - MIN_CLIP_MS) return -1
        val right = c.copy(
            id = nextId++, startMs = atMs,
            srcInMs = if (c.srcDurMs > 0) c.srcInMs + (atMs - c.startMs) else c.srcInMs,
        )
        replace(c.copy(endMs = atMs))
        clips += right
        transforms[c.id]?.let { transforms[right.id] = it }
        crops[c.id]?.let { crops[right.id] = it }
        // ключи делятся между половинами: левые остаются, правые сдвигаются к началу правой половины
        keyframes[c.id]?.let { keys ->
            val cut = atMs - c.startMs
            val leftKeys = keys.filter { it.timeMs <= cut }
            val rightKeys = keys.filter { it.timeMs > cut }.map { it.copy(timeMs = it.timeMs - cut) }
            if (leftKeys.isEmpty()) keyframes.remove(c.id) else keyframes[c.id] = leftKeys
            if (rightKeys.isNotEmpty()) keyframes[right.id] = rightKeys
        }
        // стык с правым соседом переезжает к правой половине
        transitions = transitions.map { if (it.leftId == id) it.copy(leftId = right.id) else it }.toMutableList()
        sanitize()
        return right.id
    }

    /** Удаление на основной дорожке «схлопывает» пустоту: следующие клипы подтягиваются влево. */
    fun remove(id: Long): Boolean {
        if (find(id) == null) return false
        clips.removeAll { it.id == id }
        sanitize()
        return true
    }

    // ───────── магнитная основная дорожка (Ripple Edit) ─────────
    private fun baseClip(id: Long): Clip? = gestureBase?.clips?.firstOrNull { it.id == id }

    /** Подрезка слева: клип остаётся на месте, режется его начало, следующие клипы подтягиваются. */
    private fun trimMainStart(current: Clip, newStartMs: Long): Boolean {
        val base = baseClip(current.id)?.takeIf { it.row == 0 } ?: current
        val bounded = base.srcDurMs > 0
        val lo = if (bounded) -base.srcInMs else -MAX_EXTEND_MS
        val hi = base.lengthMs - MIN_CLIP_MS
        val delta = (newStartMs - base.startMs).coerceIn(lo, max(lo, hi))
        replace(base.copy(srcInMs = if (bounded) base.srcInMs + delta else base.srcInMs, endMs = base.endMs - delta))
        sanitize()
        return true
    }

    /** Подрезка справа: меняется длина, следующие клипы смещаются так, чтобы пустот не было. */
    private fun trimMainEnd(current: Clip, newEndMs: Long): Boolean {
        val base = baseClip(current.id)?.takeIf { it.row == 0 } ?: current
        val room = if (base.srcDurMs > 0) base.srcDurMs - base.srcInMs else MAX_EXTEND_MS
        val len = (newEndMs - base.startMs).coerceIn(MIN_CLIP_MS, max(MIN_CLIP_MS, room))
        replace(base.copy(endMs = base.startMs + len))
        sanitize()
        return true
    }

    /** Перетаскивание: клип занимает место по положению своего центра среди соседей (позиции соседей — на начало жеста). */
    private fun moveMain(current: Clip, newStartMs: Long): Boolean {
        val reference = (gestureBase?.clips ?: clips).filter { it.row == 0 }.sortedBy { it.startMs }
        val others = reference.filter { it.id != current.id }
        val center = newStartMs + current.lengthMs / 2
        val index = others.count { it.startMs + it.lengthMs / 2 < center }
        val order = others.map { it.id }.toMutableList().also { it.add(index, current.id) }
        var cursor = 0L
        for (id in order) {
            val c = find(id) ?: continue
            replace(c.copy(startMs = cursor, endMs = cursor + c.lengthMs)); cursor += c.lengthMs
        }
        sanitize()
        return true
    }

    /** Основная дорожка всегда без пустот: клипы идут подряд в порядке начала. */
    private fun compactMain() {
        var cursor = 0L
        val moved = clips.filter { it.row == 0 }.sortedBy { it.startMs }.map { c ->
            c.copy(startMs = cursor, endMs = cursor + c.lengthMs).also { cursor += c.lengthMs }
        }
        val byId = moved.associateBy { it.id }
        clips = clips.map { byId[it.id] ?: it }.toMutableList()
    }

    // ───────── переходы ─────────
    fun rightNeighbour(leftId: Long): Clip? {
        val l = find(leftId) ?: return null
        if (l.row != 0) return null
        return clips.firstOrNull { it.row == 0 && it.id != l.id && it.startMs == l.endMs }
    }

    /** Максимальная длительность перехода после клипа leftId (0 — стыка нет). */
    fun maxTransitionMs(leftId: Long): Long = rightNeighbour(leftId)?.lengthMs ?: 0L

    /** shaderId == null — снять переход. false, если стыка нет. */
    fun setTransition(leftId: Long, shaderId: String?, durationMs: Long): Boolean {
        val r = rightNeighbour(leftId) ?: return false
        transitions.removeAll { it.leftId == leftId }
        if (shaderId != null) transitions += Transition(leftId, r.id, shaderId, durationMs)
        sanitize()
        return true
    }

    // ───────── положение кадра на холсте ─────────
    fun setTransform(clipId: Long, t: ClipTransform): Boolean {
        if (find(clipId) == null) return false
        val v = t.sane()
        if (v.isIdentity) transforms.remove(clipId) else transforms[clipId] = v
        return true
    }

    // ───────── кадрирование ─────────
    /** null или полный прямоугольник — снять кадрирование. */
    fun setCrop(clipId: Long, r: CropRect?): Boolean {
        if (find(clipId) == null) return false
        if (r == null || r.sane().isFull) crops.remove(clipId) else crops[clipId] = r.sane()
        return true
    }

    // ───────── ключевые кадры (как в CapCut: ромбики на дорожке клипа) ─────────
    /** Ключ ровно в момент [timeMs] (от начала клипа) или null. */
    fun keyframeAt(clipId: Long, timeMs: Long): Keyframe? = KeyframeTrack.findAt(keyframes[clipId].orEmpty(), timeMs)

    /** Добавляет ключ со значением [t] в момент [timeMs]; ключ на этом месте перезаписывается. */
    fun setKeyframe(clipId: Long, timeMs: Long, t: ClipTransform): Boolean {
        val c = find(clipId) ?: return false
        val at = timeMs.coerceIn(0L, c.lengthMs)
        keyframes[clipId] = KeyframeTrack.upsert(keyframes[clipId].orEmpty(), Keyframe(at, t.sane()))
        return true
    }

    /** true — ключ добавлен, false — ключ под курсором был и удалён. Значение нового ключа — текущая интерполяция. */
    fun toggleKeyframe(clipId: Long, timeMs: Long): Boolean {
        val c = find(clipId) ?: return false
        val at = timeMs.coerceIn(0L, c.lengthMs)
        val keys = keyframes[clipId].orEmpty()
        val (rest, removed) = KeyframeTrack.removeAt(keys, at)
        if (removed) {
            if (rest.isEmpty()) keyframes.remove(clipId) else keyframes[clipId] = rest
            return false
        }
        keyframes[clipId] = KeyframeTrack.upsert(keys, Keyframe(at, state().transformAt(clipId, at)))
        return true
    }

    // ───────── история ─────────
    /** Вызывается один раз ПЕРЕД жестом/операцией. */
    fun checkpoint() {
        gestureBase = state()
        undoStack.addLast(state())
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    fun discardCheckpointIfNoop() {
        if (undoStack.isNotEmpty() && undoStack.last() == state()) undoStack.removeLast()
    }

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(state()); restore(s); return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state()); restore(s); return true
    }

    // ───────── сохранение (формат V1; совместим с ранее сохранёнными проектами) ─────────
    fun serialize(): String = buildString {
        append("V1\t").append(nextId).append('\n')
        clips.forEach { c ->
            append(c.id).append('\t').append(c.row).append('\t').append(c.type.code).append('\t')
                .append(c.startMs).append('\t').append(c.endMs).append('\t').append(c.srcInMs).append('\t')
                .append(c.srcDurMs).append('\t').append(c.uri).append('\n')
        }
        transitions.forEach { t -> append("T\t${t.leftId}\t${t.rightId}\t${t.shaderId}\t${t.durationMs}\n") }
        transforms.forEach { (id, t) -> append("X\t$id\t${t.x}\t${t.y}\t${t.scale}\t${t.rotationDeg}\n") }
        crops.forEach { (id, r) -> append("C\t$id\t${r.left}\t${r.top}\t${r.right}\t${r.bottom}\n") }
        keyframes.forEach { (id, keys) -> keys.forEach { k -> append("K\t$id\t${k.timeMs}\t${k.x}\t${k.y}\t${k.scale}\t${k.rotationDeg}\n") } }
    }

    fun load(data: String): Boolean {
        val newClips = mutableListOf<Clip>()
        val newTr = mutableListOf<Transition>()
        val newX = mutableMapOf<Long, ClipTransform>()
        val newK = mutableMapOf<Long, MutableList<Keyframe>>()
        val newC = mutableMapOf<Long, CropRect>()
        var next = 1L
        var header = false
        for (line in data.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t', limit = 8)
            try {
                if (!header) {
                    if (f.size < 2 || f[0] != "V1") return false
                    next = f[1].toLong(); header = true; continue
                }
                if (f[0] == "T") {
                    val t = line.split('\t')
                    if (t.size >= 5) newTr += Transition(t[1].toLong(), t[2].toLong(), t[3], t[4].toLong())
                    continue
                }
                if (f[0] == "X") {
                    val t = line.split('\t')
                    if (t.size >= 6) newX[t[1].toLong()] = ClipTransform(t[2].toFloat(), t[3].toFloat(), t[4].toFloat(), t[5].toFloat())
                    continue
                }
                if (f[0] == "C") {
                    val t = line.split('\t')
                    if (t.size >= 6) newC[t[1].toLong()] = CropRect(t[2].toFloat(), t[3].toFloat(), t[4].toFloat(), t[5].toFloat()).sane()
                    continue
                }
                if (f[0] == "K") {
                    val t = line.split('\t')
                    if (t.size >= 7) newK.getOrPut(t[1].toLong()) { mutableListOf() } +=
                        Keyframe(t[2].toLong(), t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat())
                    continue
                }
                if (f.size < 8) continue
                newClips += Clip(f[0].toLong(), f[1].toInt(), MediaType.of(f[2].toInt()), f[3].toLong(), f[4].toLong(), f[5].toLong(), f[6].toLong(), f[7])
            } catch (_: NumberFormatException) { return false }
        }
        if (!header) return false
        clips = newClips; transitions = newTr; transforms = newX; crops = newC
        keyframes = newK.mapValues { it.value.toList() }.toMutableMap(); nextId = next
        undoStack.clear(); redoStack.clear()
        sanitize()
        return true
    }

    // ───────── внутреннее ─────────
    private fun find(id: Long) = clips.firstOrNull { it.id == id }
    private fun replace(c: Clip) { val i = clips.indexOfFirst { it.id == c.id }; if (i >= 0) clips[i] = c }
    private fun restore(s: TimelineState) {
        clips = s.clips.toMutableList(); transitions = s.transitions.toMutableList()
        transforms = s.transforms.toMutableMap(); keyframes = s.keyframes.toMutableMap(); crops = s.crops.toMutableMap()
    }

    /** Убирает переходы, потерявшие стык, и зажимает длительность в [MIN_TRANSITION_MS, длина входящего клипа]. */
    private fun sanitize() {
        compactMain()
        val ids = clips.map { it.id }.toSet()
        transforms.keys.retainAll(ids)
        keyframes.keys.retainAll(ids)
        crops.keys.retainAll(ids)
        // ключи за пределами (подрезанного) клипа прижимаются к его краям
        keyframes.replaceAll { id, keys ->
            val len = find(id)?.lengthMs ?: 0L
            keys.map { it.copy(timeMs = it.timeMs.coerceIn(0L, len)) }
                .fold(emptyList()) { acc, k -> KeyframeTrack.upsert(acc, k, 0L) }
        }
        transitions = transitions.mapNotNull { t ->
            val l = find(t.leftId); val r = find(t.rightId)
            if (l == null || r == null || l.row != 0 || r.row != 0 || l.endMs != r.startMs) null
            else t.copy(durationMs = t.durationMs.coerceIn(MIN_TRANSITION_MS, max(MIN_TRANSITION_MS, r.lengthMs)))
        }.toMutableList()
    }

    companion object {
        const val MIN_CLIP_MS = 100L
        const val MIN_TRANSITION_MS = 200L
        private const val MAX_EXTEND_MS = 600_000L
        private const val INF = Long.MAX_VALUE / 4
        private const val MAX_HISTORY = 100
    }
}
