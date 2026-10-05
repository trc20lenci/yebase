package com.base.editor.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.base.editor.domain.TimelineState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportQuality(val label: String, val shortSide: Int, val bitrate: Int) {
    P480("480p", 480, 3_000_000), P720("720p", 720, 6_000_000), P1080("1080p", 1080, 12_000_000), P1440("2K", 1440, 20_000_000)
}

data class ExportRequest(
    val state: TimelineState,
    val aspect: Float,
    val quality: ExportQuality = ExportQuality.P1080,
    /** Целевая частота кадров: 24 / 30 / 60. */
    val fps: Int = 30,
    val hevc: Boolean = false,
    val removeAudio: Boolean = false,
    val captions: CaptionTrack? = null,
    val texts: List<com.base.editor.text.TextClip> = emptyList(),
)

sealed interface ExportState {
    data object Preparing : ExportState
    data class Progress(val percent: Int, val attempt: Int) : ExportState
    /** Первая попытка не удалась — экспорт повторяется в упрощённом режиме. */
    data class Retrying(val reason: String) : ExportState
    data class Done(val file: File, val sizeBytes: Long) : ExportState
    data class Failed(val message: String, val cause: Throwable? = null) : ExportState
}

/**
 * Экспорт итогового MP4 через Media3 Transformer.
 *  • Кодирование — аппаратный MediaCodec (с включённым fallback на другой кодек/профиль).
 *  • Transformer живёт на отдельном HandlerThread: главный поток не занят ни на миллисекунду.
 *  • Сборка Composition — на Dispatchers.Default; файлы/MediaStore — на Dispatchers.IO.
 *  • Сбой кодека/GL → одна автоматическая повторная попытка: без шейдеров, H.264, 0.75× разрешения.
 */
@UnstableApi
class VideoExportManager(
    private val context: Context,
    private val factory: CompositionFactory,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    private val app = context.applicationContext

    fun export(req: ExportRequest): Flow<ExportState> = callbackFlow {
        trySend(ExportState.Preparing)
        val thread = HandlerThread("base-export").apply { start() }
        val handler = Handler(thread.looper)
        var transformer: Transformer? = null
        var cancelled = false

        fun attempt(n: Int) {
            val simple = n > 0
            val scale = if (simple) 0.75f else 1f
            val canvas = CompositionFactory.canvasFor(req.aspect, (req.quality.shortSide * scale).toInt())
            val output = outputFile()
            val composition: Composition? = runCatching {
                factory.build(CompositionRequest(req.state, canvas, req.removeAudio, safeMode = simple, captions = req.captions, texts = req.texts, fps = req.fps))
            }.getOrNull()
            if (composition == null) { trySend(ExportState.Failed("Нет клипов для экспорта")); close(); return }

            val encoder = DefaultEncoderFactory.Builder(app)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate((req.quality.bitrate * scale * scale).toInt()).build(),
                ).build()

            val t = Transformer.Builder(app)
                .setLooper(thread.looper)
                .setVideoMimeType(if (req.hevc && !simple) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        trySend(ExportState.Done(output, output.length())); close()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.e(TAG, "экспорт: ${exportException.errorCodeName}", exportException)
                        output.delete()
                        if (!cancelled && n == 0 && exportException.errorCode in RECOVERABLE) {
                            trySend(ExportState.Retrying(exportException.errorCodeName))
                            attempt(1)
                        } else {
                            trySend(ExportState.Failed(humanMessage(exportException), exportException)); close()
                        }
                    }
                }).build()
            transformer = t
            t.start(composition, output.absolutePath)

            // опрос прогресса на том же looper'е, что и Transformer
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (cancelled || transformer !== t) return
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) trySend(ExportState.Progress(holder.progress, n))
                    handler.postDelayed(this, PROGRESS_MS)
                }
            }
            handler.postDelayed(poll, PROGRESS_MS)
        }

        handler.post { runCatching { attempt(0) }.onFailure { trySend(ExportState.Failed("Экспорт не запустился", it)); close() } }

        awaitClose {
            cancelled = true
            handler.post { runCatching { transformer?.cancel() }; thread.quitSafely() }
        }
    }.flowOn(dispatchers.default)

    /** Копирует готовый файл в галерею (Movies/BASE). Вызывать после [ExportState.Done]. */
    suspend fun saveToGallery(file: File): Uri = withContext(dispatchers.io) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BASE")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore отказал")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        file.delete()
        uri
    }

    private fun outputFile(): File {
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "BASE_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
    }

    private fun humanMessage(e: ExportException) = when (e.errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "Устройство не смогло подобрать видеокодер для такого разрешения"
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Один из файлов не удалось декодировать"
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> "Нет доступа к исходному файлу"
        else -> "Ошибка экспорта (${e.errorCodeName})"
    }

    private companion object {
        const val TAG = "BaseExport"
        const val PROGRESS_MS = 250L
        val RECOVERABLE = setOf(
            ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
    }
}
