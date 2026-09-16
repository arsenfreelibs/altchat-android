#!/usr/bin/env python3
"""Rebrand Delta Chat -> Alt Chat in Android string resources (and any other text files given).

Usage: scripts/merge/rebrand-strings.py [files...]   (default: src/main/res/values*/strings.xml)

Run after every upstream merge: upstream translation updates re-introduce "Delta Chat"
and delta.chat URLs in hunks that merged without conflict. Leaves `i.delta.chat` and
translator comments' meaning intact; only brand words and URLs are rewritten.
"""
import re, sys, glob, os
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAP = [
 (r"https?://delta\.chat/download/?", "https://alt-chat.me/app/"),
 (r"https?://get\.delta\.chat/?", "https://alt-chat.me/app/"),
 (r"https?://providers\.delta\.chat/?", "https://alt-chat.me/providers"),
 (r"https?://securejoin\.delta\.chat/?", "https://alt-chat.me/securejoin"),
 (r"https?://support\.delta\.chat/?", "child.aplic@gmail.com"),
 (r"delta@merlinux\.eu", "child.aplic@gmail.com"),
 (r"https?://delta\.chat(/[\w/#.-]*)?", lambda m: "https://alt-chat.me" + (m.group(1) or "")),
 (r"(?<![.\w-])delta\.chat(/[\w/#.-]*)?", lambda m: "alt-chat.me" + (m.group(1) or "")),
 (r"Delta[ -]?Chat", "Alt Chat"),
]
files = sys.argv[1:] or sorted(glob.glob(os.path.join(ROOT, "src/main/res/values*/strings.xml")))
changed = 0
for path in files:
    s = open(path, encoding="utf-8").read(); o = s
    for pat, rep in MAP:
        s = re.sub(pat, rep, s)
    if s != o:
        open(path, "w", encoding="utf-8").write(s); changed += 1
print(f"rebranded {changed}/{len(files)} files")
