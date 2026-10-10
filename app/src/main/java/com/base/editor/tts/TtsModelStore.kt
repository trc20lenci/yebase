package com.base.editor.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Скачивание и распаковка голоса Piper (tar.bz2) в filesDir/tts-voices/<id>; одна загрузка за раз, готовность — файлом `.ready`. */
class TtsModelStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val root = File(context.applicationContext.filesDir, "tts-voices").apply { mkdirs() }
    private val lock = Mutex()

    class Files(val model: File, val tokens: File, val dataDir: File)

    private fun dir(v: TtsVoice) = File(root, v.id)
    fun isReady(v: TtsVoice) = File(dir(v), ".ready").exists()

    private fun files(v: TtsVoice): Files {
        val d = dir(v)
        val inner = d.listFiles { f -> f.isDirectory }?.firstOrNull() ?: d          // в архиве один верхний каталог
        val model = inner.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull() ?: throw IOException("в голосе нет модели")
        val tokens = File(inner, "tokens.txt").takeIf { it.exists() } ?: throw IOException("в голосе нет tokens.txt")
        val data = File(inner, "espeak-ng-data").takeIf { it.isDirectory } ?: throw IOException("в голосе нет espeak-ng-data")
        return Files(model, tokens, data)
    }

    suspend fun ensure(v: TtsVoice): Files = withContext(io) {
        lock.withLock {
            if (isReady(v)) return@withLock files(v)
            val d = dir(v); d.deleteRecursively(); d.mkdirs()
            val part = File(root, "${v.id}.part").apply { delete() }
            try {
                var last: Exception? = null
                var ok = false
                repeat(3) { attempt ->
                    if (ok) return@repeat
                    try { download(v, part); ok = true } catch (e: IOException) { last = e; Log.w(TAG, "загрузка голоса, попытка ${attempt + 1}", e); part.delete() }
                }
                if (!ok) throw last ?: IOException("не удалось скачать голос")
                extract(part, d)
                val f = files(v)                                    // проверка, что всё на месте
                File(d, ".ready").writeText("1")
                f
            } catch (e: Exception) { d.deleteRecursively(); throw e } finally { part.delete() }
        }
    }

    private suspend fun download(v: TtsVoice, part: File) {
        val c = (URL(v.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true }
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val expected = c.contentLengthLong
            c.inputStream.buffered(128 * 1024).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) { coroutineContext.ensureActive(); val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                }
            }
            if (part.length() == 0L || (expected > 0 && part.length() != expected)) throw IOException("файл голоса повреждён")
        } finally { c.disconnect() }
    }

    private suspend fun extract(archive: File, into: File) {
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 128 * 1024))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val e = tar.nextEntry ?: break
                val out = File(into, e.name).canonicalFile
                if (!out.path.startsWith(base)) throw IOException("недопустимый путь в архиве")      // защита от выхода за каталог
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { tar.copyTo(it) } }
            }
        }
    }

    private companion object { const val TAG = "BaseTts" }
}
