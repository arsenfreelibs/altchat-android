# Параллельные релеи у контактов: анализ и план реализации

## Цель

Обеспечить отказоустойчивость доставки сообщений: у каждого контакта может быть до 3 релеев (email-адресов). При отправке сообщение уходит на **все** адреса контакта. При получении дубли (одинаковый Message-ID с разных релеев) дедуплицируются — пользователь видит одно сообщение. Если один релей уходит оффлайн, уходит на обслуживание и т.д. — сообщение всё равно доставляется через оставшиеся.

---

## Анализ текущей архитектуры

### Как работают релеи сейчас

**Текущая модель:**
- У **отправителя** может быть несколько релеев → при `BccSelf` сообщение отправляется на все свои адреса (через `add_self_recipients`)
- У **контакта** — **один адрес** (`contacts.addr` — одно поле TEXT). Сообщение отправляется только на один адрес контакта
- Дедупликация: по `rfc724_mid` (Message-ID) — если сообщение с таким ID уже есть в БД, оно пропускается (`receive_imf.rs:548`)

### Ключевые файлы в Rust ядре

| Файл | Роль |
|------|------|
| `transport.rs` | `transports` таблица, `ConfiguredLoginParam` |
| `scheduler.rs` | Для каждого transport создаётся IMAP inbox_loop. Один SMTP loop |
| `mimefactory.rs` | Формирование MIME, определение `recipients` по `chats_contacts` |
| `chat.rs` | `send_msg` → `prepare_send_msg` → `create_send_msg_jobs` |
| `smtp.rs` | `send_msg_to_smtp`, `smtp_send`, `add_self_recipients` |
| `receive_imf.rs` | Приём сообщений, дедупликация по `rfc724_mid` |
| `contact.rs` | Контакты, одно поле `addr` |
| `tables.sql` | Схема: `contacts.addr TEXT` — один адрес |

### Что нужно переделать для параллельных релеев у контакта

#### 1. Схема БД: несколько адресов у контакта

Сейчас: `contacts.addr TEXT` — один email. Нужна новая таблица `contact_addrs` (Вариант А, рекомендуемый):

```sql
CREATE TABLE contact_addrs (
    contact_id INTEGER NOT NULL,
    addr TEXT NOT NULL COLLATE NOCASE,
    is_primary INTEGER DEFAULT 0,
    UNIQUE(addr),
    FOREIGN KEY(contact_id) REFERENCES contacts(id)
);
CREATE INDEX contact_addrs_contact ON contact_addrs(contact_id);
CREATE INDEX contact_addrs_addr ON contact_addrs(addr);
```

Поле `contacts.addr` оставить как основной адрес для обратной совместимости. Миграция в `migrations.rs`.

#### 2. Отправка на все релеи контакта

Ключевое место: `mimefactory.rs:308-358` — формирование `recipients`. Сейчас для каждого контакта в чате берётся `c.addr` (одна строка). Нужно:

- При формировании `recipients` для каждого `contact_id` запрашивать **все адреса** из `contact_addrs`
- Добавлять все адреса контакта в список `recipients` (в поле BCC, чтобы не раскрывать все адреса)
- Message-ID (`rfc724_mid`) остаётся **одинаковым** для всех копий — это ключ дедупликации

**Файлы для изменения:** `mimefactory.rs` — `MimeFactory::from_msg()`, формирование recipients. Возможно `chat.rs` — `create_send_msg_jobs`.

#### 3. Дедупликация на приёме (уже работает!)

В `receive_imf.rs:548`:
```rust
} else if let Some(old_msg_id) = message::rfc724_mid_exists(context, rfc724_mid).await? {
    info!(context, "Message is already downloaded.");
    if mime_parser.incoming {
        return Ok(None);  // ← пропускаем дубль
    }
}
```

**Это уже работает!** Если одно и то же сообщение (с одинаковым Message-ID) приходит через несколько релеев, первое будет сохранено, остальные — пропущены. Поскольку каждый транспорт запускает свой `inbox_loop` (`scheduler.rs:754`), сообщения с разных релеев обрабатываются параллельно, и `rfc724_mid_exists` защищает от дублей.

#### 4. UI: управление адресами контакта

На Android-стороне нужен интерфейс для:
- Просмотра списка адресов (релеев) контакта
- Добавления дополнительных адресов контакту
- Удаления/выбора основного адреса

#### 5. Резолвинг контакта при получении

