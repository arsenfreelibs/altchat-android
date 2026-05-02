# Архитектура реле (транспортов) в Delta Chat Android

## Что такое реле?

Реле (relay / transport) — это email-аккаунт, через который Delta Chat отправляет и получает сообщения. В профиле может быть несколько реле одновременно; одно из них помечается как основное (main relay), остальные — дополнительные. Термины «реле» и «транспорт» используются в коде взаимозаменяемо.

---

## Параметры реле для восстановления доступа

> **Все данные перед передачей шифруются OpenPGP (SEIPD v2, RFC 9580)** открытым ключом сервера — X25519, AES-128, AEAD OCB. Схема шифрования описана в [docs/encryption.md](encryption.md). Сервер хранит только зашифрованный блоб — в открытом виде ничего не передаётся.

Полный список полей `EnteredLoginParam` (источник: [`EnteredLoginParam.java`](../src/main/java/chat/delta/rpc/types/EnteredLoginParam.java)):

| Поле | Тип | Обязательное |
|------|-----|:---:|
| `addr` | String | ✅ |
| `password` | String | ✅ |
| `privateKey` (OpenPGP) | String (armored) | ✅ |
| `smtpPassword` | String | — |
| `imapServer` | String | — |
| `imapPort` | Integer | — |
| `imapSecurity` | Socket | — |
| `imapUser` | String | — |
| `smtpServer` | String | — |
| `smtpPort` | Integer | — |
| `smtpSecurity` | Socket | — |
| `smtpUser` | String | — |
| `certificateChecks` | EnteredCertificateChecks | — |
| `oauth2` | Boolean | — |

---

## Точки входа: как пользователь добавляет реле

### 1. Сканирование QR-кода (основной способ)

**Поток:**
```
RelayListActivity (кнопка FAB "+")
  → QrActivity (экран камеры, EXTRA_SCAN_RELAY=true)
  → QrCodeHandler.handleOnlyAddRelayQr()
  → QrCodeHandler.addRelay()
  → Rpc.addTransportFromQr(accId, qrData)
```

QR-код может содержать два вида данных:
- `DC_QR_ACCOUNT` — новый chatmail-аккаунт, зашифрованный в QR. Параметры сервера fetch-ятся с провайдера.
- `DC_QR_LOGIN` — конкретный адрес + опции сервера.

Перед подтверждением добавления пользователю показывается диалог с адресом реле. Если на устройстве включён Screen Lock, запрашивается биометрия / PIN.

### 2. Ручной ввод (EditRelayActivity)

**Поток:**
```
InstantOnboardingActivity / AdvancedPreferenceFragment
  → EditRelayActivity (пустой = создание, с EXTRA_ADDR = редактирование)
  → setupConfig()
  → Rpc.addOrUpdateTransport(accId, param)
```

Поля ввода:
| Поле | Назначение |
|------|-----------|
| `emailInput` | Email-адрес реле (`addr`) |
| `passwordInput` | Пароль IMAP |
| `smtpPasswordInput` | Пароль SMTP (если отличается) |
| `imapServerInput/Port/Security` | Ручные настройки IMAP |
| `smtpServerInput/Port/Security` | Ручные настройки SMTP |
| `certCheck` | Проверка TLS-сертификата |

Если `EditRelayActivity` запускается без `EXTRA_ADDR` — создается новое реле. Если с адресом — открываются текущие параметры из RPC для редактирования.

### 3. Deep link / Intent (InstantOnboardingActivity)

Если приложение открывается через URI-схему (например, `dcaccount://…`), `InstantOnboardingActivity` достаёт URI из Intent и передаёт его в `RelayListActivity` через `EXTRA_QR_DATA`. Далее — тот же QR-путь.

---

## Процесс подключения (конфигурирование)

При вызове `Rpc.addOrUpdateTransport()` или `Rpc.addTransportFromQr()` в Rust-ядре запускается `add_or_update_transport`:

```
add_or_update_transport(param)
  1. stop_io()                        — остановить активные соединения
  2. add_transport_inner(param)
     a. normalize addr
     b. alloc_ongoing()               — захватить "слот" текущей операции
     c. inner_configure(param)        — автоконфигурация (autoconfig/provider DB/пробные соединения)
     d. param.save(context)           — сохранить entered_param в БД
     e. emit ConfigureProgress(1000)  — 100% прогресс = успех
     f. free_ongoing()
  3. start_io()                       — запустить IO с обновлёнными транспортами
```

В ходе `inner_configure` эмитируются события `DC_EVENT_CONFIGURE_PROGRESS` (значение 0–1000), которые `EditRelayActivity` / `QrCodeHandler` отображают как прогресс-диалог в процентах.

Если конфигурирование завершилось ошибкой, IO перезапускается только если хотя бы один транспорт уже есть (`is_configured()` — проверяет `SELECT COUNT(*) FROM transports`).

---

## Где и как хранятся учётные данные

### База данных SQLite (главное хранилище)

Все данные реле хранятся в таблице `transports` в SQLite-базе аккаунта Delta Chat (файл расположен во внутреннем хранилище приложения — `files/accounts/<id>/db.sqlite`):

