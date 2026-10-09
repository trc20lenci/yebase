package com.base.editor.captions.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Загрузка и хранение моделей распознавания речи (filesDir/speech-models). */
class SpeechModelStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val root = File(context.applicationContext.filesDir, "speech-models").apply { mkdirs() }

    /** Фоновая предзагрузка и запрос из генерации не должны качать одну модель дважды. */
    private val lock = Mutex()

    fun isReady(lang: SpeechLanguage) = File(File(root, lang.modelName), READY).exists()

    /** Возвращает папку модели, при необходимости скачав её. [onProgress] — 0f..1f. */
    suspend fun ensure(lang: SpeechLanguage, onProgress: (Float) -> Unit): File = withContext(io) { lock.withLock {
        val dir = File(root, lang.modelName)
        if (File(dir, READY).exists()) return@withContext modelRoot(dir)

        val tmp = File(root, "${lang.modelName}.part").apply { deleteRecursively(); mkdirs() }
        val conn = (URL(lang.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("Сервер вернул ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (lang.approxMb * 1_048_576L)
            var read = 0L
            ZipInputStream(conn.inputStream.buffered(64 * 1024)).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name).canonicalFile
                    if (!out.path.startsWith(tmp.canonicalPath + File.separator)) throw SecurityException("Недопустимый путь в архиве")
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        while (true) {
                            val n = zip.read(buf); if (n < 0) break
                            os.write(buf, 0, n); read += n
                            onProgress((read.toFloat() / (total * 1.6f)).coerceIn(0f, 0.99f))   // zip сжат: оценка по распакованному объёму
                        }
                    }
                }
            }
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw java.io.IOException("Не удалось сохранить модель")
            File(dir, READY).writeText("ok")
            onProgress(1f)
            modelRoot(dir)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            Log.e(TAG, "модель ${lang.modelName}", e)
            throw e
        } finally {
            conn.disconnect()
        }
    } }

    /** Внутри архива модель обычно лежит в единственной подпапке. */
    private fun modelRoot(dir: File): File {
        if (File(dir, "am").isDirectory) return dir
        return dir.listFiles { f -> f.isDirectory && File(f, "am").isDirectory }?.firstOrNull() ?: dir
    }

    private companion object { const val TAG = "BaseSpeechModels"; const val READY = ".ready" }
}
