#!/usr/bin/env python3
"""Checks on the boot recordings (waits until the server has them).

  - the first step is the kernel's entry point, run with paging and long
    mode still off; by the last step both are on
  - kernel main runs kinit and userinit; init and sh run before the end
  - memory can be read far from where the image last was (checkpoints)
  - stepping by line from the entry reaches main, and over a call that
    never returns (call main) steps into it instead of to the end; over a
    rep string instruction (x86-style's bss loop) takes it whole
  - the entry's 32-bit code reads as 32-bit (no rip-relative lgdt), and
    the GDT it loads has a real base and limit
  - the timeline marks where paging and long mode come on and where user
    mode first runs; the first process starts in forkret
"""
import time

from common import check, finish, first, get

ENTRY = {"rx64-x86-style": 0x100020, "rx64-riscv-style": 0x100000}
CR0_PG = 1 << 31
EFER_LME = 1 << 8


for kernel in [k["id"] for k in get("/api/kernels")]:
    print(f"== {kernel}")
    deadline = time.time() + 1800
    while True:
        b = get(f"/api/kernels/{kernel}/boot")
        if b["status"] in ("READY", "FAILED") or time.time() > deadline:
            break
        time.sleep(5)
    check(b["status"] == "READY", f"boot recording {b['status']} {b.get('error') or ''}")
    if b["status"] != "READY":
        continue
    rid, n = b["id"], b["summary"]["steps"]
    print(f"  {n:,} steps, indexed in {b['summary']['indexMillis']} ms")

    # by line from the entry to main: never thrown to the end of the boot
    s, where = 0, None
    for _ in range(80):
        nxt = get(f"/api/recordings/{rid}/step?from={s}&op=line-over&skip=true")["step"]
        if nxt == s or nxt >= n - 1:
            break
        s = nxt
        where = get(f"/api/recordings/{rid}/state?step={s}")["location"]
        if where["symbol"] == "main":
            break
    check(where is not None and where["symbol"] == "main" and s < n // 10,
          f"line-over from the entry reaches main at step {s:,} ({where['file'] if where else None})")
    reps = [k for k in range(0, 40) if get(f"/api/recordings/{rid}/state?step={k}")["location"]["disas"].startswith("rep stos")]
    if reps:
        o = get(f"/api/recordings/{rid}/step?from={reps[0]}&op=over")["step"]
        lo = get(f"/api/recordings/{rid}/state?step={o}")["location"]
        check(not lo["disas"].startswith("rep stos") and o > reps[0] + 1000,
              f"over the rep stosb at step {reps[0]} -> step {o:,} ({lo['label']})")

    s0 = get(f"/api/recordings/{rid}/state?step=0")
    check(int(s0["pc"], 16) == ENTRY[kernel], f"step 0 at {s0['pc']} ({s0['location']['label']})")
    check(s0["location"]["symbol"] == "_entry", "step 0 is _entry")
    r0, rn = s0["registers"], get(f"/api/recordings/{rid}/state?step={n - 1}")["registers"]
    check(int(r0["cr0"], 16) & CR0_PG == 0 and int(r0["efer"], 16) & EFER_LME == 0,
          f"paging and long mode off at entry (cr0 {r0['cr0']}, efer {r0['efer']})")
    check(int(rn["cr0"], 16) & CR0_PG != 0 and int(rn["efer"], 16) & EFER_LME != 0,
          f"both on at the end (cr0 {rn['cr0']}, efer {rn['efer']})")

    kinit = first(rid, "kinit")
    check(kinit is not None, "kinit found")
    if kinit:
        path = [p["label"] for p in get(f"/api/recordings/{rid}/state?step={kinit['start']}")["path"]]
        check(path[-2] == "main" and path[0] == "boot", f"path to kinit: {' > '.join(path)}")
        main = get(f"/api/recordings/{rid}/state?step={kinit['start']}")["path"][-2]
        kids = [k["label"] for k in get(f"/api/recordings/{rid}/tree?node={main['id']}")]
        check("userinit" in kids, f"main calls {', '.join(kids[:12])}...")
    roots = get(f"/api/recordings/{rid}/tree?node=-1")
    runs = [k["label"] for k in roots]
    check(any("init" in r for r in runs) and any("sh" in r for r in runs),
          f"runs include init and sh: {runs[-4:]}")
    folded = [k for k in roots if k["kind"] == "run" and k["count"] >= 3]
    check(bool(folded) and "scheduler" in folded[0]["label"],
          f"alternating runs folded: {[(k['label'], k['count']) for k in folded]}")

    # 32-bit entry code: its lgdt and the GDT it loads
    marks = get(f"/api/recordings/{rid}/timeline?from=0&to={n}&buckets=10")["milestones"]
    lg = next((m["step"] for m in marks if m["label"] == "lgdt"), None)
    if lg is not None:
        dis = get(f"/api/recordings/{rid}/state?step={lg}")["location"]["disas"]
        cpu = get(f"/api/recordings/{rid}/cpu?step={lg + 1}")
        check("%rip" not in dis and cpu["gdtr"]["known"] and cpu["gdtr"]["limit"] > 0 and int(cpu["gdtr"]["base"], 16) > 0,
              f"32-bit lgdt at step {lg} ({dis}): GDTR base {cpu['gdtr']['base']} limit {cpu['gdtr']['limit']}")

    t = time.time()
    tl = get(f"/api/recordings/{rid}/timeline?from=0&to={n}&buckets=1000")
    ms = [m["label"] for m in tl["milestones"]]
    check(any("+PG" in m for m in ms) and any("+LME" in m for m in ms) and "first user-mode instruction" in ms,
          f"milestones: {ms[:8]}")
    fk = first(rid, "forkret")
    check(fk is not None, f"the first process starts in forkret (a frame of its own) at step {fk['start'] if fk else None}")
    dt = time.time() - t
    check(len(tl["lanes"]) >= 2 and dt < 2, f"timeline of all {n:,} steps: {len(tl['lanes'])} lanes in {dt:.2f} s")

    for step in (n // 2, n - 1, 1000, n // 3):
        t = time.time()
        get(f"/api/recordings/{rid}/memory?step={step}&addr=0x100000&len=16&space=phys")
        dt = time.time() - t
        check(dt < 3, f"memory at step {step:,}: {dt:.2f} s")

finish()
