# Project Structure — Описание файлов

## Корень проекта

| Файл | Назначение |
|---|---|
| `build.gradle` | Gradle-скрипт сборки Android-приложения (зависимости, flavors, версии) |
| `settings.gradle` | Объявление модулей Gradle-проекта |
| `gradle.properties` | Глобальные переменные Gradle (версии SDK, JVM флаги) |
| `local.properties` | Локальные пути (SDK, NDK) — не коммитится в git |
| `google-services.json` | Firebase конфиг для gplay flavor (Push Notifications) |
| `gradlew` / `gradlew.bat` | Gradle wrapper скрипты |
| `proguard-rules.pro` | Правила ProGuard/R8 для минификации релизной сборки |
| `test-proguard-rules.pro` | ProGuard правила для тестовых сборок |
| `Dockerfile` | Docker-образ для воспроизводимой сборки в CI |
| `flake.nix` | Nix-флейк для воспроизводимой среды разработки |
| `README.md` | Основная документация проекта |
| `BUILDING.md` | Инструкции по сборке (NDK, Rust toolchain) |
| `CHANGELOG.md` | История изменений по версиям |
| `CONTRIBUTING.md` | Руководство для контрибьюторов |
| `ICONS.md` | Документация по иконкам приложения |
| `RELEASE.md` | Инструкции по выпуску релиза |
| `android-architecture.md` | Архитектура Android-приложения (слои, потоки вызовов) |
| `backend-architecture.md` | Архитектура backend user directory сервиса |
| `main_points.md` | Рабочие заметки и исследования (invite-ссылки, core-изменения) |
| `standards.md` | Ссылка на standards.md в deltachat-core-rust |

---

## `scripts/`

Вспомогательные shell-скрипты для разработки и CI.

| Файл | Назначение |
|---|---|
| `ndk-make.sh` | Сборка нативной `.so` библиотеки через Android NDK |
| `update-core.sh` | Обновление submodule `deltachat-core-rust` до новой версии |
| `update-rpc-bindings.sh` | Регенерация автогенерированных RPC биндингов (`Rpc.java`, `types/*`) |
| `install-toolchains.sh` | Установка Rust cross-compilation toolchains для Android ABI |
| `rust-toolchain` | Pinned версия Rust toolchain (читается `rustup`) |
| `clean-core.sh` | Очищает артефакты сборки `deltachat-core-rust` |
| `add-language.sh` | Добавляет новый язык в конфиги Transifex |
| `check-translations.sh` | Проверяет полноту переводов |
| `grep-string.sh` | Поиск строки во всех исходниках |
| `codespell.sh` | Запуск проверки орфографии в коде |
| `create-local-help.sh` | Создаёт локальную копию справочной документации |
| `tx-pull-source.sh` | Скачивает исходные строки с Transifex |
| `tx-pull-translations.sh` | Скачивает переводы с Transifex |
| `tx-push-source.sh` | Загружает исходные строки на Transifex |
| `tx-update-changed-sources.sh` | Обновляет только изменённые ресурсы на Transifex |
| `upload-beta.sh` | Загружает beta-сборку в маркет |
| `upload-release.sh` | Загружает release-сборку в маркет |

---

## `jni/`

JNI и нативный код — мост между Java и Rust.

| Файл/Папка | Назначение |
|---|---|
| `dc_wrapper.c` | JNI glue: маппинг Java-методов `DcContext`/`DcAccounts` на Rust FFI (`deltachat.h`) |
| `Android.mk` | NDK Makefile: перечень C-файлов и флаги компилятора |
| `Application.mk` | NDK глобальные настройки (поддерживаемые ABI, C++ стандарт) |
| `deltachat-core-rust/` | Git submodule: Rust core (IMAP, SMTP, e2e-шифрование, база данных) |
| `arm64-v8a/`, `armeabi-v7a/`, `x86/`, `x86_64/` | Промежуточные объектные файлы нативной сборки под каждый ABI |

---

## `libs/`

Скомпилированные нативные библиотеки (`libdeltachat.so`) под каждый ABI — подключаются в итоговый APK.

---

## `src/main/java/com/b44t/messenger/` — JNI Bridge Layer

Тонкие Java-обёртки над Rust-структурами. Хранят `long *CPtr` (C-указатели).

| Файл | Назначение |
|---|---|
| `DcAccounts.java` | Менеджер всех аккаунтов: `startIo()`, `stopIo()`, `backgroundFetch()`, `selectAccount()` |
| `DcContext.java` | Обёртка над одним аккаунтом: константы событий, legacy API (getChat, getMsg...) |
| `DcChat.java` | Данные чата: имя, тип, флаги mute/archive/protect |
| `DcChatlist.java` | Список чатов от core (с фильтром/поиском) |
| `DcContact.java` | Данные контакта: имя, email, аватар, верификация |
| `DcMsg.java` | Данные сообщения: текст, тип, вложение, статус доставки |
| `DcEvent.java` | Событие от core (id, data1, data2, accountId) |
| `DcEventChannel.java` | Многопоточный канал событий для `DcAccounts` |
| `DcEventEmitter.java` | Legacy emitter событий (один аккаунт) |
| `DcJsonrpcInstance.java` | Нативный JSON-RPC endpoint: `request()` / `getNextResponse()` |
| `DcLot.java` | Возвращаемый тип нескольких `DcContext` методов (резюме чата/контакта) |
| `DcMediaGalleryElement.java` | Элемент медиа-галереи (сообщение с вложением) |
| `DcProvider.java` | Информация о почтовом провайдере (настройки IMAP/SMTP) |
| `DcBackupProvider.java` | ContentProvider для стриминга бэкапа через QR/WiFi |
| `FFITransport.java` | Реализует `Rpc.Transport` через `DcJsonrpcInstance` (FFI) |

