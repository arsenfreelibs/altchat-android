# Восстановление авторизации (POST /v1/users/quick-register)

Описание текущего контракта вызова `POST /v1/users/quick-register` и поведения клиента при отсутствии/невалидности JWT‑токена.

## Точки вызова `quickRegister()`

Обёртка эндпоинта:
- [AltApiService.java:45-47](../src/main/java/org/thoughtcrime/securesms/altplatform/network/AltApiService.java#L45-L47) — `quickRegister(RegisterRequest)` → `POST /v1/users/quick-register`.
- [AltPlatformService.java:333-388](../src/main/java/org/thoughtcrime/securesms/altplatform/AltPlatformService.java#L333-L388) — высокоуровневый `quickRegister(String displayName)`: собирает адреса транспортов, ключи, шифрует приватный ключ recovery‑паролем, шлёт запрос, при успехе сохраняет JWT (`AltTokenStorage.saveToken`).

Прямых вызовов `quickRegister()` в проекте **два**:

### 1. Первичная регистрация при онбординге

[InstantOnboardingActivity.java:497-505](../src/main/java/org/thoughtcrime/securesms/InstantOnboardingActivity.java#L497-L505)

```java
// quickRegister runs in background; on failure the token is simply absent
// and will be retried silently next time.
String displayName = pendingDisplayName;
AltPrefs.setRegistered(getApplicationContext(), displayName, null);
executor.execute(() -> {
    new AltPlatformService(getApplicationContext()).quickRegister(displayName);
});
navigateToMain();
```

Поведение:
- Запускается один раз в фоне после `progressSuccess()`.
- Флаг `setRegistered` выставляется **до** ответа сервера, чтобы не показывать экран регистрации.
- При неудаче (сеть, 5xx, и т. п.) — токен просто не сохраняется. Автоматического ретрая в этой точке **нет** (несмотря на комментарий в коде).

### 2. Lazy‑recovery в поиске пользователей

[AltPlatformService.java:200-219](../src/main/java/org/thoughtcrime/securesms/altplatform/AltPlatformService.java#L200-L219)

```java
public List<UserProfileResponse> searchUsers(String query) {
    AltApiResponse<List<UserProfileResponse>> resp = api.search(query);
    if (resp.isSuccess() && resp.data != null) return resp.data;
    if (resp.httpCode == 401) {
        String displayName = DcHelper.get(context, DcHelper.CONFIG_DISPLAY_NAME);
        if (displayName != null && !displayName.isEmpty()) {
            QuickRegisterResult result = quickRegister(displayName);
            if (result == QuickRegisterResult.SUCCESS) {
                AltApiResponse<List<UserProfileResponse>> retry = api.search(query);
                if (retry.isSuccess() && retry.data != null) return retry.data;
            }
        }
    }
    return null;
}
```

Поведение:
- При `401` от `GET /v1/users/search` вызывается `quickRegister(displayName)`.
- При `SUCCESS` — поиск повторяется один раз.
- Это **единственное** место с автоматическим восстановлением авторизации.

## Поведение при отсутствующем / невалидном токене

| Ситуация | Что происходит |
|----------|----------------|
| Токена нет в `AltTokenStorage` | Запрос уходит без `Authorization`, сервер вернёт 401. |
| Токен есть, но невалидный/протухший (401) | См. таблицу ниже — зависит от конкретного эндпоинта. |
| `quickRegister` сам вернул 401 | Не бывает — это публичный эндпоинт. Возвращается `NETWORK_ERROR` либо `USERNAME_TAKEN` (409). |

### Кто перехватывает 401

| Эндпоинт | Метод | Файл | Реакция на 401 |
|----------|-------|------|----------------|
| `/v1/users/search` | `searchUsers` | [AltPlatformService.java:200](../src/main/java/org/thoughtcrime/securesms/altplatform/AltPlatformService.java#L200) | **quick‑register + retry** |
| `/v1/users/me/private-key` | `getPrivateKey` | [AltPlatformService.java:175](../src/main/java/org/thoughtcrime/securesms/altplatform/AltPlatformService.java#L175) | молча падает |
| `/v1/users/{username}` | `getProfile` | [AltPlatformService.java:221](../src/main/java/org/thoughtcrime/securesms/altplatform/AltPlatformService.java#L221) | возвращает `null` |
| Любой другой авторизованный | — | — | без восстановления |

Централизованного 401‑interceptor в [AltApiClient](../src/main/java/org/thoughtcrime/securesms/altplatform/network/) **нет**.

## Практические последствия

1. Если первичный `quickRegister` после онбординга упал — токена нет, но `setRegistered` уже выставлен. Восстановление произойдёт **только** когда пользователь откроет поиск пользователей.
2. Открытие профиля по username, чтение приватного ключа и любые другие авторизованные операции с протухшим/отсутствующим токеном — молча провалятся, без попытки реги.
3. Контракт `quick-register` идемпотентен с точки зрения клиента: при `409 username_taken` возвращается `USERNAME_TAKEN`, при успехе — новый токен сохраняется поверх старого.

## Возможное улучшение

Единый 401‑interceptor в `AltApiClient`, который при первом 401 на любом авторизованном запросе вызывает `quickRegister(displayName)` и автоматически ретраит исходный запрос один раз — поведение, которое сейчас реализовано только в `searchUsers()`. Это уберёт асимметрию между эндпоинтами и сделает восстановление прозрачным для UI.
