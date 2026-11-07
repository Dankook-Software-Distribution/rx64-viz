#!/usr/bin/env python3
"""End-to-end checks against a running server (default http://localhost:8080).

For each kernel: boot, run `echo hi` and `lazytouch`, and check that the
recordings say what the kernels actually do:
  - echo's main calls write twice; each write traps with rax = SYS_write
    into the syscall gate stub, and the path reaches uartwrite
  - lazytouch takes three page faults on consecutive pages; each page is
    unmapped before its fault and mapped after, and the retried store
    writes i + 1 into it
  - step "over" from `int $64` lands right after the syscall returns,
    "out" leaves the current call
"""
import urllib.request

from common import BASE, call, check, find_all, finish, state

SYS_write = 16
SYSCALL_VECTOR = 64
PAGE_FAULT_VECTOR = 14


def kernel_test(kernel):
    print(f"== {kernel}")
    s = call("POST", "/api/sessions", {"kernel": kernel})["id"]
    call("POST", f"/api/sessions/{s}/ready")

    # ---- echo hi ----
    rec = call("POST", f"/api/sessions/{s}/run", {"command": "echo hi"})
    check(rec["status"] == "ready", f"echo hi indexed ({rec['summary']['steps']} steps)")
    rid = rec["id"]
    console = urllib.request.urlopen(f"{BASE}/api/sessions/{s}/console").read().decode()
    check("echo hi\nhi\n$ " in console.replace("\r", ""), "console shows hi")

    writes = find_all(rid, lambda k: k["label"] == "syscall write")
    check(len(writes) >= 2, f"syscall write nodes: {len(writes)}")
    w = writes[0]
    st = state(rid, w["start"])
    check(int(st["registers"]["rax"], 16) == SYS_write, "rax = SYS_write at the gate")
    check(st["cpl"] == 0, "CPL 0 in the stub")
    check(w["vector"] == SYSCALL_VECTOR, f"trap through vector {w['vector']} ({st['location']['symbol']})")
    before = state(rid, w["start"] - 1)
    check(before["cpl"] == 3 and before["location"]["disas"].startswith("int"),
          f"previous step is user `{before['location']['disas']}`")
    check(before["location"]["binary"] == "echo", "user binary is echo")
    path = [p["label"] for p in state(rid, w["start"] + 1)["path"]]
    check(path[0].endswith("echo") and "main" in path and "write" in path,
          f"path {path[:4]}")
    uart = find_all(rid, lambda k: k["label"] == "uartwrite", node=w["id"], limit=1)
    check(len(uart) == 1, "write reaches uartwrite")

    over = call("GET", f"/api/recordings/{rid}/step?from={w['start'] - 1}&op=over")["step"]
    check(over == w["end"], f"over int $64: {w['start'] - 1} -> {over} (syscall ends {w['end']})")
    check(state(rid, over)["cpl"] == 3, "back in user mode after over")
    inner = find_all(rid, lambda k: k["label"] == "sys_write", node=w["id"], limit=1)[0]
    out = call("GET", f"/api/recordings/{rid}/step?from={inner['start'] + 3}&op=out")["step"]
    check(out == inner["end"], f"out of sys_write -> {out} (ends {inner['end']})")

    # C source lines
    loc = state(rid, inner["start"])["location"]
    check(loc["symbol"] == "sys_write" and loc["file"].endswith("/sysfile.c"),
          f"sys_write is in {loc['file']}:{loc['line']}")
    src = call("GET", f"/api/kernels/{kernel}/source?path={loc['file']}")
    check("sys_write(void)" in "\n".join(src["lines"]), "source file has sys_write")
    nxt = call("GET", f"/api/recordings/{rid}/step?from={inner['start']}&op=line-over")["step"]
    l2 = state(rid, nxt)["location"]
    check(l2["file"] == loc["file"] and l2["line"] > loc["line"] and nxt < inner["end"],
          f"line-over in sys_write: line {loc['line']} -> {l2['line']}")
    user = state(rid, w["start"] - 1)["location"]
    check(user["file"] == "user/usys.S" and user["symbol"] == "write",
          f"int $64 comes from {user['file']}:{user['line']} ({user['symbol']})")
    back = call("GET", f"/api/recordings/{rid}/step?from={over}&op=line-over")["step"]
    check(state(rid, back)["location"]["file"].startswith("user/"), "line-over after the syscall stays in user code")

    # ---- lazytouch ----
    rec = call("POST", f"/api/sessions/{s}/run", {"command": "lazytouch"})
    check(rec["status"] == "ready", f"lazytouch indexed ({rec['summary']['steps']} steps)")
    rid = rec["id"]
    faults = find_all(rid, lambda k: k["vector"] == PAGE_FAULT_VECTOR)
    check(len(faults) == 3, f"page faults: {len(faults)}")
    addrs = [int(f["label"].split()[-1], 16) for f in faults]
    check(all(addrs[i + 1] - addrs[i] == 4096 for i in range(len(addrs) - 1)),
          f"fault addresses {[hex(a) for a in addrs]}")
    for i, f in enumerate(faults):
        va = hex(addrs[i])
        pre = call("GET", f"/api/recordings/{rid}/pagewalk?step={f['start']}&va={va}")
        post = call("GET", f"/api/recordings/{rid}/pagewalk?step={f['end']}&va={va}")
        check(pre["pa"] is None and post["pa"] is not None,
              f"fault {i}: {va} unmapped before, mapped to {post['pa']} after "
              f"({len(post['levels'])} levels)")
        user = post["levels"][-1]["user"] if post["levels"] else False
        check(user, f"fault {i}: leaf PTE has U set")
        # the faulting store runs again after iretq (maybe after an
        # interrupt that was pending): find it among the stores
        stores = call("GET", f"/api/recordings/{rid}/stores?from={f['end']}&to={f['end'] + 200000}")
        hit = [w for w in stores if int(w["pa"], 16) == int(post["pa"], 16)]
        check(bool(hit) and int(hit[0]["value"], 16) == i + 1,
              f"fault {i}: retried store writes {hit[0]['value'] if hit else None} at step "
              f"{hit[0]['step'] if hit else None}")
        if hit:
            mem = call("GET", f"/api/recordings/{rid}/memory?step={hit[0]['step'] + 1}&addr={va}&len=1")
            check(int(mem["data"], 16) == i + 1, f"fault {i}: byte at {va} = {int(mem['data'], 16)} after it")
        mem0 = call("GET", f"/api/recordings/{rid}/memory?step={f['start']}&addr={post['pa']}&len=1&space=phys")
        check(int(mem0["data"], 16) != i + 1, f"fault {i}: rewound memory has old value")

    call("DELETE", f"/api/sessions/{s}")


for k in [x["id"] for x in call("GET", "/api/kernels")]:
    kernel_test(k)
finish()
