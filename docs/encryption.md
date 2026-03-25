# Шифрование токена устройства

Исходный код: [jni/deltachat-core-rust/src/push.rs](../jni/deltachat-core-rust/src/push.rs)

## Библиотека

| Параметр | Значение |
|---|---|
| Язык | Rust |
| Крейт | [`pgp`](https://crates.io/crates/pgp) (rPGP — pure-Rust реализация OpenPGP) |
| Версия | `0.19.0` |
| Cargo.toml | `pgp = { version = "0.19.0", default-features = false }` |

rPGP — это полностью нативная Rust-реализация стандарта OpenPGP без зависимости от системного GnuPG.



## Пошаговый процесс шифрования

### 1. Паддинг

```rust
fn pad_device_token(s: &str) -> String {
    let expected_len: usize = 512;
    // заполняем пробелами до 512 символов
    format!("{s}{padding}")
}
```

Входная строка дополняется пробелами до **512 символов**, чтобы по длине шифртекста нельзя было восстановить длину исходных данных.

### 2. Разбор публичного ключа

Публичный ключ хардкоден в исходниках:

```
-----BEGIN PGP PUBLIC KEY BLOCK-----
mDMEab7mWxYJKwYBBAHaRw8BAQdAio08NeDM7rB3XN/LrDf4txEkliLkBMaspoZ5
...
-----END PGP PUBLIC KEY BLOCK-----
```

Из ключа берётся первый **encryption subkey** (X25519).

### 3. Шифрование SEIPD v2 (RFC 9580)

```rust
let mut msg = pgp::composed::MessageBuilder::from_bytes("", padded_device_token)
    .seipd_v2(
        &mut rng,
        SymmetricKeyAlgorithm::AES128,   // симметричный алгоритм
        AeadAlgorithm::Ocb,              // AEAD-режим
        ChunkSize::C8KiB,                // размер чанка
    );
msg.encrypt_to_key(&mut rng, &encryption_subkey)?;
```

| Параметр | Значение |
|---|---|
| Формат пакета | SEIPD v2 (Symmetrically Encrypted Integrity Protected Data v2) |
| Стандарт | [RFC 9580](https://www.rfc-editor.org/rfc/rfc9580) |
| Симметричный алгоритм | AES-128 |
| AEAD-режим | OCB (Offset Codebook Mode) |
| Размер чанка | 8 KiB |
| Асимметричный алгоритм ключа | X25519 (curve25519, из профиля `rfc9580`) |

### 4. Кодирование результата

```rust
format!("openpgp:{}", base64::engine::general_purpose::STANDARD.encode(encoded_message))
```

Зашифрованное сообщение **не** оборачивается в ASCII armor — оно кодируется в **Base64 (стандартный алфавит)** и добавляется префикс `openpgp:`.

Итоговый формат:
```
openpgp:<base64-encoded raw OpenPGP message>
```



## Как сделать расшифровщик в другом проекте

### Что нужно:
- Приватный ключ (парный к публичному выше)
- Библиотека, поддерживающая **OpenPGP RFC 9580 / SEIPD v2 + OCB**

### Java

**Зависимость (Maven / Gradle):**
```xml
<!-- Maven -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpg-jdk18on</artifactId>
    <version>1.78.1</version>
</dependency>
```
```groovy
// Gradle
implementation 'org.bouncycastle:bcpg-jdk18on:1.78.1'
```

#### Шифрование (Java)

```java
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.bc.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

public static String encrypt(String plaintext, String publicKeyAsc) throws Exception {
    // 1. Паддинг до 512 символов
    String padded = plaintext.length() < 512
        ? plaintext + " ".repeat(512 - plaintext.length())
        : plaintext;
    byte[] data = padded.getBytes(StandardCharsets.UTF_8);

    // 2. Парсим публичный ключ, берём encryption subkey
    PGPPublicKeyRingCollection keyRings = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(publicKeyAsc.getBytes())),
        new BcKeyFingerprintCalculator()
    );
    PGPPublicKey encKey = null;
    outer:
    for (PGPPublicKeyRing ring : keyRings) {
        for (PGPPublicKey key : ring) {
            if (key.isEncryptionKey()) { encKey = key; break outer; }
        }
    }
    if (encKey == null) throw new IllegalStateException("No encryption key found");

    // 3. Шифруем AES-128 + integrity packet (SEIPD)
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

#### Расшифровка (Java)

```java
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.bc.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public static String decrypt(String encrypted, String secretKeyAsc) throws Exception {
    String b64 = encrypted.startsWith("openpgp:") ? encrypted.substring(8) : encrypted;
    byte[] raw = Base64.getDecoder().decode(b64);

    PGPSecretKeyRingCollection secretKeys = new PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(secretKeyAsc.getBytes())),
        new BcKeyFingerprintCalculator()
    );

    PGPObjectFactory factory = new BcPGPObjectFactory(raw);
    Object obj = factory.nextObject();
    if (obj instanceof PGPEncryptedDataList encList) {
        for (var it = encList.getEncryptedDataObjects(); it.hasNext(); ) {
            PGPEncryptedData encData = it.next();
            if (encData instanceof PGPPublicKeyEncryptedData pkData) {
                PGPSecretKey secretKey = secretKeys.getSecretKey(pkData.getKeyID());
                if (secretKey == null) continue;

                PGPPrivateKey privateKey = secretKey.extractPrivateKey(
                    new BcPBESecretKeyDecryptorBuilder(
                        new BcPGPDigestCalculatorProvider()
                    ).build(new char[0]) // ключ без пароля
                );

                InputStream decStream = pkData.getDataStream(
                    new BcPublicKeyDataDecryptorFactory(privateKey)
                );
                PGPObjectFactory decFactory = new BcPGPObjectFactory(decStream);
                Object decObj = decFactory.nextObject();
                if (decObj instanceof PGPLiteralData litData) {
                    byte[] result = litData.getInputStream().readAllBytes();
                    // убираем паддинг из пробелов
                    return new String(result, StandardCharsets.UTF_8).stripTrailing();
                }
            }
        }
    }
    throw new IllegalStateException("Unable to decrypt message");
}
```

---

### C#

**Зависимость (NuGet):**
```
BouncyCastle.Cryptography >= 2.4.0
```
```xml
<PackageReference Include="BouncyCastle.Cryptography" Version="2.4.0" />
```

#### Шифрование (C#)

```csharp
using Org.BouncyCastle.Bcpg;
using Org.BouncyCastle.Bcpg.OpenPgp;
using System;
using System.IO;
using System.Text;