Сейчас контакт определяется по `From:` адресу (`contact.rs`). Если у контакта 3 адреса (по одному на каждый релей), нужно чтобы при получении сообщения от любого из этих адресов оно привязывалось к **одному контакту**.

Изменения в:
- `contact.rs` — `add_or_lookup` — поиск по всем адресам контакта
- `receive_imf.rs` — определение `from_id`

### Итоговый план изменений

| # | Что | Где | Сложность |
|---|-----|-----|-----------|
| 1 | Таблица `contact_addrs` + миграция | `migrations.rs`, `tables.sql` | Средняя |
| 2 | API для CRUD адресов контакта | `contact.rs`, FFI/JSON-RPC | Средняя |
| 3 | Отправка на все адреса контакта | `mimefactory.rs` (recipients) | Средняя |
| 4 | Резолвинг контакта по любому адресу | `contact.rs`, `receive_imf.rs` | Высокая |
| 5 | Дедупликация | **Уже работает** через `rfc724_mid` | — |
| 6 | UI: список адресов контакта | Android (Kotlin/Java) | Средняя |

**Самый критичный момент** — пункт 3 (отправка на все адреса) и пункт 4 (резолвинг при получении). Дедупликация **уже встроена** в ядро благодаря Message-ID.

---

## Детальное описание текущей архитектуры

### Релеи отправителя (уже работают)

Система уже поддерживает несколько транспортов (релеев) у **отправителя**. Каждый транспорт — отдельный email-аккаунт с IMAP+SMTP.

- Таблица `transports` в SQLite, лимит 5 штук — константа `MAX_TRANSPORT_RELAYS` в `jni/deltachat-core-rust/src/configure.rs:50`
- При `BccSelf` сообщение копируется на все свои адреса через функцию `add_self_recipients()` в `jni/deltachat-core-rust/src/smtp.rs:691-718`
- Список вторичных адресов берётся через `get_secondary_self_addrs()` в `jni/deltachat-core-rust/src/config.rs:956-961` — запрос `SELECT addr FROM transports WHERE addr NOT IN (SELECT value FROM config WHERE keyname='configured_addr')`
- Каждый транспорт запускает свой `inbox_loop` в `jni/deltachat-core-rust/src/scheduler.rs:754-770`
- Один общий `smtp_loop` для отправки в `jni/deltachat-core-rust/src/scheduler.rs:649-737`
- Адрес основного (активного) реле хранится в таблице `config` с ключом `configured_addr`

### Таблица transports

Определена в миграции `jni/deltachat-core-rust/src/sql/migrations.rs:1219-1225`:

```sql
CREATE TABLE transports (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    addr            TEXT NOT NULL,          -- email-адрес реле
    entered_param   TEXT NOT NULL,          -- JSON: то, что ввёл пользователь (вкл. пароль)
    configured_param TEXT NOT NULL,         -- JSON: рабочие параметры после автоконфигурации
    UNIQUE(addr)
);
```

Структура `ConfiguredLoginParam` в `jni/deltachat-core-rust/src/transport.rs:162-194`:

```rust
pub(crate) struct ConfiguredLoginParam {
    pub addr: String,
    pub imap: Vec<ConfiguredServerLoginParam>,
    pub imap_user: String,
    pub imap_password: String,
    pub smtp: Vec<ConfiguredServerLoginParam>,
    pub smtp_user: String,
    pub smtp_password: String,
    pub provider: Option<&'static Provider>,
    pub certificate_checks: ConfiguredCertificateChecks,
    pub oauth2: bool,
}
```

Загрузка всех транспортов — `ConfiguredLoginParam::load_all()` в `jni/deltachat-core-rust/src/transport.rs:276-286`:

```rust
pub(crate) async fn load_all(context: &Context) -> Result<Vec<(u32, Self)>> {
    context
        .sql
        .query_map_vec("SELECT id, configured_param FROM transports", (), |row| {
            let id: u32 = row.get(0)?;
            let json: String = row.get(1)?;
            let param = Self::from_json(&json)?;
            Ok((id, param))
        })
        .await
}
```

### Адрес контакта (одно поле)

Таблица `contacts` в `jni/deltachat-core-rust/src/sql/tables.sql:7-17` хранит **один** адрес на контакт:

```sql
CREATE TABLE contacts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT DEFAULT '',
    addr TEXT DEFAULT '' COLLATE NOCASE,  -- один email
    origin INTEGER DEFAULT 0,
    blocked INTEGER DEFAULT 0,
    last_seen INTEGER DEFAULT 0,
    param TEXT DEFAULT '',
    authname TEXT DEFAULT '',
    selfavatar_sent INTEGER DEFAULT 0
);
CREATE INDEX contacts_index1 ON contacts (name COLLATE NOCASE);
CREATE INDEX contacts_index2 ON contacts (addr COLLATE NOCASE);
```

Rust-структура `Contact` в `jni/deltachat-core-rust/src/contact.rs` — поле `addr: String` (одна строка).

`ContactId` — обёртка над `u32` (`contact.rs:52`). Зарезервированные ID: `SELF(1)`, `INFO(2)`, `DEVICE(5)`.

### Формирование получателей при отправке

В `jni/deltachat-core-rust/src/mimefactory.rs:308-399` (`MimeFactory::from_msg()`) для каждого контакта в чате берётся **один** `c.addr` из `chats_contacts JOIN contacts`:

```rust
// mimefactory.rs:307-358 — SQL-запрос для получения recipients:
"SELECT
 c.authname,
 c.addr,              // ← один адрес контакта
 c.fingerprint,
 c.id,
 cc.add_timestamp,
 cc.remove_timestamp,
 k.public_key
 FROM chats_contacts cc
 LEFT JOIN contacts c ON cc.contact_id=c.id
 LEFT JOIN public_keys k ON k.fingerprint=c.fingerprint
 WHERE cc.chat_id=?
 AND (cc.contact_id>9 OR (cc.contact_id=1 AND ?))
 ORDER BY cc.add_timestamp DESC"
```

Далее для каждой строки:

```rust
// mimefactory.rs:356-358
if !recipients_contain_addr(&to, &addr) {
    if id != ContactId::SELF {
        recipients.push(addr.clone());  // один адрес в recipients
    }
}
```

Функция `recipients()` в `mimefactory.rs:723` просто клонирует собранный `Vec<String>`.

### Цепочка отправки сообщения

```
chat.rs:2614  send_msg()
  → chat.rs:2668  prepare_send_msg()
    → chat.rs:2736  chat.prepare_msg_raw()     — сохранить в msgs
    → chat.rs:2738  create_send_msg_jobs()     — создать записи в таблице smtp
      → mimefactory.rs:191  MimeFactory::from_msg()  — сформировать MIME + recipients
      → mimefactory.rs:723  recipients()              — вернуть список адресов
  → scheduler.rs:673  send_smtp_messages()     — SMTP loop подхватывает
    → smtp.rs:338  send_msg_to_smtp()          — отправка конкретного сообщения
      → smtp.rs:409  smtp_send()               — отправка через SMTP-соединение
```

### Дедупликация при приёме (уже работает)

В `jni/deltachat-core-rust/src/receive_imf.rs:548-579`:

```rust
// receive_imf.rs:548 — проверка дубля по Message-ID:
} else if let Some(old_msg_id) = message::rfc724_mid_exists(context, rfc724_mid).await? {
    // ...
    info!(context, "Message is already downloaded.");
    if mime_parser.incoming {
        return Ok(None);  // ← пропускаем дубль для входящих
    }
    // Для исходящих — другая логика (BCC-self detection)
}
```

Функция `rfc724_mid_exists()` в `jni/deltachat-core-rust/src/message.rs:2169-2176`:

```rust
pub(crate) async fn rfc724_mid_exists(
    context: &Context,
    rfc724_mid: &str,
) -> Result<Option<MsgId>> {
    Ok(rfc724_mid_exists_ex(context, rfc724_mid, "1")
        .await?
        .map(|(id, _)| id))
}
```

`rfc724_mid_exists_ex()` в `message.rs:2183-2200` ищет по `msgs.rfc724_mid` или `msgs.pre_rfc724_mid`.

**Вывод:** если одно и то же сообщение (с одинаковым Message-ID) приходит через несколько релеев, первое будет сохранено, остальные — пропущены. Каждый транспорт запускает свой `inbox_loop` (`scheduler.rs:754`), сообщения с разных релеев обрабатываются параллельно, и `rfc724_mid_exists` защищает от дублей.

### Scheduler: параллельные IMAP-соединения

В `jni/deltachat-core-rust/src/scheduler.rs:741-787` — `Scheduler::start()`:

