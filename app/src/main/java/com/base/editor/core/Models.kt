package com.base.editor.core

enum class MediaType(val code: Int) {
    VIDEO(0), IMAGE(1), AUDIO(2), TEXT(3);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: VIDEO }
}

/** Клип на шкале времени (зеркало base::Clip из C++). Время — миллисекунды. */
data class Clip(
    val id: Long,
    val row: Int,
    val type: MediaType,
    val startMs: Long,
    val endMs: Long,
    val srcInMs: Long,
    val srcDurMs: Long,
    val uri: String,
    /** Скорость воспроизведения (0.1…10×). Длина на таймлайне = длина исходного отрезка / speed. */
    val speed: Float = 1f,
    /** Громкость клипа: 0 — без звука, 1 — оригинал, 2 — усиление ×2 (+6 дБ). */
    val volume: Float = 1f,
) {
    val lengthMs get() = endMs - startMs
    /** Длина исходного отрезка, который занимает клип (мс исходника). */
    val srcSpanMs get() = (lengthMs * speed).toLong()
}

/**
 * Переход «в» клип rightId на стыке с левым соседом leftId.
 * Занимает первые durationMs входящего клипа: уходящий клип в этот момент показан последним кадром.
 */
data class Transition(val leftId: Long, val rightId: Long, val shaderId: String, val durationMs: Long)

/**
 * Положение кадра клипа на холсте. x/y — смещение в долях кадра (вправо/вниз), scale — масштаб,
 * rotationDeg — поворот по часовой стрелке.
 */
data class ClipTransform(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotationDeg: Float = 0f) {
    val isIdentity get() = x == 0f && y == 0f && scale == 1f && rotationDeg == 0f
    fun sane() = copy(x = x.coerceIn(-2f, 2f), y = y.coerceIn(-2f, 2f), scale = scale.coerceIn(0.1f, 8f))
}

/**
 * Ключевой кадр трансформации клипа (как в CapCut): значение [ClipTransform] в момент [timeMs].
 * Время — от НАЧАЛА клипа на таймлайне (мс), поэтому ключи переживают сдвиг клипа по дорожке.
 */
data class Keyframe(
    val timeMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotationDeg: Float,
) {
    constructor(timeMs: Long, t: ClipTransform) : this(timeMs, t.x, t.y, t.scale, t.rotationDeg)
    fun transform() = ClipTransform(x, y, scale, rotationDeg)
}

/** Хромакей: цвет ключа (ARGB), порог схожести 0…1, сглаживание края 0…1, цвет, которым заливается убранное. */
data class ChromaKey(
    val color: Int = 0xFF00FF00.toInt(), val similarity: Float = 0.30f, val smoothness: Float = 0.10f,
    val bgColor: Int = 0xFF000000.toInt(),
)

enum class BgMode { COLOR, BLUR }

/** Удаление фона по маске сегментации: фон заливается цветом или размывается; опциональная обводка контура. */
data class BgRemoval(
    val mode: BgMode = BgMode.COLOR, val bgColor: Int = 0xFF000000.toInt(), val blur: Float = 0.6f,
    val outline: Boolean = false, val outlineColor: Int = 0xFFFFFFFF.toInt(), val outlineWidth: Float = 0.5f,
)

/** Прямоугольник кадрирования в долях исходного кадра (0..1, начало слева сверху). */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val isFull get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f
    val width get() = right - left
    val height get() = bottom - top
    fun sane(): CropRect {
        val l = left.coerceIn(0f, 0.95f); val t = top.coerceIn(0f, 0.95f)
        return CropRect(l, t, right.coerceIn(l + 0.05f, 1f), bottom.coerceIn(t + 0.05f, 1f))
    }
}

/** Файл, выбранный в галерее. */
data class PickedMedia(val uri: String, val type: MediaType, val durationMs: Long) {
    fun encode() = "${type.code}|$durationMs|$uri"
    companion object {
        fun decode(s: String): PickedMedia {
            val (t, d, u) = s.split("|", limit = 3)
            return PickedMedia(u, MediaType.of(t.toInt()), d.toLong())
        }
    }
}

data class ProjectMeta(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val hasVideo: Boolean,
    val thumbPath: String?,
)

const val IMAGE_DEFAULT_MS = 3000L
