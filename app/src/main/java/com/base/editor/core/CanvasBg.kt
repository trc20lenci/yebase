package com.base.editor.core

/** Фон холста — то, что видно на полях вокруг кадра (когда клип отодвинут, уменьшен или не совпадает по пропорциям). */
enum class CanvasBg(val id: String, val label: String, val argb: Int?) {
    BLACK("black", "Чёрный", 0xFF000000.toInt()),
    BLUR("blur", "Размытие", null),               // исходное видео, растянутое и размытое
    WHITE("white", "Белый", 0xFFFFFFFF.toInt()),
    TEAL("teal", "Бирюзовый", 0xFF00CCDD.toInt()),
    PINK("pink", "Розовый", 0xFFEC4899.toInt()),
    PURPLE("purple", "Фиолетовый", 0xFF7C3AED.toInt()),
    YELLOW("yellow", "Жёлтый", 0xFFFFE600.toInt());

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: BLACK }
}
