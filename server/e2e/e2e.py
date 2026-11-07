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
  - syscalls read like strace (exec("echo", ["echo", "hi"]) = 2, the
    bytes read() got, wait() sleeping and returning the child's pid); a
    command opens at its first user-mode C line
  - loop-out leaves wakeup's loop over the process table, loop-back goes
    back before it; run-to finds the first run of sys_write's first line
  - the step filter: line-into at a call of acquire does not stop in it
  - the timeline shows every syscall and interrupt of the summary
  - memory: the address space names lazytouch's text, data, guard page
    and stack, each fault adds a heap page, and that page's frame was on
    the kernel's free list (kmem.freelist) before the fault
  - the stack: the frame the CPU pushes for int $64 (rip after the int,
    user cs and rsp) and for a page fault (error code, faulting rip), the
    kernel frames of sys_write, the user frames of write and main
"""
import urllib.request

from common import BASE, call, check, find_all, finish, get, state

SYS_write = 16
SYSCALL_VECTOR = 64
PAGE_FAULT_VECTOR = 14


def stack(rid, step):
    return get(f"/api/recordings/{rid}/stack?step={step}")


def slot(stk, label):
    found = [x for x in stk["slots"] if x["label"] == label]
    return found[0] if found else None


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

    # step filter: line-into over a call of acquire stays in the caller
    ka = find_all(rid, lambda k: k["label"] == "kalloc", limit=1)[0]
    acq = [k for k in get(f"/api/recordings/{rid}/tree?node={ka['id']}") if k["label"] == "acquire"][0]
    call_step = acq["start"] - 1
    plain = call("GET", f"/api/recordings/{rid}/step?from={call_step}&op=line-into")["step"]
    filt = call("GET", f"/api/recordings/{rid}/step?from={call_step}&op=line-into&skip=true")["step"]
    check(state(rid, plain)["location"]["symbol"] == "acquire"
          and filt >= acq["end"] and state(rid, filt)["location"]["symbol"] == "kalloc",
          f"line-into at the call of acquire: {state(rid, plain)['location']['label']} without the filter, "
          f"{state(rid, filt)['location']['label']} with it")
    back = call("GET", f"/api/recordings/{rid}/step?from={filt}&op=line-back&skip=true")["step"]
    check(not (acq["start"] <= back < acq["end"]) and state(rid, back)["location"]["symbol"] == "kalloc",
          f"line-back with the filter stays in kalloc ({state(rid, back)['location']['label']})")
    kids = [k["label"] for k in get(f"/api/recordings/{rid}/tree?node={ka['id']}&skip=true")]
    hidden = {"acquire", "release", "push_off", "pop_off", "holding", "mycpu"}
    check(not hidden & set(kids), f"kalloc's children with the filter: {kids}")

    # loops: out of wakeup's loop over the process table, and back before it
    wk = find_all(rid, lambda k: k["label"] == "wakeup", limit=1)[0]
    wkids = get(f"/api/recordings/{rid}/tree?node={wk['id']}")
    inloop = wkids[0]["start"] - 1  # a call in the loop body
    lo = call("GET", f"/api/recordings/{rid}/step?from={inloop}&op=loop-out")
    lb = call("GET", f"/api/recordings/{rid}/step?from={inloop}&op=loop-back")
    entry = call("GET", f"/api/recordings/{rid}/step?from={wk['start']}&op=loop-out")
    check(lo["found"] and wkids[-1]["end"] <= lo["step"] < wk["end"]
          and state(rid, lo["step"])["location"]["symbol"] == "wakeup",
          f"loop-out of wakeup's loop: step {inloop} -> {lo['step']} "
          f"({state(rid, lo['step'])['location']['label']})")
    check(lb["found"] and wk["start"] <= lb["step"] < wkids[0]["start"],
          f"loop-back: before the loop at {state(rid, lb['step'])['location']['label']}")
    check(not entry["found"] and entry["step"] == wk["start"], "loop-out at wakeup's entry: not in a loop")

    # run to a line: the first time sys_write's first line runs, from step 0
    first_sw = min(k["start"] for k in find_all(rid, lambda k: k["label"] == "sys_write"))
    sw = state(rid, first_sw)["location"]
    rt = call("GET", f"/api/recordings/{rid}/run-to?from=0&file={sw['file']}&line={sw['line']}")
    rb = call("GET", f"/api/recordings/{rid}/run-to?from={first_sw + 500}&file={sw['file']}&line={sw['line']}&back=true")
    check(rt["found"] and rt["step"] == first_sw and rb["found"] and rb["step"] == first_sw,
          f"run to {sw['file']}:{sw['line']}: forward from 0 and back from +500 both reach step {first_sw}")
    sig = call("GET", f"/api/recordings/{rid}/run-to?from=0&file={sw['file']}&line={sw['line'] - 1}")
    check(sig["found"] and sig["step"] == first_sw,
          f"run to line {sw['line'] - 1} (sys_write's name, no code) goes to line {sw['line']}")

    # syscalls, strace-style: what echo hi really does
    sc = get(f"/api/recordings/{rid}/syscalls")
    lines = [(c["process"], c["text"] + (" = " + c["result"] if c["returned"] and c["result"] else "")) for c in sc]
    texts = [t for _, t in lines]
    fork = [c for c in sc if c["name"] == "fork"]
    ex = [c for c in sc if c["name"] == "exec"]
    check('exec("echo", ["echo", "hi"]) = 2' in texts and 'write(1, "hi", 2) = 2' in texts
          and 'write(1, "\\n", 1) = 1' in texts and "exit(0)" in texts,
          f"syscalls: {[t for t in texts if not t.startswith('read')]}")
    check(bool(fork) and bool(ex) and ex[0]["process"].startswith("proc[")
          and any(c["process"].endswith("echo") for c in sc if c["name"] == "write"),
          f"fork() = {fork[0]['result'] if fork else None}; exec from {ex[0]['process'] if ex else None}")
    reads = [c for c in sc if c["name"] == "read" and c["output"]]
    check(bool(reads) and reads[-1]["output"] == '"\\n"', f"sh's last read: kernel wrote {reads[-1]['output'] if reads else None}")
    wait = [c for c in sc if c["name"] == "wait"]
    check(bool(wait) and wait[0]["resumeStep"] is not None and wait[0]["result"] == fork[0]["result"],
          f"wait slept and returned the child's pid {wait[0]['result'] if wait else None}")
    tree_w = get(f"/api/recordings/{rid}/nodes?ids={w['id']}")[0]
    check(tree_w["detail"] is not None and tree_w["detail"].startswith("write(1, "),
          f"event tree shows {tree_w['detail']}")
    st0 = state(rid, rec["summary"]["start"])
    check(st0["cpl"] == 3 and st0["location"]["file"].endswith(".c"),
          f"opens at step {rec['summary']['start']}: {st0['location']['file']}:{st0['location']['line']} ({st0['location']['label']})")

    # timeline: every trap once, every bucket covered by some thread
    n = rec["summary"]["steps"]
    tl = get(f"/api/recordings/{rid}/timeline?from=0&to={n}&buckets=400")
    by = {k: sum(m["count"] for m in tl["markers"] if m["kind"] == k) for k in ("syscall", "interrupt")}
    check(by["syscall"] == rec["summary"]["syscalls"] and by["interrupt"] == rec["summary"]["interrupts"],
          f"timeline traps {by} = summary ({rec['summary']['syscalls']}, {rec['summary']['interrupts']})")
    covered = all(sum(l["run"][b] for l in tl["lanes"]) >= 100 for b in range(tl["buckets"]))
    echo = [l for l in tl["lanes"] if "echo" in l["label"]]
    check(covered and bool(echo) and any(echo[0]["user"]),
          f"timeline: {len(tl['lanes'])} lanes cover every bucket, echo's has user mode")

    # stack: the CPU's frame for int $64, then the kernel's frames
    stk = stack(rid, w["start"])
    rip, cs, rsp = (slot(stk, "trapframe." + f) for f in ("rip", "cs", "rsp"))
    pc_after = int(before["pc"], 16) + 2  # int $64 is 2 bytes
    check(rip is not None and int(rip["value"], 16) == pc_after and rip["target"].startswith("write+"),
          f"{stk['label']}: trapframe.rip = {rip and rip['value']} ({rip and rip['target']})")
    check(cs is not None and int(cs["value"], 16) & 3 == 3, "trapframe.cs is a user selector")
    check(rsp is not None and rsp["value"] == before["registers"]["rsp"], "trapframe.rsp = user rsp")
    stk = stack(rid, inner["start"] + 3)
    fns = [f["function"] for f in stk["frames"]]
    check(stk["kind"] == "kernel" and fns[0] == "sys_write" and "syscall" in fns,
          f"{stk['label']}: frames {fns}")
    ret = [x for x in stk["slots"] if x["label"] == "return address"]
    check(bool(ret) and ret[0]["target"].startswith("syscall+"),
          f"sys_write returns to {ret[0]['target'] if ret else None}")
    stk = stack(rid, w["start"] - 1)
    fns = [f["function"] for f in stk["frames"]]
    check(stk["kind"] == "user" and fns[:2] == ["write", "main"], f"user stack frames {fns}")

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
        stk = stack(rid, f["start"])
        err, rip = slot(stk, "trapframe.err"), slot(stk, "trapframe.rip")
        faulting = state(rid, f["start"] - 1)["pc"]
        check(err is not None and int(err["value"], 16) & 6 == 6 and rip is not None
              and rip["value"] == faulting,
              f"fault {i}: CPU pushed error code {err and err['value']} (user write), rip {faulting}")
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
        # memory: regions, physical frames, the free list
        pre_r = get(f"/api/recordings/{rid}/regions?step={f['start']}")
        post_r = get(f"/api/recordings/{rid}/regions?step={f['end']}")
        kinds = {g["kind"] for g in pre_r["regions"]}
        check(pre_r["program"] == "lazytouch" and {"user-text", "user-data", "guard", "user-stack"} <= kinds,
              f"fault {i}: regions of {pre_r['program']}: {sorted(k for k in kinds if 'user' in k or k == 'guard')}")
        heap = [g for g in post_r["regions"] if g["kind"] == "heap"]
        check(bool(heap) and int(heap[0]["end"], 16) == addrs[i] + 4096,
              f"fault {i}: heap ends at {heap[0]['end'] if heap else None} after the fault")
        frame = hex(int(post["pa"], 16) & ~0xfff)
        fb = get(f"/api/recordings/{rid}/frame?step={f['start']}&pa={frame}")
        fa = get(f"/api/recordings/{rid}/frame?step={f['end']}&pa={frame}")
        pf = [c for c in get(f"/api/recordings/{rid}/syscalls") if c["kind"] == "exception"][i]
        check(pf["text"] == f"page fault at {va}" and pf["output"].startswith("user write at main+")
              and pf["output"].endswith("page not present"), f"fault {i}: {pf['text']}: {pf['output']}")
        check(fb["kind"] == "f" and fb["freeIndex"] is not None and fa["kind"] == "u"
              and va in fa["mappedAt"],
              f"fault {i}: frame {frame} free (#{fb['freeIndex']} on the list) before, user page at {fa['mappedAt']} after")
        pb = get(f"/api/recordings/{rid}/physmap?step={f['start']}")
        pa_ = get(f"/api/recordings/{rid}/physmap?step={f['end']}")
        check(pb["freeError"] is None and pa_["freeCount"] < pb["freeCount"]
              and pa_["counts"]["u"] == pb["counts"]["u"] + 1,
              f"fault {i}: free frames {pb['freeCount']} -> {pa_['freeCount']}, user frames "
              f"{pb['counts']['u']} -> {pa_['counts']['u']}")
        mem0 = call("GET", f"/api/recordings/{rid}/memory?step={f['start']}&addr={post['pa']}&len=1&space=phys")
        check(int(mem0["data"], 16) != i + 1, f"fault {i}: rewound memory has old value")

    call("DELETE", f"/api/sessions/{s}")


for k in [x["id"] for x in call("GET", "/api/kernels")]:
    kernel_test(k)
finish()
