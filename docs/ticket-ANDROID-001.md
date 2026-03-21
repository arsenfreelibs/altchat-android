# ANDROID-001: Интеграция с Alt Platform — Регистрация, Восстановление ключей и Поиск пользователей

**Jira Link:** https://altchatme.atlassian.net/browse/ANDROID-001

---

## Ticket Description

### Problem Statement

Приложение deltachat-android работает как автономный мессенджер поверх email/IMAP, но не интегрировано с платформой Alt. Пользователи не могут зарегистрироваться в директории Alt, не могут найти других участников платформы по никнейму, а при смене устройства теряют доступ к PGP-ключам — нет механизма резервного копирования через Alt Backend. Платформенная идентичность (никнейм, публичный ключ, шифрованный бэкап приватного ключа) нигде не ведётся на стороне Android-приложения.

**Current state:**
| Компонент | Расположение | Статус |
|---|---|---|
| Alt API HTTP-клиент | — | Отсутствует |
| Экраны регистрации на платформе | — | Отсутствуют |
| Экран восстановления ключей | — | Отсутствует |
| Поиск пользователей Alt | — | Отсутствует |
| Хранение JWT-токена | — | Отсутствует |
| Шифрование приватного ключа паролем восстановления | — | Отсутствует |

### Solution

Реализовать Android-клиентскую часть модуля пользователей платформы Alt. Интеграция строится как отдельный пакет `altplatform/` поверх существующей архитектуры — без изменения DeltaChat core и RPC-слоя. Новый функционал добавляется в UI в двух точках:

1. **После онбординга** — кнопка/предложение зарегистрироваться на платформе Alt (задать никнейм, передать публичный ключ).
2. **В поиске контактов** — новая вкладка/кнопка «Найти в Alt» для поиска пользователей платформы.

Архитектура:
```
┌───────────────────────────────────────────────────────────┐
│  UI: AltRegistrationActivity / AltUserSearchActivity       │
│      AltRegistration*Fragment / AltUserSearchFragment      │
├───────────────────────────────────────────────────────────┤
│  Application: AltPlatformService                           │
│    — оркестрирует вызовы API + взаимодействие с DC core    │
├───────────────────────────────────────────────────────────┤
│  Network: AltApiClient (OkHttp) + AltApiService            │
│    — HTTP-вызовы к Alt Backend API                         │
├──────────────────┬────────────────────────────────────────┤
│  DeltaChat Core  │  AltTokenStorage / AltPrefs             │
│ (экспорт ключей) │  (SharedPreferences/Keystore)           │
└──────────────────┴────────────────────────────────────────┘
```

### Technical Approach

1. Реализовать HTTP-клиент (`AltApiClient`) на базе `OkHttp` с DTO и `AltApiService`.
2. Реализовать `AltPlatformService` — логику регистрации: экспорт публичного ключа из DC core, шифрование приватного ключа паролем восстановления (AES-GCM), отправка в Alt API.
3. Реализовать flow регистрации — 3 шага: ввод никнейма/email аккаунта (addr подставляется автоматически из DC core) → код из email → пароль восстановления.
4. Реализовать flow восстановления ключей — 3 шага: ввод никнейма/email → код → пароль восстановления → импорт ключей в DC core.
5. Реализовать экран поиска пользователей Alt — вызов `GET /v1/users/search`, результаты с кнопкой «Добавить в контакты».
6. Добавить точку входа в поиск из `ConversationListActivity` и/или `ContactSelectionListFragment`.
7. Хранить JWT-токен в `SharedPreferences`, зашифрованный через Android Keystore (EncryptedSharedPreferences).

### Related Tickets

- **DEV-002**: Project Setup — Alt Backend API (структура проекта, заглушки)
- **DEV-010**: Users Module — Phase 1 Implementation (серверная реализация, с которой интегрируется Android)

---

## Detailed Specifications

### 1. Пакет `altplatform/network/` — HTTP-клиент

**Goal**: Типобезопасный клиент для вызовов Alt Backend API.

**Изменения**:
- Создать `AltApiClient.java` — строит `OkHttpClient` с таймаутами 15 с, добавляет `Authorization: Bearer <token>` если токен есть.
- Создать `AltApiService.java` — методы для всех эндпоинтов модуля пользователей (синхронные через `OkHttp Call`, вызываются из фонового потока).
- Создать DTO-классы в `altplatform/network/dto/`:  реквест-объекты (`RegisterRequest`, `VerifyRequest`, `ResendCodeRequest`, `RestoreRequest`) и объект ответа (`UserProfileResponse` с полями `addr`, `name`, `fingerprint`, `publicKey`).

**Конфигурационные параметры**:
| Параметр | Тип | Обязательный | Описание |
| :--- | :--- | :--- | :--- |
| `ALT_API_BASE_URL` | `String` (BuildConfig) | Да | Базовый URL Alt Backend (например `https://api.altchat.me`) |

---

### 2. Пакет `altplatform/` — AltPlatformService

**Goal**: Сервисный слой — согласует HTTP-вызовы с операциями DC core.

