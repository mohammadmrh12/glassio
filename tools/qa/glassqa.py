#!/usr/bin/env python3
"""
Glassio QA from a computer: one tool for checking navigation, focus, speed, the glass, picture quality and security
on a real TV over adb.

  TV=<tv-ip>:5555 tools/qa/glassqa.py <command> [...]

Commands
  install [apk]          wake the TV, then install (installing while Glassio's screensaver runs kills it, and the
                         TV goes to sleep)
  keys K1 K2 ...         send keys: DPAD_UP, DPAD_DOWNx3 (repeat), wait:1.5, shot:name
  shot NAME              screenshot -> build/qa/NAME.png (+ a 960 px copy)
  perf NAME K1 K2 ...    frame times while the keys run: share of slow frames, p50/p90/p99, GPU and main thread
  idle NAME [seconds]    frame times with nothing pressed (does anything redraw for nothing?)
  glide K1 ...           screen recording: when each movement starts and how long it takes to settle
  live [x0 y0 x1 y1]     is the glass following a video? compares the glass area with the background over 5 shots
  focus                  where the selection is (dock / top / carousel / player / rows / inputs)
  res [seconds]          resources: Glassio's CPU (per thread too), memory (Java, native, graphics), threads, the
                         TV's free memory and load, and how many frames it draws with nothing pressed
  watch NAME K1 K2 ...   resources sampled every second while the keys run (CPU, memory, frames)
  nav                    navigation smoke test: home, dock, menu, library, settings, back; a screenshot, the
                         selection and the app in front after each step, then a crash check
  profile K1 K2 ...      CPU profile (simpleperf) while the keys run: time per thread, then the main thread's
                         heaviest Glassio functions with real names (R8 mapping); full report in build/qa/
  pref key=value ...     change settings (needs the dev key from the TV)
  security               on this computer: private data in tracked files, exported components, dev hooks, lint
  all                    nav + the speed suite + security, written to build/qa/report.md

Results and pictures go to build/qa/ (git-ignored). CONTRIBUTING.md explains the dev key.
"""
import os, re, subprocess, sys, time, shutil

PKG = "com.glasslauncher.tv"
ACT = f"{PKG}/.MainActivity"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "build", "qa")
os.makedirs(OUT, exist_ok=True)
TV = os.environ.get("TV", "")
SEL = ["-s", TV] if TV else []


def adb(*a):
    return subprocess.run(["adb"] + SEL + list(a), capture_output=True, text=True).stdout


def sh(cmd):
    return adb("shell", cmd)


def devkey():
    return sh(f"cat /sdcard/Android/data/{PKG}/files/devkey").strip()


def am(*extras):
    sh(f"am start -n {ACT} " + " ".join(extras))


# ---------------------------------------------------------------- keys and pictures
def shot(name):
    p = os.path.join(OUT, name + ".png")
    with open(p, "wb") as f:
        f.write(subprocess.run(["adb"] + SEL + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
    try:
        from PIL import Image
        Image.open(p).resize((960, 540)).save(os.path.join(OUT, name + ".small.png"))
    except Exception:
        pass
    return p


def keys(seq):
    for k in seq:
        if k.startswith("wait:"):
            time.sleep(float(k[5:]))
        elif k.startswith("shot:"):
            shot(k[5:])
        else:
            m = re.match(r"(.+)x(\d+)$", k)
            name, n = (m.group(1), int(m.group(2))) if m else (k, 1)
            for _ in range(n):
                sh(f"input keyevent KEYCODE_{name}")
                time.sleep(0.25)


def home():
    """Glassio in front, on its home screen. (An injected Home key goes to the TV's own launcher: Glassio takes the
    Home button through its accessibility service, which only sees the remote's own key.)"""
    am("")
    time.sleep(1.5)
    keys(["BACK", "wait:0.6", "BACK", "wait:1.5"])


def awake():
    return "mWakefulness=Awake" in sh("dumpsys power")


def wake():
    """Screen on and no screensaver (a dream killed by an install puts the TV to sleep)."""
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    for _ in range(3):
        if "DreamActivity" not in foreground() and "Screensaver" not in foreground():
            break
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1.5)


def foreground():
    # (Right after a key the focus can be on no window for a moment: asked again.)
    for _ in range(4):
        m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}", sh("dumpsys window"))
        if m:
            return m.group(1)
        time.sleep(0.5)
    return ""


def crashes():
    return [l for l in adb("logcat", "-d", "-b", "crash").splitlines() if PKG in l or "FATAL" in l]