---

## `src/main/java/chat/delta/rpc/` — RPC Layer

Автогенерированный JSON-RPC клиент (**не редактировать вручную**).

| Файл | Назначение |
|---|---|
| `Rpc.java` | Все методы JSON-RPC API core (autogenerated): `sendMsg`, `getChatMsgs`, `addAccount`… |
| `BaseTransport.java` | Базовая реализация транспорта: Future-based очередь запросов, worker-thread |
| `RpcException.java` | Исключение при RPC-вызове (ошибка от core) |

### `chat/delta/rpc/types/` — RPC DTO (autogenerated)

| Файл | Назначение |
|---|---|
| `Account.java` | DTO аккаунта (id, addr, displayName, profileImage) |
| `BasicChat.java` | Базовые поля чата (id, name, type) |
| `CallInfo.java` | Информация о звонке (URL, hash, video) |
| `CallState.java` | Состояние звонка (enum: idle, ringing, active…) |
| `ChatListItemFetchResult.java` | Результат загрузки одного элемента списка чатов |
| `ChatType.java` | Тип чата (enum: single, group, mailingList, broadcast) |
| `ChatVisibility.java` | Видимость чата (enum: normal, archived, pinned) |
| `Contact.java` | DTO контакта (id, addr, displayName, color, верификация) |
| `DownloadState.java` | Статус загрузки вложения (enum: done, inProgress, failure…) |
| `EnteredCertificateChecks.java` | Настройки проверки SSL-сертификата сервера |
| `EnteredLoginParam.java` | Параметры входа (email, пароль, IMAP/SMTP настройки) |
| `EphemeralTimer.java` | Таймер самоудаления сообщений |
| `Event.java` | RPC-событие от core (accountId, type, data) |
| `EventType.java` | Тип события (enum: msgsChanged, incomingMsg, configureProgress…) |
| `FullChat.java` | Полные данные чата (все поля включая members, ephemeralTimer) |
| `HttpResponse.java` | HTTP-ответ (для webxdc fetch-запросов) |
| `Location.java` | GPS-локация (lat, lon, accuracy, timestamp) |
| `Message.java` | DTO сообщения (id, text, viewtype, timestamp, состояние) |
| `MessageData.java` | Данные сообщения (текст, вложения, quote) |
| `MessageInfo.java` | Расширенная информация (delivery log, причина ошибки) |
| `MessageListItem.java` | Элемент списка сообщений (DayMarker или Message) |
| `MessageLoadResult.java` | Результат загрузки пачки сообщений |
| `MessageNotificationInfo.java` | Данные для построения push-уведомления |
| `MessageQuote.java` | Цитируемое сообщение (id, текст, отправитель) |
| `MessageReadReceipt.java` | Read receipt (кто и когда прочитал) |
| `MessageSearchResult.java` | Результат поиска: chatId, msgId, сниппет |
| `MuteDuration.java` | Длительность mute чата (секунды или бесконечно) |
| `NotifyState.java` | Состояние уведомлений чата (enum: notify, muted, mentionOnly) |
| `Pair.java` | Параметрическая пара значений |
| `ProviderInfo.java` | Информация о почтовом провайдере (status, hints, link) |
| `Qr.java` | Распарсенный QR-код (тип + данные: addr, fingerprint, group…) |
| `Reaction.java` | Одна реакция (эмодзи + contactId) |
| `Reactions.java` | Все реакции на сообщение |
| `SecurejoinSource.java` | Источник SecureJoin (QR scan vs invite link) |
| `SecurejoinUiPath.java` | UI-путь при SecureJoin (show/scan) |
| `Socket.java` | Тип соединения (enum: auto, SSL, STARTTLS, plain) |
| `SystemMessageType.java` | Тип системного сообщения (enum: memberAdded, groupNameChanged…) |
| `VcardContact.java` | Контакт из vCard (addr, displayName, PGP-ключ, фото в base64) |
| `Viewtype.java` | Тип view сообщения (enum: text, image, gif, audio, video, webxdc…) |
| `WebxdcMessageInfo.java` | Информация о webxdc-сообщении (name, icon, summary) |

### `chat/delta/util/`

| Файл | Назначение |
|---|---|
| `ListenableFuture.java` | Интерфейс Future с callback завершения |
| `SettableFuture.java` | Future, которому можно присвоить значение извне (используется в `BaseTransport`) |

---

## `src/main/java/com/codewaves/stickyheadergrid/`

| Файл | Назначение |
|---|---|
| `StickyHeaderGridAdapter.java` | Базовый адаптер для grid с прилипающими секционными заголовками |
| `StickyHeaderGridLayoutManager.java` | LayoutManager для grid с прилипающими заголовками (используется в медиа-галерее) |

---

## `src/main/java/org/thoughtcrime/securesms/` — основной пакет приложения

### Корень пакета