**Изменения**:
- Создать `AltPlatformService.java` — инжектируется через `ApplicationContext`:
  - `register(username, email, addr, displayName, recoveryPassword)` — экспортирует ключи из DC core через `dcContext.imex(DC_IMEX_EXPORT_SELF_KEYS, tempDir)` (асинхронно — ждёт `DC_EVENT_IMEX_PROGRESS == 1000`), читает `public.asc` / `private.asc`, шифрует приватный ключ паролем восстановления (`AltKeyCrypto.encrypt`), вызывает `AltApiService.register()`. (`email` — почта аккаунта для верификации; `addr` — Delta Chat адрес для обмена сообщениями, берётся автоматически из `dcContext.getConfig("addr")`).
  - `verifyEmail(email, code)` — вызывает `/v1/users/verify`, сохраняет возвращённый JWT через `AltTokenStorage`.
  - `resendCode(email)` — вызывает `/v1/users/resend-code`.
  - `restore(username, email)` — инициирует восстановление (отправка кода).
  - `downloadAndImportKey(token, recoveryPassword)` — скачивает зашифрованный blob (`GET /me/private-key`), расшифровывает локально паролем восстановления, записывает `private.asc` во временную директорию, импортирует ключ обратно в DC core через `dcContext.imex(DC_IMEX_IMPORT_SELF_KEYS, tempDir)` (асинхронно — ждёт `DC_EVENT_IMEX_PROGRESS == 1000`), удаляет временный файл.
  - `searchUsers(query)` — вызывает `GET /v1/users/search?q=`, возвращает `List<UserProfileResponse>`.
  - `getProfile(username)` — вызывает `GET /v1/users/{username}`.
  - `addContactFromAlt(userProfile)` — создаёт DeltaChat-контакт с импортом публичного ключа через `Rpc.importVcardContents()`: формирует vCard-строку с полями `FN`, `EMAIL`, `KEY` (base64 PGP-ключ) и вызывает `DcHelper.getRpc().importVcardContents(accountId, vcardString)` — ядро само добавляет запись в `public_keys` и `contacts`, вычисляет fingerprint и устанавливает верификацию.

---

### 3. Пакет `altplatform/crypto/` — AltKeyCrypto

**Goal**: Зашифровать для хранения на сервере **приватный ключ ядра DC** — тот самый OpenPGP-ключ, которым ядро подписывает и расшифровывает сообщения E2EE с контактами.

> **Почему это именно тот ключ**: `dcContext.imex(DC_IMEX_EXPORT_SELF_KEYS, dir)` экспортирует пару `public.asc` / `private.asc` — бинарный OpenPGP keypair, fingerprint которого хранится в `contacts.fingerprint`. Импорт обратно через `imex(DC_IMEX_IMPORT_SELF_KEYS, dir)` полностью восстанавливает E2EE-идентичность. Это единственный способ перенести ключи на новое устройство.

**Изменения**:
- Создать `AltKeyCrypto.java`:
  - `encrypt(byte[] plaintext, String password)` → `byte[]` — принимает raw-байты файла `private.asc` (экспортированный ядром приватный OpenPGP-ключ). AES-256-GCM, ключ деривируется из пароля через PBKDF2WithHmacSHA256 (100 000 итераций), 16-байтный random salt, 12-байтный random nonce. Формат blob: `[16 bytes salt][12 bytes nonce][ciphertext + 16 bytes GCM tag]`.
  - `decrypt(byte[] ciphertext, String password)` → `byte[]` — обратная операция; бросает `AltCryptoException` при неверном пароле (GCM tag mismatch).
- Нигде не хранить пароль восстановления. Только на время операции в памяти. Никаких внешних криптобиблиотек — только `javax.crypto` (стандартная Android JCE).

---

### 4. Пакет `altplatform/storage/` — AltTokenStorage и AltPrefs

**Goal**: Безопасное хранение JWT-токена и состояния регистрации.

**Изменения**:
- Создать `AltTokenStorage.java`:
  - Использует `EncryptedSharedPreferences` (AndroidX Security Crypto) — ключ в Android Keystore.
  - `saveToken(String token)` / `getToken()` / `clearToken()`.
- Создать `AltPrefs.java`:
  - Хранит в обычном `SharedPreferences`: `alt_username` (String), `alt_email` (String), `alt_registered` (boolean).
  - `isRegistered()`, `getUsername()`, `getEmail()`, `setRegistered(username, email)`.

---

### 5. UI — Flow регистрации (`altplatform/registration/`)

**Goal**: Провести пользователя через трёхшаговую регистрацию на платформе Alt.

**Изменения**:
- Создать `AltRegistrationActivity.java` — контейнер с `FragmentManager`, управляет переходами между шагами.
- Создать `AltStep1Fragment.java` (Никнейм и Email аккаунта):
  - Поля:
    - `EditText` для никнейма (валидация: `[a-zA-Z0-9_]{3,30}`).
    - `EditText` для **email аккаунта** — почта для OTP-верификации и восстановления (не обязательно совпадает с DC addr).
    - `EditText` для отображаемого имени (необязательно).
    - Read-only поле, показывающее **Delta Chat addr** (автоматически из `dcContext.getConfig("addr")`), с пояснением: «Ваш адрес для приёма сообщений».
  - Кнопка «Далее» — вызывает `AltPlatformService.register()` в `ExecutorService`, показывает `ProgressDialog`.
  - Обрабатывает ошибки: `username_taken` (409), `email_taken` (409), `username_reserved` (422), невалидный формат (422).
- Создать `AltStep2Fragment.java` (Код подтверждения):
  - `EditText` для 6-значного кода.
  - Кнопка «Подтвердить» — вызывает `AltPlatformService.verifyEmail()`.
  - Кнопка «Отправить повторно» — вызывает `AltPlatformService.resendCode()`, блокируется на 60 с (countdown timer).
  - Обрабатывает ошибки: `invalid_or_expired_code` (400), rate limit (429).
- Создать `AltStep3Fragment.java` (Пароль восстановления):
  - Два поля для пароля восстановления (с подтверждением).
  - Явное предупреждение: "Если вы забудете этот пароль — восстановление ключей будет невозможно. Хранить его нужно отдельно от пароля приложения."
  - Кнопка «Завершить» — запускает фоновую операцию (экспорт ключей + шифрование + передача). По завершении показывает успех, сохраняет `AltPrefs.setRegistered()`.

---

### 6. UI — Flow восстановления ключей (`altplatform/restore/`)

