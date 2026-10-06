package com.base.editor.text

import org.json.JSONArray
import org.json.JSONObject

/** Сохранение текстовых слоёв в проект; битые записи пропускаются, а не ломают загрузку. */
object TextJson {
    fun encode(items: List<TextClip>): String = JSONArray().also { arr ->
        items.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("t", c.text).put("s", c.startMs).put("d", c.durationMs)
                .put("x", c.positionX.toDouble()).put("y", c.positionY.toDouble()).put("size", c.fontSizeSp.toDouble())
                .put("color", c.textColor).put("bg", c.backgroundColor).put("rot", c.rotationDeg.toDouble()).put("pag", c.pagTemplate ?: "").put("font", c.fontId).put("stroke", c.strokeColor).put("strokeW", c.strokeWidth.toDouble()).put("shadow", c.shadowColor).put("shadowB", c.shadowBlur.toDouble()).put("shadowDy", c.shadowDy.toDouble()).put("style", c.styleId).put("anim", c.animId))
        }
    }.toString()

    fun decode(json: String?): List<TextClip> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    TextClip(
                        id = o.getString("id"), text = o.getString("t"), startMs = o.getLong("s"), durationMs = o.optLong("d", 3000L),
                        positionX = o.optDouble("x", 0.5).toFloat(), positionY = o.optDouble("y", 0.5).toFloat(),
                        fontSizeSp = o.optDouble("size", 24.0).toFloat(), textColor = o.optLong("color", 0xFFFFFFFF),
                        backgroundColor = o.optLong("bg", 0L), rotationDeg = o.optDouble("rot", 0.0).toFloat(),
                        pagTemplate = o.optString("pag").takeIf { it.isNotEmpty() },
                        fontId = o.optString("font").ifEmpty { "MONTSERRAT" },
                        strokeColor = o.optLong("stroke", 0L), strokeWidth = o.optDouble("strokeW", 0.0).toFloat(),
                        shadowColor = o.optLong("shadow", 0L), shadowBlur = o.optDouble("shadowB", 0.0).toFloat(), shadowDy = o.optDouble("shadowDy", 0.0).toFloat(),
                        styleId = o.optString("style").ifEmpty { "plain" }, animId = o.optString("anim").ifEmpty { "none" },
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
