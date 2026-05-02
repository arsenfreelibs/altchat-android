# ANDROID-XXX: Облачное резервное копирование профиля и восстановление по email

**Jira Link:** https://altchatme.atlassian.net/browse/ANDROID-XXX

---

## Ticket Description

### Problem Statement

Сейчас единственный способ перенести профиль на новое устройство — вручную сохранить
бэкап-файл `alt-chat-backup-*.tar` в `Downloads/` и физически передать его на новый
телефон (см. [docs/profile-backup.md](profile-backup.md)). Это:

- работает только если пользователь заранее знал, что нужно делать бэкап;
- ломается при потере устройства, краже, поломке без сохранённого `.tar`;
- не даёт службе поддержки никакого способа помочь пользователю — у неё нет доступа
  к ключам/чатам/контактам.

Нужен механизм автоматической отправки зашифрованного бэкапа в Alt Backend сразу
после регистрации, периодического обновления и восстановления через email-код на
чистом приложении.

**Current state:**

| Компонент | Расположение | Статус |
|---|---|---|
| Локальный экспорт бэкапа в `.tar` | [DcContext.imex()](../src/main/java/com/b44t/messenger/DcContext.java#L174) | Работает (passphrase зашит как `""`, см. [dc_wrapper.c:952](../jni/dc_wrapper.c#L952)) |
| Локальный импорт бэкапа из `.tar` | [WelcomeActivity:332](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L332) | Работает (только для несконфигурированного профиля) |
| Облачное хранилище бэкапов | — | Отсутствует |
| Recovery email отдельно от email регистрации | — | Отсутствует |
| Фоновая периодическая загрузка | — | Отсутствует |
| UI восстановления через email-код | Частично (Alt restore, ANDROID-001) | Восстанавливает только ключи, не профиль целиком |
| Эндпоинты бэкапа в Alt API | [docs/api-testing.md](../../backend-api/docs/api-testing.md) | Отсутствуют |

### Solution

Реализовать сквозной механизм «облачный бэкап профиля» с тремя точками
автоматизации:

1. **One-shot после регистрации** — сразу после получения JWT клиент создаёт первый
   бэкап и заливает на сервер. Гарантирует, что у каждого активного пользователя
   уже есть резервная копия с момента регистрации (нужно для саппорта).
2. **Периодическое обновление** — раз в сутки в фоне (WorkManager). Бэкап
   перезаписывается на сервере: один пользователь = одна актуальная копия.
3. **Recovery email** — раз в сутки на старте приложения предлагать ввести/подтвердить
   recovery email (если ещё не задан). Это второй email, **не равный** email
   регистрации, на который придёт код для восстановления.

При восстановлении на чистом приложении пользователь вводит recovery email →
получает 6-значный код → клиент верифицирует → сервер отдаёт зашифрованный архив →
клиент дешифрует и импортирует через `DC_IMEX_IMPORT_BACKUP`.

```
┌───────────────────────────────────────────────────────────────────┐
│ UI                                                                │
│  ┌──────────────────┐  ┌────────────────┐  ┌──────────────────┐  │
│  │ RecoveryEmail    │  │ Restore from   │  │ BackupSettings   │  │
│  │ Prompt (1×/день) │  │ Cloud screen   │  │ (статус/удалить) │  │
│  └──────────────────┘  └────────────────┘  └──────────────────┘  │
├───────────────────────────────────────────────────────────────────┤
│ Application: CloudBackupService                                   │
│   — orchestrates: создать .tar → зашифровать → отправить          │
│   — orchestrates: запросить код → скачать blob → дешифровать      │
│     → передать в DcContext.imex(IMPORT_BACKUP)                    │
├───────────────────────────────────────────────────────────────────┤
│ WorkManager: BackupUploadWorker (one-time + periodic 24h)         │
├───────────────────────────────────────────────────────────────────┤
│ Crypto: BackupCipher (AES-256-GCM + Argon2id из recoveryPassword) │
├───────────────────────────────────────────────────────────────────┤
│ Network: CloudBackupApi (OkHttp, см. AltApiClient из ANDROID-001) │
│   POST/GET/DELETE /v1/backup                                       │
│   PUT /v1/users/me/recovery-email + /verify                        │
│   POST /v1/backup/recovery/{request,verify,download}               │
├──────────────────┬────────────────────────────────────────────────┤
│ DeltaChat Core   │  Расширение JNI: imex(what, dir, passphrase)   │
│ (export/import)  │                                                 │
└──────────────────┴────────────────────────────────────────────────┘
```

### Technical Approach

1. Расширить JNI: добавить `DcContext.imexWithPassphrase(int what, String dir, String passphrase)`,
   которая прокидывает passphrase в `dc_imex(...)` (вместо зашитого `""`).
2. Реализовать `BackupCipher` — обёртка над AES-256-GCM с ключом, выводимым из
   `recoveryPassword` через Argon2id (соль = 16 случайных байт, хранится в заголовке
   blob'а).
3. Реализовать `CloudBackupService`:
   - **upload**: запросить экспорт в `cacheDir`, зашифровать, стримить multipart, удалить локальный `.tar`.
   - **restore**: запрос кода → ввод кода → скачать blob → дешифровать → передать как файл в `DcContext.imex(IMPORT_BACKUP, file)`.
4. Реализовать `BackupUploadWorker` (WorkManager): one-time после регистрации +
   periodic с интервалом 24 ч. Constraints: unmetered network, charging, idle.
5. Реализовать UI: `RecoveryEmailPromptDialog`, `CloudRestoreActivity`, `BackupSettingsFragment`.
6. Реализовать в Alt Backend новый модуль `Backup` с эндпоинтами и таблицами
   (см. раздел 5).
7. Хранить `recoveryEmail`, дату последней загрузки, дату последнего prompt'а в
   `EncryptedSharedPreferences` (паттерн из ANDROID-001).

### Related Tickets

- **ANDROID-001**: Интеграция с Alt Platform — даёт `AltApiClient`, JWT-хранилище,
  `recoveryPassword` (он же используется для шифрования бэкапа здесь).
- **DEV-010**: Users Module — даёт регистрацию/верификацию email, на которой
  висит `POST /v1/users/register` и flow `restore`.
- **DEV-XXX (новый)**: Backup Module — серверная часть этого тикета.

---

## Detailed Specifications

### 1. Сценарии пользователя

#### 1.1 Сразу после регистрации

1. Пользователь прошёл `/v1/users/register` → `/v1/users/verify`, получил JWT.
2. UI показывает экран «**Защитите свой аккаунт**» с двумя действиями:
   - «Указать email для восстановления» (рекомендуется);
   - «Пропустить» (можно сделать позже).
3. **Независимо от выбора пользователя**, в фоне ставится one-time
   `BackupUploadWorker`. Цель — гарантировать, что у саппорта есть копия профиля
   к моменту, когда пользователь начнёт пользоваться приложением.
4. Worker создаёт `.tar`, шифрует и заливает на `POST /v1/backup`.
5. Если шага 2 пользователь выбрал «Указать» — открывается `RecoveryEmailPromptDialog`,
   email подтверждается через `PUT /v1/users/me/recovery-email` → код → `…/verify`.

#### 1.2 Периодическое обновление

- При первом старте приложения за день Worker планируется как `OneTimeWorkRequest`
  с задержкой до next-idle-charging-window. Constraints:
  `RequiresCharging=true`, `RequiredNetworkType=UNMETERED`, `RequiresDeviceIdle=true`.
- Если за 7 дней constraints не сработали — relax: `RequiresCharging=false`,
  `RequiredNetworkType=CONNECTED` (по согласованию).
- На сервере хранится **только последняя версия** бэкапа. История версий не нужна
  (см. раздел 5.2 «модель данных»).

#### 1.3 Запрос recovery email раз в день

- При старте приложения, если `recoveryEmail` ещё не задан и с последнего prompt'а
  прошло ≥ 24 ч — показать non-blocking диалог `RecoveryEmailPromptDialog`.
- Кнопки: «Указать email», «Напомнить позже», «Не показывать снова».
- «Не показывать снова» сохраняет флаг навсегда (можно сбросить из настроек
  `BackupSettingsFragment`).

#### 1.4 Восстановление на чистом приложении

1. На `WelcomeActivity` рядом с существующей кнопкой «Restore from backup» (она
   ищет локальный `.tar`) добавить кнопку «**Restore from cloud**».
2. Пользователь вводит recovery email (или, если он не помнит, — email регистрации:
   сервер допускает оба, см. эндпоинт ниже).
3. Клиент дёргает `POST /v1/backup/recovery/request {email}`. Сервер шлёт 6-значный
   код на email, отвечает `202`.
4. Пользователь вводит код. Клиент дёргает `POST /v1/backup/recovery/verify {email, code}`.
   Сервер возвращает `{recoveryToken, blobMeta: {size, sha256, createdAt, kdfParams}}`.
   `recoveryToken` — JWT с TTL 10 мин, scope=`backup:download`.
5. Клиент дёргает `GET /v1/backup/recovery/download` с `Authorization: Bearer <recoveryToken>`,
   получает зашифрованный blob потоком.
6. Клиент запрашивает у пользователя `recoveryPassword` (тот, что задавался при
   регистрации/первом бэкапе).
7. Клиент дешифрует blob → пишет `.tar` в `cacheDir` → вызывает
   `DcContext.imex(DC_IMEX_IMPORT_BACKUP, path)`.
8. После успеха временный файл и blob удаляются.

#### 1.5 Сценарий саппорта

- Пользователь не задавал recovery email (или забыл его) → пишет в саппорт.
- Саппорт через админ-панель находит пользователя по `username`, проверяет личность
  (вне scope ТЗ).
- Саппорт нажимает «Send backup to user email» → `POST /v1/admin/backup/{username}/send`.
- Сервер шлёт пользователю на **email регистрации** ссылку с одноразовым токеном.
  Ссылка ведёт в приложение через deeplink → запускает flow `1.4` начиная с шага 5.
- **Без `recoveryPassword` пользователь всё равно не расшифрует blob**. Это
  сознательное ограничение: саппорт не может «получить доступ к профилю» —
  только облегчить доставку зашифрованного blob'а.

---

### 2. Безопасность и шифрование

Бэкап содержит:
- SMTP/IMAP пароли (таблица `config` внутри `dc.db`);
- приватный OpenPGP-ключ пользователя (`keypairs`);
- всю историю переписки и контакты;
- блобы/вложения.

Поэтому **plain tar на сервер не выкладывается ни при каких условиях**.

#### 2.1 Криптосхема (рекомендуется — Вариант A, end-to-end)

```
plaintext_tar = export_backup(passphrase = "")     // ядро отдаёт незашифрованный .tar
salt          = random(16)
key           = Argon2id(recoveryPassword, salt,
                         m=64MiB, t=3, p=1) → 32 bytes
nonce         = random(12)
ciphertext    = AES-256-GCM(key, nonce, plaintext_tar, aad = header_bytes)

blob = MAGIC(4) || VERSION(1) || salt(16) || nonce(12) || ciphertext || tag(16)
        ←──────────────── header_bytes ───────────────→
```

- `recoveryPassword` пользователь задаёт **один раз** при первом включении
  облачного бэкапа (или переиспользуется из ANDROID-001, если уже задан).
- Сервер видит только `blob` целиком. `recoveryPassword` на сервер не попадает.
- При восстановлении пользователь вводит этот же `recoveryPassword`. Если забыл —
  бэкап бесполезен. Это честный trade-off за privacy.
- Argon2id-параметры подобрать так, чтобы на современном Android-флагмане
  деривация занимала 0.5–1.5 с (защита от перебора при утечке blob'а).

#### 2.2 Альтернатива (Вариант B, server-trusted) — не рекомендуется

Шифрование публичным ключом сервера (как в `docs/encryption.md`). Удобно для
саппорта (он реально может выдать пользователю расшифрованный профиль), но
нарушает E2E: компрометация сервера = компрометация всех бэкапов. Брать только
если продакт сознательно выбирает удобство над приватностью.

> В ТЗ дальше предполагается **Вариант A**.

#### 2.3 Что делать со старым ядерным passphrase'ом

Сейчас [`dc_wrapper.c:952`](../jni/dc_wrapper.c#L952) хардкодит `""`. Не трогаем
это поведение в ядре — оставляем `.tar` незашифрованным **на этапе экспорта**, а
дальше шифруем сами на Android-стороне (легче контролировать KDF, версии, формат
заголовка). Альтернативно — пробросить passphrase в ядро через новую JNI-функцию
и использовать SQLCipher-шифрование, но тогда мы:
- получаем шифрование только для `dc_database_backup.sqlite` внутри tar, блобы
  остаются открытыми;
- теряем возможность сменить KDF без обновления ядра.

Поэтому шифруем **снаружи** на уровне всего blob'а — раздел 2.1.

---

### 3. Расширение DeltaChat-обёртки (Java/JNI)

#### 3.1 Java

Добавить в [`DcContext.java`](../src/main/java/com/b44t/messenger/DcContext.java):

```java
/**
 * @param what       DC_IMEX_EXPORT_BACKUP / DC_IMEX_IMPORT_BACKUP
 * @param dir        каталог (для export) или файл (для import)
 * @param passphrase passphrase для SQLCipher; "" = без шифрования
 */
public native void imexWithPassphrase(int what, String dir, String passphrase);
```

Существующий `imex(int, String)` оставить ради совместимости — он вызывает
`imexWithPassphrase(what, dir, "")`.

#### 3.2 JNI

В [`jni/dc_wrapper.c`](../jni/dc_wrapper.c) рядом со строкой 949 добавить:

```c
JNIEXPORT void Java_com_b44t_messenger_DcContext_imexWithPassphrase(
    JNIEnv *env, jobject obj, jint what, jstring dir, jstring passphrase)
{
    CHAR_REF(dir);
    CHAR_REF(passphrase);
        dc_imex(get_dc_context(env, obj), what, dirPtr, passphrasePtr);
    CHAR_UNREF(passphrase);
    CHAR_UNREF(dir);
}
```

В рамках MVP `passphrase = ""` (шифруем мы снаружи, см. 2.3).

---

### 4. Android-клиент

#### 4.1 Хранилище

`EncryptedSharedPreferences` (паттерн из ANDROID-001), namespace
`cloud_backup`:

| Ключ | Тип | Назначение |
|---|---|---|
| `recovery_email` | String? | подтверждённый email для восстановления |
| `recovery_email_pending` | String? | email, на который послан код, но ещё не подтверждён |
| `recovery_password_hash` | String? | хэш для проверки «тот ли пароль ввёл пользователь» (Argon2id с другой солью), без обратимости |
| `last_backup_at` | Long | unix-ms последней успешной загрузки |
| `last_backup_size` | Long | байты последнего загруженного blob'а |
| `last_prompt_at` | Long | unix-ms последнего показа `RecoveryEmailPromptDialog` |
| `prompt_disabled` | Boolean | пользователь нажал «не показывать снова» |

#### 4.2 BackupUploadWorker

Подкласс `androidx.work.CoroutineWorker`. Алгоритм `doWork()`:

1. Проверить наличие JWT и `recoveryPassword`. Если нет — `Result.success()` (не пытаемся, не ругаемся).
2. Создать tmp-каталог `cacheDir/cloud-backup-<uuid>/`.
3. Вызвать `dcContext.imex(DC_IMEX_EXPORT_BACKUP, tmpDir)` — синхронно ждать
   `DC_EVENT_IMEX_PROGRESS = 1000` или `0`.
4. Найти созданный `alt-chat-backup-*.tar` в tmpDir.
5. Стримно зашифровать в `cipher.bin` (см. 2.1) — без полной загрузки в RAM.
6. POST `multipart/form-data` на `/v1/backup` с `Authorization: Bearer <jwt>`.
7. Удалить tmpDir (включая plain tar).
8. Записать `last_backup_at`, `last_backup_size`.
9. При ошибке — `Result.retry()` с экспоненциальным backoff, до 5 попыток.

Constraints:

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

Запуск:

- **One-time** сразу после успешного `verify`-флоу (см. ANDROID-001) — без
  constraints, чтобы загрузить быстрее.
- **Periodic** один раз при первом старте приложения с конфигурированным профилем.

#### 4.3 UI

| Экран / диалог | Что делает |
|---|---|
| `RecoveryEmailPromptDialog` | Раз в 24 ч предлагает ввести email; не блокирует UI |
| `RecoveryEmailVerifyActivity` | Вводит код, дёргает `…/recovery-email/verify` |
| `BackupSettingsFragment` | Settings → Cloud Backup: статус (есть/нет, дата, размер), кнопки «Загрузить сейчас», «Удалить с сервера», «Сменить recovery email», «Сменить recovery password» |
| `CloudRestoreActivity` | Стартует с `WelcomeActivity` → кнопка «Restore from cloud»; шаги email → код → recoveryPassword → восстановление |

#### 4.4 Точки интеграции

- `ApplicationContext.onCreate` после блока инициализации `DcAccounts`
  ([ApplicationContext.java:189](../src/main/java/org/thoughtcrime/securesms/ApplicationContext.java#L189))
  — проверка `last_prompt_at` и плановая постановка PeriodicWorkRequest.
- `WelcomeActivity` — добавить кнопку «Restore from cloud» рядом с «Restore from backup»
  ([WelcomeActivity.java:255](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L255)).
- В flow регистрации Alt (ANDROID-001) — после успешной верификации поставить
  one-time `BackupUploadWorker`.

---

### 5. Backend API

Новый scope `/v1/backup`. Все эндпоинты в проекте `AltChat.Api`, новый модуль
`Backup` рядом с `Users`/`Relays`. Ошибки в формате `{"error":"..."}`, как в
существующем API ([api-testing.md](../../backend-api/docs/api-testing.md)).

#### 5.1 Эндпоинты

##### 5.1.1 `POST /v1/backup` — загрузить/перезаписать бэкап

- **Auth**: `Authorization: Bearer <jwt>`
- **Body**: `multipart/form-data`
  - `blob` (file, required): зашифрованный blob (формат из 2.1)
  - `sha256` (string, required): hex sha256 от `blob`
  - `kdf` (json, required): `{"algo":"argon2id","m":65536,"t":3,"p":1}` — для
    отображения пользователю при восстановлении
- **Лимиты**:
  - размер blob'а ≤ 50 МБ;
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
  -F "blob=@/tmp/cipher.bin;type=application/octet-stream" \
  -F "sha256=2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae" \
  -F 'kdf={"algo":"argon2id","m":65536,"t":3,"p":1}'
```

##### 5.1.2 `GET /v1/backup` — метаданные текущего бэкапа

- **Auth**: JWT
- **Ответы**:
  - `200 {"size":...,"sha256":"...","createdAt":"<iso>","kdf":{...}}`
  - `404 {"error":"no_backup"}`

```bash
curl -s $BASE/v1/backup -H "Authorization: Bearer $TOKEN"
```

##### 5.1.3 `DELETE /v1/backup` — удалить свой бэкап

- **Auth**: JWT
- **Ответы**: `204` / `404 {"error":"no_backup"}`

##### 5.1.4 `PUT /v1/users/me/recovery-email` — задать/сменить recovery email

- **Auth**: JWT
- **Body**: `{"email":"new@example.com"}`
- Сервер шлёт код на новый email, сохраняет как `pending`.
- **Ответы**: `202` / `409 {"error":"same_as_account_email"}` /
  `429` / `400 {"error":"invalid_email"}`

```bash
curl -s -X PUT $BASE/v1/users/me/recovery-email \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"recover@example.com"}'
```

##### 5.1.5 `POST /v1/users/me/recovery-email/verify` — подтвердить кодом

- **Auth**: JWT
- **Body**: `{"code":"123456"}`
- **Ответы**: `200 {"recoveryEmail":"recover@example.com"}` /
  `400 {"error":"invalid_or_expired_code"}`

##### 5.1.6 `DELETE /v1/users/me/recovery-email` — снять recovery email

- **Auth**: JWT
- **Ответ**: `204`

##### 5.1.7 `POST /v1/backup/recovery/request` — запросить код на чистом устройстве

- **Auth**: **нет** (на чистом приложении токена нет)
- **Body**: `{"email":"recover@example.com"}` или `{"email":"<email регистрации>"}`
- Сервер ищет пользователя по `recoveryEmail` **или** по email регистрации
  (оба варианта допустимы — пользователь может не помнить, какой задавал).
  Для защиты от перебора отвечает одинаково в обоих случаях `202` независимо
  от того, найден пользователь или нет.
- Шлёт код на найденный email. Если пользователь не найден — ничего не шлёт,
  но всё равно `202`.
- **Лимиты**: 3 запроса / час / IP, 5 / сутки / email.

```bash
curl -s -X POST $BASE/v1/backup/recovery/request \
  -H "Content-Type: application/json" \
  -d '{"email":"recover@example.com"}'
```

##### 5.1.8 `POST /v1/backup/recovery/verify` — подтвердить и получить токен на скачивание

- **Auth**: нет
- **Body**: `{"email":"recover@example.com","code":"123456"}`
- **Ответы**:
  - `200 {"recoveryToken":"<jwt, scope=backup:download, ttl=600s>","blobMeta":{...}}`
  - `400 {"error":"invalid_or_expired_code"}`
  - `404 {"error":"no_backup"}` — пользователь найден, но бэкапа нет

##### 5.1.9 `GET /v1/backup/recovery/download` — скачать blob

- **Auth**: `Authorization: Bearer <recoveryToken>` (scope=`backup:download`)
- **Ответ**: `200 application/octet-stream`, тело — сам blob.
  Заголовок `X-Backup-SHA256: <hex>` для контроля целостности на клиенте.
- **Один токен — одно успешное скачивание.** После 200 токен инвалидируется.
- `401` если токен не того scope или просрочен.

##### 5.1.10 `POST /v1/admin/backup/{username}/send` — саппорт пушит ссылку на скачивание

- **Auth**: `X-Admin-Key: <admin_key>`
- **Body**: пусто или `{"reason":"<свободный текст для лога>"}`
- Сервер генерирует одноразовый токен (scope=`backup:download`, ttl=24ч) и шлёт
  на **email регистрации** пользователя письмо с deeplink в приложение
  `altchat://restore?token=...`.
- **Ответы**: `202` / `404 {"error":"user_not_found"}` / `404 {"error":"no_backup"}`

```bash
curl -s -X POST $BASE/v1/admin/backup/alice/send \
  -H "X-Admin-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"reason":"ticket #4711, ID verified"}'
```

#### 5.2 Модель данных (Backend)

```sql
CREATE TABLE user_backups (
    user_id        BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    blob_path      TEXT NOT NULL,            -- путь в S3/MinIO/диске
    blob_size      BIGINT NOT NULL,
    sha256         TEXT NOT NULL,
    kdf_params     JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE users
    ADD COLUMN recovery_email           TEXT NULL,
    ADD COLUMN recovery_email_pending   TEXT NULL,
    ADD COLUMN recovery_email_code_hash TEXT NULL,
    ADD COLUMN recovery_email_code_expires_at TIMESTAMPTZ NULL;

CREATE INDEX idx_users_recovery_email ON users(recovery_email);
```

Сами blob'ы хранить **не в Postgres**, а в объектном сторадже (S3/MinIO),
имена — `backups/<user_uuid>.bin`. Postgres держит только метаданные.

#### 5.3 Хранение и удаление

- При `POST /v1/backup` старый blob атомарно заменяется новым (write to
  `<user_uuid>.bin.tmp` → rename).
- При удалении пользователя — каскадное удаление blob'а из объектного хранилища
  (отдельным джобом, чтобы не блокировать запрос).
- При смене recovery email — никаких действий на blob'е (он зашифрован клиентом
  и не зависит от email).

#### 5.4 Логирование и аудит

- Каждый upload/delete/recovery-issue логируется с user_id, ts, IP, user-agent,
  size.
- `POST /v1/admin/backup/.../send` пишется в отдельный аудит-лог саппорта с
  reason и admin-id.
- В логах **никогда** не дампить blob, только метаданные.

---

### 6. Edge cases

| Случай | Поведение |
|---|---|
| Пользователь меняет recoveryPassword на телефоне | Старый blob на сервере становится недешифруемым с нового пароля. Worker должен сразу залить новый blob, зашифрованный новым паролем. До успешной перезаписи — пометить локально «backup outdated». |
| Пользователь удалил приложение, не задал recoveryEmail, пишет в саппорт | Саппорт через `POST /v1/admin/backup/{username}/send` шлёт письмо на email регистрации с deeplink. Пользователь всё равно должен помнить recoveryPassword. |
| Двое устройств одного аккаунта | Пока поддерживаем один профиль = одно устройство (multi-device — отдельный тикет). При импорте на втором устройстве — overwrite поведения у ядра (`import_backup` стирает текущий профиль, [imex.rs:194-195](../jni/deltachat-core-rust/src/imex.rs#L194-L195)). |
| Размер бэкапа > 50 МБ | На клиенте **slim-режим**: исключаем `blobs_backup/` (вложения) — оставляем только `dc_database_backup.sqlite`. Реализуется отдельным флагом в JNI export, либо ручной post-обработкой `.tar` (распаковать → пересобрать без `blobs_backup/`). История + ключи влезут всегда. Полные блобы — best-effort, если пройдут по лимиту. |
| Восстановление прерывается на середине загрузки | Файл пишется в `cacheDir/restore.bin.part`. После полной загрузки и проверки sha256 — rename в `restore.bin`, дальше дешифровка. Незавершённый `.part` чистится при следующем старте. |
| Recovery email == email регистрации | Сервер отвергает (`409 same_as_account_email`). Recovery email должен быть заведомо другим (иначе теряется смысл — компрометация одного email = всё пропало). |
| Argon2id-параметры на слабом устройстве | KDF выполняется в `IO`-диспатчере с прогресс-бар-индикатором. Если устройство не тянет m=64MiB, fallback m=32MiB; параметры пишутся в заголовок blob'а, чтобы при восстановлении использовать те же. |
| User clear data на клиенте | Стирает `recoveryPassword` (он не на сервере). Восстановление невозможно без него — это известное ограничение E2E-схемы. Предупредить пользователя в onboarding'е. |
| Blob есть, бэкап версии новее ядра | Серверу всё равно. Клиент при импорте получит ошибку *«profile is from a newer version»* (см. [imex.rs:792-799](../jni/deltachat-core-rust/src/imex.rs#L792-L799)) — обновить приложение и повторить. |

---

### 7. План внедрения

**Phase 1 — Backend (DEV-XXX):**
1. Миграция БД (таблица `user_backups`, поля в `users`).
2. Эндпоинты 5.1.1–5.1.6 (upload/get/delete + recovery-email управление).
3. Эндпоинты 5.1.7–5.1.9 (recovery flow).
4. Эндпоинт 5.1.10 (саппорт).
5. Тесты в стиле [api-testing.md](../../backend-api/docs/api-testing.md).

**Phase 2 — Android crypto + JNI:**
6. `imexWithPassphrase` JNI + Java.
7. `BackupCipher` (Argon2id + AES-GCM) + unit-тесты на векторах.
8. Локальный e2e-тест: export → encrypt → decrypt → import (без сервера).

**Phase 3 — Android network + Worker:**
9. `CloudBackupApi` (DTO + OkHttp).
10. `BackupUploadWorker` + интеграция в `ApplicationContext`.
11. One-time запуск после регистрации (точка после ANDROID-001 verify).

**Phase 4 — Android UI:**
12. `BackupSettingsFragment` (статус, ручные действия).
13. `RecoveryEmailPromptDialog` + `RecoveryEmailVerifyActivity`.
14. `CloudRestoreActivity` + кнопка на `WelcomeActivity`.

**Phase 5 — Поддержка и мониторинг:**
15. Админ-панель: страница «Backups» с фильтром по username, кнопка «Send recovery link».
16. Метрики в backend: % пользователей с recoveryEmail, % с актуальным
    бэкапом (< 7 дней), p95 размер blob'а.

---

### 8. Открытые вопросы (нужно решение перед реализацией)

1. **E2E vs server-trusted шифрование** — рекомендую E2E (Вариант A из 2.1).
   Решение продакта.
2. **Slim vs full режим** — что делать, если бэкап > 50 МБ. Варианты: автоматически
   slim; спросить пользователя; поднять лимит для платных аккаунтов.
3. **Можно ли восстановить с того же устройства, на котором уже есть профиль?**
   Сейчас ядро запрещает (см. edge case в таблице). Возможно стоит сделать UI
   «Replace local profile from cloud» с подтверждением.
4. **Многоустройственный сценарий** — если у пользователя А устройство 1 и
   устройство 2, и на 2 он восстановился — что произойдёт с device-token'ами,
   FCM, и какой ключ Autocrypt будет «правильным»? Скорее всего отдельный
   тикет — multi-device.
5. **Удержание blob'ов после удаления аккаунта** — мгновенно стираем или GDPR-
   совместимый retention 30 дней? Скорее мгновенно, согласовать с юристом.
6. **Перешифрование blob'а на сервере при смене recoveryPassword** — клиент
   обязан перезалить, но если устройство недоступно — старый blob останется
   неактуальным. Стоит ли сервер уметь принимать сигнал «invalidate» (метка,
   что blob больше не дешифруется текущим паролем)?

---

## Definition of Done

- [ ] Все эндпоинты раздела 5.1 реализованы и покрыты тестами в стиле
      `api-testing.md`.
- [ ] Android создаёт и заливает зашифрованный blob после регистрации.
- [ ] Android заливает обновлённый blob раз в 24 ч (WorkManager).
- [ ] Раз в 24 ч на старте приложения показывается prompt про recovery email
      (если не задан и не отключён).
- [ ] На чистом приложении пользователь может ввести recovery email, получить
      код, ввести recoveryPassword и полностью восстановить профиль.
- [ ] Саппорт через админ-эндпоинт умеет послать пользователю ссылку на
      скачивание, не получая доступ к содержимому профиля.
- [ ] Blob нельзя расшифровать без `recoveryPassword` — задокументировано
      и проверено отдельным тестом.
- [ ] Лимиты (50 МБ, rate-limit upload, rate-limit recovery) работают и
      возвращают корректные коды ошибок.
- [ ] Мониторинг и аудит-лог на бэкенде показывают upload/download события
      и саппорт-ссылки.