public static string Encrypt(string plaintext, string publicKeyAsc)
{
    // 1. Паддинг до 512 символов
    string padded = plaintext.Length < 512
        ? plaintext + new string(' ', 512 - plaintext.Length)
        : plaintext;
    byte[] data = Encoding.UTF8.GetBytes(padded);

    // 2. Парсим публичный ключ, берём encryption subkey
    using var keyStream = new MemoryStream(Encoding.ASCII.GetBytes(publicKeyAsc));
    var keyRings = new PgpPublicKeyRingBundle(PgpUtilities.GetDecoderStream(keyStream));

    PgpPublicKey? encKey = null;
    foreach (PgpPublicKeyRing ring in keyRings.GetKeyRings())
        foreach (PgpPublicKey key in ring.GetPublicKeys())
            if (key.IsEncryptionKey) { encKey = key; break; }

    if (encKey is null) throw new InvalidOperationException("No encryption key found");

    // 3. Шифруем AES-128 + integrity packet
    using var outStream = new MemoryStream();
    var encGen = new PgpEncryptedDataGenerator(
        SymmetricKeyAlgorithmTag.Aes128, withIntegrityPacket: true, new SecureRandom()
    );
    encGen.AddMethod(encKey);

    using (var encOut = encGen.Open(outStream, new byte[8192]))
    {
        var litGen = new PgpLiteralDataGenerator();
        using var litOut = litGen.Open(encOut, PgpLiteralData.Binary, "", data.Length, DateTime.UtcNow);
        litOut.Write(data);
    }

    // 4. Base64 + префикс
    return "openpgp:" + Convert.ToBase64String(outStream.ToArray());
}
```

#### Расшифровка (C#)

```csharp
using Org.BouncyCastle.Bcpg.OpenPgp;
using System;
using System.IO;
using System.Text;

public static string Decrypt(string encrypted, string secretKeyAsc)
{
    string b64 = encrypted.StartsWith("openpgp:") ? encrypted[8..] : encrypted;
    byte[] raw = Convert.FromBase64String(b64);

    using var keyStream = new MemoryStream(Encoding.ASCII.GetBytes(secretKeyAsc));
    var secretKeyRing = new PgpSecretKeyRingBundle(
        PgpUtilities.GetDecoderStream(keyStream)
    );

    using var rawStream = new MemoryStream(raw);
    var factory = new PgpObjectFactory(rawStream);
    var obj = factory.NextPgpObject();

    if (obj is PgpEncryptedDataList encList)
    {
        foreach (PgpPublicKeyEncryptedData pkData in encList.GetEncryptedDataObjects())
        {
            PgpSecretKey? secretKey = secretKeyRing.GetSecretKey(pkData.KeyId);
            if (secretKey is null) continue;

            // ключ без пароля — передаём null
            PgpPrivateKey privateKey = secretKey.ExtractPrivateKey(null);

            using var decStream = pkData.GetDataStream(privateKey);
            var decFactory = new PgpObjectFactory(decStream);
            var decObj = decFactory.NextPgpObject();

            if (decObj is PgpLiteralData litData)
            {
                using var reader = new StreamReader(litData.GetInputStream(), Encoding.UTF8);
                // убираем паддинг из пробелов
                return reader.ReadToEnd().TrimEnd();
            }
        }
    }
    throw new InvalidOperationException("Unable to decrypt message");
}
```



## Публичный ключ (fingerprint)

```
43733C59548EFA357405 62DE832C6BA8B50EF8D3
```

Алгоритм: **Ed25519** (primary key, sign-only) + **X25519** (subkey, encrypt-only)
Профиль: `rfc9580` (современный OpenPGP, не legacy)
