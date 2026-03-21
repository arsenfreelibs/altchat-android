export PATH="$HOME/.cargo/bin:$PATH" && export ANDROID_NDK_ROOT=~/Library/Android/sdk/ndk/27.1.12297006 && scripts/ndk-make.sh arm64-v8a 2>&1 

JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-24.jdk/Contents/Home ./gradlew assembleDebug 2>&1



/Users/arsenarutiunian/Projects/Alt/deltachat-android/jni/deltachat-core-rust/src/stock_str.rs
// add welcome-messages. by the label, this is done only once,
        // if the user has deleted the message or the chat, it is not added again.
        let image = include_bytes!("../assets/welcome-image.jpg");
        let blob = BlobObject::create_and_deduplicate_from_bytes(self, image, "welcome.jpg")?;
        let mut msg = Message::new(Viewtype::Image);
        msg.param.set(Param::File, blob.as_name());
        msg.param.set(Param::Filename, "welcome-image.jpg");
        chat::add_device_msg(self, Some("core-welcome-image"), Some(&mut msg)).await?;

---

## Инвайт-ссылка `https://i.delta.chat/...`

### Что нужно поменять для перехода на `i.alt.chat`

| Что | Файл | Пересборка |м
|---|---|---|
| Генерация ссылки | `jni/deltachat-core-rust/src/securejoin.rs` | Rust + NDK |
| Парсинг ссылки | `jni/deltachat-core-rust/src/qr.rs` | Rust + NDK |
| Intent filter (открытие в приложении) | `src/main/AndroidManifest.xml` | Только APK |
| Java-валидация домена | `src/main/java/org/thoughtcrime/securesms/util/Util.java` | Только APK |
| **Digital Asset Links** | Разместить `https://i.alt.chat/.well-known/assetlinks.json` | Сервер |

### Детали по файлам

**securejoin.rs** — здесь строится сама ссылка:
```
https://i.delta.chat/#{fingerprint}&i={invitenumber}&s={auth}&a={addr}&n={name}
```

**qr.rs** — здесь парсится входящая ссылка:
```rust
const IDELTACHAT_SCHEME: &str = "https://i.delta.chat/#";
const IDELTACHAT_NOSLASH_SCHEME: &str = "https://i.delta.chat#";
```

**AndroidManifest.xml** — intent-filter для перехвата ссылок:
```xml
<intent-filter android:autoVerify="true">
    <data android:scheme="https" android:host="i.delta.chat" />
</intent-filter>
```

**Util.java** — проверка домена в Android-коде:
```java
public static final String INVITE_DOMAIN = "i.delta.chat";
```

> `android:autoVerify="true"` требует файл `/.well-known/assetlinks.json` на домене `i.alt.chat`, иначе ссылка будет открываться через диалог выбора браузера.

---


## Звонки (WebRTC)

### Как работает

- **Технология:** WebRTC — звонки идут **не через email-серверы**, а через отдельную инфраструктуру.
- **Сигнализация** — через обычный чат (email/IMAP). Офферы и ответы WebRTC передаются как зашифрованные сообщения в чате.
- **Медиапоток** (аудио/видео) — через ICE/STUN/TURN серверы по WebRTC.
- **UI** — открывается WebView с файлом `src/main/assets/calls/index.html` (JS + WebRTC API).

### ICE/TURN серверы (fallback — жёстко зашиты в ядре)

Файл: `jni/deltachat-core-rust/src/calls.rs`

| Тип | Хост |
|---|---|
| STUN | `nine.testrun.org` |
| TURN | **`turn.delta.chat`** (с credentials, публичные) |

Основные серверы берутся из **метаданных chatmail-сервера** (приходят при подключении к аккаунту). Если метаданных нет — используется fallback с `turn.delta.chat`.

### Детали по коду

**`jni/deltachat-core-rust/src/calls.rs`** — вся логика ICE серверов:

```rust
// Порт по умолчанию для STUN/TURN
const STUN_PORT: u16 = 3478;

// Fallback-серверы — используются если chatmail-сервер не вернул свои
pub(crate) fn create_fallback_ice_servers() -> Vec<UnresolvedIceServer> {
    vec![
        UnresolvedIceServer::Stun {
            hostname: "nine.testrun.org".to_string(),  // ← поменять на свой STUN
            port: STUN_PORT,
        },
        UnresolvedIceServer::Turn {
            hostname: "turn.delta.chat".to_string(),   // ← поменять на свой TURN
            port: STUN_PORT,
            username: "public".to_string(),            // ← поменять credentials
            credential: "o4tR7yG4rG2slhXqRUf9zgmHz".to_string(),
        },
    ]
}

// Функция, которую вызывает Android: возвращает JSON с серверами
pub async fn ice_servers(context: &Context) -> Result<String> {
    if let Some(ref metadata) = *context.metadata.read().await {
        // Сначала пробует взять серверы из метаданных chatmail-сервера
        let ice_servers = resolve_ice_servers(context, metadata.ice_servers.clone()).await?;
        Ok(ice_servers)
    } else {
        Ok("[]".to_string())
    }
}
```

