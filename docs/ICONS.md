# Иконки для замены — Alt Chat

## Иконка приложения (лаунчер)

Отображается на рабочем столе пользователя.

| Файл | Размер |
|---|---|
| `src/main/res/mipmap-mdpi/ic_launcher.png` | 48×48 px |
| `src/main/res/mipmap-hdpi/ic_launcher.png` | 72×72 px |
| `src/main/res/mipmap-xhdpi/ic_launcher.png` | 96×96 px |
| `src/main/res/mipmap-xxhdpi/ic_launcher.png` | 144×144 px |
| `src/main/res/mipmap-xxxhdpi/ic_launcher.png` | 192×192 px |

---

## Адаптивная иконка — foreground слой (Android 8+)

Логотип поверх фона. Фон — белый `#FFFFFF` (`src/main/res/values/ic_launcher_background.xml`).

| Файл | Размер |
|---|---|
| `src/main/res/mipmap-mdpi/ic_launcher_foreground.png` | 108×108 px |
| `src/main/res/mipmap-hdpi/ic_launcher_foreground.png` | 162×162 px |
| `src/main/res/mipmap-xhdpi/ic_launcher_foreground.png` | 216×216 px |
| `src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png` | 324×324 px |
| `src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png` | 432×432 px |

---

## Монохромная иконка (Android 13+)

Используется для themed icons (монохромный режим).

| Файл | Формат |
|---|---|
| `src/main/res/drawable/ic_launcher_foreground_monochrome.xml` | Vector XML |

---

## Иконка уведомлений (статус-бар)

Белая иконка на прозрачном фоне.

| Файл | Размер |
|---|---|
| `src/main/res/drawable-mdpi/ic_notifications_white_24dp.png` | 24×24 px |
| `src/main/res/drawable-hdpi/ic_notifications_white_24dp.png` | 36×36 px |
| `src/main/res/drawable-xhdpi/ic_notifications_white_24dp.png` | 48×48 px |
| `src/main/res/drawable-xxhdpi/ic_notifications_white_24dp.png` | 72×72 px |
| `src/main/res/drawable-xxxhdpi/ic_notifications_white_24dp.png` | 96×96 px |

---

## Иконки уведомлений (статус-бар)

> Должны быть **белыми на прозрачном фоне** (силуэт), иначе Android покажет серый квадрат.

### `icon_notification` — иконка новых сообщений

| Файл | Плотность |
|---|---|
| `src/main/res/drawable-mdpi/icon_notification.png` | 24×24 px |
| `src/main/res/drawable-hdpi/icon_notification.png` | 36×36 px |
| `src/main/res/drawable-xhdpi/icon_notification.png` | 48×48 px |
| `src/main/res/drawable-xxhdpi/icon_notification.png` | 72×72 px |

### `notification_permanent` — постоянная иконка фонового сервиса

| Файл | Плотность |
|---|---|
| `src/main/res/drawable-mdpi/notification_permanent.png` | 24×24 px |
| `src/main/res/drawable-hdpi/notification_permanent.png` | 36×36 px |
| `src/main/res/drawable-xhdpi/notification_permanent.png` | 48×48 px |
| `src/main/res/drawable-xxhdpi/notification_permanent.png` | 72×72 px |

---

## Быстрый способ сгенерировать все размеры

В Android Studio:
1. Правая кнопка на папке `res` → **New → Image Asset**
2. Выбрать свой исходник (SVG или PNG 1024×1024)
3. Studio сам сгенерирует все плотности для `ic_launcher` и `ic_launcher_foreground`
