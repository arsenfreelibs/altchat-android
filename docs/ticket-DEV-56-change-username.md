# DEV-56: Смена никнейма Alt

**Jira Link:** https://altchatme.atlassian.net/browse/DEV-56

---

## О чём задача

Пользователь Alt получает никнейм один раз — на регистрации — и потом
изменить его никак не может. Опечатался, передумал, хочет сменить — никак.
В этой задаче делаем экран в настройках, где пользователь вводит новый ник и
сохраняет. Если ник не подходит (некорректный, занят, зарезервирован) — сервер
сам предлагает до пяти свободных альтернатив, мы показываем их кнопками.
Тапнул по кнопке — ник подставился в поле. Нажал «Save» — готово.

Никнейм можно менять не чаще одного раза в 30 дней — за это отвечает сервер,
наша задача — корректно показать пользователю, сколько ещё ждать.

## Как это видит пользователь

1. Заходит в **Settings**, видит пункт **«Change username»**, под ним мелким
   шрифтом — его текущий ник.
2. Тапает — открывается отдельный экран. Сверху строка ввода, в ней как
   подсказка стоит текущий ник. Ниже — небольшая фраза «You can change your
   username once every 30 days».
3. Вводит новый ник. Если ввёл что-то невалидное (пусто, слишком коротко) —
   ошибка появляется сразу, до отправки.
4. Жмёт «Save» в тулбаре. Тулбар крутит индикатор, поле блокируется.
5. **Хороший случай:** ник принят, всплывает «Username changed», экран
   закрывается, в настройках уже видно новый ник.
6. **Плохой случай:** под полем появляется пояснение, что не так
   («This username is already taken» / «Invalid format» / «This username is
   reserved»). Под пояснением — до 5 кнопочек-чипов с альтернативами,
   которые сервер сам подобрал. Тап по чипу — значение мгновенно
   оказывается в поле, можно сразу нажать «Save».
7. **Случай «слишком рано»:** если пользователь уже менял ник недавно, видит
   «You can change your username again in N days», «Save» становится
   неактивным.

## Что мы реально делаем

- Новый экран **`AltChangeUsernameActivity`** с одним полем и `ChipGroup` под
  ним.
- Новый пункт в Settings, ведущий на этот экран.
- Новый метод **`AltPlatformService.changeUsername(...)`** — вся бизнес-логика
  обращения к серверу и обновления локального состояния.
- Новый метод **`AltApiService.putMeUsername(...)`** — собственно HTTP-вызов
  через существующий `AltApiClient` (он уже сделан в ANDROID-001).
- Локальное хранилище ника (`AltPrefs`) обновляется после успешного запроса —
  это нужно, чтобы Settings и любые другие экраны сразу показывали новый ник.

JWT, OkHttp-клиент, сам `AltApiClient`, конвертация ошибок, переход в
онбординг при `401` — всё это уже есть после ANDROID-001, заново не делаем,
переиспользуем.

## Контракт с API

Это единственное, что нужно знать про сервер. Эндпоинт уже реализован.

**Запрос:**

```
PUT /v1/users/me/username
Authorization: Bearer <jwt>
Content-Type: application/json

{"username": "new_nick"}
```

**Ответы:**

| HTTP | Тело ответа | Что это значит |
|---|---|---|
| `200` | `{"username":"new_nick"}` | Успех. Ник сменился, сохраняем локально. |
| `400` | `{"error":"same_username"}` | Пользователь ввёл тот же ник, что у него уже есть. |
| `422` | `{"error":"invalid_username","suggestions":[...]}` | Формат не подходит. |
| `422` | `{"error":"username_reserved","suggestions":[...]}` | Ник в списке зарезервированных. |
| `409` | `{"error":"username_taken","suggestions":[...]}` | Ник уже кто-то занял. |
| `429` | `{"error":"cooldown","retryAfter":<seconds>}` | Слишком рано менять. `retryAfter` — секунды до следующей попытки. |
| `401` | — | JWT протух или невалиден. |
| `5xx` / нет сети | — | Сетевая ошибка. |

`suggestions` — массив строк, **до 5 элементов**. Может быть пустым или
содержать меньше пяти. Каждый элемент уже валиден и свободен — клиент его
может сразу отправить без дополнительных проверок.

DTO для парсинга — один на все случаи, нужные поля могут быть `null`:

```java
public class ChangeUsernameResponse {
    public String username;          // только при 200
    public String error;             // при не-2xx
    public List<String> suggestions; // при 422 / 409
    public Long retryAfter;          // при 429, секунды
}
```

Тело при не-2xx тоже парсится в этот же DTO (через общий error-конвертер
`AltApiClient`).

## Поведение экрана

### Локальные проверки до сети

