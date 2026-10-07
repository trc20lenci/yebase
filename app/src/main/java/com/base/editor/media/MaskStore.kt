package com.base.editor.media

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.min

/**
 * Маски переднего плана, посчитанные заранее для исходного файла (по одной на кадр при [fps]), в одном файле:
 * заголовок (магия, ширина, высота, fps, начало диапазона в мс исходника, число кадров) + байты 0..255 (255 — передний план).
 */
class MaskStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "masks").apply { mkdirs() }

    fun file(uri: String): File = File(dir, sha1(uri) + ".mask")
    fun has(uri: String) = file(uri).let { it.exists() && it.length() > HEADER }
    fun reader(uri: String): MaskReader? = if (has(uri)) runCatching { MaskReader(file(uri)) }.getOrNull() else null
    fun delete(uri: String) { file(uri).delete() }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAGIC = 0x4D41534B
        const val HEADER = 28L        // magic, w, h, fps (по 4 байта) + fromMs (8) + count (4) + резерв (4)
    }
}

class MaskReader(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val map: MappedByteBuffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    val width: Int; val height: Int; val fps: Int; val fromMs: Long; val count: Int
    private val frameSize: Int

    init {
        require(map.getInt(0) == MaskStore.MAGIC) { "не файл масок" }
        width = map.getInt(4); height = map.getInt(8); fps = map.getInt(12)
        fromMs = map.getLong(16); count = map.getInt(24)
        frameSize = width * height
    }

    /** Маска на момент [srcMs] исходника в [out] (width*height). Вне посчитанного диапазона — всё «передний план». */
    fun read(srcMs: Long, out: ByteArray) {
        val pos = (srcMs - fromMs) * fps / 1000f
        if (count == 0 || pos < -0.5f || pos > count - 0.5f) { out.fill(255.toByte()); return }
        val i0 = floor(pos.coerceAtLeast(0f)).toInt().coerceIn(0, count - 1)
        val i1 = min(i0 + 1, count - 1)
        val t = (pos - i0).coerceIn(0f, 1f)
        val b0 = MaskStore.HEADER.toInt() + i0 * frameSize
        val b1 = MaskStore.HEADER.toInt() + i1 * frameSize
        if (t < 0.02f || i0 == i1) { for (k in 0 until frameSize) out[k] = map.get(b0 + k) }
        else for (k in 0 until frameSize) {
            val a = map.get(b0 + k).toInt() and 0xFF; val b = map.get(b1 + k).toInt() and 0xFF
            out[k] = (a + (b - a) * t).toInt().toByte()
        }
    }

    override fun close() { runCatching { raf.close() } }
}
