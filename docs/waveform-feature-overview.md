# Waveform Feature Overview (iOS → Android porting guide)

Коммиты: `4d9f0b16`, `0f877c62`, `03f423a3`, `692fbfe6`

---

## Архитектура waveform-фичи

### 1. Извлечение PCM-данных — `AudioWaveformHelper`

Файл: `deltachat-ios/Helper/AudioWaveformHelper.swift`

- Открывает аудиофайл через `AVAudioFile`, читает до **2M фреймов** в `AVAudioPCMBuffer` с float-данными.
- Все каналы усредняются в **моно** (среднее арифметическое абсолютных значений).
- Массив сжимается до **40 бакетов** методом **max-per-chunk** (берётся пик амплитуды в каждом сегменте).
- Результат **нормируется** так, чтобы самый высокий бар = 1.0.
- Работает в фоновом потоке (`DispatchQueue.global`), результат возвращается на главный поток.

> На Android аналог: `MediaCodec` / `AudioRecord` для декодирования + те же формулы downsampling.

---

### 2. Вью для отрисовки — `WaveformView`

Файл: `deltachat-ios/Chat/Views/WaveformView.swift`

- Custom `UIView` с `draw(_ rect:)` через `UIBezierPath`.
- Принимает `samples: [Float]` и `progress: Float` — оба сеттера вызывают `setNeedsDisplay()`.
- **40 вертикальных баров** с скруглёнными углами. Ширина бара = 60% слота, зазор 40%.
- Цвет: сыгранная часть (`progress * width`) — `tintColor`, остальное — 25% прозрачности.
- Пока `samples` пустой — показывает **placeholder**: все бары на высоте 25% (loading state).
- Жесты: `UITapGestureRecognizer` + `UIPanGestureRecognizer` → вызывают `seekAction: ((Float) -> Void)`.

> На Android аналог: кастомный `View` с `onDraw(canvas)` через `RectF` + `Paint`.

---

### 3. Контейнер ячейки — `AudioPlayerView`

Файл: `deltachat-ios/Chat/Views/AudioPlayerView.swift`

Layout слева направо:
```
[Play/Pause 45×45] — [WaveformView, height=32] — [DurationLabel]
```

- `setProgress(Float)` → `waveformView.progress`
- `setWaveform([Float])` → `waveformView.samples`
- `seekAction` пробрасывается напрямую в `waveformView.seekAction`

---

### 4. Кэш и координация — `AudioController`

Файл: `deltachat-ios/Chat/AudioController.swift`

- `waveformCache: [Int: [Float]]` — in-memory кэш по `messageId`.
- `getAudioWaveform(messageId:successHandler:)` — проверяет кэш, если нет — запускает `AudioWaveformHelper.extractSamples`.
- `seekAudio(messageId:progress:)` — устанавливает `player.currentTime = progress * player.duration` и сразу обновляет вью.
- Прогресс обновляется **таймером каждые 0.1 сек**: `Timer` → `setProgress` → `waveformView.progress` → `setNeedsDisplay`.
- При реюзе ячейки: `audioPlayerView.reset()` сбрасывает `progress = 0`, `samples = []`.

---

### 5. Связка с ячейкой — `AudioMessageCell`

Файл: `deltachat-ios/Chat/Views/Cells/AudioMessageCell.swift`

- При `update(msg:)` вызывает два делегатных колбека:
  - `getAudioDuration` → `audioPlayerView.setDuration`
  - `getAudioWaveform` → `audioPlayerView.setWaveform`
- `seekAction` ячейки → `delegate?.seekAudio(messageId:progress:)` → `AudioController.seekAudio`

---

## Флоу для Android

```
AudioCell.bind(msg)
  └─ AudioController.getWaveform(msgId)
       ├─ (cache hit) → cell.setWaveform(samples)
       └─ (cache miss) → декодировать PCM в фоне
            → downsample 40 max-per-chunk бакетов
            → normalize [0,1]
            → кэшировать
            → cell.setWaveform(samples) на main thread

WaveformView.onDraw(canvas)
  └─ 40 скруглённых RectF
     ├─ x < progress*width → accentColor
     └─ иначе → accentColor @ 25% alpha

WaveformView.onTouchEvent / GestureDetector
  └─ seekListener.onSeek(x / width)
       └─ player.seekTo(progress * duration)
```