**`jni/deltachat-core-rust/src/imap.rs`** — получение ICE серверов от chatmail-сервера:

```
IMAP METADATA ключ: /shared/vendor/deltachat/turn
Формат значения:    hostname:timestamp:password
```
Запрашивается при подключении и обновляется каждые 12 часов. Если сервер не вернул — используется fallback из `calls.rs`.

**`src/main/java/org/thoughtcrime/securesms/calls/CallActivity.java`** — Android вызывает:
```java
// Загружает WebRTC UI из локального файла
webView.loadUrl("file:///android_asset/calls/index.html");

// JS API — передаёт ICE серверы в WebRTC
public String getIceServers() {
    return rpc.iceServers(accId);  // вызывает Rust calls.rs::ice_servers()
}
```

**`src/main/assets/calls/index.html`** — WebRTC UI (JS), использует ICE серверы полученные через `window.calls.getIceServers()`.

### Что нужно для замены на свою инфраструктуру

| Вариант | Что делать | Пересборка |
|---|---|---|
| **Легко** | Настроить chatmail-сервер чтобы отдавал свой TURN через IMAP METADATA `/shared/vendor/deltachat/turn` | Не нужна |
| **Сложно** | Поменять fallback в `calls.rs` (`turn.delta.chat` → свой хост + credentials) | Rust + NDK |

Для coturn формат метаданных: `hostname:unix_timestamp:password`

---

### Что означает "Лёгкий" вариант подробнее

Когда приложение подключается к почтовому серверу (IMAP), оно сразу запрашивает у него специальный ключ:
```
/shared/vendor/deltachat/turn
```

Если chatmail-сервер поддерживает это и возвращает значение в формате:
```
turn.myserver.com:1758650868:secretpassword
```

То приложение **автоматически** использует этот TURN-сервер для звонков — без пересборки ядра.

То есть если у тебя есть свой chatmail-сервер и свой TURN-сервер (coturn), ты просто настраиваешь на chatmail-сервере этот IMAP METADATA-ключ с адресом своего coturn — и всё, звонки пойдут через твою инфраструктуру.

Если же сервер этот ключ не возвращает — приложение падает на fallback и использует turn.delta.chat, который зашит в Rust-ядре.

---

То есть достаточно:
1. Поднять свой **TURN-сервер** (coturn)
2. На своём **chatmail-сервере** настроить отдачу этого IMAP METADATA-ключа с адресом своего coturn

Если сервер ключ **не возвращает** — приложение падает на fallback и использует `turn.delta.chat` из Rust-ядра.


## Политика конфиденциальности relay-сервера

**Файл:** `src/main/java/org/thoughtcrime/securesms/InstantOnboardingActivity.java`

Ссылка на privacy policy формируется динамически:
```java
// строка 350
"https://" + providerHost + "/privacy.html"
```

По умолчанию `providerHost = "nine.testrun.org"` (строка 70), значит дефолтная ссылка:
```
https://nine.testrun.org/privacy.html
```

Если пользователь выбрал другой relay — подставляется его хост автоматически.

> Приложение ожидает, что на каждом relay-сервере будет доступен путь `/privacy.html`.
> Для своего сервера нужно разместить файл по адресу `https://your-relay.com/privacy.html`.

---

## Хранение credentials и ключей

### Где хранятся

Все данные хранятся в **одном SQLite файле** без шифрования на уровне файла:

```
/data/data/<app_id>/files/accounts/<account_id>/dc.db
```

На Android защита только через песочницу — без root другое приложение файл прочитать не может.

### Что хранится в `dc.db`

| Что | Таблица/ключ | Формат |
|---|---|---|
| IMAP пароль | `config` → `configured_mail_pw` | plaintext |
| SMTP пароль | `config` → `configured_smtp_pw` | plaintext |
| IMAP логин, сервер, порт | `config` | plaintext |
| **Приватный PGP ключ** (для расшифровки) | таблица `keypairs` → `private_key` | бинарный PGP |
| Публичный PGP ключ | `keypairs` → `public_key` | бинарный PGP |
| Все сообщения | `msgs` | plaintext (уже расшифрованные) |
| Контакты, чаты | `contacts`, `chats` | plaintext |