Эти случаи отрабатываем сами, не дёргая сервер:

| Что ввёл пользователь | Что показываем |
|---|---|
| Пусто | «Username can't be empty» |
| Меньше 3 или больше 30 символов | «Use 3 to 30 characters» |
| То же, что уже сохранено в `AltPrefs` | «This is already your username» |

Поле ввода имеет **input filter** на `[A-Za-z0-9_]` — посторонние символы
вообще не попадают в поле. Это удобство, не валидация: окончательное
решение по формату всё равно за сервером (он может вернуть
`invalid_username` по причинам, о которых мы не знаем).

### Маппинг ответов в UI

| Ответ от `changeUsername` | Что показываем |
|---|---|
| `Success(name)` | Toast «Username changed», `setResult(RESULT_OK)`, закрываем экран |
| `SameUsername` | Inline-ошибка «This is already your username», без чипов |
| `Invalid(suggestions)` | Inline-ошибка «Invalid format. Try one of these:» + чипы |
| `Reserved(suggestions)` | Inline-ошибка «This username is reserved. Try one of these:» + чипы |
| `Taken(suggestions)` | Inline-ошибка «This username is already taken. Try one of these:» + чипы |
| `Cooldown(retryAfterSec)` | Inline-ошибка «You can change your username again in N days» (`ceil(retryAfterSec / 86400)`), `Save` выключен до закрытия экрана |
| `Unauthorized` | Toast «Session expired», уход в онбординг (хелпер из ANDROID-001) |
| `Network` | Toast «Network error», поле/Save разблокируем, чипы не трогаем |

### Чипы с предложениями

- Появляются только при `Invalid` / `Reserved` / `Taken`.
- Сколько пришло (0–5), столько и рисуем.
- Тап по чипу — значение в поле, `ChipGroup` скрывается, ошибка стирается,
  фокус снова в поле.
- **Save сам не нажимаем** — пусть пользователь нажмёт. Защита от случайного
  клика.

### Layout (эскиз)

`res/layout/alt_change_username_activity.xml`:

```
LinearLayout (vertical)
├── Toolbar (action Save)
└── ScrollView
    └── LinearLayout (vertical, padding 16dp)
        ├── TextInputLayout
        │     └── TextInputEditText  android:id="@+id/username_input"
        ├── TextView                 android:id="@+id/username_hint"
        │     text="@string/alt_change_username_cooldown_hint"
        ├── TextView                 android:id="@+id/username_error"
        │     visibility="gone"
        └── ChipGroup                android:id="@+id/username_suggestions"
              visibility="gone"
              app:singleLine="false"
```

Чипы создаём кодом — `Chip` + `setOnClickListener`.

## Сервисная часть

### `AltPlatformService.changeUsername`

```java
public void changeUsername(
    Context context,
    String newUsername,
    ChangeUsernameCallback callback
);

public interface ChangeUsernameCallback {
    void onSuccess(String newUsername);
    void onSameUsername();
    void onInvalid(List<String> suggestions);
    void onReserved(List<String> suggestions);
    void onTaken(List<String> suggestions);
    void onCooldown(long retryAfterSec);
    void onUnauthorized();
    void onNetworkError(Throwable t);
}
```

Сетевой вызов на background-потоке (как остальные методы сервиса), коллбэки
обратно — на main thread.

Алгоритм:

1. Берём JWT из хранилища ANDROID-001. Нет → `onUnauthorized()`.
2. Если `newUsername.equals(AltPrefs.getUsername(context))` → `onSameUsername()`,
   без сети.
3. Дёргаем `AltApiService.putMeUsername(token, {"username": newUsername})`.
4. По коду ответа выбираем callback (см. таблицу контракта).
5. На `Success` — сначала `AltPrefs.setUsername(context, body.username)`,
   потом `onSuccess(body.username)`.
6. `IOException` или `5xx` → `onNetworkError(t)`.

### `AltApiService.putMeUsername`

```java
@PUT("/v1/users/me/username")
Call<ChangeUsernameResponse> putMeUsername(
    @Header("Authorization") String bearer,
    @Body ChangeUsernameRequest body
);
```

`ChangeUsernameRequest` — простая обёртка с одним полем `username`.

## Где в коде что трогаем

