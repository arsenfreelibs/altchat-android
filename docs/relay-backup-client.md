# Резервное копирование и восстановление реле — интеграция на клиенте

> **Связанные документы:**
> - Бизнес-требования: [`backend-api/docs/module-relays-ba.md`](../backend-api/docs/module-relays-ba.md)
> - Архитектура реле (Android): [`deltachat-android/docs/relay-architecture.md`](relay-architecture.md)
> - Схема шифрования: [`deltachat-android/docs/encryption.md`](encryption.md)

---

## Цель

Когда пользователь подключает реле, его параметры автоматически сохраняются на сервере Alt в зашифрованном виде. При смене устройства или переустановке реле восстанавливаются с сервера без ручного ввода.

---

## Ключевая схема шифрования

| Направление | Кем шифрует | Кем расшифрует |
|------------|--------------|----------------|
| **Сохранение** (`PUT /relays`) | Клиент — публичным ключом сервера | Сервер — своим приватным ключом |
| **Восстановление** (`GET /relays`) | Сервер — публичным ключом пользователя | Клиент — своим приватным ключом |

Сервер знает публичный ключ пользователя — он хранится в `User.PublicKey` и передан при регистрации. Дополнительно передавать ничего не нужно.

---

## Когда вызывать

| Событие | Действие |
|---------|----------|
| `DC_EVENT_CONFIGURE_PROGRESS = 1000` (реле успешно добавлено/изменено) | `PUT /relays` |
| Первый запуск после входа / восстановления | `GET /relays` → импортировать каждое реле в ядро |

---

# ФАЗА 1 — Сохранение реле

> Реализуется в первую очередь.

---

### Триггер

Событие `DC_EVENT_CONFIGURE_PROGRESS = 1000` — реле успешно добавлено / изменено.

### Последовательность действий

1. Получить параметры реле через `Rpc.listTransports` — выбрать нужный по `addr`.
2. Сформировать JSON из всех полей `EnteredLoginParam` (включая `privateKey`).
3. Зашифровать JSON **публичным ключом сервера** (см. раздел «Шифрование»).
4. Отправить `PUT /relays` с JWT-токеном.

Это фоновая операция. Не блокировать UI, не показывать ошибки пользователю при сбое (только логировать).

### API — `PUT /relays`

```
PUT /relays
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "encryptedData": "openpgp:<base64>"
}
```

| Статус | Действие на клиенте |
|--------|---------------------|
| `200 OK` | Успех |
| `400 Bad Request` | Залогировать, продолжить работу |
| `401 Unauthorized` | Обновить токен, повторить |

---

# ФАЗА 2 — Восстановление реле

> Реализуется позже, отдельно от Фазы 1.

---

### Триггер

Первый запуск после входа пользователя на новом устройстве / после переустановки (получен JWT).

### Как работает восстановление

Данные на сервере хранятся зашифрованными ключом сервера — клиент не может их расшифровать.
Поэтому при `GET /relays` сервер выполняет перешифровку:

1. Расшифровывает блоб **своим приватным ключом**.
2. Перешифровывает открытые данные **публичным ключом пользователя** (берётся из `User.PublicKey`).
3. Отвечает перешифрованным блобом.

Клиент получает блоб, зашифрованный **его собственным публичным ключом**, и расшифровывает его **своим приватным ключом**.

### Последовательность действий

1. Отправить `GET /relays` с JWT-токеном.
2. Для каждой записи в ответе:
   - Расшифровать `encryptedData` своим приватным ключом (DeltaChat core имеет к нему доступ).
   - Десериализовать JSON в `EnteredLoginParam`.
   - Вызвать `Rpc.addOrUpdateTransport(accId, enteredLoginParam)` для восстановления реле в ядре.

### API — `GET /relays`

```
GET /relays
Authorization: Bearer <jwt>
```

**Ответ `200 OK`:**
```json
[
  {
    "addr": "user@chatmail.at",
    "encryptedData": "openpgp:<base64>"
  }
]
```

`encryptedData` — блоб, перешифрованный сервером публичным ключом пользователя. Расшифровывается приватным ключом пользователя.

| Статус | Действие на клиенте |
|--------|---------------------|
| `200 OK` | Обработать список (может быть пустым) |
| `401 Unauthorized` | Обновить токен, повторить |

---

## Авторизация

Все запросы к `/relays` требуют заголовка:
```
Authorization: Bearer <jwt>
```

