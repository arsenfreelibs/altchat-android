# Alt Chat Android — Claude Rules

## Project Overview

This is **Alt Chat** (`me.alt.chat`), a fork of [deltachat/deltachat-android](https://github.com/deltachat/deltachat-android).

- **Android repo (this):** `arsenfreelibs/altchat-android` — branch `main`
- **Rust core submodule:** `arsenfreelibs/core.git` — branch `develop` (at `jni/deltachat-core-rust/`)
- **Upstream android:** `upstream` remote → `deltachat/deltachat-android`
- **Upstream rust:** `upstream` remote → `deltachat/deltachat-core-rust`
- **Support email:** `child.aplic@gmail.com`

---

## Full Upstream Sync Checklist

Сначала проверь что нового:
```bash
scripts/check-upstream.sh
```

Выполнять строго по порядку:

```
[ ] 1. Rust: fetch + merge upstream/main в arsenfreelibs/core develop
[ ] 2. Rust: проверить брендинг (grep), починить если нужно
[ ] 3. Rust: push origin develop
[ ] 4. Android: обновить submodule pointer → git add + commit
[ ] 5. Android: fetch + merge upstream/main в origin/main
[ ] 6. Android: пересобрать .so (scripts/ndk-make.sh) — ТОЛЬКО после мержа android:
       jni/dc_wrapper.c — файл upstream и должен соответствовать новому deltachat.h
[ ] 7. Android: проверить брендинг (grep) + ./gradlew spotlessApply compileFossDebugJavaWithJavac compileGplayDebugJavaWithJavac testFossDebugUnitTest
[ ] 8. Android: push origin main (и обновить сабмодуль в iOS-форке)
```

Детальные команды — в секциях ниже.

---

## CRITICAL: Branding Rules

**NEVER** let any of the following appear in user-facing strings, notifications, or UI:

| Forbidden | Replace with |
|-----------|-------------|
| `Delta Chat` | `Alt Chat` |
| `DeltaChat` | `Alt Chat` |
| `Delta-Chat` | `Alt Chat` |
| `delta.chat/download` | `alt-chat.me/app/` |
| `get.delta.chat` | `alt-chat.me/app/` |
| `delta.chat/help` | `alt-chat.me/help` |
| `delta.chat/donate` | `alt-chat.me/donate` |
| `providers.delta.chat` | `alt-chat.me/providers` |
| `securejoin.delta.chat` | `alt-chat.me/securejoin` |
| `support.delta.chat` | `child.aplic@gmail.com` |
| `delta@merlinux.eu` | `child.aplic@gmail.com` |
| `deltachat.org` | *(remove)* |

**Do NOT change:**
- `github.com/deltachat/` links (source code references)
- Rust crate names (`deltachat`, `deltachat-rpc-server`)
- **IMAP ID `("name", "Delta Chat")`** в rust `imap/client.rs` — идентификатор клиента для сервера.
- **Default ICE-серверы** `nine.testrun.org` / `turn.delta.chat` в rust `calls.rs` — рабочая инфраструктура звонков; менять только когда будет свой STUN/TURN.
- **`NOTIFIERS_PUBLIC_KEY` в rust `push.rs`** — это НАШ ключ для `notifications.alt-to.online`, отличается от upstream. Взять upstream-версию файла целиком = сломать push. Проверять после каждого мержа.
- Логтеги и тестовые фикстуры с "Delta Chat" (rust `*_tests.rs`, `e2ee.rs`, `dehtml.rs` …) — не пользовательские.

**Инвайт-ссылки:** форк использует `https://alt-chat.me/#…` вместо `https://i.delta.chat/#…` (rust `securejoin.rs`, `qr.rs`, Android `AndroidManifest.xml` host). Это согласованное решение, при мерже сохранять наш домен, но брать новые параметры upstream (например `{r_param}`).

**Известное расхождение:** в `strings.xml` (34 локали, ~90 строк) используется домен `alt.chat` / `get.alt.chat` (ранний наивный sed), а канонический домен — `alt-chat.me`. Решение о единой замене отложено.
- HTML anchor IDs like `#what-is-delta-chat`
- **IMAP folder name `"DeltaChat"`** (rust `imap.rs` `if folder == "DeltaChat"`, `sql/migrations.rs` default `$.imap.folder`) — server-side mvbox folder, NOT user-facing. Renaming orphans existing users' messages and breaks cross-client sync (iOS/desktop/upstream all move mail to/from this exact folder name).
- Logcat tags `Log.x("DeltaChat", …)` and wake-lock tag `DeltaChat:ProximityLock` — internal, never shown to users.

After every merge, run:
```bash
grep -rn "Delta Chat\|DeltaChat\|delta\.chat\|deltachat\.org\|delta@merlinux\|support\.delta\.chat\|get\.delta\.chat" \
  src/main/java src/main/res jni/deltachat-core-rust/src \
  --include="*.java" --include="*.xml" --include="*.rs" \
  --exclude-dir=".git" --exclude-dir="target"
```

---

## Workflow: Merging Upstream Rust (Step 1)

Rust submodule lives at `jni/deltachat-core-rust/`, branch `develop`.

```bash
# 1. Check what's new
git -C jni/deltachat-core-rust fetch upstream
git -C jni/deltachat-core-rust log HEAD..upstream/main --oneline

# 2. Merge
git -C jni/deltachat-core-rust merge upstream/main

# 3. Resolve conflicts — strategy:
#    - strings.xml / user-facing strings: git checkout --ours (keep our branding)
#    - logic files: hybrid — take upstream fixes + keep our custom code
#    - submodule pointer: git checkout --ours

# 4. Scan for branding leaks (see above)

# 5. Push rust fork
git -C jni/deltachat-core-rust push origin develop
```

### Known rust branding fixes (re-check after each merge):

| File | What to fix |
|------|-------------|
| `src/accounts.rs` | "Delta Chat is already running..." → "Alt Chat is already running..." |
| `src/sql.rs` | DB update error message → use `child.aplic@gmail.com` |
| `src/receive_imf.rs` | "using Delta Chat on multiple devices" → "Alt Chat" |
| `src/imex.rs` | "newer version of Delta Chat" + test assertions → "Alt Chat" |
| `src/webxdc.rs` | "requires a newer Delta Chat version" → "Alt Chat version" |
| `src/qr/dclogin_scheme.rs` | "DeltaChat does not understand this QR Code" → "Alt Chat" |
| `src/tools.rs` | "bug in the Delta Chat core" → "Alt Chat core" (появилось upstream 2026-08) |
| `src/mimeparser.rs` | "re-installed Delta Chat … re-setup Delta Chat" → "Alt Chat" |
| `src/imap.rs` | "Please report this bug to …" → `child.aplic@gmail.com` (без точки в конце, как у upstream) |
| `src/securejoin.rs` | хост инвайт-ссылок → `https://alt-chat.me/#`, параметры upstream сохранить |
| `src/stock_str.rs` | `get.delta.chat` → `get.alt-chat.me`, `delta.chat/donate` → `alt-chat.me/donate` |
| `src/webxdc/webxdc_tests.rs`, `src/receive_imf/receive_imf_tests.rs`, `src/imex.rs` (тесты) | assert'ы должны ждать "Alt Chat" |
| `src/contact/contact_tests.rs` | `test_get_contacts`: форк ищет по адресу как подстроке (`alice@` → 1), upstream ждёт 0 — оставлять ожидание форка |

---

## Workflow: Updating Android Submodule Pointer (Step 2)

After pushing rust, update the android repo to point to the new rust commit:

```bash
# Verify submodule is on latest develop
git -C jni/deltachat-core-rust log --oneline -1

# Stage the new submodule pointer
git add jni/deltachat-core-rust
git commit -m "chore: update rust submodule to latest develop"
git push origin main
```

---

## Workflow: Merging Upstream Android (Step 3)

```bash
# 1. Check what's new
git fetch upstream
git log HEAD..upstream/main --oneline

# 2. Merge
git merge upstream/main

# 3. Conflict resolution strategy:

#    ALWAYS use --ours (keep ours):
#    - src/main/assets/help/**/*.html
#    - jni/deltachat-core-rust  (submodule pointer)
#    - build.gradle versionCode/versionName (we use our own versioning)
#
#    strings.xml — НЕ file-level --ours (теряются новые ключи upstream → R.string.* не компилируется):
#    1) в конфликтных хунках взять upstream (theirs), 2) scripts/merge/rebrand-strings.py по ВСЕМ
#    values*/strings.xml (авто-смерженные переводы приносят "Delta Chat" обратно),
#    3) scripts/merge/restore-fork-strings.py — возвращает наши ключи (alt_*, tos_*, пасскод…),
#    выпавшие вместе с хунками, 4) проверить дубли name= (aapt падает на дублях).

#    HYBRID merge (take upstream improvements + keep our features):
#    - ConversationActivity.java  → keep cancelAudioNoteAutoFinish()
#    - ConversationListFragment.java → keep filteredIndices/queryFilter/filterBar logic
#    - InputPanel.java → keep recordingDotView fadeout
#    - AudioView.java → take upstream improvements, check for updateTimestampsAndSeekBar
#    - AudioPlaybackViewModel.java → take upstream media stop/clear; ОДИН cyclePlaybackSpeed():
#      upstream-версия, но через наш setPlaybackSpeed() (мини-плеер читает скорость из AudioPlaybackState)
#    - AudioView.java + audio_view.xml → наша WaveformView (SeekBar upstream НЕ брать), плюс
#      upstream speed-кнопка/recording-state/footer; applyRecordingState → waveformView.setTouchEnabled
#    - activity_call.xml → наша раскладка (glow, rings, center_info_block); upstream ссылается на
#      @id/top_bar и caller_icon_container, которых у нас нет → их хунки не брать
#    - CallActivity.java → оба: наши glow/dots/timer/updateButtonBackground + upstream setupAccessibility
#      (следить за закрывающей скобкой между методами)
#    - CallCoordinator.java / CallViewModel.java → upstream (сессии, telecom, реконнект); наш
#      hasAnsweredLocally-guard от multi-device echo убран в мерже 2026-09
#    - Prefs.isReliableService() → наша версия (default ON)
#    - AudioRecorder.java → upstream + наш guard "size == 0 → discard"
#    - InputPanel.java → наш videoNoteListener + upstream переименование onPause()→cancelRecording()
#    - updater/AppUpdate.java → SELF_UPDATE_SUPPORTED = false (self-updater upstream отключён:
#      у нас свой util/update/AppUpdateChecker)
#    - build.gradle → androidComponents-блок с заменой на "altchat", camerax/navigation наши,
#      desugaring/zxing от upstream
#    - jni/dc_wrapper.c + DcContact.java + ProfileAdapter/ProfileFragment/DcHelper → core после 2.60
#      убрал dc_contact_is_verified()/dc_contact_get_verifier_id() (chatmail/core b1da53a56). Мы уже
#      выкинули JNI-методы и «Introduced by»; когда upstream android сделает то же — брать upstream.

# 4. After merge: scan for branding leaks (see above)

# 5. Push
git push origin main
```

### Key custom features to preserve during android merges:

- **`cancelAudioNoteAutoFinish()`** in `ConversationActivity.java` `onPause()`
- **Filter bar** (`filteredIndices`, `queryFilter`, `filterBarFilters`, `filterBar`) in `ConversationListFragment.java`
- **`recordingDotView`** fadeout in `InputPanel.java`
- **Auto-proxy** logic (obfuscated credentials) in `build.gradle`
- **`ALT_API_BASE_URL`** build config field in `build.gradle`
- **Version scheme** `versionCode 7xx` / `versionName "1.0.xx"` — never take upstream's versioning

---

## Checking for New Upstream Commits

```bash
# Android
git fetch upstream
git log HEAD..upstream/main --oneline

# Rust
git -C jni/deltachat-core-rust fetch upstream
git -C jni/deltachat-core-rust log HEAD..upstream/main --oneline
```

---

## Version Bumping

- `build.gradle`: `versionCode` and `versionName "1.0.xx"` — bump manually before release
- Never adopt upstream's `versionCode`/`versionName`

---

## Building Native Libs (after rust submodule update)

Rust changes don't auto-compile into `.so` — must rebuild manually:

```bash
# Full build (all 4 ABIs — slow, ~20 min):
scripts/ndk-make.sh

# Fast build for one arch (for local testing on arm64 device):
scripts/ndk-make.sh arm64-v8a

# Debug build:
scripts/ndk-make.sh --debug arm64-v8a
```

Requires: Rust `1.91.1` (see `scripts/rust-toolchain`) + NDK `27.0.12077973`.

Output: `.so` files go into `libs/<abi>/`.

---

## Code Style (Spotless)

Before committing Java files, run spotless to avoid CI failures:

```bash
./gradlew spotlessApply
```

XML files in `src/*/res/values*/strings.xml` are excluded from spotless (line-break changes would invalidate translations).

---

## Quick Build Commands

```bash
# Debug APK (foss flavor):
./gradlew assembleFossDebug

# Release APK:
./gradlew assembleFossRelease

# Run unit tests:
./gradlew test
```

---

## iOS Fork Sync

iOS project at `/Users/romanvalchuk/Projects/alt-chat-ios` also uses the rust submodule.
After pushing rust `develop`, check if iOS needs updating too:
```bash
git -C /Users/romanvalchuk/Projects/alt-chat-ios/deltachat-ios/libraries/deltachat-core-rust log --oneline -1
```
