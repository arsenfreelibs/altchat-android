# Backend Architecture — Alt Chat User Directory

## Контекст

Приложение (me.altchat) использует email/IMAP для сообщений. Backend — это **отдельный сервис** (user directory), не трогает почтовую инфраструктуру.

---

## Стек

| Слой | Технология | Обоснование |
|---|---|---|
| Язык | **C# / .NET 8** | Строгая типизация, высокая производительность, отличный тулинг |
| HTTP | **ASP.NET Core (Minimal API)** | Встроенный DI, middleware, rate limiting из коробки |
| ORM | **Entity Framework Core** | Миграции, типобезопасные запросы, PostgreSQL провайдер |
| БД | **PostgreSQL** | Надёжность, full-text search для поиска по имени |
| Кэш / rate-limit store | **Redis** | Быстрый, TTL из коробки (`StackExchange.Redis`) |
| Деплой | **Docker + VPS** (Hetzner/DigitalOcean) | Просто, дёшево |

---

## Модель данных

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        TEXT UNIQUE NOT NULL,       -- уникальный никнейм
    display_name    TEXT,
    email_addr      TEXT NOT NULL,              -- DeltaChat email для контакта
    public_key_fp   TEXT,                       -- fingerprint PGP-ключа
    device_hash     TEXT NOT NULL,              -- хэш от device attestation
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX users_username_idx ON users(username);
CREATE INDEX users_display_name_fts ON users USING gin(to_tsvector('simple', display_name));
```

EF Core модель:
```csharp
public class User
{
    public Guid Id { get; set; }
    public string Username { get; set; } = null!;
    public string? DisplayName { get; set; }
    public string EmailAddr { get; set; } = null!;
    public string? PublicKeyFp { get; set; }
    public string DeviceHash { get; set; } = null!;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}
```

---

## API Endpoints

### POST /v1/users/register
Регистрация нового пользователя.

**Request:**
```json
{
  "username": "alice",
  "display_name": "Alice",
  "email_addr": "alice@nine.testrun.org",
  "public_key_fp": "AABBCCDD...",
  "device_token": "<attestation_token>"
}
```

**Response 201:**
```json
{ "id": "uuid", "username": "alice" }
```

**Errors:** 409 username taken, 400 invalid attestation, 429 rate limit.

---

### GET /v1/users/search?q=alice&limit=20
Поиск пользователей по username или display_name.

**Response 200:**
```json
{
  "results": [
    {
      "username": "alice",
      "display_name": "Alice",
      "email_addr": "alice@nine.testrun.org",
      "public_key_fp": "AABBCCDD..."
    }
  ]
}
```

---

### GET /v1/users/{username}
Получить профиль конкретного пользователя.

**Response 200:** тот же объект что и в search results.

---

## Защита от злоупотреблений

### Проблема
- Нельзя дать скрипту создавать тысячи аккаунтов
- Один телефон = один аккаунт (или жёсткий лимит)
- API не должен быть открыт для curl без верификации устройства

### Решение: Android Device Attestation (Play Integrity API)

**Цепочка:**
```
Приложение → запрашивает Play Integrity Token у Google
             → токен содержит: app package, device integrity verdict, request hash
             → токен отправляется на backend
             → backend проверяет через Google API (googleapis.com)
             → если verdict = MEETS_DEVICE_INTEGRITY → разрешить регистрацию
```

**Что проверяет Play Integrity:**
- Приложение оригинальное (подписано твоим ключом, `me.altchat`)
- Устройство прошло Android CTS (не рутовано грубо)
- Токен одноразовый (nonce привязывается к запросу)

**Реализация на стороне приложения:**
```kotlin
// build.gradle: implementation 'com.google.android.play:integrity:1.3.0'

val integrityManager = IntegrityManagerFactory.create(context)
val nonce = Base64.encode(sha256(username + email + timestamp))

integrityManager.requestIntegrityToken(
    IntegrityTokenRequest.builder()
        .setNonce(nonce)
        .build()
).addOnSuccessListener { response ->
    val token = response.token()
    // отправить token в /v1/users/register
}
```

**Реализация на стороне бэкенда (C#):**
```csharp
public class PlayIntegrityService(HttpClient httpClient, IConfiguration config)
{
    public async Task<string> VerifyAndGetDeviceHashAsync(string token, string expectedNonce)
    {
        var packageName = "me.altchat";
        var url = $"https://playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken";

        var response = await httpClient.PostAsJsonAsync(url, new { integrity_token = token });
        var result = await response.Content.ReadFromJsonAsync<IntegrityTokenResponse>();

        // Проверить: packageName == "me.altchat"
        // Проверить: nonce совпадает
        // Проверить: deviceRecognitionVerdict содержит MEETS_DEVICE_INTEGRITY
        var deviceId = result!.TokenPayloadExternal.DeviceIntegrity.DeviceRecognitionVerdict;
        return ComputeSha256(string.Join("", deviceId)); // уникален per-device
    }
}
```

**Привязка устройства:**
```sql
-- Один device_hash = один аккаунт
ALTER TABLE users ADD CONSTRAINT unique_device UNIQUE (device_hash);
```

### Rate Limiting (ASP.NET Core + Redis)

ASP.NET Core 8 имеет встроенный `RateLimiter`, для распределённого rate limit — Redis:

```csharp
// Program.cs
builder.Services.AddRateLimiter(options =>
{
    options.AddFixedWindowLimiter("register", o =>
    {
        o.PermitLimit = 5;
        o.Window = TimeSpan.FromHours(1);
        o.QueueLimit = 0;
    });
    options.AddFixedWindowLimiter("search", o =>
    {
        o.PermitLimit = 60;
        o.Window = TimeSpan.FromMinutes(1);
    });
});

