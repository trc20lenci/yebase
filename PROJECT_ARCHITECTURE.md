# BASE — архитектура проекта

Android-видеоредактор: Kotlin, Jetpack Compose, Media3 (Transformer/CompositionPlayer), TFLite (LiteRT), libPAG.
minSdk 29, compileSdk 36, Kotlin 2.2.20, AGP 8.9.2, Gradle 8.11.1, Compose BOM 2024.12.01, Media3 1.11.1.

## 1. Структура пакетов и файлов

```
app/src/main/java/com/base/editor/
├── MainActivity.kt              — единственная Activity
├── BaseApp.kt                   — навигация: main, picker/new/{tab}, picker/add/{projectId}, editor/{projectId}
├── MainScreen.kt                — вкладки «Дом», «Проекты», «Я»
├── AppViewModel.kt
├── core/
│   ├── Models.kt                — MediaType, Clip, Transition, ClipTransform, Keyframe, PickedMedia, ProjectMeta
│   └── TransitionCatalog.kt     — каталог 44 GLSL-переходов (assets/shaders/manifest.json)
├── domain/                      — чистый Kotlin без Android (покрыт юнит-тестами)
│   ├── TimelineModel.kt         — дорожки, клипы, переходы, трансформации, ключевые кадры, undo/redo, сериализация V1
│   └── KeyframeTrack.kt         — хранение и линейная интерполяция ключей (логика из Lottie keyframe, Apache 2.0)
├── media/
│   ├── TimelineController.kt    — связь модели с CompositionPlayer, конвейер пересборки Composition
│   ├── CompositionFactory.kt    — модель → Composition (единая для превью и экспорта)
│   ├── VideoExportManager.kt    — экспорт MP4 через Transformer, Flow<ExportState>, повтор в упрощённом режиме
│   ├── AppDispatchers.kt
│   ├── CaptionExportOverlay.kt  — вжигание субтитров в экспорт (TextureOverlay)
│   ├── TextExportOverlay.kt     — вжигание текстовых слоёв
│   ├── PagExportOverlay.kt      — вжигание PAG-титров
│   └── gl/                      — TransitionEffect, TailCaptureEffect, TransitionBridge, GlBlit, GlSources
├── captions/
│   ├── CaptionModels.kt         — CaptionItem, WordTimestamp, CaptionStyle, CaptionFont, WordAnimation
│   ├── CaptionPresets.kt        — 4 стиля (неон #FFE600 / #00FF66)
│   ├── CaptionOps.kt, CaptionSegmenter.kt, CaptionJson.kt, CaptionFonts.kt
│   ├── CaptionRenderer.kt       — единый рисовальщик субтитров (превью = экспорт)
│   ├── CaptionManager.kt        — владелец субтитров проекта (карточки, стиль, генерация)
│   └── asr/                     — AudioPcmExtractor, SpeechActivity, WhisperFrontend,
│                                  WhisperModelStore, WhisperTranscriber, AutoCaptionGenerator
├── text/                        — TextClip, TextTrack, TextJson, TextClipRenderer
├── pag/                         — PagTemplateStore, PagTitles
├── data/
│   ├── ProjectRepository.kt     — проекты: filesDir/projects/<id>.json (+ обложка .jpg)
│   ├── MediaRepository.kt       — запрос медиатеки устройства
│   ├── Thumbs.kt                — кэш миниатюр + MediaProbe (размер кадра, длительность аудио)
│   ├── Format.kt, PickedMediaInbox.kt
└── ui/
    ├── theme/                   — BaseColors, BaseTheme
    ├── home/, projects/, profile/, picker/
    └── editor/
        ├── EditorScreen.kt      — экран редактора (плеер, панели, экспорт)
        ├── EditorViewModel.kt   — состояние + TimelineActions + CanvasActions
        ├── TimelineView.kt      — многодорожечный таймлайн (Canvas): видео, текст, субтитры, аудио
        ├── CanvasTransformOverlay.kt — жесты на холсте (логика MultiTouchListener из PhotoEditor, MIT)
        ├── CaptionOverlay.kt, CaptionPanel.kt
        ├── TextOverlay.kt, TextEditorSheet.kt, PagTitleOverlay.kt
```

Ресурсы: шрифты Montserrat/Oswald/Rubik/Russo One/Pacifico; `assets/shaders` (44 перехода + превью webp);
`assets/asr/filters_vocab_multilingual.bin` (мел-фильтры и мультиязычный словарь Whisper).

Тесты (`app/src/test`): TimelineModelTest, KeyframeTrackTest (+ KeyframeModelTest, AudioTrackModelTest),
CaptionLogicTest, TextTrackTest, SpeechActivityTest.

## 2. TimelineController ↔ Media3

- **Модель** (`TimelineModel`) меняется мгновенно на главном потоке — это чистая арифметика.
- **Пересборка Composition** — на `Dispatchers.Default`; запросы склеиваются (Channel.CONFLATED),
  в плеер уходит только последняя версия. `setComposition` — строго на главном потоке.