| Файл | Что делаем |
|---|---|
| `altplatform/AltApiService.java` | Добавляем метод `putMeUsername` и DTO `ChangeUsernameRequest` / `ChangeUsernameResponse`. |
| `altplatform/AltApiClient.java` | Если общий error-конвертер уже умеет парсить тело при не-2xx — ничего; если нет — добавить. |
| `altplatform/AltPlatformService.java` | Метод `changeUsername(...)` + интерфейс `ChangeUsernameCallback`. |
| `altplatform/storage/AltPrefs.java` | Добавить `setUsername(Context, String)` (если ещё нет). |
| `org/thoughtcrime/securesms/altplatform/AltChangeUsernameActivity.java` | Новый файл, экран. |
| `res/layout/alt_change_username_activity.xml` | Новый layout (см. эскиз выше). |
| `org/thoughtcrime/securesms/preferences/SettingsRootFragment.java` | Новый `Preference` «Change username», открывает экран. Видим только если `AltPrefs.isRegistered()`. После `RESULT_OK` обновляем summary из `AltPrefs`. |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | Строки из раздела «Переводы». |
| `AndroidManifest.xml` | Регистрация новой Activity. |

## Переводы

В `res/values/strings.xml` и зеркально в `res/values-ru/strings.xml`:

```
alt_change_username_title              "Change username"
alt_change_username_save               "Save"
alt_change_username_cooldown_hint      "You can change your username once every 30 days."
alt_change_username_error_empty        "Username can't be empty"
alt_change_username_error_length       "Use 3 to 30 characters"
alt_change_username_error_same         "This is already your username"
alt_change_username_error_invalid      "Invalid format. Try one of these:"
alt_change_username_error_reserved     "This username is reserved. Try one of these:"
alt_change_username_error_taken        "This username is already taken. Try one of these:"
alt_change_username_error_cooldown     "You can change your username again in %1$d days"
alt_change_username_error_network      "Network error"
alt_change_username_error_session      "Session expired"
alt_change_username_success            "Username changed"
```

## Edge cases

| Случай | Что делаем |
|---|---|
| Сервер вернул 0 suggestions | ChipGroup не показываем, остаётся только текст ошибки. |
| Сервер вернул больше 5 suggestions | Берём первые 5, остальное игнорируем. |
| Suggestions содержат символы вне `[A-Za-z0-9_]` | Не должно случаться, но прогоняем через тот же фильтр перед отрисовкой; невалидные пропускаем. |
| Пользователь тапнул чип, отредактировал поле, снова Save | Нормальный путь, никаких особых блокировок. |
| `Cooldown` пришёл при первой же попытке (например, ник уже меняли с другого клиента) | Показываем дни ожидания, `Save` выключен до закрытия экрана. |
| `Unauthorized` посреди сессии | Чистим JWT через хелпер из ANDROID-001, кидаем в онбординг. `AltPrefs.username` пока **не трогаем** — он отображается корректно до новой регистрации. |
| Поворот экрана с показанными чипами | Сохраняем `ArrayList<String>` чипов и текст ошибки в `onSaveInstanceState`, восстанавливаем в `onCreate`. |
| Двойной тап по `Save` | Action блокируется на время запроса. |
| Пользователь стирает поле в ноль и жмёт Save | Локальная проверка ловит до сети. |

## Ручной тест-план

1. Открыть «Change username» из Settings → виден текущий ник как hint.
2. Ввести валидный свободный ник → `Save` → toast, экран закрылся, в Settings
   summary обновился.
3. Очистить поле, `Save` → «Username can't be empty», сети нет.
4. Ввести `ab`, `Save` → «Use 3 to 30 characters», сети нет.
5. Ввести **тот же** ник, что текущий → «This is already your username», сети нет.
6. Ввести `xyz123` (или другой ник, который сервер посчитает невалидным) →
   inline-ошибка «Invalid format» + до 5 чипов.
7. Тапнуть чип → значение в поле, ошибка/чипы скрылись, поле в фокусе.
8. Ввести `admin` (reserved) → ошибка «reserved» + чипы.
9. Ввести занятый ник → ошибка «taken» + чипы.
10. Сразу после успешной смены попытаться сменить ещё раз → cooldown с
    указанием дней, `Save` выключен.
11. Выключить интернет → `Save` → toast «Network error», поле снова доступно.
12. Поворот устройства с показанными чипами → чипы и текст ошибки сохраняются.
13. Принудительно протухший JWT → toast «Session expired», переход в онбординг.

## Definition of Done

- [ ] `AltChangeUsernameActivity` реализован, открывается из Settings, layout
      и переводы заведены.
- [ ] Все 8 callback'ов `ChangeUsernameCallback` мапятся в UI как описано.
- [ ] При ошибках с `suggestions` под полем рисуются до 5 чипов; тап по чипу
      подставляет значение в поле.
- [ ] При `cooldown` показывается срок в днях, `Save` выключен до закрытия
      экрана.
- [ ] После успеха `AltPrefs` обновлён, Settings сразу показывает новый ник.
- [ ] Локальные проверки (пусто / длина / тот же ник) не доходят до сети.
- [ ] Тест-план прогнан вручную, проблем нет.
- [ ] Строки заведены в `values/` и `values-ru/`.
