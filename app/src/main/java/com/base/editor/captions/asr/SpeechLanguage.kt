package com.base.editor.captions.asr

/** Языки распознавания (компактные офлайн-модели; скачиваются один раз). */
enum class SpeechLanguage(val label: String, val modelName: String, val url: String, val approxMb: Int) {
    RU("Русский", "vosk-model-small-ru-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45),
    EN("English", "vosk-model-small-en-us-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40),
}