- **CompositionPlayer** воспроизводит композицию; перемотка до готовности плеера откладывается в `pendingSeekMs`.
- **CompositionFactory.build()** (одна и та же для превью и экспорта):
  - основная дорожка → `EditedMediaItemSequence` (аудио+видео): обрезка — `ClippingConfiguration`,
    приведение к холсту — `Presentation.LAYOUT_SCALE_TO_FIT` (пропорции исходника не нарушаются),
    на стыках — `TailCaptureEffect` + `TransitionEffect` (GLSL);
  - аудиодорожка (музыка) → вторая **параллельная** `EditedMediaItemSequence` (только `TRACK_TYPE_AUDIO`,
    `setRemoveVideo(true)`): Media3 микширует её со звуком клипов и в превью, и в экспорте;
  - субтитры/текст/PAG — оверлеи уровня композиции (`OverlayEffect`), только в экспорте;
    в превью их рисует Compose поверх PlayerView.
- **Экспорт** — `VideoExportManager` (Transformer → MP4), поток `Flow<ExportState>`,
  при сбое — повтор в упрощённом режиме (без эффектов, меньшее разрешение).

## 3. Модели данных

- **Clip** — клип на шкале: `id, row (0 — основная, 1 — аудио), type (VIDEO/IMAGE/AUDIO/TEXT),
  startMs/endMs (на таймлайне), srcInMs/srcDurMs (в исходнике), uri`.
- **ClipTransform** — положение кадра на холсте: `x, y` (доли кадра), `scale`, `rotationDeg`.
- **Keyframe** — ключевой кадр трансформации: `timeMs` (от начала клипа!) + значения `x, y, scale, rotationDeg`.
  Хранятся в `TimelineModel.keyframes: Map<clipId, List<Keyframe>>`. Между соседними ключами — линейная
  интерполяция `V1 + (V2 - V1)·t` (`KeyframeTrack.at`); в превью/экспорте её считает `MatrixTransformation`
  на каждый кадр. UI: кнопка-ромбик в строке с undo/redo (только когда клип выбран), маркеры-ромбики
  на полоске клипа, автоключ при любом движении кадра пальцем (если у клипа уже есть ключи).
- **AudioTrack** — это `Clip` с `row = 1, type = AUDIO`: перетаскивание по времени, обрезка краёв
  (ручки у выбранного блока), магнит к соседям. Добавление — кнопка «Звук» → системный выбор `audio/*`.
- **CaptionItem / WordTimestamp** (= SubtitleSegment) — карточка субтитров + пословные тайминги;
  стиль — `CaptionStyle` (шрифт, цвета, анимация слова).
- **TextClip** — текстовый слой: текст, интервал `startMs + durationMs`, позиция/размер/поворот,
  опциональный PAG-шаблон.
- **Transition** — переход «в» клип `rightId` на стыке с `leftId`: `shaderId`, `durationMs`.

Сериализация таймлайна (формат V1, построчно): заголовок `V1`, строки клипов, `T` — переходы,
`X` — статические трансформации, `K` — ключевые кадры. Субтитры и тексты — JSON-поля в файле проекта.

## 4. Распознавание речи (автосубтитры)

- Модель **whisper-base** (мультиязычная, поддерживает русский), TFLite-конвертация из
  `moonshine-ai/openai-whisper` (тот же формат, что у nyadla-sys/whisper.tflite):
  вход `[1, 80, 3000] float32`, выход — токены `int32 [1, 448]`.
- `WhisperModelStore`: тихое скачивание (~126 МБ) в `filesDir/asr` при первой генерации,
  проверка размера и SHA-256, в UI — только индикатор «Создание субтитров...».
- `WhisperFrontend` — лог-мел-спектрограмма (как в эталоне), разбор токенов с мультиязычным словарём.
- `SpeechActivity` — участки с голосом и оценка таймингов слов (±~50 мс, настоящих word-timestamps нет).
- Рендеринг (`CaptionRenderer`): контур STROKE 2.5 dp (join/cap ROUND) + чистая заливка поверх +
  фиксированная тень (offset 2 dp, чёрный 40%). Без BlurMaskFilter и размытий.

## 5. Сборка и библиотеки

```bash
./gradlew assembleDebug      # debug-APK
./gradlew testDebugUnitTest  # юнит-тесты (чистый Kotlin, без устройства)
```

CI: `.github/workflows/build.yml` — сборка debug-APK и юнит-тесты на каждый пуш в main.

Зависимости: Compose BOM 2024.12.01, Media3 1.11.1 (transformer, effect, exoplayer, ui, common),
LiteRT 1.0.1 (TFLite), libpag 4.5.98, Navigation-Compose, Lifecycle. NDK нет.

Использованный открытый код (лицензии — в `licenses/NOTICE.txt`):
- логика жестов холста — PhotoEditor (MIT), `MultiTouchListener.java`;
- интерполяция ключевых кадров — Lottie (Apache 2.0), `animation/keyframe`;
- модель Whisper TFLite и формат словаря — moonshine-ai/openai-whisper / nyadla-sys/whisper.tflite (MIT);
- эталонные параметры фронтенда Whisper — openai/whisper, whisper.cpp.