```rust
// scheduler.rs:754 — для каждого транспорта создаётся свой IMAP inbox_loop:
for (transport_id, configured_login_param) in ConfiguredLoginParam::load_all(ctx).await? {
    let (conn_state, inbox_handlers) =
        ImapConnectionState::new(ctx, transport_id, configured_login_param.clone()).await?;
    let handle = {
        let ctx = ctx.clone();
        task::spawn(inbox_loop(ctx, inbox_start_send, inbox_handlers))
    };
    inboxes.push(inbox);

    // Опционально — отдельный поток для mvbox:
    if ctx.should_watch_mvbox().await? {
        // ...ещё один ImapConnectionState для FolderMeaning::Mvbox
    }
}
```

Один `smtp_loop` для всех (`scheduler.rs:789-791`).

### Таблица imap (привязка к транспорту)

Определена в миграции `migrations.rs:1396-1412`:

```sql
CREATE TABLE imap (
    transport_id INTEGER NOT NULL,  -- ID транспорта
    rfc724_mid TEXT NOT NULL DEFAULT '',
    folder TEXT NOT NULL DEFAULT '',
    uid INTEGER NOT NULL DEFAULT 0,
    uidvalidity INTEGER NOT NULL DEFAULT 0,
    target TEXT NOT NULL DEFAULT '',
    UNIQUE (transport_id, folder, uid, uidvalidity)
);
CREATE INDEX imap_folder ON imap(transport_id, folder);
CREATE INDEX imap_rfc724_mid ON imap(transport_id, rfc724_mid);
```

Каждое IMAP-сообщение привязано к `transport_id` — при fetch из разных релеев создаются разные записи в `imap`, но дедупликация по `rfc724_mid` в таблице `msgs` предотвращает дублирование самого сообщения.

### BCC-self: как сообщение попадает на все свои релеи

`jni/deltachat-core-rust/src/smtp.rs:691-718`:

```rust
pub(crate) async fn add_self_recipients(
    context: &Context,
    recipients: &mut Vec<String>,
    encrypted: bool,
) -> Result<()> {
    if context.get_config_delete_server_after().await? != Some(0) || !recipients.is_empty() {
        // Только для зашифрованных — chatmail не принимает незашифрованные
        if encrypted {
            for addr in context.get_secondary_self_addrs().await? {
                recipients.push(addr);  // все вторичные адреса
            }
        }
        // `from` должен быть последним — см. receive_imf_inner()
        let from = context.get_primary_self_addr().await?;
        recipients.push(from);
    }
    Ok(())
}
```

---

## Ключевые файлы Rust-ядра

Все пути относительно `jni/deltachat-core-rust/`.

| Файл | Роль | Ключевые строки |
|------|------|-----------------|
| `src/transport.rs` | Таблица `transports`, структура `ConfiguredLoginParam`, загрузка/сохранение параметров | `ConfiguredLoginParam` :162, `load()` :249, `load_all()` :276 |
| `src/scheduler.rs` | Для каждого transport — свой IMAP `inbox_loop`. Один `smtp_loop` | `Scheduler::start()` :742, inbox_loop :754, smtp_loop :649 |
| `src/mimefactory.rs` | Формирование MIME, определение `recipients` по `chats_contacts` | `from_msg()` :191, recipients SQL :308, `recipients()` :723 |
| `src/chat.rs` | `send_msg()` → `prepare_send_msg()` → `create_send_msg_jobs()` | `send_msg` :2614, `prepare_send_msg` :2668, `create_send_msg_jobs` :2810 |
| `src/smtp.rs` | `send_msg_to_smtp()`, `smtp_send()`, `add_self_recipients()` | `smtp_send` :180, `send_msg_to_smtp` :338, `add_self_recipients` :691 |
| `src/receive_imf.rs` | Приём сообщений, дедупликация по `rfc724_mid` | дедупликация :548, `receive_imf_inner` :479 |
| `src/message.rs` | `rfc724_mid_exists()`, структура `Message` | `rfc724_mid_exists` :2169, `rfc724_mid_exists_ex` :2183 |
| `src/contact.rs` | Контакты, одно поле `addr`, `add_or_lookup`, `lookup_id_by_addr` | `Contact` struct, `add_or_lookup_ex` :860 |
| `src/sql/tables.sql` | Базовая схема БД | `contacts` :7, `msgs` :55 |
| `src/sql/migrations.rs` | Миграции схемы (114+ версий) | `transports` :1219, `imap` :1396 |
| `src/config.rs` | `get_all_self_addrs()`, `get_secondary_self_addrs()` | `get_all_self_addrs` :948, `get_secondary_self_addrs` :956 |
| `src/configure.rs` | Конфигурирование транспортов, лимит `MAX_TRANSPORT_RELAYS` | :48-50 |
| `src/imap.rs` | IMAP-клиент, привязка к `transport_id` | `transport_id` поле :75, `new()` :256 |