# ---------------------------------------------------------------- frame times
def framestats(label):
    txt = sh(f"dumpsys gfxinfo {PKG} framestats")
    hdr, rows = None, []
    for l in txt.splitlines():
        if l.startswith("Flags,"):
            hdr = l.split(",")
        elif hdr and l[:1].isdigit() and l.count(",") > 10:
            rows.append(dict(zip(hdr, l.split(","))))
    rows = [r for r in rows if r.get("Flags") == "0"]
    if not rows:
        return {"label": label, "frames": 0}
    g = lambda r, k: int(r[k])
    tot = sorted((g(r, "FrameCompleted") - g(r, "IntendedVsync")) / 1e6 for r in rows)
    gpu = sorted((g(r, "FrameCompleted") - g(r, "IssueDrawCommandsStart")) / 1e6 for r in rows)
    ui = sorted((g(r, "SyncStart") - g(r, "HandleInputStart")) / 1e6 for r in rows)
    p = lambda a, q: a[min(len(a) - 1, int(len(a) * q))]
    return {"label": label, "frames": len(tot), "slow": round(100 * sum(x > 16.7 for x in tot) / len(tot)),
            "over33": sum(x > 33.4 for x in tot), "p50": p(tot, .5), "p90": p(tot, .9), "p99": p(tot, .99),
            "gpu50": p(gpu, .5), "gpu90": p(gpu, .9), "ui90": p(ui, .9)}


def fmt(s):
    if not s.get("frames"):
        return f"{s['label']:18s} no frames (nothing redrew)"
    return (f"{s['label']:18s} frames {s['frames']:4d}  slow {s['slow']:3d}%  >33ms {s['over33']:3d}  p50 {s['p50']:5.1f}  "
            f"p90 {s['p90']:5.1f}  p99 {s['p99']:5.1f}  gpu p50 {s['gpu50']:5.1f} p90 {s['gpu90']:5.1f}  ui p90 {s['ui90']:5.1f}")


def perf(label, seq):
    sh(f"dumpsys gfxinfo {PKG} reset")
    keys(seq)
    s = framestats(label)
    print(fmt(s))
    return s


# ---------------------------------------------------------------- motion from a screen recording
def glide(seq):
    from PIL import Image, ImageChops, ImageStat
    sh("rm -f /sdcard/qa.mp4")
    dur = 2 + sum(float(k[5:]) for k in seq if k.startswith("wait:")) + 0.3 * len(seq)
    rec = subprocess.Popen(["adb"] + SEL + ["shell", f"screenrecord --time-limit {int(dur) + 1} --size 960x540 /sdcard/qa.mp4"])
    time.sleep(1)
    keys(seq)
    rec.wait()
    mp4 = os.path.join(OUT, "glide.mp4")
    adb("pull", "/sdcard/qa.mp4", mp4)
    d = os.path.join(OUT, "glide_frames")
    shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
    subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", "fps=60,scale=480:-1", d + "/%05d.png"])
    prev, runs, cur = None, [], None
    for i, f in enumerate(sorted(os.listdir(d))):
        im = Image.open(os.path.join(d, f)).convert("L")
        if prev is not None and ImageStat.Stat(ImageChops.difference(im, prev)).mean[0] > 1.5:
            if cur is None or i - cur[1] > 6:
                cur = [i, i]; runs.append(cur)
            cur[1] = i
        prev = im
    for r in runs:
        print(f"movement at {r[0] / 60:5.2f}s  lasted {(r[1] - r[0]) * 1000 / 60:4.0f} ms")
    return runs


# ---------------------------------------------------------------- glass follows the video?
def live(box=None):
    from PIL import Image, ImageChops, ImageStat
    box = box or (1640, 1018, 1860, 1030)  # a strip of the dock's glass (1080p screen)
    bg = (600, 300, 900, 500)
    ims = []
    for i in range(5):
        ims.append(Image.open(shot(f"live{i}")).convert("L")); time.sleep(0.7)
    d = lambda r, i: ImageStat.Stat(ImageChops.difference(ims[i].crop(r), ims[i + 1].crop(r))).mean[0]
    g = [round(d(box, i), 2) for i in range(4)]; b = [round(d(bg, i), 2) for i in range(4)]
    print("glass changes:", g); print("background changes:", b)
    frozen = max(b) > 3 and max(g) < 0.3
    print("RESULT:", "FROZEN glass (the background moves, the glass doesn't)" if frozen else "ok")
    return not frozen


# ---------------------------------------------------------------- resources
def pid():
    return sh(f"pidof {PKG}").strip().split(" ")[0]


