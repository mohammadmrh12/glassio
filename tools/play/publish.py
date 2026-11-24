#!/usr/bin/env python3
"""
Uploads the Play build's bundle to a Google Play test track and sends it for review, through the Google Play
Developer API (a service account that may only release to test tracks; its key's path is PLAY_API_KEY in
local.properties, never in git).

  tools/play/publish.py status                      # the tracks and their releases
  tools/play/publish.py upload [--track alpha] [--notes "text"] [--aab path]

The bundle defaults to app/build/outputs/bundle/playRelease/app-play-release.aab (build it first with
./gradlew :app:bundlePlayRelease). Release notes default to the newest section of CHANGELOG.md, cut to 500 characters.
"""
import argparse, json, os, re, sys, warnings

warnings.filterwarnings("ignore")
import google.auth.transport.requests
from google.oauth2 import service_account
import requests

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = "com.glasslauncher.tv.play"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PKG
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PKG


def key_path():
    for line in open(os.path.join(ROOT, "local.properties"), encoding="utf-8"):
        if line.startswith("PLAY_API_KEY="):
            return line.split("=", 1)[1].strip()
    sys.exit("PLAY_API_KEY is not in local.properties")


def session():
    cred = service_account.Credentials.from_service_account_file(
        key_path(), scopes=["https://www.googleapis.com/auth/androidpublisher"])
    cred.refresh(google.auth.transport.requests.Request())
    s = requests.Session()
    s.headers["Authorization"] = "Bearer " + cred.token
    return s


def ok(r):
    if r.status_code >= 300:
        sys.exit(f"{r.request.method} {r.url.split('?')[0]} -> {r.status_code}: {r.text[:600]}")
    return r.json() if r.text else {}


def version_of(aab_path):
    text = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    code = re.search(r"versionCode = (\d+)", text).group(1)
    name = re.search(r'versionName = "([^"]+)"', text).group(1)
    return code, name


def notes_from_changelog(name):
    text = open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8").read()
    m = re.search(r"^## " + re.escape(name) + r".*?\n(.*?)(?=^## )", text, re.S | re.M)
    body = (m.group(1) if m else "").strip()
    lines = [re.sub(r"\*\*|\*|`", "", l).strip() for l in body.splitlines() if l.strip().startswith("- ")]
    out = "Glassio " + name
    for l in lines:
        first = l.split(":")[0] if ":" in l[:60] else l
        cand = out + "\n" + first[:120]
        if len(cand) > 480:
            break
        out = cand
    return out


def status(s):
    e = ok(s.post(API + "/edits"))["id"]
    try:
        for t in ok(s.get(f"{API}/edits/{e}/tracks")).get("tracks", []):
            for r in t.get("releases", []):
                print(f"{t['track']:<12} {r.get('name', ''):<14} {r.get('status', ''):<10} codes {','.join(r.get('versionCodes', []))}")
    finally:
        s.delete(f"{API}/edits/{e}")


def upload(s, track, notes, aab):
    code, name = version_of(aab)
    notes = notes or notes_from_changelog(name)
    e = ok(s.post(API + "/edits"))["id"]
    print(f"edit {e}: uploading {os.path.basename(aab)} ({os.path.getsize(aab) // 1024} KB) for {name} ({code})")
    with open(aab, "rb") as f:
        b = ok(s.post(f"{UPLOAD}/edits/{e}/bundles?uploadType=media", data=f,
                      headers={"Content-Type": "application/octet-stream"}, timeout=600))
    if str(b.get("versionCode")) != code:
        sys.exit(f"the bundle is {b.get('versionCode')}, build.gradle.kts says {code}: build it again")
    body = {"track": track, "releases": [{"name": f"{code} ({name})", "versionCodes": [code], "status": "completed",
                                           "releaseNotes": [{"language": "en-US", "text": notes}]}]}
    ok(s.put(f"{API}/edits/{e}/tracks/{track}", json=body))
    ok(s.post(f"{API}/edits/{e}:commit"))
    print(f"{track}: {code} ({name}) committed and sent for review")
    print("notes:\n" + notes)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("what", choices=["status", "upload"])
    p.add_argument("--track", default="alpha")
    p.add_argument("--notes")
    p.add_argument("--aab", default=os.path.join(ROOT, "app/build/outputs/bundle/playRelease/app-play-release.aab"))
    a = p.parse_args()
    s = session()
    status(s) if a.what == "status" else upload(s, a.track, a.notes, a.aab)
