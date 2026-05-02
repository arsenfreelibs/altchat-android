---
Документ описывает бэкап профиля в файл: что это за файл, что внутри,
куда он сохраняется на Android, как зашифрован и как из него восстановить
профиль на новом устройстве. Источник истины — `dc_imex` в Rust-ядре.
---

# Бэкап профиля в файл и восстановление

Речь идёт о механизме «**Settings → Chats → Backup**», который создаёт **один
единственный файл**, переживающий удаление приложения. Его пользователь сохраняет
себе (Google Drive, флешка, другой телефон) и подсовывает на новом устройстве на
экране первого запуска, чтобы получить профиль обратно — со всеми чатами,
сообщениями, контактами, ключами и вложениями.

Это **не** копия внутреннего каталога `accounts/<uuid>/` — это специально
сформированный TAR-архив, который ядро умеет создавать (`DC_IMEX_EXPORT_BACKUP`)
и распаковывать (`DC_IMEX_IMPORT_BACKUP`).

---

## 1. Что это за файл

| Параметр | Значение |
|---|---|
| Тип | обычный POSIX **TAR-архив** (без gzip/lz4, без шифрования слоя архива) |
| Имя | `alt-chat-backup-YYYY-MM-DD-NN-<email>.tar` |
| MIME | `application/x-tar` |
| Куда кладётся | публичный **`Downloads/`** на устройстве |
| Создаётся через | `tokio_tar::Builder` ([imex.rs:569](../jni/deltachat-core-rust/src/imex.rs#L569)) |
| Один файл = | один профиль (один аккаунт) |
| Версия формата | `DCBACKUP_VERSION = 4` ([qr.rs:44](../jni/deltachat-core-rust/src/qr.rs#L44)) |

**Шаблон имени** ([imex.rs:421-436](../jni/deltachat-core-rust/src/imex.rs#L421-L436)):

```
alt-chat-backup-2026-05-02-00-arsen@example.com.tar
                └─дата UTC─┘ ↑  └─── primary_self_addr ───┘
                         порядковый номер 00..63
```

- `YYYY-MM-DD` — дата UTC создания.
- `NN` (`00`–`63`) — порядковый номер, чтобы можно было сделать до 64 бэкапов в день;
  ядро берёт первый свободный номер.
- `<email>` — основной email-адрес профиля (`primary_self_addr`).

Имя жёстко задано форматом, потому что `imex_has_backup` ищет последний бэкап
в каталоге **строковым сравнением имён** ([imex.rs:114-138](../jni/deltachat-core-rust/src/imex.rs#L114-L138)) —
лексикографически больший = более свежий. Менять руками не стоит.

---

## 2. Куда файл попадает на Android

Каталог назначения определяется в [DcHelper.getImexDir():240-246](../src/main/java/org/thoughtcrime/securesms/connect/DcHelper.java#L240-L246):

```java
public static File getImexDir() {
  return Environment.getExternalStoragePublicDirectory(
      Environment.DIRECTORY_DOWNLOADS);
}
```

То есть на устройстве — стандартный публичный `Downloads/`, абсолютный путь обычно:

```
/storage/emulated/0/Download/alt-chat-backup-2026-05-02-00-arsen@example.com.tar
```

Свойства этого расположения (важны для восстановления):

- **Переживает удаление приложения.** Это публичное хранилище, ОС не чистит его при `Uninstall`/`Clear Data`.
- **Виден файловому менеджеру.** Можно отправить в Google Drive / Telegram / на ПК через USB.
- **Требует разрешений** на старых Android — `WRITE_EXTERNAL_STORAGE`, см. запрос
  в [ChatsPreferenceFragment.java:300-307](../src/main/java/org/thoughtcrime/securesms/preferences/ChatsPreferenceFragment.java#L300-L307).
  На Android 11+ (`SDK_INT >= R`) импорт идёт через системный picker
  (`AttachmentManager.selectMediaType(..., "application/x-tar", ...)`,
  [WelcomeActivity.java:262-264](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L262-L264)) — пользователь сам
  тыкает в файл, разрешение на каталог не нужно.

> Сознательно **не** используется DownloadManager — иначе ОС удалит файл при удалении
> приложения (комментарий в [DcHelper.java:244](../src/main/java/org/thoughtcrime/securesms/connect/DcHelper.java#L244)).

---

## 3. Что внутри TAR-архива

Структура архива (см. [export_backup_stream](../jni/deltachat-core-rust/src/imex.rs#L558-L583)):

```
alt-chat-backup-2026-05-02-00-arsen@example.com.tar
├── dc_database_backup.sqlite          ← дамп всей БД профиля
└── blobs_backup/
    ├── <hash1>.jpg                    ← все вложения 1-в-1 из dc.db-blobs/
    ├── <hash2>.ogg
    ├── <hash3>.pdf
    └── ...
```

Имена в архиве — константы ядра:

```rust
const DBFILE_BACKUP_NAME: &str  = "dc_database_backup.sqlite";   // imex.rs:37
const BLOBS_BACKUP_NAME: &str   = "blobs_backup";                // imex.rs:38
```

### 3.1 `dc_database_backup.sqlite`

Это **отдельная копия** базы профиля, созданная не как `cp dc.db`, а через
SQLCipher-команду `sqlcipher_export` ([imex.rs:768-789](../jni/deltachat-core-rust/src/imex.rs#L768-L789)):

```rust
conn.execute("ATTACH DATABASE ? AS backup KEY ?", (dest, passphrase))?;
conn.query_row("SELECT sqlcipher_export('backup')", [], ...)?;
conn.execute("DETACH DATABASE backup", [])?;
```

То есть рабочая `dc.db` атачится к новой пустой базе, и `sqlcipher_export`
постранично переносит туда все таблицы. Перед этим:

1. Принудительно ставится `BccSelf=1` ([imex.rs:758](../jni/deltachat-core-rust/src/imex.rs#L758)).
2. В `config` пишутся `backup_time` (unix-timestamp) и `backup_version=4`
   ([imex.rs:759-766](../jni/deltachat-core-rust/src/imex.rs#L759-L766)).
3. Запускается `housekeeping` (чистка осиротевших блобов) и `VACUUM`
   ([imex.rs:767-773](../jni/deltachat-core-rust/src/imex.rs#L767-L773)) — чтобы файл был компактным.

Внутри этой SQLite-базы — **полностью те же таблицы**, что и в обычной `dc.db`
профиля (см. [tables.sql](../jni/deltachat-core-rust/src/sql/tables.sql)):

- `config` — настройки профиля, **включая логин/пароль SMTP/IMAP**;
- `keypairs` — **приватный OpenPGP-ключ пользователя**;
- `acpeerstates` — публичные ключи и Autocrypt-состояние всех контактов;
- `contacts`, `chats`, `chats_contacts`, `msgs` — контакты, чаты, сообщения;
- `jobs`, `tokens`, `multi_device_sync`, `imap`, `smtp_*`, `locations`, `leftgrps` — служебные.

То есть бэкап — это **слепок всего профиля**, ровно того, что нужно для полного восстановления.

### 3.2 `blobs_backup/`

Каталог со всеми файлами из `dc.db-blobs/` рабочего профиля — фото/видео/аудио,
аватары, webxdc-бандлы. Имена сохраняются 1-в-1 ([imex.rs:575-579](../jni/deltachat-core-rust/src/imex.rs#L575-L579)):

```rust
for blob in blobdir.iter() {
    let mut file = File::open(blob.to_abs_path()).await?;
    let path_in_archive = PathBuf::from(BLOBS_BACKUP_NAME).join(blob.as_name());
    builder.append_file(path_in_archive, &mut file).await?;
}
```

Поэтому ссылки из `msgs.param` после восстановления продолжают работать.

---

## 4. Шифрование бэкапа — важно!

API `dc_imex` принимает четвёртым аргументом **passphrase** для бэкапа
([imex.rs:82-87](../jni/deltachat-core-rust/src/imex.rs#L82-L87)). Если он непустой —
`dc_database_backup.sqlite` зашифровывается SQLCipher этим паролем (отдельным от
ключа из Android Keystore!). Если пустой — **база не шифруется вовсе**.

В текущей сборке Android JNI-обёртка **всегда передаёт пустую строку**
([dc_wrapper.c:949-953](../jni/dc_wrapper.c#L949-L953)):

```c
JNIEXPORT void Java_com_b44t_messenger_DcContext_imex(
    JNIEnv *env, jobject obj, jint what, jstring dir)
{
    CHAR_REF(dir);
        dc_imex(get_dc_context(env, obj), what, dirPtr, "");   // ← passphrase = ""
    CHAR_UNREF(dir);
}
```

Это значит:

> ⚠ **Бэкап `.tar`, созданный в Android-приложении, не зашифрован.**
> Любой, у кого окажется этот файл, получит полный доступ к учёткам SMTP/IMAP,
> приватным OpenPGP-ключам, всем сообщениям и вложениям профиля.

Сам TAR-архив тоже не шифрован — обёртки нет, шифроваться может только
вложенная SQLite-база, и в Android-сборке этого не делается.

Если нужна защита бэкапа — единственный вариант сейчас:
1. либо добавить запрос пароля в Java/JNI и проросить его в `dc_imex`
   (тогда восстанавливать тоже надо будет с этим паролем);
2. либо хранить `.tar` уже за внешним шифрованием (например, в зашифрованной
   архивной папке/облаке).

---

## 5. Как создаётся бэкап (под капотом)

Пошагово ([export_backup, imex.rs:449-483](../jni/deltachat-core-rust/src/imex.rs#L449-L483)):

1. **Получить email** профиля — `context.get_primary_self_addr()`.
2. **Подобрать имя файла** через `get_next_backup_path` ([imex.rs:415-443](../jni/deltachat-core-rust/src/imex.rs#L415-L443)).
   Возвращает три пути в `Downloads/`:
   - `…<addr>.db` — временный файл для дампа SQLite,
   - `…<addr>.tar.part` — временный файл для tar в процессе записи,
   - `…<addr>.tar` — итоговое имя.
3. **`export_database`** ([imex.rs:741-790](../jni/deltachat-core-rust/src/imex.rs#L741-L790)):
   останавливает IMAP/SMTP-планировщик, ставит `backup_time`/`backup_version`,
   делает `VACUUM`, потом через `ATTACH DATABASE ... KEY ''` + `sqlcipher_export`
   копирует БД в `…<addr>.db`. На этом этапе `dc.db` приложения **читается под
   своим Keystore-ключом**, а `…<addr>.db` пишется уже **расшифрованной** (passphrase=`""`).
4. **`export_backup_stream`** ([imex.rs:558-583](../jni/deltachat-core-rust/src/imex.rs#L558-L583)):
   открывает `…<addr>.tar.part` на запись, оборачивает в `tokio_tar::Builder`,
   кладёт первым файлом `dc_database_backup.sqlite` (это и есть `…<addr>.db`),
   затем — все блобы из `BlobDirContents` под путями `blobs_backup/<имя>`.
   По мере записи летят прогресс-события `DC_EVENT_IMEX_PROGRESS` (1…1000)
   через `ProgressWriter`.
5. **Атомарный финал**: `fs::rename(…<addr>.tar.part, …<addr>.tar)`
   ([imex.rs:480](../jni/deltachat-core-rust/src/imex.rs#L480)). До этого момента
   итогового имени не существует — поэтому `imex_has_backup` никогда не подхватит
   полу-записанный файл при сбое.
6. **Эмитится `DC_EVENT_IMEX_FILE_WRITTEN`** с финальным путём
   ([imex.rs:481](../jni/deltachat-core-rust/src/imex.rs#L481)) — UI показывает «бэкап готов».

Временные `.db` и `.tar.part` гарантированно удаляются `TempPathGuard`
([imex.rs:454-455](../jni/deltachat-core-rust/src/imex.rs#L454-L455)) даже при
ошибке/отмене.

### Кто это запускает на Android

[`ChatsPreferenceFragment.java:317`](../src/main/java/org/thoughtcrime/securesms/preferences/ChatsPreferenceFragment.java#L317):

```java
startImexOne(DcContext.DC_IMEX_EXPORT_BACKUP);
```

→ [`ListSummaryPreferenceFragment.java:113-123`](../src/main/java/org/thoughtcrime/securesms/preferences/ListSummaryPreferenceFragment.java#L113-L123):

```java
String path = DcHelper.getImexDir().getAbsolutePath();   // = .../Downloads
dcContext.imex(DC_IMEX_EXPORT_BACKUP, path);
```

→ JNI → `dc_imex(ctx, 11, path, "")` → `Accounts/Context::imex(ExportBackup, path, "")`.

Если профилей несколько — кнопка «Backup all» вызывает `startImexAll`,
которая просто проходит по всем `accountId` и для каждого делает свой `.tar`.

---

## 6. Как восстановить профиль из этого файла

### 6.1 Когда импорт возможен

Импорт работает **только в свежий, несконфигурированный профиль** —
проверка в [imex.rs:64](../jni/deltachat-core-rust/src/imex.rs#L64):

> Importing a backup is only possible as long as the context is not configured
> or used in another way.

Поэтому в Android UI экран импорта показывается на стартовом
[`WelcomeActivity`](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java) —
до того, как пользователь ввёл email/пароль или нажал «Создать аккаунт».

### 6.2 Сценарий пользователя

1. Удалил приложение / сменил телефон.
2. Установил Alt Chat.
3. Положил `alt-chat-backup-…tar` куда-то, откуда система может его открыть
   (обычно — в `Downloads/`).
4. На первом экране тапнул «Restore from backup».
5. Дальше зависит от версии Android:
   - **Android 11+ (`SDK_INT >= R`)**: открывается системный picker
     с фильтром `application/x-tar` ([WelcomeActivity.java:262-264](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L262-L264)).
     Пользователь сам выбирает файл — каталог-разрешение не нужно. Файл копируется
     во внутренний `cacheDir` через `copyToCacheDir`
     ([WelcomeActivity.java:336-344](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L336-L344)),
     потому что ядру нужен реальный путь, а не `content://` URI.
   - **Старые Android**: ядро автоматом ищет последний `alt-chat-*.tar`
     в `Downloads/` через `imexHasBackup`
     ([WelcomeActivity.java:266](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L266)),
     спрашивает подтверждение, и импортирует именно его.
6. Вызывается `dcContext.imex(DC_IMEX_IMPORT_BACKUP, file)`
   ([WelcomeActivity.java:332](../src/main/java/org/thoughtcrime/securesms/WelcomeActivity.java#L332)).
7. Дальше всё происходит в ядре (см. ниже).
8. В конце профиль уже сконфигурирован, IMAP/SMTP стартует, чаты на месте.

### 6.3 Что делает ядро при импорте

[`import_backup_stream_inner`, imex.rs:315-406](../jni/deltachat-core-rust/src/imex.rs#L315-L406):

1. Открывает `.tar` через `tokio_tar::Archive`.
2. Проходит по всем entry. Каждую распаковывает в `context.get_blobdir()`
   (то есть в `…/accounts/<uuid>/dc.db-blobs/`) — да, изначально всё валится в
   blobdir, включая `dc_database_backup.sqlite`. Это нормальный workaround.
3. Файлы из `blobs_backup/<имя>` тут же **переименовываются** в
   `dc.db-blobs/<имя>` ([imex.rs:347-358](../jni/deltachat-core-rust/src/imex.rs#L347-L358)) —
   плоско, без подкаталога.
4. Когда tar закончился, в blobdir лежит `dc_database_backup.sqlite`.
   Вызывается `context.sql.import(unpacked_database, passphrase)`
   ([imex.rs:362-368](../jni/deltachat-core-rust/src/imex.rs#L362-L368)) — это
   обратная операция к экспорту: текущая (пустая) `dc.db` атачится к
   `dc_database_backup.sqlite`, и `sqlcipher_export` льёт всё обратно.
   На выходе — рабочая `dc.db`, зашифрованная **уже новым Keystore-ключом**
   нового устройства.
5. Распакованный `dc_database_backup.sqlite` удаляется ([imex.rs:373-377](../jni/deltachat-core-rust/src/imex.rs#L373-L377)).
6. Проверяется `backup_version` ([check_backup_version, imex.rs:792-799](../jni/deltachat-core-rust/src/imex.rs#L792-L799)):
   если бэкап новее, чем умеет ядро (`> DCBACKUP_VERSION`) — ошибка
   *«This profile is from a newer version of Delta Chat»*. То есть откатить
   бэкап со свежего DC на старое приложение **нельзя**. Наоборот — можно.
7. Запускаются миграции схемы (`run_migrations`).
8. Чистятся device-сообщения от свежеустановленного приложения, чтобы UI был
   как «после восстановления», а не как «новая установка»
   ([delete_and_reset_all_device_msgs, imex.rs:400-404](../jni/deltachat-core-rust/src/imex.rs#L400-L404)).

При любой ошибке всё откатывается ([imex.rs:383-398](../jni/deltachat-core-rust/src/imex.rs#L383-L398)):
текущая `dc.db` удаляется, распакованные блобы тоже, и пересоздаётся пустая
БД — пользователь возвращается в состояние «несконфигурированный профиль» и
может попробовать ещё раз.

---

## 7. Что **не** попадает в бэкап

Несколько вещей умышленно остаются на старом устройстве:

- **Ключ от рабочей `dc.db`** из Android Keystore (он привязан к устройству).
  При импорте новое устройство создаёт **свой** ключ.
- **Настройки приложения**, не связанные с профилем: тема, шрифты, язык,
  раскладка, рингтоны/LED — всё это в `SharedPreferences`, не в `dc.db`,
  и в бэкап не входит ([imex.rs:57-58](../jni/deltachat-core-rust/src/imex.rs#L57-L58):
  *«does not contain device dependent settings as ringtones or LED notification settings»*).
- **FCM push-токен** — пере-регистрируется заново на новом устройстве.
- **Логи приложения, кеши, временные файлы.**

То есть восстанавливается именно **профиль чата** (учётка, ключи, история,
контакты, вложения), а не «образ всего приложения».

---

## 8. Сводка

| Вопрос | Ответ |
|---|---|
| Один файл на профиль? | Да, один `.tar` — один профиль |
| Имя файла | `alt-chat-backup-YYYY-MM-DD-NN-<email>.tar` |
| Где сохраняется | `/storage/emulated/0/Download/` (публичный `Downloads/`) |
| Переживает удаление приложения? | Да |
| Видит ли его файловый менеджер? | Да |
| Формат | TAR (без сжатия) |
| Что внутри | `dc_database_backup.sqlite` + `blobs_backup/<все вложения>` |
| Зашифрован? | **В текущей Android-сборке — нет** (passphrase=`""` зашит в JNI) |
| Содержит SMTP/IMAP пароли? | Да (в таблице `config` SQLite-базы) |
| Содержит приватные OpenPGP-ключи? | Да (таблица `keypairs`) |
| Содержит всю историю сообщений? | Да (таблица `msgs` + блобы) |
| Версия формата | `DCBACKUP_VERSION = 4` |
| Куда импортируется | в свежесозданный пустой профиль через `WelcomeActivity` |
| Можно импортировать в уже настроенный профиль? | Нет, только в несконфигурированный |
| Можно ли откатить бэкап на старую версию приложения? | Только если `backup_version <= DCBACKUP_VERSION` старой сборки |
