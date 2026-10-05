package com.base.editor.domain

import com.base.editor.core.ClipTransform
import com.base.editor.core.Keyframe

/**
 * Дорожка ключевых кадров одного клипа: хранение, поиск и интерполяция между ключами.
 * Чистый Kotlin без Android-зависимостей — проверяется на JVM.
 *
 * Логика интерполяции перенесена из Lottie (com/airbnb/lottie/animation/keyframe —
 * Keyframe.java, BaseKeyframeAnimation.java, Apache 2.0): ключи отсортированы по времени,
 * значение в момент t ищется как пара соседних ключей K1..K2, а между ними — линейная
 * интерполяция по нормализованному прогрессу: value = V1 + (V2 - V1) * t.
 * До первого ключа действует его значение, после последнего — значение последнего.
 */
object KeyframeTrack {

    /** Допуск попадания курсора в существующий ключ (мс) — для иконки «ромбик с минусом» и удаления. */
    const val HIT_TOLERANCE_MS = 40L

    /** Значение трансформации в момент [timeMs] (мс от начала клипа). Ключи могут быть не отсортированы. */
    fun at(keys: List<Keyframe>, timeMs: Long): ClipTransform {
        if (keys.isEmpty()) return ClipTransform()
        val sorted = keys.sortedBy { it.timeMs }
        val first = sorted.first()
        if (timeMs <= first.timeMs) return first.transform()
        val last = sorted.last()
        if (timeMs >= last.timeMs) return last.transform()
        var i = 1
        while (i < sorted.size && sorted[i].timeMs < timeMs) i++
        val k1 = sorted[i - 1]; val k2 = sorted[i]
        val span = (k2.timeMs - k1.timeMs).toFloat()
        val t = if (span <= 0f) 0f else (timeMs - k1.timeMs) / span      // нормализованный прогресс 0..1
        return ClipTransform(
            x = lerp(k1.x, k2.x, t),
            y = lerp(k1.y, k2.y, t),
            scale = lerp(k1.scale, k2.scale, t),
            rotationDeg = lerp(k1.rotationDeg, k2.rotationDeg, t),
        )
    }

    /** Ключ ровно под курсором (с допуском [HIT_TOLERANCE_MS]) или null. */
    fun findAt(keys: List<Keyframe>, timeMs: Long, toleranceMs: Long = HIT_TOLERANCE_MS): Keyframe? =
        keys.firstOrNull { kotlin.math.abs(it.timeMs - timeMs) <= toleranceMs }

    /**
     * Добавляет ключ; если ключ на этой миллисекунде уже есть (с допуском) — заменяет его значение.
     * Возвращает новый отсортированный список.
     */
    fun upsert(keys: List<Keyframe>, key: Keyframe, toleranceMs: Long = HIT_TOLERANCE_MS): List<Keyframe> =
        (keys.filterNot { kotlin.math.abs(it.timeMs - key.timeMs) <= toleranceMs } + key).sortedBy { it.timeMs }

    /** Удаляет ключ под курсором. true — ключ был и удалён. */
    fun removeAt(keys: List<Keyframe>, timeMs: Long, toleranceMs: Long = HIT_TOLERANCE_MS): Pair<List<Keyframe>, Boolean> {
        val hit = findAt(keys, timeMs, toleranceMs) ?: return keys to false
        return keys.filterNot { it === hit || it == hit } to true
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
