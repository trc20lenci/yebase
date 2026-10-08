package com.base.editor.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.base.editor.core.Clip
import com.base.editor.core.IMAGE_DEFAULT_MS
import com.base.editor.core.MediaType
import com.base.editor.core.PickedMedia
import com.base.editor.core.ProjectMeta
import com.base.editor.domain.TimelineModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Проекты хранятся как filesDir/projects/<id>.json (+ <id>.jpg — обложка). */
class ProjectRepository private constructor(private val app: Context) {
    private val dir = File(app.filesDir, "projects").apply { mkdirs() }
    /** Увеличивается при любом изменении — списки на экранах перечитываются. */
    val revision = MutableStateFlow(0)

    suspend fun list(): List<ProjectMeta> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { runCatching { meta(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.modifiedAt }
    }

    suspend fun create(items: List<PickedMedia>): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val tl = TimelineModel()
        items.forEach { m ->
            val len = if (m.type == MediaType.VIDEO) m.durationMs else IMAGE_DEFAULT_MS
            tl.addClip(0, m.type, m.uri, if (m.type == MediaType.VIDEO) m.durationMs else 0, len)
        }
        write(id, name = nextName(), timeline = tl.serialize(), clips = tl.state().clips)
        id
    }

    suspend fun loadTimeline(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(File(dir, "$id.json").readText()).getString("timeline") }.getOrNull()
    }

    suspend fun loadCaptions(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(File(dir, "$id.json").readText()).optString("captions").takeIf { it.isNotEmpty() } }.getOrNull()
    }

    suspend fun loadTexts(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(File(dir, "$id.json").readText()).optString("texts").takeIf { it.isNotEmpty() } }.getOrNull()
    }

    suspend fun loadBg(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(File(dir, "$id.json").readText()).optString("bg").takeIf { it.isNotEmpty() } }.getOrNull()
    }

    suspend fun loadFormat(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(File(dir, "$id.json").readText()).optString("format").takeIf { it.isNotEmpty() } }.getOrNull()
    }

    /** captions/texts == null — оставить сохранённые ранее данные без изменений. */
    suspend fun save(id: String, timeline: String, clips: List<Clip>, captions: String? = null, texts: String? = null, format: String? = null, bg: String? = null) = withContext(Dispatchers.IO) {
        val old = runCatching { JSONObject(File(dir, "$id.json").readText()) }.getOrNull()
        write(id, old?.optString("name") ?: nextName(), timeline, clips, old?.optString("thumbKey"),
            captions ?: old?.optString("captions"), texts ?: old?.optString("texts"), format ?: old?.optString("format"), bg ?: old?.optString("bg"))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete(); File(dir, "$id.jpg").delete(); revision.value++
    }

    private suspend fun write(id: String, name: String, timeline: String, clips: List<Clip>, oldThumbKey: String? = null, captions: String? = null, texts: String? = null, format: String? = null, bg: String? = null) {
        val main = clips.filter { it.row == 0 }.minByOrNull { it.startMs }
        val thumbKey = main?.let { "${it.uri}@${it.srcInMs}" }.orEmpty()
        if (main != null && thumbKey != oldThumbKey) {
            Thumbs.projectCover(app, main.uri, main.type, main.srcInMs)?.let { bmp ->
                File(dir, "$id.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
        val json = JSONObject()
            .put("id", id).put("name", name).put("modifiedAt", System.currentTimeMillis())
            .put("durationMs", clips.maxOfOrNull { it.endMs } ?: 0L)
            .put("sizeBytes", clips.map { it.uri }.distinct().sumOf { sizeOf(it) })
            .put("hasVideo", clips.any { it.type == MediaType.VIDEO })
            .put("thumbKey", thumbKey).put("timeline", timeline).put("captions", captions.orEmpty()).put("texts", texts.orEmpty()).put("format", format.orEmpty()).put("bg", bg.orEmpty())
        File(dir, "$id.json").writeText(json.toString())
        revision.value++
    }

    private fun meta(j: JSONObject): ProjectMeta {
        val id = j.getString("id")
        val jpg = File(dir, "$id.jpg")
        return ProjectMeta(id, j.getString("name"), j.getLong("modifiedAt"), j.getLong("durationMs"),
            j.getLong("sizeBytes"), j.getBoolean("hasVideo"), jpg.takeIf { it.exists() }?.absolutePath)
    }

    private fun sizeOf(uri: String): Long = runCatching {
        app.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    /** Имя как в макете: MMdd-NN (0926-01, 0926-02 ...). */
    private fun nextName(): String {
        val prefix = SimpleDateFormat("MMdd", Locale.US).format(Date())
        val n = dir.listFiles { f -> f.extension == "json" }.orEmpty().count {
            runCatching { JSONObject(it.readText()).getString("name").startsWith(prefix) }.getOrDefault(false)
        }
        return "%s-%02d".format(prefix, n + 1)
    }

    companion object {
        @Volatile private var inst: ProjectRepository? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: ProjectRepository(ctx.applicationContext).also { inst = it } }
    }
}
