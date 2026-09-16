#!/usr/bin/env python3
"""Restore fork-specific string resources lost while resolving strings.xml merge conflicts.

Usage (during a merge, before committing): scripts/merge/restore-fork-strings.py [--base <rev>]

For every src/main/res/values*/strings.xml it re-adds, before </resources>:
  * keys present in HEAD (our pre-merge tree) but not in the merge-base -> our own strings
    (passcode, alt_* onboarding/restore, tos_*, ...), and
  * for values/strings.xml only: keys that upstream removed but our code still references.
Keys already present in the working file are never duplicated.
"""
import re, subprocess, glob, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
def git(*a):
    r = subprocess.run(["git", "-C", ROOT, *a], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else ""
base = git("merge-base", "HEAD", "upstream/main").strip()
if "--base" in sys.argv: base = sys.argv[sys.argv.index("--base") + 1]
EL = re.compile(r'<(string|plurals|string-array)\s+name="([^"]+)"[^>]*?(?:/>|>.*?</\1>)', re.S)
parse = lambda t: {m.group(2): m.group(0) for m in EL.finditer(t)}
refs = set()
for root, _, fs in os.walk(os.path.join(ROOT, "src")):
    for f in fs:
        if f.endswith((".java", ".kt", ".xml")):
            t = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
            for kind in ("string", "plurals", "array"):
                refs.update(re.findall(r"R\." + kind + r"\.([A-Za-z0-9_]+)", t))
            refs.update(re.findall(r"@string/([A-Za-z0-9_]+)", t))
total = 0
for path in sorted(glob.glob(os.path.join(ROOT, "src/main/res/values*/strings.xml"))):
    rel = os.path.relpath(path, ROOT); is_default = rel == "src/main/res/values/strings.xml"
    head = parse(git("show", f"HEAD:{rel}")); mb = parse(git("show", f"{base}:{rel}"))
    cur_txt = open(path, encoding="utf-8").read(); cur = parse(cur_txt)
    ours = {k for k in head if k not in mb}
    restore = [k for k in head if k not in cur and (k in ours or (is_default and k in refs))]
    if not restore: continue
    block = ("\n    <!-- Alt Chat: fork-specific strings (restored after upstream merge) -->\n"
             + "\n".join("    " + head[k].strip() for k in restore) + "\n")
    i = cur_txt.rfind("</resources>")
    open(path, "w", encoding="utf-8").write(cur_txt[:i] + block + cur_txt[i:])
    total += len(restore); print(f"{rel}: restored {len(restore)}")
print(f"total restored: {total}")