**Goal**: Восстановить ключи на новом устройстве через Alt Platform.

**Изменения**:
- Создать `AltRestoreActivity.java` — контейнер с `FragmentManager`.
- Создать `AltRestoreStep1Fragment.java`:
  - Поля: никнейм, email.
  - Кнопка «Далее» — вызывает `AltPlatformService.restore()`, отправляет код на email.
  - Ошибка `user_not_found` (404) — сообщить пользователю.
- Создать `AltRestoreStep2Fragment.java` (код подтверждения):
  - Аналогично `AltStep2Fragment`, но проверяет код восстановления.
- Создать `AltRestoreStep3Fragment.java` (пароль восстановления):
  - Одно поле для пароля восстановления.
  - Кнопка «Восстановить» — вызывает `AltPlatformService.downloadAndImportKey()`.
  - Если расшифровка не удалась — сообщить: "Неверный пароль восстановления".

---

### 7. UI — Поиск пользователей Alt (`altplatform/search/`)

**Goal**: Найти пользователя платформы Alt по никнейму или имени и добавить в контакты.

**Изменения**:
- Создать `AltUserSearchActivity.java` — Activity с `Toolbar` и встроенным поиском.
- Создать `AltUserSearchFragment.java`:
  - `SearchView` в тулбаре — вызывает `AltPlatformService.searchUsers(query)` с задержкой 400 мс (debounce).
  - `RecyclerView` с результатами.
  - Пустое состояние: иконка + текст «Начните вводить никнейм или имя».
  - Состояние «не авторизован»: кнопка «Зарегистрироваться в Alt» → `AltRegistrationActivity`.
- Создать `AltUserSearchAdapter.java`:
  - `RecyclerView.Adapter` — отображает `UserProfileResponse`: никнейм (bold), отображаемое имя, email.
  - Кнопка «Написать» на каждой строке — вызывает `AltPlatformService.addContactFromAlt(profile)`, затем открывает `ConversationActivity` с новым контактом.

---

### 8. Точки входа в UI

**Goal**: Органично встроить Alt Platform функционал в существующий UI.

**Изменения**:

**`ConversationListActivity.java`** — MODIFY:
- В меню (overflow menu) добавить пункт «Найти в Alt» — запускает `AltUserSearchActivity`.
- После первого онбординга (если `AltPrefs.isRegistered() == false`) показывать Snackbar: «Зарегистрируйтесь в Alt, чтобы вас могли найти по никнейму» с кнопкой «Зарегистрироваться».

**`ContactSelectionListFragment.java`** — MODIFY:
- Добавить кнопку/таб «Поиск в Alt» в шапке фрагмента — запускает `AltUserSearchActivity`.

---

### 9. Механизм добавления контакта с публичным ключом (ядро DC)

**Goal**: Понять точный путь вызовов для добавления найденного пользователя в DC-контакты с E2EE.

**Метод ядра**: `Rpc.importVcardContents(accountId, vcardString)` — JSON-RPC метод `import_vcard_contents`, реализованный в `jni/deltachat-core-rust/src/contact.rs` (`import_vcard_contact`). Принимает строку vCard в формате 4.0, возвращает `List<Integer>` — id созданных/обновлённых контактов.

**Что делает ядро внутри (выполняется атомарно)**:
1. Парсит vCard, читает `EMAIL` и `KEY` (base64 OpenPGP).
2. Вычисляет fingerprint: `public_key.dc_fingerprint().hex()`.
3. `INSERT INTO public_keys (fingerprint, public_key) VALUES (?, ?) ON CONFLICT (fingerprint) DO NOTHING`.
4. `INSERT INTO contacts (name, name_normalized, addr, fingerprint, origin, authname) VALUES ... ON CONFLICT (fingerprint) DO UPDATE SET addr=..., authname=...` — `origin = 16777216` (`Origin::CreateChat`).
5. `UPDATE contacts SET verifier = 1 WHERE fingerprint = ? AND verifier = 0` — устанавливает верификацию через `ContactId::SELF` → **зелёная галочка**.

**vCard-формат** для передачи в `importVcardContents`:
```
BEGIN:VCARD
VERSION:4.0
FN:<displayName или addr>
EMAIL:<addr>
KEY;MEDIATYPE=application/pgp-keys:<publicKey в base64, как пришёл с сервера>
END:VCARD
```
> Поле `KEY` передаётся без переносов строк. Если ключ большой — base64 без пробелов, одной строкой.

**Пример вызова из Java** (в `AltPlatformService`, фоновый поток):
```java
Rpc rpc = DcHelper.getRpc(context);
int accountId = DcHelper.getAccounts(context).getSelectedAccountId();

String vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\n"
    + "FN:" + profile.name + "\r\n"
    + "EMAIL:" + profile.addr + "\r\n"
    + "KEY;MEDIATYPE=application/pgp-keys:" + profile.publicKey + "\r\n"
    + "END:VCARD";

List<Integer> ids = rpc.importVcardContents(accountId, vcard);
int contactId = ids.get(0);  // использовать для открытия чата
```

**Получить contactId для открытия чата** после добавления:
```java
// Если нужно открыть чат — создаём chatId из contactId
int chatId = rpc.createChatByContactId(accountId, contactId);
// Далее открываем ConversationActivity с chatId
```

**Получение публичного ключа и fingerprint своего аккаунта** (для регистрации на сервере):
- Через JNI: `DcContext.getSelfPublicKey()` / `DcContext.getSelfFingerprint()` (проверить наличие в `DcContext.java`).
- Если JNI-методов нет — через Rpc: `DcHelper.getRpc(context).getEncryptionInfo(accountId, contactId)` возвращает текстовый блок с fingerprint; либо экспорт ключей через `DcContext.exportSelfKeys(outputDir)` и чтение файла `public.asc`.

