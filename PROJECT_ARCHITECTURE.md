# BASE — архитектура проекта

Android-видеоредактор: Kotlin, Jetpack Compose, Media3 (Transformer/CompositionPlayer), sherpa-onnx (распознавание речи), libPAG.
minSdk 29, compileSdk 36, Kotlin 2.2.20, AGP 8.9.2, Gradle 8.11.1, Compose BOM 2024.12.01, Media3 1.11.1.

## 1. Структура пакетов и файлов

```
app/src/main/java/com/base/editor/
├── MainActivity.kt              — единственная Activity
├── BaseApp.kt                   — навигация: main, picker/new/{tab}, picker/add/{projectId}, editor/{projectId}
├── MainScreen.kt                — вкладки «Дом», «Проекты», «Я»
├── AppViewModel.kt
├── core/
│   ├── Models.kt                — MediaType, Clip, Transition, ClipTransform, Keyframe, CropRect, PickedMedia, ProjectMeta
│   ├── CanvasFormat.kt          — пресеты формата холста: Оригинал, 9:16, 16:9, 1:1, 4:5, 3:4
│   └── TransitionCatalog.kt     — каталог 44 GLSL-переходов (assets/shaders/manifest.json)
├── domain/                      — чистый Kotlin без Android (покрыт юнит-тестами)
│   ├── TimelineModel.kt         — дорожки, клипы, переходы, трансформации, ключевые кадры, кадрирование, undo/redo, сериализация V1
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
│   ├── CaptionLanguage.kt       — Русский (ru) / Английский (en) / Авто
│   ├── CaptionPresets.kt        — 4 стиля (неон #FFE600 / #00FF66)
│   ├── CaptionOps.kt, CaptionSegmenter.kt, CaptionJson.kt, CaptionFonts.kt
│   ├── CaptionRenderer.kt       — единый рисовальщик субтитров (превью = экспорт)
│   ├── CaptionManager.kt        — владелец субтитров проекта (карточки, стиль, генерация)
│   └── asr/                     — AudioPcmExtractor, SpeechActivity, SpeechLanguage (ru/en), SpeechModelStore (загрузка модели),
│                                  AutoCaptionGenerator (Vosk)
├── text/                        — TextClip (+шрифт, контур, тень, стиль, анимация), TextTrack, TextJson, TextClipRenderer,
│                                  TextFonts (9 вшитых шрифтов с кириллицей), TextStyles (пресеты + TextAnimator)
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
        ├── CropDialog.kt        — кадрирование: рамка, сетка 3×3, угловые маркеры, пресеты пропорций
        ├── CaptionOverlay.kt, CaptionPanel.kt (+ переключатель языка)
        ├── TextOverlay.kt, PagTitleOverlay.kt
        ├── TextEditorSheet.kt   — TextInputSheet (строка ввода) и TextToolbar (панель текста в слоте инструментов)
        └── ExportSheet.kt       — лист экспорта: дискретные слайдеры разрешения и FPS, оценка веса
    └── theme/                   — Theme.kt (цвета, Inter), Motion.kt (токены движения, pressable, staggeredEnter)
```

Ресурсы: шрифты Montserrat/Oswald/Rubik/Russo One/Pacifico; `assets/shaders` (44 перехода + превью webp).
Модель распознавания в APK не входит — скачивается при первом запросе субтитров.

Тесты (`app/src/test`): TimelineModelTest, KeyframeTrackTest, CaptionLogicTest, WordTimingsTest,
TextTrackTest, SpeechActivityTest.

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
  - кадрирование клипа — `androidx.media3.effect.Crop` (границы в NDC) ставится ПЕРВЫМ в цепочке эффектов,
    до вписывания в холст, поэтому `Presentation` вписывает уже обрезанный кадр;
  - положение кадра (`ClipTransform`/ключи) — `MatrixTransformation`. В превью она читает актуальное состояние
    модели на каждом кадре (`CompositionRequest.liveTransforms`), поэтому правка положения и ключей НЕ пересобирает
    композицию: `TimelineController.commitTransformEdit()` перерисовывает текущий кадр перемоткой в ту же позицию.
    В экспорте берётся снимок состояния;
  - экспорт: `ExportRequest.fps` (24/30/60). Для 24 и 30 — `FrameDropEffect` на уровне композиции; для 60 кадры
    не добавляются (частота не выше исходной), у фото `setFrameRate` берётся из выбора;
  - субтитры/текст/PAG — оверлеи уровня композиции (`OverlayEffect`), только в экспорте;
    в превью их рисует Compose поверх PlayerView.