// Применение к endpoints
app.MapPost("/v1/users/register", RegisterHandler)
   .RequireRateLimiting("register");

app.MapGet("/v1/users/search", SearchHandler)
   .RequireRateLimiting("search");
```

Для per-device лимита через Redis:
```csharp
// Проверить что device_hash не регистрировался раньше — через БД (UNIQUE constraint)
// Для per-IP через Redis: ключ = "reg:ip:{ip}", TTL = 1 час, increment + expire
```

### Дополнительно
- Поле `username` — только латиница + цифры + underscore, 3–30 символов, `[RegularExpression]` валидация через `DataAnnotations`
- Поиск доступен без авторизации, но rate-limit жёсткий
- Логировать все 429 через встроенный `ILogger` → structured logs

---

## FOSS вариант (без Google Play Integrity)

Для F-Droid сборки (`foss` flavor) Play Integrity недоступен.

**Альтернатива:**
- **Ограничение:** один email_addr = один аккаунт (+ проверка через отправку кода на email)
- Email-верификация:
  1. Backend генерирует 6-значный код, отправляет на `email_addr`
  2. Приложение вводит код — POST `/v1/users/verify`
  3. Только после верификации аккаунт активируется
- Отправка email — через `MailKit` (NuGet пакет, стандарт для C#)

---

## Авторизация для поиска (опционально, фаза 2)

Если поиск должен быть только для зарегистрированных пользователей:
- При регистрации backend возвращает **JWT** (RS256, short-lived 7 дней + refresh token)
- Search endpoint требует `Authorization: Bearer <jwt>`
- В ASP.NET Core JWT из коробки: `builder.Services.AddAuthentication().AddJwtBearer(...)`

---

## Структура проекта (ASP.NET Core)

```
AltChat.Api/
    Program.cs                  # точка входа, DI, middleware
    Endpoints/
        UsersEndpoints.cs       # MapPost register, MapGet search, MapGet profile
    Services/
        UserService.cs          # бизнес-логика
        PlayIntegrityService.cs # верификация токена
        RateLimitService.cs     # Redis per-IP/per-device лимиты
    Data/
        AppDbContext.cs          # EF Core контекст
        Entities/User.cs
    Models/
        RegisterRequest.cs      # DTO входящих запросов
        UserResponse.cs         # DTO ответов
    Migrations/                 # EF Core миграции (dotnet ef migrations add)
    appsettings.json            # конфиг
```

---

## Деплой

```
VPS (2 vCPU / 2GB RAM достаточно для старта)
├── .NET 8 app (Docker container — mcr.microsoft.com/dotnet/aspnet:8.0)
├── PostgreSQL 16
├── Redis 7
└── Nginx (reverse proxy + TLS через Let's Encrypt)
```

**appsettings.json / env vars:**
```json
{
  "ConnectionStrings": {
    "Default": "Host=localhost;Database=altchat;Username=...;Password=..."
  },
  "Redis": "localhost:6379",
  "PlayIntegrity": {
    "ServiceAccountJson": "...",
    "PackageName": "me.altchat"
  },
  "Jwt": {
    "PrivateKey": "..."
  }
}
```

**Dockerfile:**
```dockerfile
FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /app
COPY . .
RUN dotnet publish -c Release -o /out

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /out .
ENTRYPOINT ["dotnet", "AltChat.Api.dll"]
```

---

## Что НЕ входит в этот бэкенд

- Email/IMAP сервер — остаётся chatmail (nine.testrun.org или свой)
- TURN/STUN — отдельно (coturn)
- Push-уведомления — Firebase, через chatmail
- Хранение сообщений — только на email-серверах, end-to-end encrypted

---

## Фазы разработки

| Фаза | Что | Приоритет |
|---|---|---|
| 1 | Register + Search + Play Integrity | MVP |
| 2 | Email-верификация для FOSS | После gplay MVP |
| 3 | JWT авторизация для search | Если нужна приватность поиска |
| 4 | Аналитика, мониторинг (встроенный `/metrics` + Grafana) | После стабилизации |