---

### 10. Строковые ресурсы

**Goal**: Все пользовательские строки вынесены в `strings.xml`.

**`src/main/res/values/strings.xml`** — MODIFY:
- Добавить строки для: заголовков экранов, плейсхолдеров полей, текстов ошибок (`alt_error_username_taken`, `alt_error_email_taken`, `alt_error_username_reserved`, `alt_error_invalid_code`, `alt_error_user_not_found`, `alt_error_rate_limit`), предупреждения о пароле восстановления, кнопок.

---

## Documentation Requirements

**`docs/android-architecture.md`**:
- Добавить описание пакета `altplatform/` в раздел «Application Layer» с описанием `AltPlatformService`, `AltApiClient`, `AltTokenStorage`.

**`docs/project-structure.md`**:
- Добавить описание всех новых файлов пакета `altplatform/`.

---

## Development Plan

### **Overview**

Задача добавляет в Android-приложение интеграцию с Alt Platform: регистрацию пользователя в директории, резервный бэкап PGP-ключей с шифрованием паролем восстановления, восстановление ключей на новом устройстве и поиск других пользователей платформы. Всё реализуется как изолированный пакет `altplatform/` без изменений в DeltaChat Core и RPC-слое. Взаимодействие с бэкендом — через `OkHttp` (уже присутствует в зависимостях как транзитивная зависимость).

---

### **Instructions:**

1. **Создать пакет `altplatform/` и HTTP-инфраструктуру**
   - Создать `AltApiClient.java` с настройкой `OkHttpClient` (таймауты, перехватчик Authorization)
   - Создать `AltApiService.java` с методами для всех 10 эндпоинтов
   - Создать DTO-классы в `altplatform/network/dto/`
   - Убедиться, что `okhttp3` и `Gson`/`org.json` присутствуют в `build.gradle` (проверить существующие зависимости)

2. **Реализовать `AltKeyCrypto`**
   - AES-256-GCM + PBKDF2WithHmacSHA256 для шифрования/расшифровки приватного ключа
   - Юнит-тест: зашифровать → расшифровать → убедиться, что данные совпадают

3. **Реализовать `AltTokenStorage` и `AltPrefs`**
   - `EncryptedSharedPreferences` для JWT (потребует `androidx.security:security-crypto` в `build.gradle`)
   - `AltPrefs` поверх стандартного `SharedPreferences`

4. **Реализовать `AltPlatformService`**
   - Метод `register()`: экспорт ключей из DC core, шифрование, API-вызов
   - Метод `verifyEmail()`: вызов API, сохранение JWT
   - Методы `restore()` / `downloadAndImportKey()`: скачивание, расшифровка, импорт в DC core
   - Методы `searchUsers()` / `getProfile()` / `addContactFromAlt()`

5. **Реализовать UI регистрации**
   - `AltRegistrationActivity` + три Fragment'а
   - Layout для каждого шага
   - Тексты ошибок, валидация, ProgressDialog

6. **Реализовать UI восстановления ключей**
   - `AltRestoreActivity` + три Fragment'а
   - Layout для каждого шага

7. **Реализовать UI поиска**
   - `AltUserSearchActivity` + `AltUserSearchFragment` + `AltUserSearchAdapter`
   - Layout для Activity, Fragment, строки результата

8. **Встроить точки входа**
   - Добавить пункт меню «Найти в Alt» в `ConversationListActivity`
   - Добавить кнопку в `ContactSelectionListFragment`
   - Onboarding Snackbar в `ConversationListActivity`

9. **Добавить строковые ресурсы**
   - Все строки в `strings.xml`

10. **Обновить документацию**
    - `android-architecture.md`, `project-structure.md`

---

### **Files to Review Before Starting:**

1. **`src/main/java/org/thoughtcrime/securesms/InstantOnboardingActivity.java`** — изучить паттерн создания PGP-аккаунта через DC core, `ExecutorService` для фоновых операций, `ProgressDialog`.
2. **`src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java`** — точка входа онбординга, куда нужно добавить предложение зарегистрироваться в Alt после создания аккаунта.
3. **`src/main/java/org/thoughtcrime/securesms/ContactSelectionListFragment.java`** — существующий поиск контактов, куда встраивается Alt-поиск.
4. **`src/main/java/org/thoughtcrime/securesms/ConversationListActivity.java`** — главный экран, куда добавляется пункт меню и Snackbar.
5. **`src/main/java/com/b44t/messenger/DcContext.java`** — ключевые методы: `imex(int what, String dir)` для экспорта/импорта ключей ядра (`DC_IMEX_EXPORT_SELF_KEYS = 1`, `DC_IMEX_IMPORT_SELF_KEYS = 2`), константы `DC_EVENT_IMEX_PROGRESS` / `DC_EVENT_IMEX_FILE_WRITTEN`. Метод `imex()` — **асинхронный**: вызов возвращается сразу, завершение сигнализируется событием `DC_EVENT_IMEX_PROGRESS` с `data1=1000` (успех) или `data1=0` (ошибка).
   > Именно эти ключи (`DC_IMEX_EXPORT_SELF_KEYS`) — OpenPGP keypair ядра, fingerprint которого хранится в БД `contacts.fingerprint` и используется для E2EE со всеми контактами.
