package com.base.editor.captions.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Мультиязычный Whisper-base (int8, ONNX) для sherpa-onnx. Три файла скачиваются тихо в filesDir/asr при первом запросе.
 * Целостность: SHA-256 сверяется с заголовком X-Linked-Etag (для файлов LFS это хеш содержимого), иначе — по размеру.
 */
class SherpaModelStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val dir = File(File(context.applicationContext.filesDir, "asr"), "whisper-base-int8").apply { mkdirs() }
    private val marker = File(dir, ".ok-$VERSION")

    class Files(val encoder: File, val decoder: File, val tokens: File)

    private fun files() = Files(File(dir, ENCODER), File(dir, DECODER), File(dir, TOKENS))

    suspend fun ensure(): Files = withContext(io) {
        val f = files()
        if (marker.exists() && f.encoder.exists() && f.decoder.exists() && f.tokens.exists()) return@withContext f
        marker.delete()
        for (name in listOf(ENCODER, TOKENS, DECODER)) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0 && File(dir, "$name.sha").exists()) continue
            var last: Exception? = null
            var done = false
            repeat(ATTEMPTS) { attempt ->
                if (done) return@repeat
                try { download(name, target); done = true } catch (e: IOException) {
                    last = e; Log.w(TAG, "загрузка $name, попытка ${attempt + 1}", e); delay(1500L * (attempt + 1))
                }
            }
            if (!done) throw last ?: IOException("Не удалось подготовить распознавание")
        }
        marker.writeText(VERSION)
        f
    }

    private suspend fun download(name: String, target: File) {
        val part = File(dir, "$name.part").apply { delete() }
        val first = open("$BASE_URL/$name", follow = false)
        var etag: String? = null
        val conn: HttpURLConnection
        try {
            if (first.responseCode in 300..399) {
                etag = first.getHeaderField("X-Linked-Etag")?.trim('"')
                val loc = URL(URL(BASE_URL), first.getHeaderField("Location") ?: throw IOException("нет Location")).toString()
                first.disconnect()
                conn = open(loc, follow = true)
            } else conn = first
        } catch (e: Exception) { first.disconnect(); throw if (e is IOException) e else IOException(e) }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val expected = conn.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.buffered(128 * 1024).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val sizeBad = part.length() == 0L || (expected > 0 && part.length() != expected)
            val hashBad = etag != null && etag.length == 64 && !etag.equals(sha, ignoreCase = true)
            if (sizeBad || hashBad) { part.delete(); throw IOException("Файл повреждён: $name") }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить файл")
            File(dir, "$name.sha").writeText(sha)
        } finally { conn.disconnect(); part.delete() }
    }

    private fun open(url: String, follow: Boolean) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = follow
    }

    private companion object {
        const val TAG = "BaseAsr"
        const val VERSION = "1"
        const val ATTEMPTS = 3
        const val ENCODER = "base-encoder.int8.onnx"
        const val DECODER = "base-decoder.int8.onnx"
        const val TOKENS = "base-tokens.txt"
        const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main"
    }
}