- **Перемотка** (`scrubTo`/`scrubEnd`): курсор в UI двигается сразу, в плеер уходит не больше одного `seekTo` за 33 мс
  и всегда с последней позицией; промежуточные цели отбрасываются. `play()` работает из IDLE (prepare), ENDED
  (с начала) и во время подготовки (отложенный старт); состояние буферизации — `isBuffering`.
- **Формат холста** — `CanvasFormat` → `aspect` в `EditorViewModel` → `CompositionFactory.canvasFor`; хранится в проекте
  (поле `format`). Окно предпросмотра меняет пропорции (`displayAspect`) только после применения новой композиции,
  с анимацией; содержимое обрезается `clipToBounds()`.
- **Экспорт** — `VideoExportManager` (Transformer → MP4), поток `Flow<ExportState>`,
  при сбое — повтор в упрощённом режиме (без эффектов, меньшее разрешение).

### Взаимодействие с дорожками и экран редактора

- Выделение одно на редактор: клип/аудио (`selectedId`), текстовый слой (`canvasTextId`) или карточка субтитров
  (`selectedCaptionId`). Тап по блоку выделяет его, в слоте инструментов под таймлайном (`ToolMode`) появляется своя
  панель; таймлайн остаётся живым. Блок текста/субтитров двигается только долгим нажатием по уже выделенному блоку;
  у выделенного блока есть ручки обрезки, «Разделить» режет по курсору (текст, субтитры, аудио).
- Большие панели (субтитры, переходы, формат) показываются поверх фиксированной области с fade-появлением.
- `EditorScreen` разбит на `PreviewStage`, `TransportRow`, `TimelineHost`: позиция курсора читается только в них,
  поэтому перемотка не перекомпонует весь экран. Рамка превью берёт пропорции из `canvasAspect` мгновенно; пока
  плеер не показал композицию нового формата (`displayAspect`), картинка заполняет рамку (ZOOM).
- Перемотка: `scrubTo` троттлит seek (33 мс), а `ScrubFrames` показывает кадр под курсором напрямую из файла
  (MediaMetadataRetriever), пока плеер на паузе догоняет позицию.

### Дизайн и движение

- `Theme.kt`: тёмная гамма и вторичный текст по рекомендациям ui-ux-pro-max, Inter на всё приложение.
  Акцент — бренд-цвет Cyan; рекомендованный навыком розовый лежит в `BaseColors.AccentPink`.
- `Motion.kt`: `BaseMotion` (кривая ease-out `0.23, 1, 0.32, 1`, длительности ≤ 220 мс), `Modifier.pressable`
  (сжатие 0.96 и затемнение при нажатии, ставится первым в цепочке), `staggeredEnter` (каскад первых 10 элементов
  списка), учёт системного отключения анимаций.

## 3. Модели данных

- **Clip** — клип на шкале: `id, row (0 — основная, 1 — аудио), type (VIDEO/IMAGE/AUDIO/TEXT),
  startMs/endMs (на таймлайне), srcInMs/srcDurMs (в исходнике), uri`.
- **ClipTransform** — положение кадра на холсте: `x, y` (доли кадра), `scale`, `rotationDeg`.
- **Keyframe** — ключевой кадр трансформации: `timeMs` (от начала клипа!) + значения `x, y, scale, rotationDeg`.
  Хранятся в `TimelineModel.keyframes: Map<clipId, List<Keyframe>>`. Между соседними ключами — линейная
  интерполяция `V1 + (V2 - V1)·t` (`KeyframeTrack.at`); в превью/экспорте её считает `MatrixTransformation`
  на каждый кадр. UI: кнопка-ромбик в строке с undo/redo (только когда клип выбран), маркеры-ромбики
  на полоске клипа, автоключ при любом движении кадра пальцем (если у клипа уже есть ключи).