---

## Что нужно переделать

### Проблема

У **контакта** — **один адрес** (`contacts.addr`). Сообщение отправляется только на один адрес контакта. Если релей контакта упал — сообщение не доставлено.

### Решение

Разрешить хранить несколько адресов (релеев) у контакта. При отправке — слать на все. При приёме — дедуплицировать (уже работает). При получении от любого адреса контакта — привязывать к одному контакту.

**Самые критичные пункты:** отправка на все адреса (п.3) и резолвинг контакта при получении (п.4).

---

## План изменений

### 1. Новая таблица `contact_addrs` (миграция БД)

**Файл:** `src/sql/migrations.rs`

Добавить таблицу для хранения нескольких адресов у контакта:

```sql
CREATE TABLE contact_addrs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    contact_id INTEGER NOT NULL,
    addr TEXT NOT NULL COLLATE NOCASE,
    is_primary INTEGER DEFAULT 0,
    UNIQUE(addr),
    FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
);
CREATE INDEX contact_addrs_contact_id ON contact_addrs(contact_id);
CREATE INDEX contact_addrs_addr ON contact_addrs(addr COLLATE NOCASE);
```

**Миграция:** перенести все существующие `contacts.addr` → `contact_addrs` с `is_primary=1`:

```sql
INSERT INTO contact_addrs (contact_id, addr, is_primary)
SELECT id, addr, 1 FROM contacts WHERE addr != '';
```

Поле `contacts.addr` оставить как кеш основного адреса для обратной совместимости — обновлять при изменении `is_primary`.

**Сложность:** средняя.

---

### 2. API для CRUD адресов контакта

**Файл:** `src/contact.rs`

Новые функции:

```rust
/// Добавить дополнительный адрес контакту.
pub async fn add_contact_addr(
    context: &Context,
    contact_id: ContactId,
    addr: &str,
) -> Result<()>;

/// Удалить дополнительный адрес контакта.
/// Нельзя удалить основной, если он единственный.
pub async fn remove_contact_addr(
    context: &Context,
    contact_id: ContactId,
    addr: &str,
) -> Result<()>;

/// Получить все адреса контакта.
pub async fn get_contact_addrs(
    context: &Context,
    contact_id: ContactId,
) -> Result<Vec<String>>;

/// Установить основной адрес контакта.
pub async fn set_primary_contact_addr(
    context: &Context,
    contact_id: ContactId,
    addr: &str,
) -> Result<()>;
```

**FFI/JSON-RPC:** экспортировать через `deltachat-ffi/src/lib.rs` и `deltachat-jsonrpc/` для доступа из Android.

**Сложность:** средняя.

---

### 3. Отправка на все адреса контакта

**Файл:** `src/mimefactory.rs` — `MimeFactory::from_msg()`

**Текущая логика** (строки 308–358) — SQL-запрос берёт `c.addr` (один адрес) для каждого контакта в чате. Далее строка 356-358:

```rust
if !recipients_contain_addr(&to, &addr) {
    if id != ContactId::SELF {
        recipients.push(addr.clone());
    }
}
```

**Изменение:** для каждого `contact_id` запрашивать **все адреса** из `contact_addrs`:

```rust
// Вариант 1: изменить SQL-запрос — JOIN с contact_addrs вместо contacts.addr
"SELECT
 c.authname,
 ca.addr,              // ← из contact_addrs
 c.fingerprint,
 c.id,
 cc.add_timestamp,
 cc.remove_timestamp,
 k.public_key
 FROM chats_contacts cc
 LEFT JOIN contacts c ON cc.contact_id=c.id
 LEFT JOIN contact_addrs ca ON ca.contact_id=c.id   // ← JOIN
 LEFT JOIN public_keys k ON k.fingerprint=c.fingerprint
 WHERE cc.chat_id=?
 AND (cc.contact_id>9 OR (cc.contact_id=1 AND ?))
 ORDER BY cc.add_timestamp DESC"

// Вариант 2: после основного запроса, для каждого contact_id
// дозапрашивать дополнительные адреса из contact_addrs
```