6. **`src/main/java/com/b44t/messenger/DcAccounts.java`** — понять, как получить `DcContext` для текущего аккаунта при вызове из фонового потока.
7. **`src/main/java/org/thoughtcrime/securesms/connect/DcHelper.java`** — изучить статические геттеры `getContext()`, `getRpc()`, паттерн доступа к core из UI и сервисов.
8. **`src/main/java/org/thoughtcrime/securesms/util/Prefs.java`** — паттерн хранения настроек приложения, чтобы придерживаться той же конвенции в `AltPrefs`.
9. **`build.gradle`** — проверить текущие зависимости: есть ли уже `okhttp3`, `gson`; какие версии `androidx.security` используются.
10. **`docs/backend-architecture.md`** — финальный вид архитектуры Alt Backend для понимания среды, с которой интегрируется Android.
11. **`src/main/java/org/thoughtcrime/securesms/preferences/ListSummaryPreferenceFragment.java`** — эталонный паттерн вызова `imex()`: как регистрировать observer на `DC_EVENT_IMEX_PROGRESS`, запускать операцию и ждать завершения. Именно этот паттерн нужно воспроизвести в `AltPlatformService`.
12. **`jni/deltachat-core-rust/src/contact.rs`** — функция `import_vcard_contact` (эталон для `addContactFromAlt`); формат экспортируемых ключей — файлы `*-key-default.asc` в указанной директории.

---

### **Files That Will Require Changes:**

#### **1. `src/main/java/org/thoughtcrime/securesms/altplatform/network/dto/RegisterRequest.java`** [CREATE]

DTO для `POST /v1/users/register`:
```java
public class RegisterRequest {
    public String username;
    public String email;             // почта аккаунта (для верификации и восстановления)
    public String addr;              // Delta Chat адрес (для обмена сообщениями, напр. user@nine.testrun.org)
    public String displayName;       // nullable
    public String publicKey;         // base64 OpenPGP public key
    public String fingerprint;       // HEX fingerprint
    public String encryptedPrivateKey; // base64 AES-GCM encrypted blob
}
```

#### **2. `src/main/java/org/thoughtcrime/securesms/altplatform/network/dto/VerifyRequest.java`** [CREATE]

```java
public class VerifyRequest {
    public String email;
    public String code;  // 6-digit OTP
}
```

#### **3. `src/main/java/org/thoughtcrime/securesms/altplatform/network/dto/UserProfileResponse.java`** [CREATE]

DTO ответа `GET /v1/users/search` и `GET /v1/users/{username}`:
```java
public class UserProfileResponse {
    public String addr;         // Delta Chat адрес (для vCard EMAIL и добавления контакта)
    public String name;         // displayName
    public String username;     // никнейм
    public String fingerprint;  // HEX fingerprint PGP-ключа
    public String publicKey;    // base64 OpenPGP public key
}
```

#### **4. `src/main/java/org/thoughtcrime/securesms/altplatform/network/dto/ResendCodeRequest.java`** [CREATE]

```java
public class ResendCodeRequest {
    public String email;
}
```

#### **5. `src/main/java/org/thoughtcrime/securesms/altplatform/network/dto/RestoreRequest.java`** [CREATE]

```java
public class RestoreRequest {
    public String username;
    public String email;
}
```

#### **6. `src/main/java/org/thoughtcrime/securesms/altplatform/network/AltApiClient.java`** [CREATE]

Настройка `OkHttpClient`:
- Connect timeout: 15 с, Read timeout: 15 с, Write timeout: 15 с.
- Interceptor добавляет `Content-Type: application/json` к каждому запросу.
- Если `AltTokenStorage.getToken() != null` — добавляет `Authorization: Bearer <token>`.
- Базовый URL берётся из `BuildConfig.ALT_API_BASE_URL`.

#### **7. `src/main/java/org/thoughtcrime/securesms/altplatform/network/AltApiService.java`** [CREATE]

Методы:
- `AltApiResponse<Void> register(RegisterRequest)` — POST `/v1/users/register`.
- `AltApiResponse<VerifyResponse> verify(VerifyRequest)` — POST `/v1/users/verify`.
- `AltApiResponse<Void> resendCode(ResendCodeRequest)` — POST `/v1/users/resend-code`.
- `AltApiResponse<Void> restore(RestoreRequest)` — POST `/v1/users/restore`.
- `AltApiResponse<PrivateKeyResponse> getPrivateKey()` — GET `/v1/users/me/private-key` (требует Bearer).
- `AltApiResponse<List<UserProfileResponse>> search(String query)` — GET `/v1/users/search?q=` (требует Bearer).
- `AltApiResponse<UserProfileResponse> getProfile(String username)` — GET `/v1/users/{username}`.

Все методы выполняются синхронно (вызываются из фонового потока); возвращают обёртку `AltApiResponse<T>` с полями `T data`, `int httpCode`, `String errorCode`.

#### **8. `src/main/java/org/thoughtcrime/securesms/altplatform/crypto/AltKeyCrypto.java`** [CREATE]

- `static byte[] encrypt(byte[] plaintext, String password)`: PBKDF2 (100 000 итераций, SHA-256, 32-байтный ключ), 16-байтный random salt, 12-байтный random nonce, AES-256-GCM. Формат blob: `[16 bytes salt][12 bytes nonce][ciphertext + 16 bytes GCM tag]`.
- `static byte[] decrypt(byte[] ciphertext, String password)`: обратная операция; бросает `AltCryptoException` при неверном пароле (GCM tag mismatch).
- Использует только `javax.crypto` (стандартная Android JCE) — никаких внешних криптобиблиотек.

#### **9. `src/main/java/org/thoughtcrime/securesms/altplatform/storage/AltTokenStorage.java`** [CREATE]

- Хранит JWT в `EncryptedSharedPreferences` с именем `"alt_token_prefs"`.
- Методы: `saveToken(Context, String)`, `String getToken(Context)`, `clearToken(Context)`.

#### **10. `src/main/java/org/thoughtcrime/securesms/altplatform/storage/AltPrefs.java`** [CREATE]

- Хранит в `SharedPreferences` имя `"alt_platform_prefs"`: `alt_registered` (boolean), `alt_username` (String), `alt_email` (String).
- `isRegistered(Context)`, `setRegistered(Context, String username, String email)`, `getUsername(Context)`, `getEmail(Context)`, `clear(Context)`.