def meminfo():
    txt = sh(f"dumpsys meminfo {PKG}")
    out = {}
    for k, pat in (("pss", r"TOTAL PSS:\s+(\d+)"), ("java", r"Java Heap:\s+(\d+)"), ("native", r"Native Heap:\s+(\d+)"),
                   ("graphics", r"Graphics:\s+(\d+)"), ("views", r"Views:\s+(\d+)")):
        m = re.search(pat, txt)
        if m:
            out[k] = int(m.group(1)) // 1024
    return out


def cpu_threads(seconds=2):
    p = pid()
    txt = sh(f"top -H -b -n 2 -d {seconds} -p {p}")
    block = txt.split("TID")[-1].splitlines()[1:]
    rows = []
    for l in block:
        f = l.split()
        if len(f) > 10:
            try:
                rows.append((float(f[8]), " ".join(f[11:-1]) or f[11]))
            except ValueError:
                pass
    total = sum(c for c, _ in rows)
    return total, sorted(rows, reverse=True)[:6]


def system():
    load = sh("cat /proc/loadavg").split()[:3]
    m = re.search(r"MemAvailable:\s+(\d+)", sh("cat /proc/meminfo"))
    return {"load": " ".join(load), "free_mb": int(m.group(1)) // 1024 if m else -1}


def idle_frames(seconds=3):
    sh(f"dumpsys gfxinfo {PKG} reset")
    time.sleep(seconds)
    m = re.search(r"Total frames rendered: (\d+)", sh(f"dumpsys gfxinfo {PKG}"))
    return int(m.group(1)) if m else -1


def res(seconds=3):
    total, top = cpu_threads(2)
    mem = meminfo(); sysm = system()
    threads = len(sh(f"ls /proc/{pid()}/task").split())
    fr = idle_frames(seconds)
    print(f"CPU {total:.0f}% (of one core) · threads {threads}")
    for c, n in top:
        print(f"   {c:5.1f}%  {n}")
    print(f"memory MB: PSS {mem.get('pss')} · Java {mem.get('java')} · native {mem.get('native')} · graphics {mem.get('graphics')}")
    print(f"TV: load {sysm['load']} · free {sysm['free_mb']} MB")
    rate = fr / seconds
    print(f"frames with nothing pressed: {fr} in {seconds}s ({rate:.0f}/s)" + ("  <- redraws for nothing" if rate > 20 else ""))
    return {"cpu": total, "top": top, "mem": mem, "sys": sysm, "idle_fps": rate, "threads": threads}


def watch(label, seq):
    import threading
    samples, stop = [], threading.Event()
    def loop():
        p = pid()
        while not stop.is_set():
            out = sh(f"top -b -n 1 -d 1 -p {p}").splitlines()
            cpu = next((float(l.split()[8]) for l in out if l.strip().startswith(p)), 0.0)
            samples.append((cpu, meminfo().get("pss", 0)))
    th = threading.Thread(target=loop, daemon=True); th.start()
    s = perf(label, seq)
    stop.set(); th.join(timeout=5)
    if samples:
        cpus = [c for c, _ in samples]; pss = [m for _, m in samples]
        print(f"{'':18s} CPU avg {sum(cpus) / len(cpus):.0f}% max {max(cpus):.0f}% · PSS {min(pss)}–{max(pss)} MB")
    return s, samples


# ---------------------------------------------------------------- CPU profile
def demap_lines(lines):
    mp = os.path.join(ROOT, "app/build/outputs/mapping/release/mapping.txt")
    cls, meth, cur = {}, {}, None
    if os.path.exists(mp):
        for l in open(mp):
            if not l.startswith(" ") and "->" in l:
                a, b = l.rstrip().rstrip(":").split(" -> "); cls[b] = a; cur = b
            elif cur and "->" in l:
                a, b = l.strip().split(" -> ")
                meth.setdefault((cur, b), set()).add(re.sub(r"^[\d:]*", "", a).split("(")[0].split()[-1])
    def rep(mo):
        s = mo.group(0); parts = s.split(".")
        c, f = ".".join(parts[:-1]), parts[-1]
        if c in cls:
            return cls[c].replace(PKG + ".", "") + "." + "/".join(sorted(meth.get((c, f), {f})))[:50]
        return s
    return [re.sub(r"\b[a-z][a-z0-9]{0,2}(\.[A-Za-z0-9_$]+)+", rep, l) for l in lines]


def profile(seq):
    dur = int(2 + sum(float(k[5:]) for k in seq if k.startswith("wait:")) + 0.3 * len(seq))
    rec = subprocess.Popen(["adb"] + SEL + ["shell", f"simpleperf record --app {PKG} -e cpu-clock -f 1000 -g --duration {dur} -o /data/local/tmp/qa.perf"])
    time.sleep(1.5)
    keys(seq)
    rec.wait()
    print(sh("simpleperf report -i /data/local/tmp/qa.perf --sort comm --percent-limit 2").split("Overhead")[-1])
    sh(f"simpleperf report -i /data/local/tmp/qa.perf --comms {PKG} --children --sort symbol --percent-limit 0.5 -o /data/local/tmp/qa.txt")
    out = os.path.join(OUT, "profile.txt")
    adb("pull", "/data/local/tmp/qa.txt", out)
    lines = demap_lines(open(out).read().splitlines())
    open(out, "w").write("\n".join(lines) + "\n")
    mine = [l for l in lines if re.search(r"\b(ui|data|net|saver)\.[A-Z]", l)]
    print("main thread, Glassio's own functions (inclusive %):")
    for l in mine[:25]:
        print("  " + l[:150])
    print("full report:", out)


# ---------------------------------------------------------------- selection
def focus(quiet=False):
    adb("logcat", "-c")
    am("--es dev " + devkey(), "--ez dump_focus true")
    time.sleep(1.2)
    lines = [l for l in adb("logcat", "-d", "-s", "GlassFocus").splitlines() if "hero=" in l]
    f = lines[-1].split("GlassFocus: ")[-1] if lines else "(no answer)"
    if not quiet:
        print(f)
    return f


# ---------------------------------------------------------------- navigation smoke test
NAV = [
    ("home", []),
    ("carousel", ["DPAD_UP", "wait:1"]),
    ("dock", ["DPAD_DOWN", "wait:1"]),
    ("dock_right", ["DPAD_RIGHTx3", "wait:1"]),
    ("dock_left", ["DPAD_LEFTx3", "wait:1"]),
    ("library", ["DPAD_DOWN", "wait:1.5"]),
    ("library_down", ["DPAD_DOWNx2", "wait:1"]),
    ("library_up_to_dock", ["DPAD_UPx4", "wait:1.5"]),
    ("up_to_top", ["DPAD_UP", "wait:0.6", "DPAD_UP", "wait:1.2"]),
    ("back_home", ["BACK", "wait:1"]),
    ("settings", ["@open_settings 0", "wait:1.5"]),
    ("settings_pages", ["DPAD_DOWNx4", "wait:0.6", "DPAD_RIGHT", "wait:0.6", "DPAD_DOWNx3", "wait:0.6"]),
    ("settings_back", ["BACK", "BACK", "wait:1.5"]),
]


def nav():
    if not awake():
        wake()
    adb("logcat", "-b", "crash", "-c")
    home()
    report = []
    for name, seq in NAV:
        for k in seq:
            if k.startswith("@open_settings"):
                am(f"--ei open_settings {k.split()[1]}")
            else:
                keys([k])
        shot("nav_" + name)
        fg, f = foreground(), focus(quiet=True)
        ok = PKG in fg
        report.append((name, ok, fg, f))
        print(f"{'ok ' if ok else 'BAD'} {name:20s} {f}")
    bad = crashes()
    print("crashes:", "none" if not bad else "\n  " + "\n  ".join(bad))
    home()
    return report, bad


# ---------------------------------------------------------------- security (on this computer)
def private_pattern():
    """What counts as private: the local pre-push hook's own pattern (it knows this home's addresses), else a generic
    set. (Written here literally, the pattern was itself caught by the hook.)"""
    try:
        m = re.search(r"git grep -nIE '([^']+)'", open(os.path.join(ROOT, ".git/hooks/pre-push")).read())
        if m:
            return m.group(1) + r"|BEGIN (RSA|OPENSSH) PRIVATE"
    except OSError:
        pass
    jwt = "eyJ" + "hbGciOi"
    return r"192\.168\.[0-9]+\.[0-9]+|@gmail\.com|" + jwt + r"|ghp_[A-Za-z0-9]{20}|/Users/[a-z]|BEGIN (RSA|OPENSSH) PRIVATE"


PRIVATE = private_pattern()


def security():
    os.chdir(ROOT)
    issues = []
    hits = subprocess.run(["git", "grep", "-nIE", PRIVATE, "--", ".", ":!*.apk", ":!tools/qa/glassqa.py"], capture_output=True, text=True).stdout.strip()
    if hits:
        issues.append("private data in tracked files:\n" + hits)
    tracked = subprocess.run(["git", "ls-files"], capture_output=True, text=True).stdout.split()
    for f in ("local.properties", "CLAUDE.local.md", ".claude/settings.local.json"):
        if f in tracked:
            issues.append(f"{f} is tracked by git (it must stay local)")
    man = open("app/src/main/AndroidManifest.xml").read()
    for m in re.finditer(r"<(activity|service|receiver|provider)\b([^>]*)>", man, re.S):
        attrs = m.group(2)
        name = (re.search(r'android:name="([^"]+)', attrs) or [None, "?"])[1]
        if 'exported="true"' in attrs and "permission=" not in attrs and name not in (".MainActivity", ".BootReceiver"):
            issues.append(f"exported without a permission: {m.group(1)} {name}")
    hooks = open("app/src/main/java/com/glasslauncher/tv/DevHooks.kt").read()
    gate = hooks.find("if (!allowed(app, i)) return")
    for w in ('getStringExtra("pref")', 'getStringExtra("ha_webhook")', 'getStringExtra("theme")', 'getBooleanExtra("dump_prefs"'):
        i = hooks.find(w)
        if i != -1 and (gate == -1 or i < gate):
            issues.append(f"dev hook {w} is reachable without the dev key")
    print("security:", "ok" if not issues else f"{len(issues)} issue(s)")
    for i in issues:
        print(" -", i)
    lint = subprocess.run(["./gradlew", "-q", ":app:lintDebug"], capture_output=True, text=True)
    print("lint:", "ok" if lint.returncode == 0 else "FAILED (app/build/reports/lint-results-debug.html)")
    return issues, lint.returncode == 0


# ---------------------------------------------------------------- everything
SUITE = [
    ("carousel_idle", ["wait:2"]),
    ("carousel_slides", ["DPAD_RIGHT", "wait:1.2", "DPAD_RIGHT", "wait:1.2", "DPAD_LEFT", "wait:1.2"]),
    ("dock_moves", ["DPAD_DOWN", "wait:1", "DPAD_RIGHTx5", "DPAD_LEFTx5", "wait:1"]),
    ("dock_idle", ["wait:2"]),
    ("menu_open_close", ["DPAD_UP", "wait:0.5", "DPAD_UP", "wait:1", "BACK", "wait:1"]),
]


def all_checks():
    lines = ["# Glassio QA", time.strftime("%Y-%m-%d %H:%M"), ""]
    report, bad = nav()
    lines += ["## Navigation", ""] + [f"- {'ok' if ok else '**BAD**'} {n}: {f}" for n, ok, fg, f in report]
    lines += ["", "Crashes: " + ("none" if not bad else "\n" + "\n".join(bad)), "", "## Speed", "", "```"]
    home()
    for name, seq in SUITE:
        lines.append(fmt(perf(name, seq)))
    home()
    lines += ["```", "", "## Resources (home screen, nothing pressed)", "", "```"]
    r = res()
    lines += [f"CPU {r['cpu']:.0f}% · threads {r['threads']} · idle frames {r['idle_fps']:.0f}/s",
              "top threads: " + ", ".join(f"{n} {c:.0f}%" for c, n in r["top"]),
              f"memory MB: {r['mem']} · TV {r['sys']}", "```"]
    lines += ["", "## Security", ""]
    issues, lint_ok = security()
    lines += [f"- {i}" for i in issues] or ["- no private data, components protected"]
    lines.append(f"- lint: {'ok' if lint_ok else 'failed'}")
    p = os.path.join(OUT, "report.md")
    open(p, "w").write("\n".join(lines) + "\n")
    print("report:", p)


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    c, a = sys.argv[1], sys.argv[2:]
    if c == "install":
        apk = a[0] if a else os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk")
        wake()
        print(adb("install", "-r", apk).strip())
        am("")
    elif c == "keys": keys(a)
    elif c == "shot": print(shot(a[0] if a else "shot"))
    elif c == "perf": perf(a[0], a[1:])
    elif c == "idle": perf(a[0] if a else "idle", [f"wait:{a[1] if len(a) > 1 else 3}"])
    elif c == "glide": glide(a)
    elif c == "live": live(tuple(int(x) for x in a) if len(a) == 4 else None)
    elif c == "focus": focus()
    elif c == "res": res(int(a[0]) if a else 3)
    elif c == "profile": profile(a)
    elif c == "watch": watch(a[0], a[1:])
    elif c == "nav": nav()
    elif c == "pref":
        k = devkey()
        for kv in a:
            am(f"--es dev {k}", f"--es pref '{kv}'"); time.sleep(1.2)
    elif c == "security": security()
    elif c == "all": all_checks()
    else: print(__doc__)


if __name__ == "__main__":
    main()