### Важно

- Файл **не зашифрован** — если кто-то получит `dc.db`, он получит и доступ к почте, и возможность расшифровать все сообщения
- Приватный PGP ключ лежит в той же БД что и пароли
- Резервная копия через "Export" — незашифрованный `.tar` если не задать пароль при экспорте

### Мульти-девайс и удаление сообщений

- При отправке автоматически отправляется **BCC-self** копия самому себе — так второе устройство видит исходящие
- Синхронизация состояния (прочитано, архивировано) — через зашифрованный `multi-device-sync.json` между своими устройствами
- Настройка `delete_server_after`:
  - `0` = удалить с сервера сразу после загрузки
  - `N` секунд = удалить через N секунд
  - `None` = не удалять (по умолчанию)
- При `delete_server_after=0` второе устройство может не успеть скачать сообщение — для мульти-девайс рекомендуется не удалять сразу
- Chatmail-серверы сами удаляют старые сообщения через ~несколько недель (политика сервера)

---

## Канал статистики (бот для логов)

### Как работает

- В **Настройки → Дополнительно** есть переключатель "Отправлять статистику разработчикам"
- При включении: создаётся контакт-бот из VCF-файла, с ним открывается приватный чат
- **Раз в неделю** в этот чат отправляется JSON с анонимной статистикой
- Логика целиком в `jni/deltachat-core-rust/src/stats.rs`

### Куда уходят данные (сейчас — в Delta Chat)

| Параметр | Значение |
|---|---|
| Email бота | `self_reporting@testrun.org` |
| VCF с PGP-ключом | `jni/deltachat-core-rust/assets/statistics-bot.vcf` |
| Код | `jni/deltachat-core-rust/src/stats.rs` |
| Интервал отправки | 1 раз в неделю (`3600 * 24 * 7` секунд) |

### Что отправляется

```json
{
  "core_version": "...",
  "is_chatmail": true,
  "key_create_timestamps": [...],
  "stats_id": "случайный ID",
  "contact_stats": [...],
  "message_stats": {...},
  "securejoin_sources": {...}
}
```

### Как переключить на свой бот

Требует изменения в submodule (Rust + NDK пересборка):

1. Создать email-аккаунт для бота (например `stats@alt-chat.me`)
2. Сгенерировать PGP-ключ для этого email
3. Поменять в `stats.rs`:
   ```rust
   pub(crate) const STATISTICS_BOT_EMAIL: &str = "stats@alt-chat.me";
   ```
4. Заменить `assets/statistics-bot.vcf` — VCF с новым именем, email и PGP-ключом
5. Пересобрать: `export PATH="$HOME/.cargo/bin:$PATH" && export ANDROID_NDK_ROOT=~/Library/Android/sdk/ndk/27.1.12297006 && scripts/ndk-make.sh arm64-v8a`

> Пока не меняли — статистика уходит в Delta Chat (если пользователь включил опцию).

---

## Push-уведомления (FCM, gplay flavor)

### Цепочка доставки

```
Chatmail-сервер → Firebase FCM API → устройство → IMAP fetch
```

1. При старте приложение регистрируется в FCM: `FcmReceiveService.register()`
2. Получает токен вида `fcm-me.altchat:APA91bXx...`
3. Передаёт токен в Rust через `setPushDeviceToken(token)` → ядро отправляет его на chatmail-сервер
4. Когда приходит новое письмо — chatmail-сервер дёргает **Firebase API** с этим токеном
5. Firebase доставляет пустой пинг на устройство
6. Приложение получает пинг в `onMessageReceived()` и делает IMAP fetch

**Код:** `src/gplay/java/org/thoughtcrime/securesms/notifications/FcmReceiveService.java`

> Push-пинг **не содержит текст сообщения** — это просто сигнал "иди забери почту". Реальные сообщения приходят по IMAP.

### Важно: работает только с chatmail-сервером

Обычный IMAP-сервер (Gmail, Yandex и т.д.) не умеет дёргать Firebase API — пуши не придут. Только chatmail-серверы поддерживают этот механизм.

### Почему сейчас не работают пуши (gplay debug)

| Причина | Решение |
|---|---|
| `mobilesdk_app_id` для `me.altchat.beta` — placeholder | Зарегистрировать `me.altchat.beta` в Firebase Console, скачать новый `google-services.json` |
| FCM не активирован в Firebase Console | Firebase Console → Build → **Cloud Messaging** — активировать |
| `nine.testrun.org` использует Firebase проект Delta Chat, не ваш | Нужен свой chatmail-сервер, настроенный с вашим Firebase Server Key |