Вариант 1 проще, но создаст несколько строк для каждого контакта (по одной на адрес). Нужно аккуратно обрабатывать `to`, `member_fingerprints`, `member_timestamps` — они должны формироваться **на уровне контакта**, а `recipients` — **на уровне адреса**.

**Важно:**
- Message-ID (`rfc724_mid`) остаётся **одинаковым** для всех копий — это ключ дедупликации на стороне получателя
- Одно MIME-сообщение с несколькими recipients — SMTP сам разошлёт копии
- Дополнительные адреса лучше помещать в BCC, а не в To/CC, чтобы не раскрывать все адреса контакта другим участникам
- Основной адрес — в To (как сейчас), дополнительные — в BCC

**Сложность:** средняя.

---

### 4. Резолвинг контакта при получении

**Файлы:** `src/contact.rs`, `src/receive_imf.rs`

Сейчас контакт определяется по `From:` адресу. Если у контакта 3 адреса (по одному на каждый релей), при получении сообщения от **любого** из этих адресов оно должно привязываться к **одному контакту**.

**Изменение в `contact.rs`** — функции `add_or_lookup_ex` (строка :860) и `lookup_id_by_addr`:

```rust
// Текущий запрос (lookup_id_by_addr):
"SELECT id FROM contacts WHERE addr=? COLLATE NOCASE"

// Новый запрос — сначала проверить contact_addrs:
"SELECT contact_id FROM contact_addrs WHERE addr=? COLLATE NOCASE"
// Если не найден — fallback на contacts.addr (обратная совместимость)
```

**Изменение в `receive_imf.rs`** — определение `from_id`:
- При определении отправителя проверять `contact_addrs` вместо (или в дополнение к) `contacts.addr`
- Все адреса одного контакта должны вести к одному `from_id`

**Осторожно:** много мест в коде полагаются на `contacts.addr` как на уникальный идентификатор. Места, которые нужно проверить:
- `contact.rs` — `lookup_id_by_addr()`, `add_or_lookup_ex()`, `get_by_id()`
- `receive_imf.rs` — `receive_imf_inner()`, определение `from_id`
- `mimefactory.rs` — формирование `From:` заголовка
- `securejoin.rs` — верификация контактов
- Везде где есть `SELECT ... FROM contacts WHERE addr=?`

**Сложность:** высокая. Это самый рискованный пункт.

---

### 5. Дедупликация при получении

**Статус: УЖЕ РАБОТАЕТ. Никаких изменений не требуется.**

Механизм `rfc724_mid_exists()` в `receive_imf.rs:548` проверяет Message-ID. Если одно и то же сообщение (с одинаковым Message-ID) приходит через несколько релеев получателя:

1. Первое сообщение сохраняется в БД (`msgs` таблица)
2. Все последующие дубли пропускаются (`return Ok(None)`)

Каждый транспорт запускает свой `inbox_loop` (`scheduler.rs:754`), сообщения с разных релеев обрабатываются параллельно. Возможен race condition, но SQLite + `rfc724_mid_exists()` проверка дают надёжную защиту.

---

### 6. UI: управление адресами контакта (Android)

**Файлы для создания/изменения:**

```
src/main/java/org/thoughtcrime/securesms/
├── contacts/
│   └── ContactAddrsActivity.java      -- список адресов контакта
│   └── ContactAddrsAdapter.java       -- RecyclerView adapter
│   └── AddContactAddrDialog.java      -- диалог добавления адреса
├── ContactDetailActivity.java         -- добавить кнопку "Адреса (релеи)"
```

**Функционал:**
- Просмотр списка адресов (релеев) контакта
- Добавление дополнительного адреса
- Удаление адреса (кроме последнего)
- Выбор основного адреса
- Индикация статуса адреса (онлайн/оффлайн — опционально)

**Сложность:** средняя.

---

## Порядок реализации

| Фаза | Задача | Зависимости |
|------|--------|-------------|
| **1** | Таблица `contact_addrs` + миграция | — |
| **2** | API для CRUD адресов контакта | Фаза 1 |
| **3** | Резолвинг контакта по любому адресу | Фаза 1, 2 |
| **4** | Отправка на все адреса контакта | Фаза 1, 2 |
| **5** | FFI/JSON-RPC экспорт | Фаза 2 |
| **6** | Android UI | Фаза 5 |