| Файл | Назначение |
|---|---|
| `ApplicationContext.java` | Application singleton: инициализирует `DcAccounts`, `Rpc`, `DcEventCenter`, `NotificationCenter` |
| `ApplicationPreferencesActivity.java` | Главный экран настроек приложения |
| `AllMediaActivity.java` | Экран "все медиа чата" (галерея + документы) |
| `AllMediaDocumentsAdapter.java` | Адаптер списка документов в AllMedia |
| `AllMediaDocumentsFragment.java` | Фрагмент вкладки документов в AllMedia |
| `AllMediaGalleryAdapter.java` | Адаптер сетки изображений/видео в AllMedia |
| `AllMediaGalleryFragment.java` | Фрагмент вкладки галереи в AllMedia |
| `AttachContactActivity.java` | Выбор контакта для прикрепления к сообщению (vCard) |
| `BaseActionBarActivity.java` | Базовый класс Activity с ActionBar и динамической темой |
| `BaseConversationItem.java` | Базовый ViewHolder пузыря сообщения |
| `BaseConversationListAdapter.java` | Базовый адаптер списка чатов |
| `BaseConversationListFragment.java` | Базовый фрагмент для списков чатов |
| `BindableConversationItem.java` | Интерфейс биндинга данных сообщения в View |
| `BindableConversationListItem.java` | Интерфейс биндинга данных чата в элемент списка |
| `BlockedContactsActivity.java` | Список заблокированных контактов |
| `ConnectivityActivity.java` | Экран статуса подключения к почтовому серверу |
| `ContactMultiSelectionActivity.java` | Activity множественного выбора контактов |
| `ContactSelectionActivity.java` | Activity одиночного выбора контакта |
| `ContactSelectionListFragment.java` | Фрагмент с поиском и списком контактов |
| `ConversationActivity.java` | Главный экран чата: поле ввода + `ConversationFragment` |
| `ConversationAdapter.java` | RecyclerView адаптер списка сообщений чата |
| `ConversationFragment.java` | Фрагмент с RecyclerView сообщений |
| `ConversationItem.java` | ViewHolder пузыря обычного сообщения (текст/медиа) |
| `ConversationItemSwipeCallback.java` | ItemTouchHelper callback для swipe-to-reply жеста |
| `ConversationItemTouchListener.java` | Touch listener для контекстного меню по долгому нажатию |
| `ConversationListActivity.java` | Главный экран приложения: список чатов |
| `ConversationListAdapter.java` | Адаптер элементов списка чатов |
| `ConversationListArchiveActivity.java` | Список архивированных чатов |
| `ConversationListFragment.java` | Фрагмент основного списка чатов |
| `ConversationListItem.java` | ViewHolder элемента списка чатов (аватар + превью + время) |
| `ConversationListItemInboxZero.java` | View "нет новых сообщений" в списке |
| `ConversationListRelayingActivity.java` | Список чатов при выборе адресата для relay-пересылки |
| `ConversationSwipeAnimationHelper.java` | Анимация при свайпе сообщения для reply |
| `ConversationTitleView.java` | View заголовка чата (имя, аватар, онлайн/статус) |
| `ConversationUpdateItem.java` | ViewHolder системных сообщений в чате (member added, etc.) |
| `CreateProfileActivity.java` | Экран создания/редактирования профиля (имя, аватар) |
| `DummyActivity.java` | Пустая Activity-заглушка для ContentProvider без UI |
| `EphemeralMessagesDialog.java` | Диалог настройки таймера самоудаления сообщений |
| `FullMsgActivity.java` | Полноэкранный просмотр длинного текста сообщения |
| `GroupCreateActivity.java` | Создание и редактирование групп/каналов |
| `InstantOnboardingActivity.java` | Быстрый онбординг: QR-сканирование или ввод email |
| `LocalHelpActivity.java` | Встроенная справка (WebView с локальными HTML) |
| `LogViewActivity.java` | Просмотр системных логов DeltaChat |
| `LogViewFragment.java` | Фрагмент с текстом логов |
| `MediaPreviewActivity.java` | Полноэкранный просмотр медиафайлов (изображения/видео) |
| `MessageSelectorFragment.java` | Фрагмент режима выбора нескольких сообщений (multi-select) |
| `MuteDialog.java` | Диалог выбора длительности mute чата |
| `NewConversationActivity.java` | Экран начала новой беседы (выбор контакта) |
| `PassphraseRequiredActionBarActivity.java` | Базовый класс Activity, требующий аутентификации приложения |
| `ProfileActivity.java` | Просмотр профиля контакта или группы |
| `ProfileAdapter.java` | Адаптер элементов экрана профиля |
| `ProfileAvatarItem.java` | View item аватара в профиле |
| `ProfileFragment.java` | Фрагмент с деталями профиля (email, ключ, группы) |
| `ProfileStatusItem.java` | View item статуса/биографии в профиле |
| `ProfileTextItem.java` | View item текстового поля профиля |
| `ResolveMediaTask.java` | AsyncTask разрезолвинга content URI медиа перед отправкой |
| `SetStartingPositionLinearLayoutManager.java` | LinearLayoutManager, начинающий со стартовой позиции |
| `ShareActivity.java` | Обработчик системного Intent.ACTION_SEND (шаринг из других приложений) |
| `ShareLocationDialog.java` | Диалог отправки геолокации |
| `StatsSending.java` | Логика отправки анонимной статистики использования |
| `TransportOption.java` | Модель варианта отправки (текст / файл / GIF / etc.) |
| `TransportOptions.java` | Набор доступных `TransportOption` |
| `TransportOptionsAdapter.java` | Адаптер выпадающего меню опций транспорта |
| `TransportOptionsPopup.java` | Попап выбора типа вложения/транспорта |
| `Unbindable.java` | Интерфейс для сброса биндинга View (cleanup) |
| `WebViewActivity.java` | Базовый Activity с настроенным WebView (для calls и webxdc) |
| `WebxdcActivity.java` | Activity с WebView для запуска webxdc-приложений (JS bridge) |
| `WebxdcStoreActivity.java` | Встроенный магазин webxdc-приложений |
| `WelcomeActivity.java` | Экран приветствия при первом запуске |

---

### `accounts/`

| Файл | Назначение |
|---|---|
| `AccountSelectionListAdapter.java` | Адаптер списка аккаунтов для переключения |
| `AccountSelectionListFragment.java` | Фрагмент выбора активного аккаунта |
| `AccountSelectionListItem.java` | ViewHolder элемента списка аккаунтов |

---

### `animation/`

| Файл | Назначение |
|---|---|
| `AnimationCompleteListener.java` | Утилитный интерфейс callback завершения анимации |

---