### Как настроить свой chatmail-сервер для пушей

Chatmail-серверу нужен **Firebase Server Key** (Legacy) или **Service Account** (новый API):
- Firebase Console → Project settings → Cloud Messaging → скопировать Server Key
- Передать на chatmail-сервер в его конфиг

После этого пуши от вашего chatmail-сервера будут приходить через ваш Firebase проект `altchat-a811d`.

### Настройка FCM V1 на chatmail-сервере (новые версии)

Для новых версий chatmail нужен **Service Account JSON** (не Legacy Server Key).

**Получить из Firebase:**
1. Firebase Console → проект **altchat-a811d**
2. ⚙️ → **Project settings** → вкладка **Service accounts**
3. Нажать **Generate new private key** → скачается JSON:
   ```json
   {
     "type": "service_account",
     "project_id": "altchat-a811d",
     "private_key_id": "...",
     "private_key": "-----BEGIN RSA PRIVATE KEY-----...",
     "client_email": "firebase-adminsdk-xxx@altchat-a811d.iam.gserviceaccount.com",
     ...
   }
   ```

**Установить на сервер:**
- Положить файл на chatmail-сервер (обычно `/etc/chatmail/fcm-service-account.json`)
- В конфиге chatmail прописать путь к этому файлу

> Точный параметр конфига зависит от версии chatmail — нужно смотреть документацию конкретной версии сервера.

---

## Импорт контактов с сервера по API

### Задача

При старте или по запросу — получать список контактов с сервера и добавлять их в локальную БД, чтобы сразу можно было переписываться с E2EE.

### Что должен вернуть сервер на каждый контакт

```json
{
  "addr": "alice@example.com",
  "name": "Alice",
  "fingerprint": "A1B2C3D4E5F6...",
  "public_key": "<base64-encoded OpenPGP public key>"
}
```

- `fingerprint` — постоянный (меняется только при сбросе аккаунта), пользователь отправляет его на сервер при регистрации
- `public_key` — бинарный OpenPGP ключ в base64; без него сообщения пойдут без шифрования
- Без `fingerprint` — контакт не будет верифицирован (нет зелёной галочки)

### Куда писать в БД

| Таблица | Колонки | Значения |
|---|---|---|
| `public_keys` | `fingerprint`, `public_key` | HEX fingerprint + бинарный BLOB (base64 decode) |
| `contacts` | `addr`, `authname`, `name_normalized`, `fingerprint`, `origin` | email, имя, lowercase имя, HEX fingerprint, `16777216` (Origin::CreateChat) |
| `contacts.verifier` | `verifier` | `1` (ContactId::SELF) — если хочешь зелёную галочку |

`chats` и `chats_contacts` трогать **не нужно** — чат создаётся автоматически когда пользователь открывает переписку.

### SQL операции

```sql
-- 1. Сохранить публичный ключ
INSERT INTO public_keys (fingerprint, public_key)
VALUES (?, ?)
ON CONFLICT (fingerprint) DO NOTHING;

-- 2. Добавить или обновить контакт
INSERT INTO contacts (name, name_normalized, addr, fingerprint, origin, authname)
VALUES ('', ?, ?, ?, 16777216, ?)
ON CONFLICT (fingerprint) DO UPDATE SET
    addr = excluded.addr,
    authname = excluded.authname;

-- 3. Верифицировать (опционально — даёт зелёную галочку)
UPDATE contacts SET verifier = 1
WHERE fingerprint = ? AND verifier = 0;
```

### Готовая функция в коде

`jni/deltachat-core-rust/src/contact.rs` — функция `import_vcard_contact` делает именно это:
1. Принимает `addr`, `authname`, `public_key` (base64)
2. Вычисляет fingerprint из ключа через `public_key.dc_fingerprint().hex()`
3. Делает `INSERT INTO public_keys`
4. Вызывает `Contact::add_or_lookup_ex` с `Origin::CreateChat`

Можно добавить новый API-метод по её образцу, принимающий JSON с сервера.

### Как получить fingerprint и ключ своего пользователя для отправки на сервер

```rust
// Fingerprint
let fp = self_fingerprint(context).await?;  // src/key.rs

// Публичный ключ (base64)
let key = load_self_public_key(context).await?;
let key_base64 = key.to_base64();
```

На Android — через существующий JNI API: `DcContext.getSelfPublicKey()` / `getFingerprint()`.

