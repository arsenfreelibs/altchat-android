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
[ ] 5. Android: пересобрать .so (scripts/ndk-make.sh)
[ ] 6. Android: fetch + merge upstream/main в origin/main
[ ] 7. Android: проверить брендинг (grep), починить если нужно
[ ] 8. Android: push origin main
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
- `i.delta.chat` protocol invitation links (used in QR/invite flow)
- HTML anchor IDs like `#what-is-delta-chat`

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
#    - src/main/res/values*/strings.xml  (all language variants)
#    - src/main/assets/help/**/*.html
#    - jni/deltachat-core-rust  (submodule pointer)
#    - build.gradle versionCode/versionName (we use our own versioning)

#    HYBRID merge (take upstream improvements + keep our features):
#    - ConversationActivity.java  → keep cancelAudioNoteAutoFinish()
#    - ConversationListFragment.java → keep filteredIndices/queryFilter/filterBar logic
#    - InputPanel.java → keep recordingDotView fadeout
#    - AudioView.java → take upstream improvements, check for updateTimestampsAndSeekBar
#    - AudioPlaybackViewModel.java → take upstream media stop/clear

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