### `attachments/`

| Файл | Назначение |
|---|---|
| `Attachment.java` | Интерфейс вложения (URI, MIME-тип, размер, состояние) |
| `DcAttachment.java` | Вложение из `DcMsg` (уже полученный файл в core) |
| `UriAttachment.java` | Вложение из локального URI устройства (для отправки) |

---

### `audio/`

| Файл | Назначение |
|---|---|
| `AudioCodec.java` | Enum кодеков аудио (AAC / OGG Opus) |
| `AudioRecorder.java` | Запись голосовых сообщений через `MediaRecorder` |

---

### `calls/`

| Файл | Назначение |
|---|---|
| `CallActivity.java` | WebRTC-звонок: WebView + JS-интерфейс, слушатель `DcEvent` звонков |
| `CallUtil.java` | Утилиты звонков: проверка поддержки, построение Intent запуска |

---

### `components/`

Переиспользуемые кастомные UI-компоненты.

| Файл | Назначение |
|---|---|
| `AnimatingToggle.java` | View с анимированным переключением между состояниями |
| `AttachmentTypeSelector.java` | Диалог/меню выбора типа вложения |
| `AvatarImageView.java` | ImageView для отображения аватара контакта/группы |
| `AvatarSelector.java` | Диалог выбора, съёмки или удаления аватара |
| `AvatarView.java` | Составной View аватара с буквенным плейсхолдером |
| `BorderlessImageView.java` | ImageView без фоновой рамки |
| `CallItemView.java` | View элемента звонка в истории чата |
| `CircleColorImageView.java` | Круглый ImageView с цветным фоном |
| `ComposeText.java` | EditText поля ввода сообщения (undo/redo, paste) |
| `ContactFilterToolbar.java` | Toolbar с полем фильтрации списка контактов |
| `ControllableViewPager.java` | ViewPager с программным включением/выключением свайпа |
| `ConversationItemFooter.java` | Подвал пузыря: время, статус доставки, реакции |
| `ConversationItemThumbnail.java` | Превью вложения внутри пузыря сообщения |
| `CornerMask.java` | Маска скруглённых углов для произвольного View |
| `DeliveryStatusView.java` | Иконка статуса доставки (отправлено / доставлено / прочитано) |
| `DocumentView.java` | View документа в пузыре (иконка + имя + размер) |
| `FromTextView.java` | TextView имени отправителя в группе |
| `GlideDrawableListeningTarget.java` | Glide Target с callback завершения загрузки изображения |
| `HidingLinearLayout.java` | LinearLayout с анимированным показом/скрытием |
| `InputAwareLayout.java` | Layout, отслеживающий состояние системной клавиатуры |
| `InputPanel.java` | Панель ввода: `ComposeText` + кнопки вложения/записи/отправки |
| `KeyboardAwareLinearLayout.java` | LinearLayout, реагирующий на изменение высоты клавиатуры |
| `MediaView.java` | View для медиа-контента (изображение или видео) |
| `MicrophoneRecorderView.java` | View записи голосового сообщения с анимацией микрофона |
| `QuoteView.java` | View цитируемого сообщения внутри ввода и пузыря |
| `RecentPhotoViewRail.java` | Горизонтальный rail последних фотографий из галереи |
| `RemovableEditableMediaView.java` | View прикреплённого вложения с кнопкой удаления |
| `ScaleStableImageView.java` | ImageView, сохраняющий масштаб/позицию при смене контента |
| `SearchToolbar.java` | Toolbar с полем поиска |
| `SendButton.java` | Кнопка отправки с переключением send/микрофон |
| `ShapeScrim.java` | Полупрозрачный затемняющий скрим для MediaPreview |
| `SquareFrameLayout.java` | FrameLayout с равными шириной и высотой |
| `SwitchPreferenceCompat.java` | Preference со Switch-виджетом (compat) |
| `ThumbnailView.java` | Превью изображения/видео с индикатором загрузки |
| `VcardView.java` | View визитной карточки (vCard) в сообщении |
| `WebxdcView.java` | View превью webxdc-приложения в пузыре |
| `ZoomingImageView.java` | ImageView с поддержкой pinch-to-zoom и pan |
| `audioplay/AudioPlaybackState.java` | LiveData состояния воспроизведения аудио |
| `audioplay/AudioPlaybackViewModel.java` | ViewModel управления аудиоплеером (media3) |
| `audioplay/AudioView.java` | View плеера аудио-сообщения (play/pause, прогресс, скорость) |
| `emoji/AutoScaledEmojiTextView.java` | TextView с автомасштабированием emoji до правильного размера |
| `emoji/EmojiToggle.java` | Кнопка переключения emoji-клавиатуры / системной клавиатуры |
| `emoji/MediaKeyboard.java` | Клавиатура emoji и стикеров |
| `emoji/StickerPickerView.java` | View выбора стикера |
| `recyclerview/DeleteItemAnimator.java` | Аниматор удаления элемента из RecyclerView |
| `registration/PulsingFloatingActionButton.java` | FAB с пульсирующей анимацией |
| `reminder/DozeReminder.java` | Напоминание добавить приложение в исключения Doze-режима |
| `subsampling/AttachmentBitmapDecoder.java` | Декодер bitmap для `SubsamplingScaleImageView` |
| `subsampling/AttachmentRegionDecoder.java` | Region-декодер для больших изображений (tiles) |
| `viewpager/ExtendedOnPageChangedListener.java` | Расширенный listener смены страницы ViewPager |
| `viewpager/HackyViewPager.java` | ViewPager с фиксом краша при свайпе поверх ZoomImageView |

---

### `connect/`

Управление подключением, аккаунтами и событиями core.