#### **11. `src/main/java/org/thoughtcrime/securesms/altplatform/AltPlatformService.java`** [CREATE]

Центральный сервисный класс. Не является Android `Service` — обычный Java-класс, получаемый через `ApplicationContext`.

Ключевые методы:

- `RegisterResult register(Context, username, email, addr, displayName, recoveryPassword)`:
  1. `addr` получается вызывающей стороной как `DcHelper.getContext(context).getConfig("addr")` — Delta Chat адрес текущего аккаунта.
  2. Вызывает `dcContext.imex(DC_IMEX_EXPORT_SELF_KEYS, tempDir)` — **асинхронный** вызов ядра. Регистрирует одноразовый observer на `DC_EVENT_IMEX_PROGRESS` через `DcEventCenter`: ждёт `data1 == 1000` (успех) или `data1 == 0` (ошибка). Паттерн см. `ListSummaryPreferenceFragment.java`.
  3. По завершении читает `tempDir/public-key-default.asc` → base64 (publicKey), HEX fingerprint из заголовка `-----BEGIN PGP PUBLIC KEY BLOCK-----` / UID-строки (или через `getContactEncrInfo(CONTACT_ID_SELF)`).
  4. Читает `tempDir/secret-key-default.asc` → `AltKeyCrypto.encrypt(privateKeyBytes, recoveryPassword)` → base64 (encryptedPrivateKey).
  5. Удаляет временную директорию.
  6. Вызывает `AltApiService.register()` с `{ username, email, addr, displayName, publicKey, fingerprint, encryptedPrivateKey }`.
  7. Возвращает enum-результат: `SUCCESS`, `USERNAME_TAKEN`, `EMAIL_TAKEN`, `USERNAME_RESERVED`, `INVALID_USERNAME`, `NETWORK_ERROR`.

- `VerifyResult verifyEmail(Context, email, code)`:
  1. Вызывает `AltApiService.verify()`.
  2. При `200` — сохраняет JWT через `AltTokenStorage.saveToken()`.
  3. Возвращает: `SUCCESS`, `INVALID_CODE`, `NETWORK_ERROR`.

- `ResendResult resendCode(Context, email)`:
  1. Вызывает `AltApiService.resendCode()`.
  2. Возвращает: `SUCCESS`, `RATE_LIMITED`, `USER_NOT_FOUND`, `ALREADY_ACTIVE`, `NETWORK_ERROR`.

- `RestoreInitResult initiateRestore(Context, username, email)`:
  1. Вызывает `AltApiService.restore()`.
  2. Возвращает: `SUCCESS`, `USER_NOT_FOUND`, `NETWORK_ERROR`.

- `RestoreKeyResult restoreKey(Context, email, code, recoveryPassword)`:
  1. Вызывает `AltApiService.verify()` — получает JWT, сохраняет через `AltTokenStorage`.
  2. Скачивает зашифрованный blob через `AltApiService.getPrivateKey()`.
  3. Расшифровывает: `AltKeyCrypto.decrypt(blob, recoveryPassword)` — если GCM tag не совпал, бросает `AltCryptoException` → возвращает `WRONG_PASSWORD`.
  4. Записывает расшифрованные байты в `tempDir/secret-key-default.asc`.
  5. Вызывает `dcContext.imex(DC_IMEX_IMPORT_SELF_KEYS, tempDir)` — **асинхронный** вызов ядра. Ждёт `DC_EVENT_IMEX_PROGRESS == 1000` (успех) или `0` (ошибка) через `DcEventCenter`. После импорта ядро начинает использовать восстановленный ключ для E2EE.
  6. Удаляет временную директорию.
  7. Возвращает: `SUCCESS`, `WRONG_PASSWORD`, `INVALID_CODE`, `NETWORK_ERROR`.

- `List<UserProfileResponse> searchUsers(Context, query)`: вызывает API, возвращает список или пустой список при ошибке.

- `UserProfileResponse getProfile(Context, username)`: возвращает профиль или `null`.

- `int addContactFromAlt(Context, UserProfileResponse profile)` → возвращает `contactId` созданного контакта:
  1. Формирует vCard-строку в памяти (без записи на диск):
     ```
     BEGIN:VCARD
     VERSION:4.0
     FN:<profile.name или profile.addr>
     EMAIL:<profile.addr>
     KEY;MEDIATYPE=application/pgp-keys:<profile.publicKey>  ← base64 PGP, как пришёл с сервера
     END:VCARD
     ```
  2. Вызывает `DcHelper.getRpc(context).importVcardContents(accountId, vcardString)` — ядро:
     - вычисляет fingerprint из ключа (`public_key.dc_fingerprint().hex()`),
     - делает `INSERT INTO public_keys` (ON CONFLICT DO NOTHING),
     - делает `INSERT INTO contacts ... ON CONFLICT (fingerprint) DO UPDATE`,
     - устанавливает `verifier = ContactId::SELF` → **зелёная галочка**.
  3. Возвращает `contactId` из списка (первый элемент ответа `List<Integer>`).
  4. Временные файлы не создаются — всё в памяти.

#### **12. `src/main/java/org/thoughtcrime/securesms/altplatform/registration/AltRegistrationActivity.java`** [CREATE]

Контейнер регистрации. Управляет стеком Fragment:
- Step 1 → Step 2 → Step 3.
- Передаёт данные между шагами через `Bundle` аргументы фрагментов.
- `layout/activity_alt_registration.xml`: `Toolbar` + `FragmentContainerView`.

#### **13. `src/main/java/org/thoughtcrime/securesms/altplatform/registration/AltStep1Fragment.java`** [CREATE]