JWT выдаётся сервером Alt после успешной верификации email при регистрации или восстановлении аккаунта. Сервер извлекает из него `uid` (claim `sub`) и привязывает реле к конкретному пользователю.

---

## Шифрование перед отправкой

JSON шифруется **публичным ключом сервера**, хардкоденным в приложении.

| Параметр | Значение |
|----------|----------|
| Алгоритм | OpenPGP SEIPD v2 (RFC 9580) |
| Асимметричный ключ | X25519 (encryption subkey) |
| Симметричный алгоритм | AES-128 |
| AEAD-режим | OCB |
| Паддинг | Пробелы до 512 символов |
| Итоговый формат | `openpgp:<base64>` (без ASCII-armor) |

Подробная спецификация и примеры кода → [encryption.md](encryption.md).

### Публичный ключ сервера (fingerprint: `43733C59548EFA357405 62DE832C6BA8B50EF8D3`)

Хардкодится в приложении:

```
-----BEGIN PGP PUBLIC KEY BLOCK-----
mDMEab7mWxYJKwYBBAHaRw8BAQdAio08NeDM7rB3XN/LrDf4txEkliLkBMaspoZ5
...
-----END PGP PUBLIC KEY BLOCK-----
```

### Пример шифрования (Android / Java)

```java
// Зависимость: implementation 'org.bouncycastle:bcpg-jdk18on:1.78.1'

public static String encryptForServer(String plaintext) throws Exception {
    // 1. Паддинг до 512 символов
    String padded = plaintext.length() < 512
        ? plaintext + " ".repeat(512 - plaintext.length())
        : plaintext;
    byte[] data = padded.getBytes(StandardCharsets.UTF_8);

    // 2. Публичный ключ сервера (хардкод)
    PGPPublicKeyRingCollection keyRings = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(SERVER_PUBLIC_KEY.getBytes())),
        new BcKeyFingerprintCalculator()
    );
    PGPPublicKey encKey = null;
    outer:
    for (PGPPublicKeyRing ring : keyRings) {
        for (PGPPublicKey key : ring) {
            if (key.isEncryptionKey()) { encKey = key; break outer; }
        }
    }

    // 3. Шифруем AES-128 + integrity packet
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(
        new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_128)
            .setWithIntegrityPacket(true)
            .setSecureRandom(new SecureRandom())
    );
    encGen.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(encKey));
    try (OutputStream encOut = encGen.open(out, new byte[8192]);
         OutputStream litOut = new PGPLiteralDataGenerator()
             .open(encOut, PGPLiteralData.BINARY, "", data.length, new Date())) {
        litOut.write(data);
    }

    // 4. Base64 + префикс
    return "openpgp:" + Base64.getEncoder().encodeToString(out.toByteArray());
}
```

Для iOS — аналогичная логика с любой библиотекой, поддерживающей **OpenPGP RFC 9580 / SEIPD v2 + OCB** (например, ObjectivePGP или SwiftPGP).

---

## Данные реле для шифрования (`EnteredLoginParam`)

Обязательные поля:

| Поле | Тип | Источник |
|------|-----|----------|
| `addr` | String | Email-адрес реле |
| `password` | String | Пароль IMAP |
| `privateKey` | String (armored) | Приватный PGP-ключ пользователя |

Необязательные поля (передавать если заполнены):

| Поле | Тип |
|------|-----|
| `smtpPassword` | String |
| `imapServer` | String |
| `imapPort` | Integer |
| `imapSecurity` | String |
| `imapUser` | String |
| `smtpServer` | String |
| `smtpPort` | Integer |
| `smtpSecurity` | String |
| `smtpUser` | String |
| `certificateChecks` | String |
| `oauth2` | Boolean |

Пример JSON до шифрования:
```json
{
  "addr": "user@chatmail.at",
  "password": "s3cr3t",
  "privateKey": "-----BEGIN PGP PRIVATE KEY BLOCK-----\n...",
  "imapServer": null,
  "smtpServer": null
}
```

> `privateKey` — тот же приватный OpenPGP-ключ пользователя, который был создан при регистрации. Подробнее о структуре — в [relay-architecture.md](relay-architecture.md).

---

## Обработка ошибок

| Ситуация | Поведение |
|----------|-----------|
| Сервер вернул `400` | Залогировать, продолжить работу |
| Сервер вернул `401` | Повторить с обновлённым токеном |
| Нет сети | Поставить в очередь, повторить при восстановлении соединения |
| `GET /relays` вернул пустой массив | Нормальная ситуация — реле ещё не сохранялись |