| Файл | Назначение |
|---|---|
| `AccountManager.java` | Переключение и миграция аккаунтов |
| `AttachmentsContentProvider.java` | ContentProvider для раздачи файлов вложений другим приложениям |
| `DcContactsLoader.java` | AsyncTaskLoader загрузки контактов из `DcContext` |
| `DcEventCenter.java` | Observer-шина событий core: `addObserver` / `emit` |
| `DcHelper.java` | Утилитный фасад: геттеры `DcContext`/`Rpc`/`DcAccounts`, конфиги, stock-strings |
| `DirectShareUtil.java` | Настройка прямых шорткатов шаринга в конкретный чат |
| `FetchWorker.java` | `WorkManager.Worker`: фоновая синхронизация (`startIo` + sleep 60s) |
| `ForegroundDetector.java` | Определяет, находится ли приложение в foreground |
| `KeepAliveService.java` | Foreground service, удерживающий IO активным в фоне |
| `NetworkStateReceiver.java` | BroadcastReceiver: вызывает `maybeNetwork()` при смене сети |

---

### `contacts/`

| Файл | Назначение |
|---|---|
| `ContactAccessor.java` | Доступ к системной адресной книге Android |
| `ContactSelectionListAdapter.java` | Адаптер списка контактов с inline-поиском |
| `ContactSelectionListItem.java` | ViewHolder элемента контакта в списке выбора |
| `NewContactActivity.java` | Экран добавления нового контакта по email-адресу |
| `avatars/ContactPhoto.java` | Интерфейс источника фото контакта |
| `avatars/FallbackContactPhoto.java` | Fallback фото (инициалы или иконка) когда нет аватара |
| `avatars/GeneratedContactPhoto.java` | Генерированный аватар из инициалов + уникального цвета |
| `avatars/GroupRecordContactPhoto.java` | Аватар группы из `DcChat` |
| `avatars/LocalFileContactPhoto.java` | Аватар из локального файла (selfavatar) |
| `avatars/MyProfileContactPhoto.java` | Аватар собственного профиля |
| `avatars/ProfileContactPhoto.java` | Аватар из профиля контакта (скачанный core'ом) |
| `avatars/ResourceContactPhoto.java` | Аватар из ресурсов drawable |
| `avatars/SystemContactPhoto.java` | Аватар из системной адресной книги |
| `avatars/VcardContactPhoto.java` | Аватар из vCard (base64 image) |

---

### `crypto/`

| Файл | Назначение |
|---|---|
| `DatabaseSecret.java` | Ключ шифрования локальной БД вложений (обёртка над `byte[]`) |
| `DatabaseSecretProvider.java` | Генерация и чтение `DatabaseSecret` через Android Keystore |
| `KeyStoreHelper.java` | Шифрование/дешифрование ключа с помощью Android Keystore AES |

---

### `database/`

| Файл | Назначение |
|---|---|
| `Address.java` | Email-адрес как тип (обёртка над String) |
| `AttachmentDatabase.java` | Константы статусов вложений (DONE / STARTED / FAILED) |
| `CursorRecyclerViewAdapter.java` | `RecyclerView.Adapter` на базе `Cursor` |
| `loaders/BucketedThreadMediaLoader.java` | Loader медиафайлов чата, сгруппированных по месяцу |
| `loaders/PagingMediaLoader.java` | Paging loader для навигации prev/next в MediaPreview |
| `loaders/RecentPhotosLoader.java` | Loader последних N фотографий с устройства |
| `model/ThreadRecord.java` | Модель строки чата для `ConversationListItem` |

---

### `geolocation/`

| Файл | Назначение |
|---|---|
| `DcLocation.java` | Singleton последней известной GPS-точки (Observable) |
| `DcLocationManager.java` | Координирует `LocationBackgroundService` и отправку локации в core |
| `LocationBackgroundService.java` | Bound service: получает GPS обновления через системный `LocationManager` |

---

### `glide/`

| Файл | Назначение |
|---|---|
| `ContactPhotoFetcher.java` | Glide `DataFetcher` для загрузки аватара контакта из core |
| `ContactPhotoLoader.java` | Glide `ModelLoader` регистрирует `ContactPhotoFetcher` |

---

### `imageeditor/`

Полноценный in-app редактор изображений (рисование, текст, обрезка).

| Файл | Назначение |
|---|---|
| `Bounds.java` | Прямоугольные границы элемента редактора |
| `CanvasMatrix.java` | Матрица трансформации canvas (pan/zoom/rotate) |
| `ColorableRenderer.java` | Интерфейс рендерера с поддержкой цвета кисти |
| `DrawingSession.java` | Сессия рисования (путь кисти, цвет, размер) |
| `EditSession.java` | Базовая сессия редактирования элемента |
| `ElementDragEditSession.java` | Сессия перемещения элемента drag-жестом |
| `ElementEditSession.java` | Сессия редактирования (drag + scale combined) |
| `ElementScaleEditSession.java` | Сессия масштабирования элемента |
| `HiddenEditText.java` | Невидимый `EditText` для ввода текста поверх фото |
| `ImageEditorMediaConstraints.java` | Ограничения размера/качества редактируемого изображения |
| `ImageEditorView.java` | View холста редактора (touch events → edit sessions) |
| `Renderer.java` | Интерфейс рендерера элемента на canvas |
| `RendererContext.java` | Контекст рендеринга (canvas, матрица, пресеты цветов) |
| `ThumbDragEditSession.java` | Сессия перетаскивания контрольной точки thumb |
| `UndoRedoStackListener.java` | Listener изменений стека undo/redo |
| `model/AlphaAnimation.java` | Анимация прозрачности элемента |
| `model/AnimationMatrix.java` | Анимируемая матрица трансформации |
| `model/Bisect.java` | Бисекция для hit-testing элементов |
| `model/CropThumbRenderer.java` | Рендерер ручки-точки кадрирования |
| `model/EditorElement.java` | Элемент редактора (изображение, текст, линия рисунка) |
| `model/EditorElementHierarchy.java` | Иерархия: overlay → crop → image |
| `model/EditorFlags.java` | Флаги состояния (editable, moveable, deleteable…) |
| `model/EditorModel.java` | Полная модель состояния редактора + стек undo/redo |
| `model/ElementStack.java` | Z-order стек элементов |
| `model/InBoundsMemory.java` | Запоминает последнее допустимое положение элемента |
| `model/ParcelUtils.java` | Утилиты сериализации матриц через `Parcel` |
| `model/ThumbRenderer.java` | Рендерер контрольной точки управления (кружок) |
| `model/UndoRedoStacks.java` | Стек состояний для undo/redo |
| `renderers/AutomaticControlPointBezierLine.java` | Кривая Безье с автовычислением контрольных точек |
| `renderers/BezierDrawingRenderer.java` | Рендерер рисования вектором кисти |
| `renderers/CropAreaRenderer.java` | Рендерер области кадрирования (рамка + тени) |
| `renderers/InvalidateableRenderer.java` | Рендерер, умеющий инвалидировать View для перерисовки |
| `renderers/InverseFillRenderer.java` | Затемнение области вне кадра кадрирования |
| `renderers/MultiLineTextRenderer.java` | Рендерер многострочного текста поверх изображения |
| `renderers/OvalGuideRenderer.java` | Рендерер овального направляющего |

---

### `jobmanager/`

Очередь фоновых задач с поддержкой WakeLock.

| Файл | Назначение |
|---|---|
| `Job.java` | Базовый класс задачи с WakeLock и lifecycle методами |
| `JobConsumer.java` | Thread, ждущий задачи из `JobQueue` и выполняющий их |
| `JobManager.java` | Публичный API: `add(Job)` + управление WakeLock |
| `JobParameters.java` | Параметры задачи (группа, retention и т.д.) |
| `JobQueue.java` | `BlockingQueue` задач |
| `requirements/Requirement.java` | Интерфейс условия выполнения задачи |

---

### `messagerequests/`

| Файл | Назначение |
|---|---|
| `MessageRequestsBottomView.java` | View панели "принять / удалить контакт" (message request) |

---

### `mms/`

Модель слайдов/вложений для compose и отображения.

| Файл | Назначение |
|---|---|
| `AttachmentManager.java` | Управляет списком вложений черновика сообщения |
| `Slide.java` | Базовый класс вложения-слайда (URI, MIME, thumbnail) |
| `SlideDeck.java` | Набор слайдов одного сообщения |
| `AudioSlide.java` | Слайд аудио-вложения |
| `DocumentSlide.java` | Слайд документа |
| `GifSlide.java` | Слайд GIF-анимации |
| `ImageSlide.java` | Слайд изображения |
| `VideoSlide.java` | Слайд видео |
| `StickerSlide.java` | Слайд стикера |
| `VcardSlide.java` | Слайд vCard-контакта |
| `MediaConstraints.java` | Ограничения размера медиа (для разных режимов) |
| `PartAuthority.java` | URI authority для ContentProvider вложений |
| `QuoteModel.java` | Модель цитируемого сообщения при compose |
| `SlideClickListener.java` | Интерфейс обработчика тапа по вложению |
| `SignalGlideModule.java` | `@GlideModule`: регистрирует кастомные Glide ModelLoader'ы |
| `DecryptableStreamUriLoader.java` | Glide `ModelLoader` для чтения шифрованных URIs |
| `DecryptableStreamLocalUriFetcher.java` | Glide `DataFetcher` для чтения шифрованных локальных файлов |

---

### `notifications/`

| Файл | Назначение |
|---|---|
| `NotificationCenter.java` | Построение и показ системных уведомлений (входящие + звонки) |
| `InChatSounds.java` | Звуки внутри открытого чата (incoming, sent) |
| `MarkReadReceiver.java` | BroadcastReceiver: пометка чата прочитанным из уведомления |
| `RemoteReplyReceiver.java` | BroadcastReceiver: отправка ответа прямо из уведомления |
| `DeclineCallReceiver.java` | BroadcastReceiver: отклонение входящего звонка из уведомления |

---

### `permissions/`

| Файл | Назначение |
|---|---|
| `Permissions.java` | Fluent API запроса разрешений с rationale диалогом |
| `PermissionsRequest.java` | Builder-объект запроса разрешений |
| `RationaleDialog.java` | Диалог объяснения зачем нужно разрешение |

---

### `preferences/`

| Файл | Назначение |
|---|---|
| `AdvancedPreferenceFragment.java` | Расширенные настройки: логи, сброс, управление аккаунтом |
| `AppearancePreferenceFragment.java` | Настройки внешнего вида (тема, язык, шрифт) |
| `ChatBackgroundActivity.java` | Выбор фона чата из галереи или встроенных |
| `ChatsPreferenceFragment.java` | Настройки чатов (Enter sends, качество медиа) |
| `CorrectedPreferenceFragment.java` | Исправленный `PreferenceFragment` (compat fix) |
| `ListSummaryPreferenceFragment.java` | Preference с summary в виде выбранных значений |
| `NotificationsPreferenceFragment.java` | Настройки уведомлений (звук, вибро, LED, приватность) |
| `widgets/NotificationPrivacyPreference.java` | Preference выбора уровня приватности уведомлений |
| `widgets/ProfilePreference.java` | Preference отображения текущего профиля в настройках |

---

### `profiles/`

| Файл | Назначение |
|---|---|
| `AvatarHelper.java` | Утилиты сохранения и загрузки аватара из файловой системы |

---

### `providers/`

| Файл | Назначение |
|---|---|
| `PersistentBlobProvider.java` | ContentProvider для долгосрочного хранения blob-файлов (вложения) |
| `SingleUseBlobProvider.java` | ContentProvider для временных blob-файлов (удаляются после чтения) |

---

### `proxy/`

| Файл | Назначение |
|---|---|
| `ProxyListAdapter.java` | Адаптер списка настроенных прокси-серверов |
| `ProxySettingsActivity.java` | Экран добавления/удаления SOCKS5/HTTP прокси |

---

### `qr/`

| Файл | Назначение |
|---|---|
| `QrActivity.java` | Экран сканирования QR-кода |
| `QrScanFragment.java` | Фрагмент с превью камеры и сканером QR |
| `QrShowActivity.java` | Экран отображения QR-кода своего invite |
| `QrShowFragment.java` | Фрагмент отрисовки QR-кода |
| `QrCodeHandler.java` | Обработка отсканированного QR: `checkQr` + dispatch по типу |
| `BackupTransferActivity.java` | Activity передачи/получения бэкапа через QR + WiFi |
| `BackupProviderFragment.java` | Фрагмент отображения QR-кода для отдачи бэкапа |
| `BackupReceiverFragment.java` | Фрагмент сканирования QR-кода для приёма бэкапа |
| `CustomCaptureManager.java` | Кастомный менеджер камеры для QR-сканера (ZXing) |
| `RegistrationQrActivity.java` | Сканирование QR для регистрации по invite-ссылке |

---

### `reactions/`

| Файл | Назначение |
|---|---|
| `ReactionsConversationView.java` | View реакций под пузырём сообщения (эмодзи + счётчики) |
| `ReactionsDetailsFragment.java` | BottomSheet с детальным списком реакторов |
| `AddReactionView.java` | View кнопки добавления реакции (+) |
| `ReactionRecipientItem.java` | ViewHolder элемента списка кто поставил реакцию |
| `ReactionRecipientsAdapter.java` | Адаптер списка реакторов в BottomSheet |

---

### `recipients/`

| Файл | Назначение |
|---|---|
| `Recipient.java` | Android-side модель получателя: объединяет `DcContact` + `DcChat` + аватар |
| `RecipientForeverObserver.java` | Интерфейс постоянного наблюдателя изменений `Recipient` |
| `RecipientModifiedListener.java` | Weak-observer изменений `Recipient` |

---

### `relay/`

| Файл | Назначение |
|---|---|
| `RelayListActivity.java` | Список аккаунтов/чатов для relay-пересылки сообщения |
| `RelayListAdapter.java` | Адаптер списка relay |
| `EditRelayActivity.java` | Редактирование текста relay-сообщения перед пересылкой |

---

### `scribbles/`

| Файл | Назначение |
|---|---|
| `ScribbleActivity.java` | Activity редактора изображений (обёртка `ImageEditorFragment`) |
| `ImageEditorFragment.java` | Фрагмент редактора: соединяет `ImageEditorView` с HUD |
| `ImageEditorHud.java` | HUD инструментов: кисть, текст, crop, цвет, undo/redo |
| `StickerLoader.java` | Загрузчик стикеров для вставки в редактор |
| `StickerSelectActivity.java` | Activity выбора стикера для наклейки на фото |
| `StickerSelectFragment.java` | Фрагмент с сеткой доступных стикеров |
| `UriGlideRenderer.java` | Renderer вставленного изображения через Glide |
| `widget/ColorPaletteAdapter.java` | Адаптер палитры цветов кисти |
| `widget/VerticalSlideColorPicker.java` | Вертикальный слайдер выбора цвета |

---

### `search/`

| Файл | Назначение |
|---|---|
| `SearchFragment.java` | Фрагмент поиска по сообщениям и контактам |
| `SearchListAdapter.java` | Адаптер результатов поиска (контакты + сообщения) |
| `SearchViewModel.java` | ViewModel: debounced поиск через RPC, LiveData результатов |
| `model/SearchResult.java` | Модель результата (список контактов + список сообщений) |

---

### `service/`

| Файл | Назначение |
|---|---|
| `AudioPlaybackService.java` | media3 `MediaSessionService` для воспроизведения аудио в фоне |
| `BootReceiver.java` | `BroadcastReceiver`: запускает `DcAccounts.startIo()` после перезагрузки |
| `FetchForegroundService.java` | Foreground service для фоновой синхронизации (пока приложение в background) |
| `GenericForegroundService.java` | Утилитный foreground service для создания канала уведомлений |
| `NotificationController.java` | Контроллер lifetime foreground notification |
| `PanicResponderListener.java` | Слушает panic-intent (PanicKit) для экстренного удаления данных |

---

### `util/`

| Файл | Назначение |
|---|---|
| `AccessibilityUtil.java` | Проверка настроек accessibility (TalkBack активен?) |
| `AndroidSignalProtocolLogger.java` | Маршрут Signal Protocol логов в Android Log |
| `AsyncLoader.java` | Улучшенный `AsyncTaskLoader` |
| `AvatarUtil.java` | Загрузка и генерация аватара через Glide |
| `BitmapDecodingException.java` | Исключение ошибки декодирования bitmap |
| `BitmapUtil.java` | Resize, compress, rotate bitmap |
| `Conversions.java` | Конвертации `byte[]` ↔ int/long |
| `DateUtils.java` | Форматирование дат (сегодня/вчера/dd MMM/т.д.) |
| `Debouncer.java` | Debounce выполнения `Runnable` (откладывает до паузы) |
| `DrawableUtil.java` | Tint и resize `Drawable` |
| `DynamicNoActionBarTheme.java` | Динамическая тема без ActionBar |
| `DynamicTheme.java` | Применение light/dark темы в runtime |
| `FileProviderUtil.java` | Генерация `FileProvider` URI для файлов |
| `FileUtils.java` | Копирование, удаление, чтение файлов |
| `FutureTaskListener.java` | Listener завершения `ListenableFutureTask` |
| `Hash.java` | MD5/SHA хэши строк и `byte[]` |
| `Hex.java` | `byte[]` ↔ hex-строка |
| `IntentUtils.java` | Построение Intent (камера, галерея, шаринг) |
| `JsonUtils.java` | Обёртки Jackson для быстрого to/from JSON |
| `LRUCache.java` | Простой LRU-кэш на `LinkedHashMap` |
| `Linkifier.java` | Детектирование ссылок и email в тексте |
| `ListenableFutureTask.java` | `FutureTask` с listener завершения |
| `LongClickCopySpan.java` | `ClickableSpan` для копирования текста долгим нажатием |
| `LongClickMovementMethod.java` | `MovementMethod` для `LongClickCopySpan` |
| `MailtoUtil.java` | Парсинг и генерация `mailto:` URI |
| `MediaUtil.java` | MIME-тип, размер, ориентация медиафайла |
| `Pair.java` | Параметрическая пара |
| `ParcelUtil.java` | Сериализация `Parcelable` в `byte[]` |
| `Prefs.java` | Обёртка над `SharedPreferences` (все настройки приложения) |
| `ResUtil.java` | Геттеры ресурсов (dimen, color, string) |
| `SaveAttachmentTask.java` | `AsyncTask` сохранения вложения в галерею / файлы |
| `ScreenLockUtil.java` | Управление блокировкой экрана биометрией / PIN |
| `SelectedContactsAdapter.java` | Адаптер chip-панели выбранных контактов |
| `SendRelayedMessageUtil.java` | Утилита отправки relay-сообщения |
| `ServiceUtil.java` | Геттеры системных сервисов (`NotificationManager`, etc.) |
| `ShareUtil.java` | Разбор share Intent (getSharedText, isForwarding, isRelaying) |
| `SignalProtocolLogger.java` | Интерфейс логгера protokol |
| `SignalProtocolLoggerProvider.java` | Провайдер `SignalProtocolLogger` |
| `SpanUtil.java` | Утилиты построения `Spannable` (bold, color, size) |
| `StickyHeaderDecoration.java` | `RecyclerView.ItemDecoration` для прилипающих заголовков |
| `Stopwatch.java` | Замер времени выполнения (log timing) |
| `StorageUtil.java` | Пути к папкам хранилища (Downloads, Documents) |
| `StreamUtil.java` | Копирование и чтение `InputStream`/`OutputStream` |
| `TextUtil.java` | Trim, isEmptyOrPseudo, trimToLength |
| `ThemeUtil.java` | Получение значения атрибута темы (attr → value) |
| `ThreadUtil.java` | Запуск на main thread, sleep с обработкой interrupt |
| `Util.java` | Разные утилиты (isEmpty, clamp, getDeviceId, runOnMainThread…) |
| `ViewUtil.java` | dp/px, show/hide View с анимацией, измерения |
| `concurrent/AssertedSuccessListener.java` | Listener Future, трактующий ошибки как fatal assert |
| `guava/Absent.java` | Реализация `Optional.absent()` |
| `guava/Function.java` | Функциональный интерфейс |
| `guava/Optional.java` | Упрощённый порт Guava `Optional` |
| `guava/Preconditions.java` | `checkNotNull` / `checkArgument` |
| `guava/Present.java` | Реализация `Optional.of()` |
| `guava/Supplier.java` | `Supplier<T>` интерфейс |
| `spans/CenterAlignedRelativeSizeSpan.java` | Span с центрированием и относительным размером текста |
| `task/ProgressDialogAsyncTask.java` | `AsyncTask` с `ProgressDialog` на время работы |
| `task/SnackbarAsyncTask.java` | `AsyncTask` со Snackbar-результатом |
| `views/ConversationAdaptiveActionsToolbar.java` | Toolbar с адаптивными action-кнопками в чате |
| `views/ProgressDialog.java` | Compat `ProgressDialog` |
| `views/Stub.java` | Lazy-inflate View (альтернатива `ViewStub`) |

---

### `video/`

| Файл | Назначение |
|---|---|
| `VideoPlayer.java` | View-обёртка ExoPlayer для воспроизведения видео |
| `exo/AttachmentDataSource.java` | ExoPlayer `DataSource` для чтения вложений |
| `exo/AttachmentDataSourceFactory.java` | Фабрика `AttachmentDataSource` |
| `recode/InputSurface.java` | EGL Surface ввода при перекодировании видео |
| `recode/MP4Builder.java` | Построитель MP4-контейнера (muxer) |
| `recode/Mp4Movie.java` | Модель MP4 (треки + сэмплы) |
| `recode/OutputSurface.java` | EGL Surface вывода при перекодировании |
| `recode/Sample.java` | Один видео/аудио сэмпл |
| `recode/TextureRenderer.java` | OpenGL ES рендерер текстуры при трансформации видео |
| `recode/Track.java` | Видео или аудио трек MP4 |
| `recode/VideoRecoder.java` | Перекодирование видео в нужный размер/битрейт перед отправкой |

---

### `webxdc/`

| Файл | Назначение |
|---|---|
| `WebxdcGarbageCollectionWorker.java` | WorkManager Worker: удаление кэша удалённых webxdc-приложений |

---

## `src/gplay/` — Google Play flavor

| Файл | Назначение |
|---|---|
| `notifications/FcmReceiveService.java` | Firebase Cloud Messaging Service: получение push → вызов `startIo()` |

## `src/foss/` — F-Droid flavor

| Файл | Назначение |
|---|---|
| `notifications/FcmReceiveService.java` | Stub без FCM: polling вместо push (совместимость с F-Droid) |
