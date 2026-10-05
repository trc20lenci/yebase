package com.base.editor.captions

/** Язык распознавания речи. [code] уходит в Whisper; пустой — автоопределение. */
enum class CaptionLanguage(val code: String, val label: String) {
    RU("ru", "Русский"), EN("en", "Английский"), AUTO("", "Авто")
}