Ввод никнейма, email аккаунта, отображаемого имени:
- `EditText` для никнейма (валидация: `[a-zA-Z0-9_]{3,30}`).
- `EditText` для email аккаунта (для верификации и восстановления).
- `EditText` для отображаемого имени (необязательно).
- Поле Delta Chat addr (`addr`) заполняется автоматически из `dcContext.getConfig("addr")` и показывается пользователю как read-only информация (пояснение: «Ваш адрес для обмена сообщениями на платформе»).
- Валидация никнейма на клиенте по regex `[a-zA-Z0-9_]{3,30}` до отправки.
- Inline-ошибка под полем никнейма при нарушении формата.
- Отображать `ProgressDialog` во время API-вызова.
- По success — перейти к `AltStep2Fragment`, передав email аккаунта.

#### **14. `src/main/java/org/thoughtcrime/securesms/altplatform/registration/AltStep2Fragment.java`** [CREATE]

Ввод кода:
- `EditText` с `inputType="number"`, `maxLength=6`.
- Кнопка «Повторно» — disabled, показывает countdown `(60с… 59с…)` после каждой отправки.
- При `RATE_LIMITED` (429) — показывать countdown по полученному ответу.
- При success — перейти к `AltStep3Fragment`.

#### **15. `src/main/java/org/thoughtcrime/securesms/altplatform/registration/AltStep3Fragment.java`** [CREATE]

Пароль восстановления:
- Два `EditText` для ввода и подтверждения пароля.
- `TextView` с предупреждением: «Если вы потеряете этот пароль, восстановление ключей будет невозможно. Не храните его вместе с паролем приложения. Сохраните в надёжном месте.»
- `CheckBox` «Я понимаю и сохранил пароль» — кнопка «Завершить» активируется только при поставленной галочке.
- При success — закрыть `AltRegistrationActivity`, показать Toast «Регистрация завершена».

#### **16. `src/main/java/org/thoughtcrime/securesms/altplatform/restore/AltRestoreActivity.java`** [CREATE]

Контейнер восстановления ключей. Аналогичен `AltRegistrationActivity`.

#### **17. `src/main/java/org/thoughtcrime/securesms/altplatform/restore/AltRestoreStep1Fragment.java`** [CREATE]

Ввод никнейма и email для поиска аккаунта на сервере.

#### **18. `src/main/java/org/thoughtcrime/securesms/altplatform/restore/AltRestoreStep2Fragment.java`** [CREATE]

Ввод кода из email. Аналог `AltStep2Fragment`.

#### **19. `src/main/java/org/thoughtcrime/securesms/altplatform/restore/AltRestoreStep3Fragment.java`** [CREATE]

Ввод пароля восстановления:
- Одно поле (без подтверждения — работаем с уже созданным паролем).
- При `WRONG_PASSWORD` — shake animation на поле, сообщение «Неверный пароль восстановления».
- При success — закрыть Activity, показать Toast «Ключи восстановлены».

#### **20. `src/main/java/org/thoughtcrime/securesms/altplatform/search/AltUserSearchActivity.java`** [CREATE]

Поиск с `SearchView` в тулбаре:
- `AltUserSearchFragment` внутри.
- Открывается через `Intent` из `ConversationListActivity` или `ContactSelectionListFragment`.

#### **21. `src/main/java/org/thoughtcrime/securesms/altplatform/search/AltUserSearchFragment.java`** [CREATE]

- Debounce 400 мс на ввод в `SearchView`.
- Пустой запрос (`q=""`) — показывать иллюстрацию «Найдите пользователей Alt по никнейму».
- Если `AltPrefs.isRegistered() == false` — показывать banner «Зарегистрируйтесь в Alt, чтобы использовать поиск» с кнопкой → `AltRegistrationActivity`.
- После нажатия «Написать» в адаптере: вызов `AltPlatformService.addContactFromAlt()` в фоне (через `ExecutorService`) → получаем `contactId` → `rpc.createChatByContactId(accountId, contactId)` → открытие `ConversationActivity` с полученным `chatId`.

#### **22. `src/main/java/org/thoughtcrime/securesms/altplatform/search/AltUserSearchAdapter.java`** [CREATE]

`RecyclerView.Adapter<AltUserSearchAdapter.ViewHolder>`:
- В ViewHolder: `TextView` для никнейма (bold), `TextView` для displayName, `TextView` для email, кнопка «Написать».
- `layout/item_alt_user_search.xml`.

#### **23. `src/main/java/org/thoughtcrime/securesms/ConversationListActivity.java`** [MODIFY]

- В `onCreateOptionsMenu()` добавить MenuItem «Найти в Alt» (иконка поиска) → `startActivity(new Intent(this, AltUserSearchActivity.class))`.
- При первом запуске после онбординга (проверка `!AltPrefs.isRegistered() && dcContext.isConfigured()`) показывать Snackbar один раз: «Зарегистрируйтесь в Alt, чтобы вас могли найти по никнейму» + кнопка «Зарегистрироваться». Показывать не чаще одного раза (флаг в `SharedPreferences`).

#### **24. `src/main/java/org/thoughtcrime/securesms/ContactSelectionListFragment.java`** [MODIFY]

- Добавить кнопку/чип «Найти в Alt» под строкой поиска, видимую только при `AltPrefs.isRegistered() == true` → запускает `AltUserSearchActivity`.

#### **25. `build.gradle`** [MODIFY]

Добавить при отсутствии:
```groovy
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
// OkHttp и Gson скорее всего уже есть как транзитивные зависимости — проверить
```

#### **26. `src/main/res/values/strings.xml`** [MODIFY]

