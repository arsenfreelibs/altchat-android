# DEV-57: Облачное резервное копирование профиля и восстановление по email

**Jira Link:** https://altchatme.atlassian.net/browse/DEV-57

---

## Ticket Description

### Problem Statement

Сейчас единственный способ перенести профиль на новое устройство — вручную сохранить
бэкап-файл `alt-chat-backup-*.tar` в `Downloads/` и физически передать его на новый
телефон (см. [docs/profile-backup.md](profile-backup.md)). Это:

- работает только если пользователь заранее знал, что нужно делать бэкап;
- ломается при потере устройства, краже, поломке без сохранённого `.tar`;
- не даёт службе поддержки никакого способа помочь пользователю.

Нужен механизм автоматической отправки бэкапа в Alt Backend сразу после регистрации
и восстановления через email-код на чистом приложении.

**Email при регистрации необязателен** — пользователь может пройти регистрацию без
него. В этом случае приложение раз в сутки на старте предлагает его задать;
заданный email становится email-ом аккаунта и используется для восстановления.

**Current state:**

| Компонент | Расположение | Статус |
|---|---|---|
| Локальный экспорт бэкапа в `.tar` | [DcContext.imex()](../src/main/java/com/b44t/messenger/DcContext.java#L174) | Работает (passphrase зашит как `""`, см. [dc_wrapper.c:952](../jni/dc_wrapper.c#L952)) — отдаёт незашифрованный `.tar` |
| Локальный импорт бэкапа из `.tar` | [WelcomeActivity:332](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L332) | Работает (только для несконфигурированного профиля) |
| Облачное хранилище бэкапов | — | Отсутствует |
| Установка email после регистрации | — | Отсутствует |
| UI восстановления профиля через email-код | — | Отсутствует |
| Эндпоинты бэкапа в Alt API | — | Отсутствуют |

### Solution

Реализация делится на **две фазы**:

**Фаза 1 (MVP):**

1. **One-shot после регистрации** — сразу после получения JWT клиент создаёт
   бэкап и заливает на сервер. Так у саппорта есть копия профиля с момента
   регистрации.
2. **Prompt email раз в сутки** — при старте приложения, если email у аккаунта
   не задан и с прошлого показа прошло ≥ 24 ч, показываем диалог «введите email
   для восстановления». Введённый email подтверждается кодом и сохраняется на
   аккаунте.
3. **Восстановление** — на чистом приложении: ввод email → 6-значный код на
   email → ввод кода → сервер отдаёт `.tar` → клиент импортирует через
   `DC_IMEX_IMPORT_BACKUP`.

**Фаза 2 (отдельный этап):**

4. **Периодическое обновление** — раз в сутки в фоне (WorkManager). Бэкап
   перезаписывается на сервере: один пользователь = одна актуальная копия.

```
┌───────────────────────────────────────────────────────────────────┐
│ UI                                                                │
│  ┌──────────────────┐  ┌────────────────┐  ┌──────────────────┐  │
│  │ EmailPrompt      │  │ Restore from   │  │ BackupSettings   │  │
│  │ (1×/день, нет    │  │ Cloud screen   │  │ (статус, удалить)│  │
│  │  email на акке)  │  │                │  │                  │  │
│  └──────────────────┘  └────────────────┘  └──────────────────┘  │
├───────────────────────────────────────────────────────────────────┤
│ Application: CloudBackupService                                   │
│   — orchestrates: создать .tar → отправить как есть               │
│   — orchestrates: запросить код → скачать .tar → импортировать    │
├───────────────────────────────────────────────────────────────────┤
│ WorkManager: BackupUploadWorker                                   │
│   Фаза 1: one-time после регистрации                              │
│   Фаза 2: periodic 24h                                            │
├───────────────────────────────────────────────────────────────────┤
│ Network: CloudBackupApi (OkHttp, см. AltApiClient из ANDROID-001) │
│   POST/GET/DELETE /v1/backup                                       │
│   PUT /v1/users/me/email + /verify                                 │
│   POST /v1/backup/recovery/{request,verify,download}               │
├──────────────────┬────────────────────────────────────────────────┤
│ DeltaChat Core   │  Используем существующий DcContext.imex(...)   │
│ (export/import)  │  без расширений (passphrase = "")              │
└──────────────────┴────────────────────────────────────────────────┘
```

Бэкап **не шифруется на клиенте**. На сервер отправляется `.tar` как есть.
Защита — TLS на транспорте + изоляция blob'ов в объектном хранилище.

### Technical Approach

1. **Backend**: новый модуль `Backup` с эндпоинтами upload/get/delete +
   recovery flow + установка email на аккаунт.
2. **Android Worker**: `BackupUploadWorker` (CoroutineWorker) — экспорт через
   `DcContext.imex(DC_IMEX_EXPORT_BACKUP, tmpDir)`, чтение `.tar`, multipart-
   загрузка, удаление tmp.
3. **Android UI**:
   - `EmailPromptDialog` — раз в 24 ч на старте приложения, если email не задан;
   - `EmailVerifyActivity` — ввод кода для подтверждения нового email;
   - `CloudRestoreActivity` — флоу восстановления на чистом приложении;
   - `BackupSettingsFragment` — Settings → Cloud Backup.
4. Хранить дату последней загрузки и дату последнего prompt'а в
   `EncryptedSharedPreferences`.
5. **JNI расширять не нужно** — текущий [`DcContext.imex(int, String)`](../src/main/java/com/b44t/messenger/DcContext.java#L174)
   с зашитым `passphrase = ""` делает ровно то, что нужно: отдаёт незашифрованный `.tar`.

### Related Tickets

- **ANDROID-001**: Интеграция с Alt Platform — даёт `AltApiClient`, JWT-хранилище.
- **DEV-10**: Users Module — даёт регистрацию и верификацию email.

---

## Detailed Specifications

### 1. Сценарии пользователя

#### 1.1 Сразу после регистрации (Фаза 1)

1. Пользователь прошёл регистрацию, получил JWT.
2. Email на аккаунте может быть задан или нет — это не влияет на upload.
3. В фоне ставится **one-time** `BackupUploadWorker` без constraints
   (`NetworkType.CONNECTED`), чтобы залить первый бэкап как можно быстрее.
4. Worker создаёт `.tar` через `DcContext.imex(DC_IMEX_EXPORT_BACKUP, tmpDir)`
   и заливает на `POST /v1/backup` как есть, без шифрования.

#### 1.2 Prompt email раз в сутки (Фаза 1)

- При старте приложения проверяем: `email на аккаунте == null && now - last_prompt_at >= 24ч`.
- Если да — показываем `EmailPromptDialog` с полем ввода email.
- Кнопки: «Указать», «Напомнить позже», «Не показывать снова».
- При «Указать»:
  1. `PUT /v1/users/me/email {email}` → сервер шлёт код, отвечает `202`;
  2. UI открывает `EmailVerifyActivity` с полем ввода кода;
  3. `POST /v1/users/me/email/verify {code}` → `200`, на аккаунте сохраняется email.
- «Не показывать снова» сохраняет флаг навсегда (можно сбросить из настроек
  `BackupSettingsFragment`).

#### 1.3 Восстановление на чистом приложении (Фаза 1)

1. На `WelcomeActivity` рядом с существующей кнопкой «Restore from backup» (она
   ищет локальный `.tar` в `Downloads/`) добавить кнопку «**Restore from cloud**».
2. Пользователь вводит email аккаунта.
3. Клиент дёргает `POST /v1/backup/recovery/request {email}`. Сервер шлёт
   6-значный код на этот email, отвечает `202`.
4. Пользователь вводит код. Клиент дёргает `POST /v1/backup/recovery/verify {email, code}`.
   Сервер возвращает `{recoveryToken, blobMeta: {size, sha256, createdAt}}`.
   `recoveryToken` — JWT с TTL 10 мин, scope=`backup:download`.
5. Клиент дёргает `GET /v1/backup/recovery/download` с
   `Authorization: Bearer <recoveryToken>` и получает `.tar` потоком.
6. Клиент пишет `.tar` в `cacheDir` → вызывает `DcContext.imex(DC_IMEX_IMPORT_BACKUP, path)`.
7. После успеха временный файл удаляется.

#### 1.4 Периодическое обновление (Фаза 2)

- Plan'ится `PeriodicWorkRequest` с интервалом 24 ч. Constraints:
  `RequiredNetworkType=UNMETERED`, `RequiresCharging=true`, `RequiresStorageNotLow=true`.
- Если за 7 дней constraints не сработали — relax до `RequiredNetworkType=CONNECTED`,
  `RequiresCharging=false` (по согласованию).
- На сервере хранится **только последняя версия** бэкапа. История версий не нужна.

---

### 2. Android-клиент

#### 2.1 Хранилище

`EncryptedSharedPreferences` (паттерн из ANDROID-001), namespace
`cloud_backup`:

| Ключ | Тип | Назначение |
|---|---|---|
| `last_backup_at` | Long | unix-ms последней успешной загрузки |
| `last_backup_size` | Long | байты последнего загруженного blob'а |
| `last_prompt_at` | Long | unix-ms последнего показа `EmailPromptDialog` |
| `prompt_disabled` | Boolean | пользователь нажал «не показывать снова» |

Email на аккаунте берётся из существующего `AltAccount`/`AltPrefs` (ANDROID-001) —
здесь не дублируется.

#### 2.2 BackupUploadWorker

Подкласс `androidx.work.CoroutineWorker`. Алгоритм `doWork()`:

1. Проверить наличие JWT. Если нет — `Result.success()` (не пытаемся, не ругаемся).
2. Создать tmp-каталог `cacheDir/cloud-backup-<uuid>/`.
3. Вызвать `dcContext.imex(DC_IMEX_EXPORT_BACKUP, tmpDir)` — синхронно ждать
   `DC_EVENT_IMEX_PROGRESS = 1000` или `0`.
4. Найти созданный `alt-chat-backup-*.tar` в tmpDir.
5. POST `multipart/form-data` на `/v1/backup` с `Authorization: Bearer <jwt>`,
   стримно отправить файл (без полной загрузки в RAM).
6. Удалить tmpDir (включая `.tar`).
7. Записать `last_backup_at`, `last_backup_size`.
8. При ошибке — `Result.retry()` с экспоненциальным backoff, до 5 попыток.

**Фаза 1** — Worker запускается **только как one-time** сразу после регистрации,
без constraints.

**Фаза 2** — добавляется `PeriodicWorkRequest`:

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresCharging(true)
    .setRequiresStorageNotLow(true)
    .build()

PeriodicWorkRequestBuilder<BackupUploadWorker>(24, TimeUnit.HOURS)
    .setConstraints(constraints)
    .setInitialDelay(Duration.ofHours(1))
    .build()
```

#### 2.3 UI

| Экран / диалог | Фаза | Что делает |
|---|---|---|
| `EmailPromptDialog` | 1 | Раз в 24 ч предлагает задать email на аккаунте, если он не задан |
| `EmailVerifyActivity` | 1 | Ввод кода, дёргает `POST /v1/users/me/email/verify` |
| `BackupSettingsFragment` | 1 | Settings → Cloud Backup: статус (есть/нет, дата, размер), кнопки «Загрузить сейчас», «Удалить с сервера» |
| `CloudRestoreActivity` | 1 | Стартует с `WelcomeActivity` → кнопка «Restore from cloud»; шаги: email → код → восстановление |

#### 2.4 Точки интеграции

- `ApplicationContext.onCreate` после блока инициализации `DcAccounts`
  ([ApplicationContext.java:189](../src/main/java/org/thoughtcrime/securesms/ApplicationContext.java#L189)) —
  проверка `last_prompt_at` + email; в Фазе 2 — постановка `PeriodicWorkRequest`.
- `WelcomeActivity` — добавить кнопку «Restore from cloud» рядом с «Restore from
  backup» ([WelcomeActivity.java:255](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L255)).
- В flow регистрации Alt (ANDROID-001) — после успешной верификации поставить
  one-time `BackupUploadWorker`.

---

### 3. Backend API

Новый scope `/v1/backup`. Модуль `Backup` рядом с `Users`/`Relays`. Ошибки в
формате `{"error":"..."}`.

#### 3.1 Эндпоинты

##### 3.1.1 `POST /v1/backup` — загрузить/перезаписать бэкап

- **Auth**: `Authorization: Bearer <jwt>`
- **Body**: `multipart/form-data`
  - `blob` (file, required): сам `.tar`-архив (без шифрования)
  - `sha256` (string, required): hex sha256 от blob
- **Лимиты**:
  - размер blob'а ≤ 100 МБ;
  - частота: не чаще 1 раза в час на пользователя (rate-limit `429`);
  - blob перезаписывает предыдущий, история не ведётся.
- **Ответы**:
  - `200 {"size":<bytes>,"sha256":"...","createdAt":"<iso>"}`
  - `400 {"error":"sha256_mismatch"}`
  - `413 {"error":"blob_too_large"}`
  - `429`
  - `401`

```bash
curl -s -X POST $BASE/v1/backup \
  -H "Authorization: Bearer $TOKEN" \
  -F "blob=@/tmp/alt-chat-backup.tar;type=application/x-tar" \
  -F "sha256=2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"
```

##### 3.1.2 `GET /v1/backup` — метаданные текущего бэкапа

- **Auth**: JWT
- **Ответы**:
  - `200 {"size":...,"sha256":"...","createdAt":"<iso>"}`
  - `404 {"error":"no_backup"}`

```bash
curl -s $BASE/v1/backup -H "Authorization: Bearer $TOKEN"
```

##### 3.1.3 `DELETE /v1/backup` — удалить свой бэкап

- **Auth**: JWT
- **Ответы**: `204` / `404 {"error":"no_backup"}`

##### 3.1.4 `PUT /v1/users/me/email` — задать/сменить email на аккаунте

- **Auth**: JWT
- **Body**: `{"email":"alice@example.com"}`
- Сервер шлёт код на указанный email, сохраняет email как `pending` до подтверждения.
- **Ответы**: `202` / `400 {"error":"invalid_email"}` /
  `409 {"error":"email_taken"}` / `429`

```bash
curl -s -X PUT $BASE/v1/users/me/email \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com"}'
```

##### 3.1.5 `POST /v1/users/me/email/verify` — подтвердить кодом

- **Auth**: JWT
- **Body**: `{"code":"123456"}`
- При успехе `pending` email становится текущим.
- **Ответы**:
  - `200 {"email":"alice@example.com"}`
  - `400 {"error":"invalid_or_expired_code"}`

##### 3.1.6 `POST /v1/backup/recovery/request` — запросить код на чистом устройстве

- **Auth**: **нет** (на чистом приложении токена нет)
- **Body**: `{"email":"<email аккаунта>"}`
- Сервер ищет пользователя по email. Для защиты от перебора отвечает `202`
  независимо от того, найден пользователь или нет.
- Шлёт код только если пользователь найден и у него есть бэкап.
- **Лимиты**: 3 запроса / час / IP, 5 / сутки / email.

```bash
curl -s -X POST $BASE/v1/backup/recovery/request \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com"}'
```

##### 3.1.7 `POST /v1/backup/recovery/verify` — подтвердить и получить токен на скачивание

- **Auth**: нет
- **Body**: `{"email":"<email аккаунта>","code":"123456"}`
- **Ответы**:
  - `200 {"recoveryToken":"<jwt, scope=backup:download, ttl=600s>","blobMeta":{...}}`
  - `400 {"error":"invalid_or_expired_code"}`
  - `404 {"error":"no_backup"}` — пользователь найден, но бэкапа нет

##### 3.1.8 `GET /v1/backup/recovery/download` — скачать blob

- **Auth**: `Authorization: Bearer <recoveryToken>` (scope=`backup:download`)
- **Ответ**: `200 application/x-tar`, тело — сам `.tar`.
  Заголовок `X-Backup-SHA256: <hex>` для контроля целостности на клиенте.
- **Один токен — одно успешное скачивание.** После 200 токен инвалидируется.
- `401` если токен не того scope или просрочен.

#### 3.2 Модель данных (Backend)

```sql
CREATE TABLE user_backups (
    user_id        BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    blob_path      TEXT NOT NULL,            -- путь в S3/MinIO/диске
    blob_size      BIGINT NOT NULL,
    sha256         TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Коды восстановления бэкапа. Отдельно от кодов входа в аккаунт, чтобы они
-- могли существовать одновременно и не пересекаться по scope.
CREATE TABLE backup_recovery_codes (
    user_id      BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    code_hash    TEXT NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    attempts     INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Если в users пока нет колонок для подтверждения email — добавить:
ALTER TABLE users
    ADD COLUMN email                TEXT NULL,
    ADD COLUMN email_pending        TEXT NULL,
    ADD COLUMN email_code_hash      TEXT NULL,
    ADD COLUMN email_code_expires_at TIMESTAMPTZ NULL;
CREATE UNIQUE INDEX idx_users_email ON users(email) WHERE email IS NOT NULL;
```

> Если в текущей схеме `users.email` уже есть — переиспользовать его.
> Финальный набор колонок согласовать с владельцем модуля Users.

Сами blob'ы хранить **не в Postgres**, а в объектном сторадже (S3/MinIO),
имена — `backups/<user_uuid>.tar`.

#### 3.3 Хранение и удаление

- При `POST /v1/backup` старый blob атомарно заменяется новым (write to
  `<user_uuid>.tar.tmp` → rename).
- При удалении пользователя — каскадное удаление blob'а из объектного хранилища
  (отдельным джобом, чтобы не блокировать запрос).
- Доступ к blob'ам — только админ-роль и владелец (через `recoveryToken`).

#### 3.4 Логирование и аудит

- Каждый upload/delete/recovery-issue логируется с user_id, ts, IP, user-agent, size.
- В логах **никогда** не дампить blob, только метаданные.

---

### 4. Edge cases

| Случай | Поведение |
|---|---|
| У пользователя нет email при регистрации | Worker всё равно заливает бэкап (привязка идёт по user_id из JWT, не по email). Восстановление по email будет недоступно до момента, когда пользователь задаст email через `PUT /v1/users/me/email`. |
| Размер бэкапа > 100 МБ | На клиенте **slim-режим**: исключаем `blobs_backup/` (вложения) — оставляем только `dc_database_backup.sqlite`. Реализуется ручной post-обработкой `.tar` (распаковать → пересобрать без `blobs_backup/`). История + ключи влезут всегда. |
| Восстановление прерывается на середине загрузки | Файл пишется в `cacheDir/restore.tar.part`. После полной загрузки и проверки sha256 — rename в `restore.tar`, дальше импорт. Незавершённый `.part` чистится при следующем старте. |
| Двое устройств одного аккаунта | Пока поддерживаем один профиль = одно устройство (multi-device — отдельный тикет). При импорте на втором устройстве — overwrite поведения у ядра (`import_backup` стирает текущий профиль, [imex.rs:194-195](../jni/deltachat-core-rust/src/imex.rs#L194-L195)). |
| Blob есть, бэкап версии новее ядра | Серверу всё равно. Клиент при импорте получит ошибку *«profile is from a newer version»* (см. [imex.rs:792-799](../jni/deltachat-core-rust/src/imex.rs#L792-L799)) — обновить приложение и повторить. |
| Конфликт кодов с кодами входа в аккаунт | Используются **разные таблицы** (`backup_recovery_codes` vs существующие). Один и тот же email может одновременно иметь активный код входа и активный backup-recovery-код. |

---

### 5. План внедрения

**Phase 1 — MVP (DEV-57):**

Backend:
1. Миграция БД (таблица `user_backups`, `backup_recovery_codes`, при необходимости — колонки `email*` в `users`).
2. Эндпоинты 3.1.1–3.1.3 (upload/get/delete).
3. Эндпоинты 3.1.4–3.1.5 (установка email + verify).
4. Эндпоинты 3.1.6–3.1.8 (recovery flow).
5. Тесты эндпоинтов.

Android:
6. `CloudBackupApi` (DTO + OkHttp).
7. `BackupUploadWorker` (только one-time запуск после регистрации).
8. `EmailPromptDialog` + `EmailVerifyActivity` + интеграция в `ApplicationContext.onCreate`.
9. `BackupSettingsFragment` (статус, ручной upload, удаление).
10. `CloudRestoreActivity` + кнопка «Restore from cloud» на `WelcomeActivity`.

**Phase 2 — Periodic update (отдельный тикет):**

11. Постановка `PeriodicWorkRequest` (24 ч, constraints) в `ApplicationContext.onCreate`.
12. Метрики в backend: % пользователей с актуальным бэкапом (< 7 дней),
    p95 размер blob'а, частота восстановлений.

---

## Definition of Done

- [ ] Все эндпоинты раздела 3.1 реализованы и покрыты тестами.
- [ ] Android создаёт и заливает `.tar` сразу после регистрации (one-time Worker).
- [ ] Если у пользователя нет email на аккаунте — раз в 24 ч на старте
      приложения показывается `EmailPromptDialog`; введённый email подтверждается
      кодом и сохраняется на аккаунте.
- [ ] На чистом приложении пользователь может ввести email аккаунта, получить
      код и полностью восстановить профиль.
- [ ] Лимиты (100 МБ, rate-limit upload, rate-limit recovery) работают и
      возвращают корректные коды ошибок.
- [ ] Аудит-лог на бэкенде показывает upload/download события.
- [ ] Phase 2 (periodic 24h) вынесена в отдельный тикет и не входит в DoD MVP.
