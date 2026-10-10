package com.base.editor.tts

/**
 * Голоса Piper (rhasspy/piper) в формате ONNX — компактные квантованные (int8, ≈21 МБ) сборки из релизов sherpa-onnx,
 * которая запускает Piper-модели на Android вместе со встроенным фонемизатором espeak-ng.
 */
enum class TtsVoice(val id: String, val label: String, val archive: String, val language: String) {
    RU_IRINA("ru-irina", "Ирина", "vits-piper-ru_RU-irina-medium-int8", "ru"),
    RU_DENIS("ru-denis", "Денис", "vits-piper-ru_RU-denis-medium-int8", "ru"),
    RU_DMITRI("ru-dmitri", "Дмитрий", "vits-piper-ru_RU-dmitri-medium-int8", "ru"),
    RU_RUSLAN("ru-ruslan", "Руслан", "vits-piper-ru_RU-ruslan-medium-int8", "ru"),
    EN_AMY("en-amy", "Amy (EN)", "vits-piper-en_US-amy-medium-int8", "en"),
    EN_LESSAC("en-lessac", "Lessac (EN)", "vits-piper-en_US-lessac-medium-int8", "en");

    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archive.tar.bz2"
}