Добавить строки:
- `alt_registration_title`, `alt_step1_title`, `alt_step2_title`, `alt_step3_title`
- `alt_restore_title`, `alt_restore_step3_title`
- `alt_search_title`, `alt_search_hint`, `alt_search_empty_state`
- `alt_error_username_taken`, `alt_error_email_taken`, `alt_error_username_reserved`
- `alt_error_invalid_username_format`, `alt_error_invalid_code`, `alt_error_already_active`
- `alt_error_user_not_found`, `alt_error_rate_limit`, `alt_error_network`
- `alt_error_wrong_recovery_password`
- `alt_recovery_password_warning`
- `alt_recovery_password_confirm_checkbox`
- `alt_onboarding_snackbar`, `alt_register_cta`
- `alt_menu_find_in_alt`, `alt_button_write`, `alt_button_restore_keys`

---

### **Testing Checklist:**

**Регистрация:**
- [ ] Никнейм из спецсимволов (`user@`) → error inline, запрос не отправляется
- [ ] Никнейм короче 3 символов (`ab`) → inline error
- [ ] Никнейм длиннее 30 символов → inline error
- [ ] Валидный никнейм, занятый email → `409`, toast «Email уже зарегистрирован»
- [ ] Валидный никнейм, занятый username → `409`, toast «Никнейм уже занят»
- [ ] Зарезервированный никнейм → `422`, toast «Никнейм зарезервирован»
- [ ] Успешная регистрация → переход к шагу 2
- [ ] Неверный код подтверждения → `400`, toast «Неверный или просроченный код»
- [ ] Повторная отправка кода — кнопка блокируется на 60 с, countdown отображается
- [ ] Повторный запрос раньше 60 с → `429`, отображён countdown
- [ ] Пароли восстановления не совпадают → кнопка «Завершить» неактивна / inline error
- [ ] Checkbox не отмечен → кнопка «Завершить» неактивна
- [ ] Успешная регистрация → `AltPrefs.isRegistered() == true`, JWT сохранён

**Восстановление ключей:**
- [ ] Неверная пара username/email → `404`, сообщение «Аккаунт не найден»
- [ ] Верная пара → код отправлен на email
- [ ] Неверный код → `400`, ошибка
- [ ] Неверный пароль восстановления → расшифровка провалилась, shake animation + ошибка
- [ ] Верный пароль → ключи импортированы в DC core, Toast «Ключи восстановлены»

**Поиск пользователей:**
- [ ] Если не зарегистрирован → banner с CTA «Зарегистрироваться в Alt»
- [ ] Пустой запрос → пустое состояние с иллюстрацией
- [ ] Поиск по части никнейма → результаты отображены (никнейм, имя, email)
- [ ] Неактивный (неподтверждённый) аккаунт не появляется в результатах
- [ ] Нажатие «Написать» на результате → контакт создан, открывается `ConversationActivity`
- [ ] Добавленный контакт имеет зелёную галочку верификации (verifier = SELF)
- [ ] При повторном нажатии «Написать» — контакт обновляется, не дублируется
- [ ] Публичный ключ пользователя импортирован в DC core (первое сообщение идёт с E2EE — замок в интерфейсе)

**Интеграция:**
- [ ] Пункт меню «Найти в Alt» отображается в `ConversationListActivity`
- [ ] Snackbar-приглашение показывается один раз после настройки аккаунта
- [ ] Snackbar не показывается повторно при следующем открытии приложения
- [ ] Кнопка «Найти в Alt» видна в `ContactSelectionListFragment` для зарегистрированных
- [ ] Кнопка «Найти в Alt» скрыта для незарегистрированных

**Безопасность:**
- [ ] JWT не хранится в открытом виде (проверить через `adb shell` / `run-as`)
- [ ] Приватный ключ не передаётся на сервер без шифрования (сетевой перехват через Charles/mitmproxy)
- [ ] Temp-файлы с ключами удаляются после операции экспорта/импорта
- [ ] Пароль восстановления НИКОГДА не передаётся на сервер и не сохраняется в SharedPreferences

**Сеть и ошибки:**
- [ ] Нет сети → корректная ошибка «Нет подключения», не краш
- [ ] Сервер недоступен (5xx) → корректная ошибка
- [ ] Истёкший JWT → обработать `401` при `/search` или `/me/private-key` (предложить повторную верификацию)
- [ ] Все существующие тесты приложения проходят без регрессий

---

### **Documentation Update:**

После завершения реализации **необходимо** обновить:

- [ ] `docs/android-architecture.md` — добавить раздел про пакет `altplatform/`, описать `AltPlatformService` в «Application Layer», добавить HTTP-клиент в диаграмму слоёв
- [ ] `docs/project-structure.md` — добавить описание всех новых файлов пакета `altplatform/`
- [ ] Проверить, что все ссылки и примеры в существующих документах остаются актуальными

**Important:** Документация должна отражать итоговое состояние кодовой базы.

---

## Implementation Status

**Status:** Pending
**Created:** 2026-03-21
**Completed:** —

### Implementation Checklist:

- [ ] HTTP-клиент (`AltApiClient`, `AltApiService`, DTO)
- [ ] Криптография (`AltKeyCrypto`)
- [ ] Хранилище (`AltTokenStorage`, `AltPrefs`)
- [ ] `AltPlatformService` со всеми методами
- [ ] UI регистрации (Activity + 3 Fragment)
- [ ] UI восстановления ключей (Activity + 3 Fragment)
- [ ] UI поиска (Activity + Fragment + Adapter)
- [ ] Точки входа в `ConversationListActivity` и `ContactSelectionListFragment`
- [ ] Строковые ресурсы
- [ ] Документация обновлена

### Implementation Summary:

*Заполняется после завершения.*

**Files Created:**
- *TBD*

**Files Modified:**
- *TBD*

**Net Result:** Интеграция Android-приложения с Alt Platform: регистрация пользователей в директории, шифрованный бэкап PGP-ключей, восстановление на новом устройстве, поиск и добавление участников платформы в контакты.
