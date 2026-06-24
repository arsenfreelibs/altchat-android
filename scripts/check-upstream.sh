#!/bin/bash
# Check for new upstream commits in rust + Android and scan for branding issues.

set -e

BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

ANDROID_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUST_DIR="$ANDROID_ROOT/jni/deltachat-core-rust"

echo -e "${BOLD}=== Fetching upstreams ===${NC}"
git -C "$RUST_DIR" fetch upstream --quiet
git -C "$ANDROID_ROOT" fetch upstream --quiet
echo "Done."

# ── Rust ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${CYAN}=== Rust: new upstream commits ===${NC}"
RUST_COMMITS=$(git -C "$RUST_DIR" log origin/develop..upstream/main --oneline)

if [ -z "$RUST_COMMITS" ]; then
  echo -e "${GREEN}✓ No new rust commits${NC}"
else
  echo "$RUST_COMMITS"
  RUST_COUNT=$(echo "$RUST_COMMITS" | wc -l | tr -d ' ')
  echo -e "${YELLOW}→ $RUST_COUNT new commit(s)${NC}"
fi

# ── Android ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${CYAN}=== Android: new upstream commits ===${NC}"
ANDROID_COMMITS=$(git -C "$ANDROID_ROOT" log origin/main..upstream/main --oneline)

if [ -z "$ANDROID_COMMITS" ]; then
  echo -e "${GREEN}✓ No new Android commits${NC}"
else
  echo "$ANDROID_COMMITS"
  ANDROID_COUNT=$(echo "$ANDROID_COMMITS" | wc -l | tr -d ' ')
  echo -e "${YELLOW}→ $ANDROID_COUNT new commit(s)${NC}"
fi

# ── Branding scan ─────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${CYAN}=== Branding scan ===${NC}"
BRANDING_HITS=$(grep -rn \
  "Delta Chat\|DeltaChat\|Delta-Chat\|delta\.chat\|deltachat\.org\|delta@merlinux\|support\.delta\.chat\|get\.delta\.chat" \
  "$ANDROID_ROOT/src/" \
  "$RUST_DIR/src/" \
  --include="*.java" --include="*.xml" --include="*.rs" --include="*.html" \
  --exclude-dir=".git" --exclude-dir="target" \
  2>/dev/null \
  | grep -v "i\.delta\.chat" \
  | grep -v "github\.com/deltachat" \
  | grep -v "[[:space:]]*//" \
  | grep -v "_tests\.rs:" \
  || true)

if [ -z "$BRANDING_HITS" ]; then
  echo -e "${GREEN}✓ No branding issues${NC}"
else
  echo -e "${RED}⚠ Branding issues found:${NC}"
  echo "$BRANDING_HITS"
fi

# ── AI summary ────────────────────────────────────────────────────────────────
if [ -z "$RUST_COMMITS" ] && [ -z "$ANDROID_COMMITS" ]; then
  echo ""
  echo -e "${GREEN}Nothing to summarize — all up to date.${NC}"
  exit 0
fi

echo ""
echo -e "${BOLD}${CYAN}=== Summary ===${NC}"

CLAUDE_BIN="${CLAUDE_CODE_EXECPATH:-$(command -v claude 2>/dev/null)}"
if [ -n "$CLAUDE_BIN" ] && [ -x "$CLAUDE_BIN" ]; then
  PROMPT="You are helping a developer who maintains 'alt.chat', an Android fork of Delta Chat. Summarize the following new upstream commits in 2-3 sentences. Focus on user-facing features and important bug fixes. Be concise."

  [ -n "$RUST_COMMITS" ] && PROMPT="$PROMPT

Rust core new commits:
$RUST_COMMITS"

  [ -n "$ANDROID_COMMITS" ] && PROMPT="$PROMPT

Android app new commits:
$ANDROID_COMMITS"

  "$CLAUDE_BIN" -p "$PROMPT"
else
  echo -e "${YELLOW}(install claude CLI to get AI summary)${NC}"
  [ -n "$RUST_COMMITS" ] && echo -e "\nRust: $RUST_COUNT new commit(s)" && echo "$RUST_COMMITS"
  [ -n "$ANDROID_COMMITS" ] && echo -e "\nAndroid: $ANDROID_COUNT new commit(s)" && echo "$ANDROID_COMMITS"
fi