- **CropRect** — прямоугольник кадрирования в долях исходного кадра `left, top, right, bottom` (0..1),
  хранится в `TimelineModel.crops: Map<clipId, CropRect>`; полный прямоугольник = «нет кадрирования».
- **CanvasFormat** — пресет холста; `aspect = null` у «Оригинал» (пропорции первого клипа основной дорожки).
- **AudioTrack** — это `Clip` с `row = 1, type = AUDIO`: перетаскивание по времени, обрезка краёв
  (ручки у выбранного блока), магнит к соседям. Добавление — кнопка «Звук» → системный выбор `audio/*`.
- **CaptionItem / WordTimestamp** (= SubtitleSegment) — карточка субтитров + пословные тайминги;
  стиль — `CaptionStyle` (шрифт, цвета, анимация слова).
- **TextClip** — текстовый слой: текст, интервал `startMs + durationMs`, позиция/размер/поворот, цвета,
  шрифт `fontId` (из `TextFonts`), опциональный PAG-шаблон. Редактирование: тап по тексту → нижняя панель
  «Текст / Разделить / Шрифты / Стиль-Цвет / Удалить»; размер, поворот и положение — жестами на холсте.
- **Transition** — переход «в» клип `rightId` на стыке с `leftId`: `shaderId`, `durationMs`.

Сериализация таймлайна (формат V1, построчно): заголовок `V1`, строки клипов, `T` — переходы,
`X` — статические трансформации, `K` — ключевые кадры, `C` — кадрирование (`C\tid\tl\tt\tr\tb`). Субтитры и тексты — JSON-поля в файле проекта.

## 4. Распознавание речи (автосубтитры)

- Движок — **Vosk** (офлайн, Apache 2.0) с компактными моделями: русская `vosk-model-small-ru-0.22` (~45 МБ) и английская
  `vosk-model-small-en-us-0.15` (~40 МБ). Язык выбирается вручную в панели «Субтитры» (чипы «Русский» / «English»);
  Vosk сразу отдаёт пословные тайминги, поэтому подсветка караоке идёт по настоящим меткам, без подгонки.
- `SpeechModelStore` скачивает архив выбранного языка в `filesDir/speech-models` (с прогрессом и отменой), распаковывает
  с защитой от выхода за каталог и отмечает готовность файлом `.ready`; дальше всё работает офлайн.
- `AutoCaptionGenerator`: для каждого видеоклипа основной дорожки `AudioPcmExtractor.stream` отдаёт PCM 16 кГц с учётом
  обрезки → `Recognizer` с `setWords(true)` → время слов клипа переводится во время проекта → `CaptionSegmenter` собирает карточки.
- Состояния генерации: `Downloading(fraction)` → `Recognizing(fraction)` → `Idle` / `Failed`.
- Рендеринг (`CaptionRenderer`): контур STROKE 2.5 dp (join/cap ROUND) + чистая заливка поверх + фиксированная тень,
  без BlurMaskFilter.

## 5. Сборка и библиотеки

```bash
./gradlew assembleDebug      # debug-APK
./gradlew testDebugUnitTest  # юнит-тесты (чистый Kotlin, без устройства)
```

CI: `.github/workflows/build.yml` — на каждый пуш в main: debug-APK, юнит-тесты (`testDebugUnitTest`) и подписанный AAB. Ошибки читаются через аннотации запуска.


Зависимости: Compose BOM 2024.12.01, Media3 1.11.1 (transformer, effect, exoplayer, ui, common),
Vosk 0.3.47 + JNA, libpag 4.5.98, Navigation-Compose, Lifecycle. NDK нет.

Использованный открытый код (лицензии — в `licenses/NOTICE.txt`):
- логика жестов холста — PhotoEditor (MIT), `MultiTouchListener.java`;
- интерполяция ключевых кадров — Lottie (Apache 2.0), `animation/keyframe`;
- распознавание речи — k2-fsa/sherpa-onnx (Apache 2.0), как внешняя зависимость без изменений;
- модель Whisper (OpenAI, MIT) в ONNX int8 — csukuangfj/sherpa-onnx-whisper-base.