```sql
CREATE TABLE transports (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    addr            TEXT NOT NULL,          -- email-адрес реле
    entered_param   TEXT NOT NULL,          -- JSON: то, что ввёл пользователь (вкл. пароль)
    configured_param TEXT NOT NULL,         -- JSON: рабочие параметры после автоконфигурации
    UNIQUE(addr)
);
```

**`entered_param`** — JSON-сериализация `EnteredLoginParam`. Содержит всё, что пользователь ввёл вручную, включая открытый пароль (plaintext):

```json
{
  "addr": "user@chatmail.at",
  "password": "s3cr3t",
  "imapServer": null,
  "imapPort": null,
  "imapSecurity": null,
  "imapUser": null,
  "smtpServer": null,
  "smtpPort": null,
  "smtpSecurity": null,
  "smtpUser": null,
  "smtpPassword": null,
  "certificateChecks": null,
  "oauth2": null
}
```

**`configured_param`** — рабочие параметры (реальные хосты, порты, флаги) после успешной автоконфигурации. Используются непосредственно для IMAP/SMTP-соединений.

> **Важно:** Пароли хранятся в открытом виде внутри SQLite-базы. Безопасность обеспечивается тем, что база находится во внутреннем хранилище Android (`data/data/…`), недоступном без root-прав, а также полнодисковым шифрованием Android (FDE/FBE).

### Ключ активного реле

Адрес основного (активного) реле сохраняется отдельно в таблице `config`:

```sql
key: "configured_addr"   value: "user@chatmail.at"
```

Это позволяет `RelayListActivity` и ядру знать, какой транспорт является основным.

---

## Управление реле: список, переключение, удаление

### RelayListActivity

- **Загрузка**: вызывает `Rpc.listTransports(accId)` — ядро делает `SELECT entered_param FROM transports`.
- **Переключение**: по клику на реле вызывает `Rpc.setConfig(accId, "configured_addr", relay.addr)` — меняет основной транспорт.
- **Редактирование**: запускает `EditRelayActivity` с `EXTRA_ADDR = relay.addr`.
- **Удаление**: диалог подтверждения → `Rpc.deleteTransport(accId, relay.addr)` (только для не-основного реле).

### Обновление UI

`RelayListActivity` подписывается на два события ядра:
- `DC_EVENT_CONFIGURE_PROGRESS(1000)` — конфигурирование завершено, перезагрузить список.
- `DC_EVENT_TRANSPORTS_MODIFIED` — транспорт добавлен/удалён/изменён.

---

## Структура классов (Android-сторона)

```
relay/
├── RelayListActivity.java      — список всех реле, FAB для добавления
├── RelayListAdapter.java       — RecyclerView adapter (EnteredLoginParam → UI)
└── EditRelayActivity.java      — форма создания/редактирования реле

qr/
├── QrActivity.java             — сканер QR с флагом EXTRA_SCAN_RELAY
└── QrCodeHandler.java          — разбор QR, вызов addRelay()

connect/
└── DcHelper.java               — константы (CONFIG_CONFIGURED_ADDRESS и др.)

chat/delta/rpc/
├── Rpc.java                    — Java-обёртка над JSON-RPC
└── types/
    └── EnteredLoginParam.java  — DTO: поля реле (addr, password, imap*, smtp*, …)
```

---

## Схема потока: добавление реле через QR

```
Пользователь нажимает "+" в RelayListActivity
        │
        ▼
QrActivity (камера, EXTRA_SCAN_RELAY=true)
        │  сканирует QR
        ▼
QrCodeHandler.handleOnlyAddRelayQr(rawString)
        │  dcContext.checkQr(rawString)  → DC_QR_ACCOUNT / DC_QR_LOGIN
        │  показывает AlertDialog с адресом
        │
        ├─ [Screen Lock включён] → ScreenLockUtil.applyScreenLock() → биометрия/PIN
        │
        ▼
QrCodeHandler.addRelay(qrData)
        │  ProgressDialog (с кнопкой "Отмена")
        │  Rpc.addTransportFromQr(accId, qrData)  [background thread]
        │
        ▼
deltachat-core-rust: add_transport_from_qr()
        │  stop_io → inner_configure → param.save → start_io
        │  эмитирует DC_EVENT_CONFIGURE_PROGRESS(0..1000)
        │
        ▼
Успех → Toast "Done" → RelayListActivity (если ещё не там)
Ошибка → AlertDialog с текстом ошибки
```

---

## Дополнительные аспекты безопасности

| Аспект | Детали |
|--------|--------|
| Хранение пароля | Plaintext в SQLite (защита — Android sandbox + шифрование диска) |
| Screen Lock | Применяется при открытии списка реле из настроек и при добавлении через deep link |
| TLS-сертификат | Настраивается через `certificateChecks` (`Automatic` / `AcceptInvalidCertificates` / `AcceptInvalidHostnames`) |
| Права доступа | SQLite-база лежит в `internal storage`, недоступна другим приложениям без root |
| Отмена операции | Кнопка "Отмена" в ProgressDialog вызывает `dcContext.stopOngoingProcess()` → Rust отменяет текущую конфигурацию |
