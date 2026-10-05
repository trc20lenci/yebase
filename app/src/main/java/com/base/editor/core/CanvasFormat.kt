package com.base.editor.core

/** Пресет формата холста. aspect == null — «Оригинал» (по первому клипу основной дорожки). */
enum class CanvasFormat(val id: String, val label: String, val aspect: Float?) {
    ORIGINAL("orig", "Оригинал", null),
    V9_16("9:16", "9:16", 9f / 16f),
    H16_9("16:9", "16:9", 16f / 9f),
    SQUARE("1:1", "1:1", 1f),
    P4_5("4:5", "4:5", 4f / 5f),
    P3_4("3:4", "3:4", 3f / 4f);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ORIGINAL }
}
