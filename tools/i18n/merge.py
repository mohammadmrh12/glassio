#!/usr/bin/env python3
"""Puts translated strings into the app: build/i18n/<lang>.<n>.json (English -> translation) -> app/src/main/assets/i18n/<lang>.tsv.

Only strings that extract.py finds on the screens are kept; a translation equal to its English is left out (it
shows the same anyway). Run from the repository's root: tools/i18n/extract.py, translate, tools/i18n/merge.py.
"""
import glob, json, os, sys
LANGS = ["ar", "fa", "tr", "fr", "es", "de", "ru", "pt"]
keys = json.load(open("build/i18n/en.json"))
os.makedirs("app/src/main/assets/i18n", exist_ok=True)
esc = lambda s: s.replace("\r", "").replace("\n", "\\n")
bad = 0
for lang in LANGS:
    got = {}
    for f in sorted(glob.glob(f"build/i18n/{lang}.*.json")):
        got.update(json.load(open(f)))
    missing = [k for k in keys if not str(got.get(k, "")).strip()]
    rows = []
    for k in keys:
        v = str(got.get(k, "")).strip("\r\n")
        if not v.strip() or v == k or "\t" in k or "\t" in v:
            continue
        # The spaces around the words are the code's: kept as the English has them.
        lead = k[: len(k) - len(k.lstrip(" "))]; tail = k[len(k.rstrip(" ")):]
        rows.append(esc(k) + "\t" + esc(lead + v.strip(" ") + tail))
    open(f"app/src/main/assets/i18n/{lang}.tsv", "w", encoding="utf-8").write("\n".join(rows) + "\n")
    print(f"{lang}: {len(rows)} of {len(keys)} written, {len(missing)} missing")
    bad += len(missing)
sys.exit(1 if bad else 0)