---

## Схема работы (после реализации)

```
Алиса (3 релея: a@r1, a@r2, a@r3)    Боб (3 релея: b@r1, b@r2, b@r3)
──────────────────────────────────     ──────────────────────────────────

Алиса отправляет сообщение Бобу.
У Алисы в contact_addrs для Боба: b@r1, b@r2, b@r3

1. send_msg() → prepare_send_msg() → create_send_msg_jobs()
2. MimeFactory::from_msg() формирует recipients:
   To: b@r1 (основной)
   BCC: b@r2, b@r3 (дополнительные)
   Message-ID: <unique-id@r1>    ← один и тот же для всех копий

3. smtp_send() отправляет один MIME на все 3 адреса:
   ┌─ SMTP → b@r1 ─┐
   ├─ SMTP → b@r2 ─┤  одно MIME-сообщение, один Message-ID
   └─ SMTP → b@r3 ─┘

4. Боб получает (каждый relay — свой inbox_loop):

   relay r1 (inbox_loop, transport_id=1) → receive_imf()
     → rfc724_mid_exists("unique-id@r1")? НЕТ
     → сохранить в msgs ✓
     → показать пользователю

   relay r2 (inbox_loop, transport_id=2) → receive_imf()
     → rfc724_mid_exists("unique-id@r1")? ДА
     → return Ok(None) — пропустить ✗

   relay r3 (inbox_loop, transport_id=3) → receive_imf()
     → rfc724_mid_exists("unique-id@r1")? ДА
     → return Ok(None) — пропустить ✗

   Результат: Боб видит ОДНО сообщение.

5. Если relay r1 оффлайн:
   relay r2 → rfc724_mid_exists? НЕТ → сохранить ✓
   relay r3 → rfc724_mid_exists? ДА → пропустить ✗

   Результат: сообщение всё равно доставлено через r2.
```

---

## Риски и edge cases

| Риск | Описание | Митигация |
|------|----------|-----------|
| Race condition при дедупликации | Два inbox_loop одновременно обрабатывают одно сообщение | SQLite UNIQUE constraint + `rfc724_mid_exists()` — надёжная защита |
| Шифрование (E2EE) | Сообщение зашифровано ключом контакта — нужен один ключ для всех адресов | Все адреса контакта привязаны к одному `fingerprint`/`public_key` в `contacts` и `public_keys` |
| Обратная совместимость | Старые версии не знают про `contact_addrs` | Поле `contacts.addr` остаётся как кеш основного адреса, обновляется при изменении `is_primary` |
| Большие группы | N участников × M адресов = N×M recipients | Лимит `MAX_SMTP_RCPT_TO` (50 обычный, 999 для chatmail — `constants.rs:221-224`). Разбивка на несколько SMTP-сообщений уже реализована |
| MDN (уведомления о прочтении) | MDN отправляется на адрес из `From:` — может не совпадать с основным | При резолвинге MDN в `smtp.rs:549` проверять `contact_addrs` |
| Синхронизация адресов между устройствами | Адреса контакта нужно синхронизировать на другие устройства | Расширить механизм sync-сообщений в `sync.rs` — добавить `SyncData::ContactAddrs` |
| `contacts.addr` как уникальный идентификатор | Много мест в коде завязаны на `WHERE addr=?` по таблице `contacts` | Постепенно переводить на поиск через `contact_addrs`, `contacts.addr` оставить как кеш |

---

## Оценка объёма работ

| Компонент | Файлы | Объём |
|-----------|-------|-------|
| Миграция БД | `src/sql/migrations.rs` | ~30 строк SQL |
| API CRUD адресов | `src/contact.rs` | ~150 строк Rust |
| Резолвинг контакта | `src/contact.rs`, `src/receive_imf.rs` | ~100 строк Rust (рефакторинг запросов) |
| Отправка на все адреса | `src/mimefactory.rs` | ~50 строк Rust |
| FFI экспорт | `deltachat-ffi/src/lib.rs`, `deltachat-jsonrpc/` | ~80 строк Rust/C |
| Android UI | `ContactAddrsActivity.java`, `ContactAddrsAdapter.java`, `ContactDetailActivity.java` | ~400 строк Java/Kotlin |
| Тесты | `src/contact/contact_tests.rs`, `src/receive_imf/receive_imf_tests.rs` | ~200 строк Rust |
| **Итого** | | **~1010 строк** |
