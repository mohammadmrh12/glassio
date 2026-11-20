#!/usr/bin/env python3
"""
Controlled speed bench for comparing two builds on the same TV.

  TV=<tv-ip>:5555 tools/qa/bench.py OUTDIR [passes]      (OUTDIR e.g. build/qa/beta1)

Every scenario first puts the selection where it has to be and checks it with dump_focus (the same keys end up in
different places depending on where the selection was), then measures: slides, dock moves, dock at rest, Library,
menu. Ends with resources and a crash check. Results: OUTDIR/ab.txt and ab.json, plus one picture per scenario.
Frame times change with the picture behind the glass, so compare several passes, not one number.
"""
import os, sys, json, time, io, contextlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glassqa as q

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
passes = int(sys.argv[2]) if len(sys.argv) > 2 else 3
log = open(os.path.join(out, "ab.txt"), "w")
def p(*a):
    s = " ".join(str(x) for x in a); print(s, flush=True); log.write(s + "\n"); log.flush()

def st():
    f = q.focus(True)
    return {k: v for k, v in (kv.split("=", 1) for kv in f.split(" ") if "=" in kv)}

def to_hero():
    for _ in range(6):
        s = st()
        if s.get("screen") == "Home" and s.get("hero") == "true": return True
        if s.get("screen") != "Home" or s.get("top") == "true": q.keys(["BACK", "wait:1"])
        elif s.get("dock") == "true": q.keys(["DPAD_UP", "wait:1"])
        else: q.home()
    return False

def to_dock():
    if not to_hero(): return False
    q.keys(["DPAD_DOWN", "wait:1.2"])
    return st().get("dock") == "true"

res = {}
def run(name, ok, seq):
    if not ok:
        p(f"{name:18s} SETUP FAILED ({q.focus(True)[:90]})"); return
    s = q.perf(name, seq); log.write(q.fmt(s) + "\n"); res[name] = s

p("# ab", time.strftime("%Y-%m-%d %H:%M"), q.sh("dumpsys package com.glasslauncher.tv | grep versionName").strip())
if not q.awake(): q.wake()
q.home()
for i in range(passes):
    # Slides: the selection on the hero's last button (›), then OK = next slide, three times.
    ok = to_hero()
    if ok: q.keys(["DPAD_RIGHTx4", "wait:2.5"])
    run(f"slides#{i}", ok, ["DPAD_CENTER", "wait:1.6", "DPAD_CENTER", "wait:1.6", "DPAD_CENTER", "wait:1.6"])
    if i == 0: q.shot("ab_slides"); os.replace(os.path.join(q.OUT, "ab_slides.small.png"), os.path.join(out, "slides.png"))
    ok = to_dock()
    run(f"dock_moves#{i}", ok, ["DPAD_RIGHTx5", "DPAD_LEFTx5", "wait:1"])
    if i == 0: q.shot("ab_dock"); os.replace(os.path.join(q.OUT, "ab_dock.small.png"), os.path.join(out, "dock.png"))
    run(f"dock_idle#{i}", ok, ["wait:3"])
    # Library: Down from the dock, then moves inside it.
    ok = to_dock()
    if ok:
        q.keys(["DPAD_DOWN", "wait:2"]); ok = st().get("screen") == "Library"
    run(f"library#{i}", ok, ["DPAD_DOWNx3", "wait:0.8", "DPAD_RIGHTx3", "wait:0.8", "DPAD_UPx3", "wait:0.8"])
    if i == 0: q.shot("ab_lib"); os.replace(os.path.join(q.OUT, "ab_lib.small.png"), os.path.join(out, "library.png"))
    q.keys(["BACK", "wait:1"])
    # Menu: Up from the hero opens it, Back closes it.
    ok = to_hero()
    run(f"menu#{i}", ok, ["DPAD_UP", "wait:1.2", "BACK", "wait:1.2"])
to_hero(); time.sleep(3)
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    r = q.res(5)
p(buf.getvalue()); res["res"] = {k: v for k, v in r.items() if k != "top"}
json.dump(res, open(os.path.join(out, "ab.json"), "w"), indent=1)
p("crashes:", q.crashes() or "none")
