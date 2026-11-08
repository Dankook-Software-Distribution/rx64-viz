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
  - processes: the proc table before fork (sh running on CPU 0, init
    sleeping in wait on its own proc); what fork (a new RUNNABLE slot,
    frames, mappings, file refs), exec (new page table), exit (ZOMBIE,
    fds closed), wait (slot freed, the child's frames back) and each page fault
    (one frame, one page) changed, with the stores inside the call
  - following a call: Syscalls' handler link goes to sys_write; line-into
    from int $64 with the step filter lands on a kernel C line; wait's
    timeline shows the child turning ZOMBIE, made by the child; Memory can
    show the shell's address space while echo runs
  - CPU tables: cs selects the user code segment before a write and the
    kernel's inside it; TSS rsp0 (x86-style: the process's kernel stack,
    riscv-style: the end of the trapframe); the syscall gate has DPL 3;
    riscv-style switches between the user and kernel IDT, x86-style
    keeps the one loaded at boot
  - the stack: the frame the CPU pushes for int $64 (rip after the int,
    user cs and rsp) and for a page fault (error code, faulting rip), the
    kernel frames of sys_write, the user frames of write and main
  - a call under way when the recording began (sh's read of "e") is
    listed, with its arguments; wait() slept on the shell's proc and was
    woken by the child; a command opens in its own program; registers are
    named (rsp on the kernel stack); every write returns its length (no
    two processes merged into one thread)
  - a page fault's handler is vmfault and it resumes at the faulting
    instruction; the heap lazytouch has not touched shows below p->sz;
    the frame a fault allocates links to kalloc's store
  - pipewait: the grandchild sleeps on the empty pipe, is woken by the
    parent's pipewrite, and has init as its parent (reparented)
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

    # a write can sleep (the UART is busy): the node then ends where it was
    # switched out, and the call returns later (the syscall's end)
    w_end = next(c["end"] for c in get(f"/api/recordings/{rid}/syscalls") if c["step"] == w["start"])
    over = call("GET", f"/api/recordings/{rid}/step?from={w['start'] - 1}&op=over")["step"]
    check(over == w_end, f"over int $64: {w['start'] - 1} -> {over} (syscall ends {w_end}"
          + (", it slept)" if w["suspended"] else ")"))
    check(state(rid, over)["cpl"] == 3, "back in user mode after over")
    inner = find_all(rid, lambda k: k["label"] == "sys_write", node=w["id"], limit=1)[0]
    out = call("GET", f"/api/recordings/{rid}/step?from={inner['start'] + 3}&op=out")["step"]
    lo = state(rid, out)["location"]
    check(out == inner["end"] if not inner["suspended"]
          else out > inner["end"] and out < w_end and lo["symbol"] != "sys_write",
          f"out of sys_write -> {out} ({lo['label']}; "
          + (f"its first part ends {inner['end']}, it slept)" if inner["suspended"] else f"ends {inner['end']})"))

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
    wk = find_all(rid, lambda k: k["label"] == "wakeup" and not k["earlier"] and k["start"] > 0, limit=1)[0]
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
    # processes and what fork, exec, exit and wait changed
    def diff(c):
        return get(f"/api/recordings/{rid}/diff?from={c['step'] - 1}&to={c['end']}")

    def fields(d, kind=None):
        return {(p["slot"], f["field"]): f for p in d["procs"] if kind in (None, p["kind"]) for f in p["fields"]}

    pt = get(f"/api/recordings/{rid}/procs?step={fork[0]['step'] - 1}")
    me = int(fork[0]["process"][5:fork[0]["process"].index("]")])
    cur = [p for p in pt["procs"] if p["slot"] == me]
    init = [p for p in pt["procs"] if p["pid"] == 1]
    check(pt["error"] is None and cur and cur[0]["state"] == "RUNNING" and cur[0]["cpu"] == 0
          and pt["cpus"][0]["slot"] == me and cur[0]["name"] == "sh"
          and [f["fd"] for f in cur[0]["files"]][:3] == [0, 1, 2] and cur[0]["files"][0]["what"] == "console",
          f"procs before fork: {[(p['slot'], p['name'], p['state']) for p in pt['procs']]}, CPU 0 runs proc[{pt['cpus'][0]['slot']}]")
    check(bool(init) and init[0]["state"] == "SLEEPING" and init[0]["chanName"] == f"proc[{init[0]['slot']}]"
          and "sleep" in init[0]["where"] and init[0]["name"] == "init",
          f"init sleeps on {init[0]['chanName'] if init else None} in {init[0]['where'][:3] if init else None}")
    df = diff(fork[0])
    made = [p for p in df["procs"] if p["kind"] == "created"]
    fs = fields(df, "created")
    check(len(made) == 1 and made[0]["pid"] == int(fork[0]["result"]) and made[0]["name"] == "sh"
          and fs.get((made[0]["slot"], "state"), {}).get("after") == "RUNNABLE"
          and fs[(made[0]["slot"], "parent")]["after"].startswith(f"proc[{me}]")
          and all(f["step"] is not None and fork[0]["step"] <= f["step"] < fork[0]["end"] for f in fs.values()),
          f"fork created proc[{made[0]['slot'] if made else None}] pid {made[0]['pid'] if made else None}, RUNNABLE, stores inside the call")
    check(df["freeAfter"] < df["freeBefore"] and len(df["allocated"]) == df["freeBefore"] - df["freeAfter"]
          and any(a["what"] and a["what"].startswith("page table of") for a in df["allocated"])
          and any(m["kind"] == "mapped" and m["label"] == "user stack" for m in df["mappings"])
          and any(f["object"] == "file[0]" and "ref" in f["after"] for f in df["files"]),
          f"fork: {len(df['allocated'])} frames allocated, {len(df['mappings'])} mapping changes, console file's ref went up")
    de = diff(ex[0])
    eslot = int(ex[0]["process"][5:ex[0]["process"].index("]")])
    ef = fields(de)
    check(ef.get((eslot, "name"), {}).get("after") == "echo" and (eslot, "page table") in ef
          and {"mapped", "unmapped"} <= {m["kind"] for m in de["mappings"] if m["slot"] == eslot}
          and len({m["table"] for m in de["mappings"] if m["slot"] == eslot}) == 2
          and any(m["label"] == "echo text" for m in de["mappings"]),
          f"exec: name -> echo, new page table, old table's pages unmapped and new ones mapped")
    ext = [c for c in sc if c["name"] == "exit" and c["process"].endswith("echo")]
    dx = diff(ext[0]) if ext else {"procs": []}
    xf = fields(dx)
    check(xf.get((eslot, "state"), {}).get("after") == "ZOMBIE" and xf.get((eslot, "fd 1"), {}).get("after") == "",
          f"exit: echo becomes ZOMBIE, its fds are closed")
    dw = diff(wait[0])
    check(any(p["kind"] == "freed" and p["slot"] == eslot for p in dw["procs"])
          and dw["freeAfter"] > dw["freeBefore"]
          and any(f["what"] and f["what"].endswith(f"of proc[{eslot}] sh") for f in dw["freed"]),
          f"wait: proc[{eslot}] freed; its frames (the child's sh image before exec) back on the free list")
    # CPU tables: user mode before the write, kernel mode inside it
    cu = get(f"/api/recordings/{rid}/cpu?step={w['start'] - 1}")
    ck = get(f"/api/recordings/{rid}/cpu?step={(w['start'] + w['end']) // 2}")
    cs_u = [g["index"] for g in cu["gdt"] if "cs" in g["selectors"]]
    cs_k = [g["index"] for g in ck["gdt"] if "cs" in g["selectors"]]
    gate = lambda v: next((g for g in v["idt"] if g["vector"] == SYSCALL_VECTOR), None)
    check(cu["gdtr"]["known"] and cs_u and cs_k and cs_u != cs_k
          and next(g for g in cu["gdt"] if g["index"] == cs_u[0])["label"] == "user code, 64-bit"
          and next(g for g in ck["gdt"] if g["index"] == cs_k[0])["label"] == "kernel code, 64-bit",
          f"GDT ({cu['gdtr']['name']}, loaded in {cu['gdtr']['function']}): cs selects GDT{cs_u} in user mode, GDT{cs_k} in the kernel")
    check(cu["tss"] is not None and cu["tss"]["rsp0Name"] is not None
          and (cu["tss"]["rsp0Name"].startswith("top of kernel stack of proc[") if kernel == "rx64-x86-style"
               else "trapframe" in cu["tss"]["rsp0Name"]),
          f"TSS rsp0 {cu['tss']['rsp'][0] if cu['tss'] else None}: {cu['tss']['rsp0Name'] if cu['tss'] else None}")
    check(cu["idtr"]["known"] and gate(cu) is not None and gate(cu)["dpl"] == 3,
          f"IDT {cu['idtr']['name']} in user mode: gate {SYSCALL_VECTOR} -> {gate(cu)['handlerName'] if gate(cu) else None}, DPL 3")
    if kernel == "rx64-riscv-style":
        check(ck["idtr"]["name"] == "kidt" and cu["idtr"]["name"].startswith("cpuentry.uidt")
              and gate(ck)["dpl"] == 0,
              f"riscv-style swaps IDTs: {cu['idtr']['name']} in user mode, {ck['idtr']['name']} in the kernel")
    else:
        check(ck["idtr"]["base"] == cu["idtr"]["base"] and cu["idtr"]["fromBoot"],
              f"x86-style keeps one IDT ({cu['idtr']['name']}, loaded at boot in {cu['idtr']['function']})")
    # what makes it easier to follow: handler links, the timeline of a call that slept,
    # stepping into a syscall lands in C, another process's address space
    cw = [c for c in sc if c["name"] == "write"][0]
    hs = get(f"/api/recordings/{rid}/state?step={cw['handlerStep']}") if cw["handlerStep"] else None
    check(cw["handler"] == "sys_write" and hs is not None and hs["location"]["symbol"] == "sys_write"
          and cw["step"] <= cw["handlerStep"] < cw["end"] and fork[0]["handler"] == "sys_fork",
          f"handler links: write -> {cw['handler']} at step {cw['handlerStep']}, fork -> {fork[0]['handler']}")
    li = get(f"/api/recordings/{rid}/step?from={cw['step'] - 1}&op=line-into&skip=true")
    ls = state(rid, li["step"])
    # the stub's first instruction (x86-style: a line of vectors.S; riscv-style: the trampoline,
    # which has no lines, walked an instruction at a time)
    check(ls["cpl"] == 0 and li["step"] <= cw["step"] + 2,
          f"line-into from int $64 with the filter: {ls['location']['file']}:{ls['location']['line']} ({ls['location']['label']})")
    walk, at = [], li["step"]
    for _ in range(60):
        st_ = state(rid, at)
        walk.append(st_["location"]["symbol"])
        if st_["location"]["file"] and st_["location"]["file"].endswith(".c"):
            break
        at = get(f"/api/recordings/{rid}/step?from={at}&op=line-into&skip=true")["step"]
    check(walk[-1] in ("trap", "usertrap", "syscall") and (kernel == "rx64-x86-style" or "uservec" in walk),
          f"line-into on to C: {len(walk)} stops through {sorted(set(walk[:-1]))} to {walk[-1]}")
    # the CPU's part of the trap, read from the recording
    tr = get(f"/api/recordings/{rid}/names?step={cw['step'] - 1}")["trap"]
    check(tr is not None and tr["kind"] == "enter" and tr["vector"] == SYSCALL_VECTOR and "DPL 3" in (tr["gate"] or "")
          and [w[0] for w in tr["words"]] == ["ss", "rsp", "rflags", "cs", "rip"],
          f"int $64: {tr and tr['gate']}; pushed {[w[0] for w in tr['words']] if tr else None}")
    # line-back is line-over backwards: from the line after the call, back to the call's line
    lo = get(f"/api/recordings/{rid}/step?from={cw['step'] - 1}&op=line-over&skip=true")["step"]
    lb = get(f"/api/recordings/{rid}/step?from={lo}&op=line-back&skip=true")["step"]
    check(state(rid, lb)["cpl"] == 3 and lb < cw["step"],
          f"line-back after the syscall: step {lb} ({state(rid, lb)['location']['label']}), before the trap")
    # who wrote the trap frame; the handler's variables
    stk = stack(rid, cw["handlerStep"])
    wr = {x["label"]: x["writer"] for x in stk["slots"] if x["label"] and x["label"].startswith("trapframe")}
    if kernel == "rx64-x86-style":  # riscv-style's trap frame is in the TRAPFRAME page, not on this stack
        check(wr.get("trapframe.rip") == "CPU" and wr.get("trapframe.rax") not in (None, "CPU"),
              f"trap frame writers: rip by {wr.get('trapframe.rip')}, rax by {wr.get('trapframe.rax')}")
    fin = get(f"/api/recordings/{rid}/step?from={cw['handlerStep']}&op=out")["step"]
    vs = get(f"/api/recordings/{rid}/variables?step={fin - 1}")
    vals = {x["name"]: x["value"] for x in vs["vars"]}
    check(vs["function"] == "sys_write" and set(vals) >= {"f", "n", "p"},
          f"sys_write's variables before it returns: {vals}")
    tl = dw["timeline"] or []
    zombie = [m for m in tl if m["object"].startswith(f"proc[{eslot}]") and m["field"] == "state" and m["after"] == "ZOMBIE"]
    check(bool(zombie) and zombie[0]["by"].startswith(f"proc[{eslot}]")
          and [m["step"] for m in tl] == sorted(m["step"] for m in tl),
          f"wait's timeline: {len(tl)} changes in order; proc[{eslot}] became ZOMBIE by {zombie[0]['by'] if zombie else None}")
    other = get(f"/api/recordings/{rid}/regions?step={cw['step']}&slot={me}")
    check(other["program"] == "sh" and int(other["cr3"], 16) != int(state(rid, cw["step"])["registers"]["cr3"], 16),
          f"proc[{me}]'s address space while echo runs: {other['program']}, page table {other['cr3']}")
    tree_w = get(f"/api/recordings/{rid}/nodes?ids={w['id']}")[0]
    check(tree_w["detail"] is not None and tree_w["detail"].startswith("write(1, "),
          f"event tree shows {tree_w['detail']}")
    st0 = state(rid, rec["summary"]["start"])
    check(st0["cpl"] == 3 and st0["location"]["file"].endswith("echo.c"),
          f"opens at step {rec['summary']['start']}: {st0['location']['file']}:{st0['location']['line']} ({st0['location']['label']})")
    f0 = sc[0]
    # (which byte depends on when the recording's mark lands: usually "e")
    check(f0["earlier"] and f0["name"] == "read" and f0["returned"] and f0["text"].startswith("read(0, ")
          and len(f0["output"] or "") == 3 and f0["output"][1] in "echo hi",
          f"first call began before the recording: {f0['text']} = {f0['result']}, kernel wrote {f0['output']}")
    wt = wait[0]["waited"]
    check(wt is not None and wt["reason"] == "sleep" and wt["on"] == f"proc[{me}]"
          and (wt["by"] or "").startswith(f"proc[{eslot}]") and wt["wokenStep"] < wait[0]["resumeStep"],
          f"wait slept on {wt and wt['on']}, woken by {wt and wt['by']} at step {wt and wt['wokenStep']}")
    nm = get(f"/api/recordings/{rid}/names?step={w['start']}")
    check("trapframe.rip" in nm["registers"].get("rsp", ""), f"rsp at the trap's first step: {nm['registers'].get('rsp')}")
    check(all(c["result"] == c["text"].rsplit(", ", 1)[1].rstrip(")") for c in sc if c["name"] == "write" and c["returned"]),
          "every write returned its length")
    # int $64 names its argument registers by the prototype
    ia = get(f"/api/recordings/{rid}/names?step={cw['step'] - 1}")["registers"]
    check("write's argument 1 (int)" in ia.get("rdi", "") and "write's argument 2" in ia.get("rsi", ""),
          f"int $64's arguments: rdi {ia.get('rdi')}, rsi {ia.get('rsi')}")
    # fork set up the child: its context resumes in forkret, its trap frame returns 0
    df = get(f"/api/recordings/{rid}/diff?from={fork[0]['step'] - 1}&to={fork[0]['end']}")
    kid = [p for p in df["procs"] if p["kind"] == "created"]
    kf = {f["field"]: f["after"] for f in kid[0]["fields"]} if kid else {}
    check(any(k.startswith("context.") and "forkret" in v for k, v in kf.items()) and kf.get("trapframe->rax") == "0x0",
          f"fork's child: {[(k, v) for k, v in kf.items() if k.startswith(('context', 'trapframe'))]}")
    # cr3 written: whose page table it was and becomes
    cr = None
    for c in sc[:6]:
        for s_ in range(c["step"], min(c["end"], c["step"] + 3000)):
            d_ = state(rid, s_)["location"]["disas"] or ""
            if "%cr3" in d_ and d_.startswith("mov") and d_.endswith("%cr3"):
                cr = get(f"/api/recordings/{rid}/names?step={s_}")["control"]
                break
        if cr or kernel == "rx64-x86-style":
            break
    if kernel == "rx64-riscv-style":
        check(cr is not None and cr["reg"] == "cr3" and "page table" in cr["change"],
              f"mov to cr3: {cr and cr['before']} -> {cr and cr['after']}: {cr and cr['change']}")

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
    st0 = state(rid, rec["summary"]["start"])
    check(st0["location"]["file"].endswith("lazytouch.c"), f"opens in {st0['location']['file']}:{st0['location']['line']}")
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
        if i == 0:
            # the store that maps the page is named as the entry it writes, and the
            # faulting process's own table can be walked while the kernel runs
            ws = call("GET", f"/api/recordings/{rid}/stores?from={f['start']}&to={f['end']}")
            pte = [w for w in ws if (w["name"] or "").startswith("PT[") and f"(va {va})" in w["name"]]
            check(bool(pte) and "of proc[" in pte[0]["name"],
                  f"fault {i}: the PTE store is named {pte[0]['name'] if pte else None}")
            me_slot = state(rid, f["start"])["slot"]
            own = call("GET", f"/api/recordings/{rid}/pagewalk?step={f['end'] - 1}&va={va}&slot={me_slot}")
            check(own["pa"] is not None and (own["table"] or "").startswith(f"proc[{me_slot}]"),
                  f"fault {i}: walked in {own['table']}'s page table: {va} -> {own['pa']}")
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
        if i == 0:
            pm = get(f"/api/recordings/{rid}/physmap?step={f['start']}")
            guard = [g for g in pre_r["regions"] if g["kind"] == "guard"]
            check(bool(guard) and pm["frames"][int(guard[0]["pa"], 16) >> 12] == "u",
                  f"the guard page's frame counts as this process's ({pm['frames'][int(guard[0]['pa'], 16) >> 12] if guard else None})")
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
        check(pf["handler"] == "vmfault" and (pf["resumesAt"] or "").startswith("main+"),
              f"fault {i}: handled in {pf['handler']}(), resumes at {pf['resumesAt']}")
        lazy = [g for g in pre_r["regions"] if g["kind"] == "unmapped"]
        check(any(int(g["va"], 16) <= addrs[i] < int(g["end"], 16) for g in lazy),
              f"fault {i}: {va} is below p->sz {pre_r['sz']} but not mapped before the fault")
        check(fb["kind"] == "f" and fb["freeIndex"] is not None and fa["kind"] == "u"
              and va in fa["mappedAt"],
              f"fault {i}: frame {frame} free (#{fb['freeIndex']} on the list) before, user page at {fa['mappedAt']} after")
        pb = get(f"/api/recordings/{rid}/physmap?step={f['start']}")
        pa_ = get(f"/api/recordings/{rid}/physmap?step={f['end']}")
        check(pb["freeError"] is None and pa_["freeCount"] < pb["freeCount"]
              and pa_["counts"]["u"] == pb["counts"]["u"] + 1,
              f"fault {i}: free frames {pb['freeCount']} -> {pa_['freeCount']}, user frames "
              f"{pb['counts']['u']} -> {pa_['counts']['u']}")
        dp = get(f"/api/recordings/{rid}/diff?from={pf['step'] - 1}&to={pf['end']}")
        check(len(dp["allocated"]) == 1 and dp["allocated"][0]["pa"] == frame
              and [(m["kind"], m["va"], m["pages"]) for m in dp["mappings"]] == [("mapped", va, 1)],
              f"fault {i}: diff = 1 frame {dp['allocated'][0]['pa'] if dp['allocated'] else None} allocated, "
              f"{[(m['kind'], m['va'], m['label']) for m in dp['mappings']]}")
        if dp["allocated"] and dp["allocated"][0]["step"] is not None:
            ks = state(rid, dp["allocated"][0]["step"])["location"]["symbol"]
            check(ks == "kalloc", f"fault {i}: the frame left the free list in {ks}")
        mem0 = call("GET", f"/api/recordings/{rid}/memory?step={f['start']}&addr={post['pa']}&len=1&space=phys")
        check(int(mem0["data"], 16) != i + 1, f"fault {i}: rewound memory has old value")

    # ---- pipewait: sleep on an empty pipe, wakeup, reparenting ----
    rec = call("POST", f"/api/sessions/{s}/run", {"command": "pipewait"})
    rid = rec["id"]
    sc = get(f"/api/recordings/{rid}/syscalls")
    gr = [c for c in sc if c["name"] == "read" and c["process"].endswith("pipewait") and c["waited"]]
    wt = gr[0]["waited"] if gr else None
    check(wt is not None and wt["reason"] == "sleep" and "pipe" in (wt["on"] or "")
          and "pipewrite" in (wt["by"] or "") and gr[0]["result"] == "5",
          f"pipewait: read slept on {wt and wt['on']}, woken by {wt and wt['by']}, = {gr[0]['result'] if gr else None}")
    if gr:
        gslot = int(gr[0]["process"][5:gr[0]["process"].index("]")])
        pt = get(f"/api/recordings/{rid}/procs?step={wt['step']}")
        g = [p for p in pt["procs"] if p["slot"] == gslot]
        check(bool(g) and g[0]["parentName"] == "init", f"pipewait: the grandchild's parent is {g[0]['parentName'] if g else None}")
    check(all(c["result"] == c["text"].rsplit(", ", 1)[1].rstrip(")") for c in sc if c["name"] == "write" and c["returned"]),
          "pipewait: every write returned its length")
    pz = [c for c in sc if c["name"] == "pause"]
    check(bool(pz) and len([w for w in pz[0]["waits"] if w["reason"] == "sleep"]) >= 1
          and all(w["wokenStep"] >= 0 and w["resumed"] > w["wokenStep"] for w in pz[0]["waits"] if w["reason"] == "sleep"),
          f"pause(2): every sleep listed ({[(w['reason'], w['on']) for w in pz[0]['waits']] if pz else None})")
    n = rec["summary"]["steps"]
    tl = get(f"/api/recordings/{rid}/timeline?from=0&to={n}&buckets=200")
    states = {sp["state"] for sp in tl["spans"]}
    check({"SLEEPING", "RUNNABLE"} <= states and any(w["lane"] != w["by"] for w in tl["wakes"])
          and any(m["kind"] == "cr3" for m in tl["markers"]),
          f"timeline: states between runs {sorted(states)}, {len(tl['wakes'])} wakeups, page-table switches marked")
    ex = [c for c in sc if c["name"] == "exit"]
    check(bool(ex) and all((c["waited"] or {}).get("reason") != "preempted" for c in ex)
          and all(c["pid"] is not None for c in sc),
          f"exit is not a preemption ({[(c['waited'] or {}).get('reason') for c in ex]}); every call has a pid")

    call("DELETE", f"/api/sessions/{s}")


for k in [x["id"] for x in call("GET", "/api/kernels")]:
    kernel_test(k)
finish()
