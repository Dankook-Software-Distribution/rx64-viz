#!/usr/bin/env python3
"""Checks on the boot recordings (waits until the server has them).

  - the first step is the kernel's entry point, run with paging and long
    mode still off; by the last step both are on
  - kernel main runs kinit and userinit; init and sh run before the end
  - memory can be read far from where the image last was (checkpoints)
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

    t = time.time()
    tl = get(f"/api/recordings/{rid}/timeline?from=0&to={n}&buckets=1000")
    dt = time.time() - t
    check(len(tl["lanes"]) >= 2 and dt < 2, f"timeline of all {n:,} steps: {len(tl['lanes'])} lanes in {dt:.2f} s")

    for step in (n // 2, n - 1, 1000, n // 3):
        t = time.time()
        get(f"/api/recordings/{rid}/memory?step={step}&addr=0x100000&len=16&space=phys")
        dt = time.time() - t
        check(dt < 3, f"memory at step {step:,}: {dt:.2f} s")

finish()
