# gdb 안에서 실행되는 xv6 상태 읽기 모듈. rx64-x86-style, rx64-riscv-style 공통.
#
# Java 백엔드가 gdb를 MI 모드로 띄우고 이 파일을 source 한다. -xv6-* MI 명령은
# 결과를 JSON 문자열 하나로 돌려준다. 64비트 값은 전부 "0x..." 문자열로 보낸다.
#
# 버전마다 다른 부분은 Arch 클래스(X86Style, RiscvStyle)에 모았다.
#   x86-style   커널이 상위 절반에 있고 모든 페이지 테이블에 매핑되어 있다.
#               gdb의 가상 주소 읽기를 그대로 쓴다.
#   riscv-style 커널은 identity 매핑이고 유저 페이지 테이블에는 없다. 유저 CR3로
#               멈춰도 읽을 수 있게 QEMU 물리 메모리 모드로 읽고, KSTACK처럼
#               identity가 아닌 커널 주소는 kernel_pagetable을 직접 따라간다.

import gdb
import json

M64 = (1 << 64) - 1
PGSIZE = 4096
PTE_ADDR = 0x000FFFFFFFFFF000
PHYSTOP = 128 * 1024 * 1024
STATES = ["UNUSED", "USED", "SLEEPING", "RUNNABLE", "RUNNING", "ZOMBIE"]
FTYPES = ["FD_NONE", "FD_PIPE", "FD_INODE", "FD_DEVICE"]
ITYPES = {0: "-", 1: "T_DIR", 2: "T_FILE", 3: "T_DEVICE"}


def ev(expr):
    return gdb.parse_and_eval(expr)


def u(v):
    return int(v) & M64


def hx(x):
    return None if x is None else "0x%x" % x


def has_symbol(name):
    try:
        return gdb.lookup_global_symbol(name) is not None or gdb.lookup_symbol(name)[0] is not None
    except gdb.error:
        return False


def inf():
    return gdb.selected_inferior()


def cstr(v, n=16):
    return v.string(length=n).split("\0")[0]


# ---------------------------------------------------------------- 버전별

class Arch:
    name = None
    flag_bits = [(0, "P"), (1, "W"), (2, "U"), (3, "PWT"), (4, "PCD"),
                 (5, "A"), (6, "D"), (7, "PS"), (63, "NX")]

    def setup(self):
        pass

    def rdk(self, va, n):
        """커널 가상 주소에서 읽기"""
        return inf().read_memory(va, n).tobytes()

    def rdpa(self, pa, n):
        """물리 주소에서 읽기"""
        raise NotImplementedError

    def flags(self, e):
        return [name for bit, name in self.flag_bits if e & (1 << bit)]

    def rd64k(self, va):
        return int.from_bytes(self.rdk(va, 8), "little")

    def table(self, pa):
        b = self.rdpa(pa, PGSIZE)
        return [int.from_bytes(b[i * 8:i * 8 + 8], "little") for i in range(512)]

    def walk_pa(self, root_pa, va):
        pa = root_pa
        for shift in (39, 30, 21, 12):
            e = int.from_bytes(self.rdpa(pa + 8 * ((va >> shift) & 0x1FF), 8), "little")
            if not e & 1:
                return None
            if shift != 12 and e & 0x80:  # 큰 페이지
                size = 1 << shift
                return (e & PTE_ADDR & ~(size - 1)) | (va & (size - 1))
            pa = e & PTE_ADDR
        return pa | (va & 0xFFF)


class X86Style(Arch):
    name = "x86-style"

    def init(self):
        self.KERNBASE = 0xFFFFFFFF80000000

    @property
    def kpt_pa(self):  # main() 시점에는 아직 0이라 매번 읽는다
        return u(ev("(long)kernel_pagetable")) - self.KERNBASE

    def rdpa(self, pa, n):
        return inf().read_memory(pa + self.KERNBASE, n).tobytes()

    def kva2pa(self, va):
        if self.KERNBASE <= va < self.KERNBASE + PHYSTOP:
            return va - self.KERNBASE
        return self.walk_pa(self.kpt_pa, va)

    def pt_pa(self, ptr):
        return ptr - self.KERNBASE if ptr else 0

    def ctx(self, c):
        return u(c["rip"]), u(c["rsp"])

    def cpu_index(self, thread_num):
        for i in range(int(ev("ncpu"))):
            if int(ev("cpus[%d].apicid" % i)) == thread_num - 1:
                return i
        return thread_num - 1

    def is_text(self, a):
        return self.KERNBASE + 0x100000 <= a < ETEXT

    def kernel_regions(self, p):
        kb = self.KERNBASE
        return [
            reg(0xFFFFFFFFFEC00000, 0xFFFFFFFFFEF00000, "DEV2V", "IOAPIC LAPIC"),
            reg(kb + PHYSTOP, kb + PHYSTOP + 2 * PGSIZE * NPROC, "KSTACK", "p->kstack " + hx(u(p["kstack"]))),
            reg(END, kb + PHYSTOP, "kalloc", ""),
            reg(ETEXT, END, ".data .bss", "etext"),
            reg(kb + 0x100000, ETEXT, ".text", "KERNLINK"),
            reg(kb, kb + 0x100000, "0-1MB", "KERNBASE"),
        ]

    def special_user_pages(self):
        return {}

    def user_kind(self, va, e, guard):
        if va == guard:
            return "guard"
        if guard is not None and guard < va <= guard + PGSIZE:
            return "stack"
        if guard is not None and va > guard:
            return "heap"
        return "data" if e & (1 << 63) else "text"


class RiscvStyle(Arch):
    name = "riscv-style"
    flag_bits = Arch.flag_bits[:8] + [(9, "R"), (10, "X"), (63, "NX")]

    def setup(self):
        gdb.execute("maint packet Qqemu.PhyMemMode:1", to_string=True)

    @property
    def kpt_pa(self):  # main() 시점에는 아직 0이라 매번 읽는다
        return u(ev("(long)kernel_pagetable"))

    def init(self):
        self.TRAMPOLINE = (1 << 47) - PGSIZE
        self.TRAPFRAME = self.TRAMPOLINE - PGSIZE
        self.CPUENTRY = self.TRAPFRAME - 2 * PGSIZE
        self.tramp_sym = u(ev("(long)&trampoline"))

    def rdpa(self, pa, n):
        return inf().read_memory(pa, n).tobytes()

    def kva2pa(self, va):
        if va < PHYSTOP:
            return va
        return self.walk_pa(self.kpt_pa, va)

    def rdk(self, va, n):
        if va < PHYSTOP:
            return self.rdpa(va, n)
        out = b""
        while n > 0:
            chunk = min(n, PGSIZE - (va & 0xFFF))
            pa = self.kva2pa(va)
            if pa is None:
                raise gdb.MemoryError("unmapped kernel va 0x%x" % va)
            out += self.rdpa(pa, chunk)
            va += chunk
            n -= chunk
        return out

    def pt_pa(self, ptr):
        return ptr

    def ctx(self, c):
        return u(c["ra"]), u(c["sp"])

    def cpu_index(self, thread_num):
        return thread_num - 1

    def tramp_to_sym(self, pc):
        if self.TRAMPOLINE <= pc < self.TRAMPOLINE + PGSIZE:
            return pc - self.TRAMPOLINE + self.tramp_sym
        return pc

    def is_text(self, a):
        return 0x100000 <= a < ETEXT or self.TRAMPOLINE <= a < self.TRAMPOLINE + PGSIZE

    def kernel_regions(self, p):
        return [
            reg(self.TRAMPOLINE, self.TRAMPOLINE + PGSIZE, "TRAMPOLINE", ""),
            reg(self.TRAPFRAME, self.TRAMPOLINE, "-", ""),
            reg(self.CPUENTRY, self.TRAPFRAME, "CPUENTRY", "GDT TSS IDT"),
            reg(self.CPUENTRY - 2 * PGSIZE * NPROC, self.CPUENTRY, "KSTACK", "p->kstack " + hx(u(p["kstack"]))),
            reg(0xFEC00000, 0xFEF00000, "IOAPIC LAPIC", ""),
            reg(END, PHYSTOP, "kalloc", ""),
            reg(ETEXT, END, ".data .bss", "etext"),
            reg(0x100000, ETEXT, ".text", "KERNBASE"),
        ]

    def special_user_pages(self):
        return {self.TRAMPOLINE: "trampoline", self.TRAPFRAME: "trapframe",
                self.CPUENTRY: "cpuentry", self.CPUENTRY + PGSIZE: "cpuentry"}

    def user_kind(self, va, e, guard):
        sp = self.special_user_pages()
        if va in sp:
            return sp[va]
        if va == guard:
            return "guard"
        if guard is not None and guard < va <= guard + PGSIZE:
            return "stack"
        if guard is not None and va > guard:
            return "heap"
        return "text" if e & (1 << 10) else "data"


def reg(start, end, name, note):
    return {"start": hx(start), "end": hx(end), "name": name, "note": note}


# ---------------------------------------------------------------- 초기화

A = None
_INIT = False


def init():
    global A, _INIT, NPROC, NOFILE, NBUF, PROC_BASE, PROC_SIZE, ETEXT, END
    global TF_SIZE, TF_FIELDS, TF_OFF, SYSCALLS
    if _INIT:
        return
    A = RiscvStyle() if has_symbol("trampoline") else X86Style()
    A.setup()
    NPROC = int(ev("sizeof(proc)/sizeof(proc[0])"))
    NOFILE = int(ev("sizeof(proc[0].ofile)/sizeof(proc[0].ofile[0])"))
    NBUF = int(ev("sizeof(bcache.buf)/sizeof(bcache.buf[0])"))
    PROC_BASE = u(ev("(long)&proc[0]"))
    PROC_SIZE = int(ev("sizeof(struct proc)"))
    ETEXT = u(ev("(long)&etext"))
    END = u(ev("(long)&end"))
    A.init()
    TF_SIZE = int(ev("sizeof(struct trapframe)"))
    TF_FIELDS = sorted(((f.bitpos // 8, f.name) for f in gdb.lookup_type("struct trapframe").fields()))
    TF_OFF = {n: o for o, n in TF_FIELDS}
    SYSCALLS = {}
    n = int(ev("sizeof(syscalls)/sizeof(syscalls[0])"))
    for i in range(n):
        a = u(ev("(long)syscalls[%d]" % i))
        if a:
            s = sym(a) or hx(a)
            SYSCALLS[i] = {"num": i, "handler": s, "addr": a,
                           "name": s[4:] if s.startswith("sys_") else s}
    _INIT = True


def sym(addr):
    if isinstance(A, RiscvStyle):
        addr = A.tramp_to_sym(addr)
    try:
        out = gdb.execute("info symbol 0x%x" % addr, to_string=True).strip()
    except gdb.error:
        return None
    if out.startswith("No symbol"):
        return None
    return out.partition(" in section ")[0].replace(" + ", "+")


def src_of(pc):
    if isinstance(A, RiscvStyle):
        pc = A.tramp_to_sym(pc)
    sal = gdb.find_pc_line(pc)
    if sal and sal.symtab and sal.line:
        return {"file": sal.symtab.filename, "line": sal.line}
    return None


# ---------------------------------------------------------------- 프로세스와 CPU

def proc_index_of(addr):
    if PROC_BASE <= addr < PROC_BASE + NPROC * PROC_SIZE:
        return (addr - PROC_BASE) // PROC_SIZE
    return None


def describe_chan(chan):
    if chan == 0:
        return None
    i = proc_index_of(chan)
    if i is not None:
        return {"addr": hx(chan), "label": "proc[%d]" % i, "kind": "proc",
                "pid": int(ev("proc[%d].pid" % i))}
    s = sym(chan)
    return {"addr": hx(chan), "label": s, "kind": "symbol" if s else "addr"}


def live_procs():
    return [i for i in range(NPROC) if int(ev("proc[%d].state" % i)) != 0]


def proc_by_pid(pid):
    for i in live_procs():
        if int(ev("proc[%d].pid" % i)) == pid:
            return i
    raise gdb.GdbError("pid %d 없음" % pid)


def read_proc(i):
    p = ev("proc[%d]" % i)
    parent = u(p["parent"])
    rip, rsp = A.ctx(p["context"])
    return {
        "slot": i,
        "addr": hx(PROC_BASE + i * PROC_SIZE),
        "pid": int(p["pid"]),
        "name": cstr(p["name"]),
        "state": STATES[int(p["state"])],
        "parent": int(p["parent"]["pid"]) if parent else None,
        "chan": describe_chan(u(p["chan"])),
        "killed": int(p["killed"]),
        "xstate": int(p["xstate"]),
        "sz": int(p["sz"]),
        "pagetable": hx(u(p["pagetable"])),
        "pagetablePa": hx(A.pt_pa(u(p["pagetable"]))),
        "kstack": hx(u(p["kstack"])),
        "trapframe": hx(u(p["trapframe"])),
        "context": {"rip": hx(rip), "ripSym": sym(rip) if rip else None, "rsp": hx(rsp)},
        "ofile": [hx(u(p["ofile"][k])) if u(p["ofile"][k]) else None for k in range(NOFILE)],
        "cwd": hx(u(p["cwd"])) if u(p["cwd"]) else None,
    }


def read_cpus():
    sel = gdb.selected_thread()
    out = []
    try:
        for t in sorted(inf().threads(), key=lambda t: t.num):
            t.switch()
            f = gdb.newest_frame()
            r = {n: u(f.read_register(n)) for n in ("rip", "rsp", "cs", "cr3", "eflags")}
            idx = A.cpu_index(t.num)
            c = ev("cpus[%d]" % idx)
            pp = u(c["proc"])
            user = (r["cs"] & 3) == 3
            out.append({
                "cpu": idx, "thread": t.num,
                "addr": hx(u(ev("(long)&cpus[%d]" % idx))),
                "mode": "user" if user else "kernel",
                "rip": hx(r["rip"]), "rsp": hx(r["rsp"]), "cr3": hx(r["cr3"]),
                "fn": None if user else sym(r["rip"]),
                "src": None if user else src_of(r["rip"]),
                "intr": bool(r["eflags"] & 0x200),
                "noff": int(c["noff"]),
                "proc": hx(pp) if pp else None,
                "pid": int(c["proc"]["pid"]) if pp else None,
                "pname": cstr(c["proc"]["name"]) if pp else None,
            })
    finally:
        sel.switch()
    return out


# ---------------------------------------------------------------- 커널 스택과 trapframe

def tf_fields(kva):
    b = A.rdk(kva, TF_SIZE)
    return [{"off": off, "name": name, "value": hx(int.from_bytes(b[off:off + 8], "little"))}
            for off, name in TF_FIELDS]


def slots(base, top, rsp, labels, prev=None, limit=64, extra=10):
    """[base, top) 영역을 top-8부터 아래로 8바이트 칸으로 읽는다. rsp 아래로 extra칸 더."""
    lo = max(base, min(rsp, top) - 8 * extra, top - 8 * limit)
    data = A.rdk(lo, top - lo)
    out = []
    for a in range(top - 8, lo - 1, -8):
        v = int.from_bytes(data[a - lo:a - lo + 8], "little")
        live = a >= rsp
        out.append({
            "addr": hx(a), "value": hx(v), "field": labels.get(a),
            "sym": sym(v) if A.is_text(v) else None, "live": live,
            "changed": prev is not None and live and prev.get(a) != (v, True),
        })
    return out


def frames_for(i, ksp=None, tsp=None, prev=None):
    """그림으로 그릴 스택 영역.
    x86-style: kstack 하나 (trapframe이 꼭대기에 있다)
    riscv-style: trapframe 페이지의 struct trapframe, 그리고 kstack
    ksp: kstack의 rsp (None이면 context의 rsp). tsp: trapframe 쪽 rsp (riscv-style)."""
    prev = prev or {}
    p = ev("proc[%d]" % i)
    base = u(p["kstack"])
    top = base + PGSIZE
    tf = u(p["trapframe"])
    if ksp is None:
        ksp = A.ctx(p["context"])[1] or top
    labels = {tf + off: name for off, name in TF_FIELDS}
    out = []
    if isinstance(A, X86Style):
        out.append({"name": "kstack", "base": hx(base), "top": hx(top), "rsp": hx(ksp),
                    "tf": hx(tf), "slots": slots(base, top, ksp, labels, prev.get("kstack"))})
    else:
        t_rsp = tf if tsp is None else tsp
        out.append({"name": "trapframe", "base": hx(tf), "top": hx(tf + TF_SIZE),
                    "uva": hx(A.TRAPFRAME), "rsp": hx(tsp) if tsp is not None else None, "tf": hx(tf),
                    "slots": slots(tf, tf + TF_SIZE, t_rsp, labels, prev.get("trapframe"),
                                   limit=TF_SIZE // 8)})
        out.append({"name": "kstack", "base": hx(base), "top": hx(top), "rsp": hx(ksp), "tf": None,
                    "slots": slots(base, top, ksp, {}, prev.get("kstack"))})
    return out


def running_rsp(i, cpus):
    """CPU에서 돌고 있으면 그 CPU의 rsp로 (ksp, tsp)를 정한다."""
    p = ev("proc[%d]" % i)
    base = u(p["kstack"])
    pid = int(p["pid"])
    for c in cpus:
        if c["pid"] != pid:
            continue
        r = int(c["rsp"], 16)
        if c["mode"] == "user":
            return base + PGSIZE, None
        if base <= r <= base + PGSIZE:
            return r, None
        if isinstance(A, RiscvStyle) and A.TRAPFRAME <= r < A.TRAPFRAME + PGSIZE:
            return base + PGSIZE, u(p["trapframe"]) + (r - A.TRAPFRAME)
    return None, None


# ---------------------------------------------------------------- 주소 공간과 페이지 테이블

def ptree(root_pa):
    """페이지 테이블 트리. PML4 0~255번(유저 절반)은 끝까지, 256~511번은 그 항목만."""
    def node(pa, level, va_base):
        t = A.table(pa)
        ents = []
        for idx, e in enumerate(t):
            if not e & 1:
                continue
            va = va_base | (idx << (39 - 9 * level))
            ent = {"idx": idx, "pte": hx(e), "flags": A.flags(e), "next": hx(e & PTE_ADDR)}
            if level == 0 and idx >= 256:
                ent["va"] = hx(va | 0xFFFF000000000000)
            elif level < 3 and not (e & 0x80):
                ent["child"] = node(e & PTE_ADDR, level + 1, va)
            else:
                ent["va"] = hx(va)
            ents.append(ent)
        return {"pa": hx(pa), "level": ["PML4", "PDPT", "PD", "PT"][level], "entries": ents}
    return node(root_pa, 0, 0)


def user_pages(root_pa):
    out = []
    l4 = A.table(root_pa)
    for i4 in range(256):
        if not l4[i4] & 1:
            continue
        l3 = A.table(l4[i4] & PTE_ADDR)
        for i3 in range(512):
            if not l3[i3] & 1:
                continue
            l2 = A.table(l3[i3] & PTE_ADDR)
            for i2 in range(512):
                if not l2[i2] & 1:
                    continue
                l1 = A.table(l2[i2] & PTE_ADDR)
                for i1 in range(512):
                    if l1[i1] & 1:
                        out.append(((i4 << 39) | (i3 << 30) | (i2 << 21) | (i1 << 12), l1[i1]))
    return out


def addrspace(i):
    p = ev("proc[%d]" % i)
    root = A.pt_pa(u(p["pagetable"]))
    pages = user_pages(root)
    special = A.special_user_pages()
    guard = next((va for va, e in pages if not e & 4 and va not in special), None)
    upages = [{"va": hx(va), "pa": hx(e & PTE_ADDR), "flags": A.flags(e),
               "kind": A.user_kind(va, e, guard)} for va, e in pages]
    tfv = {f["name"]: f["value"] for f in tf_fields(u(p["trapframe"]))} if u(p["trapframe"]) else {}
    l4 = A.table(root)
    kl4 = A.table(A.kpt_pa)
    user = {"name": "p->pagetable", "root": hx(root), "pages": upages,
            "marks": {"rip": tfv.get("rip"), "rsp": tfv.get("rsp"), "sz": hx(int(p["sz"]))},
            "regions": [], "shared": []}
    spaces = [user]
    if isinstance(A, X86Style):
        user["regions"] = A.kernel_regions(p)
        user["shared"] = [{"idx": k, "pte": hx(l4[k]), "same": l4[k] == kl4[k]}
                          for k in range(256, 512) if l4[k] & 1]
    else:
        spaces.append({"name": "kernel_pagetable", "root": hx(A.kpt_pa), "pages": [],
                       "regions": A.kernel_regions(p), "marks": {}, "shared": []})
    return {"pid": int(p["pid"]), "name": cstr(p["name"]), "spaces": spaces}


def walk(root_pa, va):
    levels = []
    pa = root_pa
    for lvl, shift in enumerate((39, 30, 21, 12)):
        idx = (va >> shift) & 0x1FF
        e = int.from_bytes(A.rdpa(pa + 8 * idx, 8), "little")
        levels.append({"level": ["PML4", "PDPT", "PD", "PT"][lvl], "table": hx(pa), "idx": idx,
                       "pte": hx(e), "flags": A.flags(e), "next": hx(e & PTE_ADDR),
                       "present": bool(e & 1)})
        if not e & 1:
            return {"va": hx(va), "root": hx(root_pa), "levels": levels, "pa": None}
        pa = e & PTE_ADDR
    return {"va": hx(va), "root": hx(root_pa), "levels": levels, "pa": hx(pa | (va & 0xFFF))}


# ---------------------------------------------------------------- 파일

def read_inode(addr):
    ip = ev("*(struct inode *)0x%x" % addr)
    addrs = [int(ip["addrs"][k]) for k in range(13)]
    return {"addr": hx(addr), "dev": int(ip["dev"]), "inum": int(ip["inum"]),
            "ref": int(ip["ref"]), "valid": int(ip["valid"]),
            "type": ITYPES.get(int(ip["type"]), str(int(ip["type"]))),
            "major": int(ip["major"]), "nlink": int(ip["nlink"]), "size": int(ip["size"]),
            "addrs": [a for a in addrs if a], "locked": int(ip["lock"]["locked"])}


def read_pipe(addr):
    pi = ev("*(struct pipe *)0x%x" % addr)
    nr, nw = int(pi["nread"]), int(pi["nwrite"])
    size = int(ev("sizeof(((struct pipe *)0)->data)"))
    data = A.rdk(addr + int(ev("(long)&((struct pipe *)0)->data")), size)
    buf = bytes(data[(nr + k) % size] for k in range(min(nw - nr, 32)))
    return {"addr": hx(addr), "nread": nr, "nwrite": nw, "size": size,
            "readopen": int(pi["readopen"]), "writeopen": int(pi["writeopen"]),
            "data": buf.decode("latin-1")}


def bcache_blocks():
    out = {}
    for k in range(NBUF):
        b = ev("bcache.buf[%d]" % k)
        if int(b["valid"]):
            out[int(b["blockno"])] = {"buf": k, "refcnt": int(b["refcnt"]),
                                      "addr": hx(u(ev("(long)&bcache.buf[%d]" % k)))}
    return out


def files_of(i):
    p = ev("proc[%d]" % i)
    files, inodes, pipes = {}, {}, {}
    fds = []
    for fd in range(NOFILE):
        fp = u(p["ofile"][fd])
        if not fp:
            continue
        fds.append({"fd": fd, "file": hx(fp)})
        if hx(fp) in files:
            continue
        f = ev("*(struct file *)0x%x" % fp)
        t = FTYPES[int(f["type"])]
        ent = {"addr": hx(fp), "type": t, "ref": int(f["ref"]),
               "readable": int(f["readable"]), "writable": int(f["writable"]),
               "off": int(f["off"]), "major": int(f["major"]),
               "pipe": hx(u(f["pipe"])) if u(f["pipe"]) else None,
               "ip": hx(u(f["ip"])) if u(f["ip"]) else None, "devsw": None}
        if t == "FD_DEVICE":
            ent["devsw"] = sym(u(ev("(long)devsw[%d].write" % ent["major"])))
        files[hx(fp)] = ent
        if ent["ip"] and ent["ip"] not in inodes:
            inodes[ent["ip"]] = read_inode(int(ent["ip"], 16))
        if ent["pipe"] and ent["pipe"] not in pipes:
            pipes[ent["pipe"]] = read_pipe(int(ent["pipe"], 16))
    cwd = u(p["cwd"])
    if cwd and hx(cwd) not in inodes:
        inodes[hx(cwd)] = read_inode(cwd)
    cached = bcache_blocks()
    for ino in inodes.values():
        ino["cached"] = [dict(blockno=b, **cached[b]) for b in ino["addrs"] if b in cached]
    return {"fds": fds, "files": list(files.values()), "inodes": list(inodes.values()),
            "pipes": list(pipes.values()), "cwd": hx(cwd) if cwd else None}


# ---------------------------------------------------------------- 물리 메모리

KINDS = ["unknown", "free", "kernel", "low", "pagetable", "user", "kstack", "trapframe",
         "pipe", "kpagetable"]


def physmem():
    npages = PHYSTOP // PGSIZE
    kind = bytearray(npages)
    owner = [0] * npages

    def setk(pa, k, pid=0, force=False):
        n = pa // PGSIZE
        if 0 <= n < npages and (force or kind[n] == 0):
            kind[n] = k
            owner[n] = pid

    end_pa = A.kva2pa(END)
    for n in range(0x100000 // PGSIZE):
        kind[n] = 3
    for n in range(0x100000 // PGSIZE, (end_pa + PGSIZE - 1) // PGSIZE):
        kind[n] = 2

    r = u(ev("(long)kmem.freelist"))
    count = 0
    while r and count < npages:
        pa = A.kva2pa(r)
        if pa is None:
            break
        setk(pa, 1, force=True)
        r = A.rd64k(r)
        count += 1

    for i in live_procs():
        p = ev("proc[%d]" % i)
        pid = int(p["pid"])
        root = A.pt_pa(u(p["pagetable"]))
        if root:
            stack = [(root, 0)]
            while stack:
                pa, lvl = stack.pop()
                setk(pa, 4, pid)
                t = A.table(pa)
                for idx in (range(256) if lvl == 0 else range(512)):
                    e = t[idx]
                    if not e & 1:
                        continue
                    if lvl < 3 and not e & 0x80:
                        stack.append((e & PTE_ADDR, lvl + 1))
                    elif lvl == 3:
                        setk(e & PTE_ADDR, 5, pid)
        if isinstance(A, RiscvStyle) and u(p["trapframe"]):
            setk(u(p["trapframe"]), 7, pid, force=True)
        ks = A.kva2pa(u(p["kstack"]))
        if ks is not None:
            setk(ks, 6, pid, force=True)

    t = A.table(A.kpt_pa)
    setk(A.kpt_pa, 9, force=True)
    for idx in range(512):
        if t[idx] & 1:
            setk(t[idx] & PTE_ADDR, 9, force=True)

    for k in range(int(ev("sizeof(ftable.file)/sizeof(ftable.file[0])"))):
        f = ev("ftable.file[%d]" % k)
        if int(f["ref"]) and FTYPES[int(f["type"])] == "FD_PIPE":
            pa = A.kva2pa(u(f["pipe"]))
            if pa is not None:
                setk(pa, 8, force=True)

    runs = []
    for n in range(npages):
        if runs and runs[-1][2] == kind[n] and runs[-1][3] == owner[n]:
            runs[-1][1] += 1
        else:
            runs.append([n, 1, kind[n], owner[n]])
    return {"npages": npages, "kinds": KINDS, "runs": runs,
            "counts": {KINDS[k]: kind.count(k) for k in range(len(KINDS))}}


# ---------------------------------------------------------------- 스냅샷

def snapshot(pid=None):
    cpus = read_cpus()
    procs = [read_proc(i) for i in live_procs()]
    out = {"arch": A.name, "ticks": int(ev("ticks")), "nproc": NPROC,
           "cpus": cpus, "procs": procs, "focus": None}
    if pid is not None and any(p["pid"] == int(pid) for p in procs):
        i = proc_by_pid(int(pid))
        ksp, tsp = running_rsp(i, cpus)
        out["focus"] = {
            "pid": int(pid),
            "frames": frames_for(i, ksp, tsp),
            "addrspace": addrspace(i),
            "ptree": ptree(A.pt_pa(u(ev("proc[%d].pagetable" % i)))),
            "files": files_of(i),
        }
    return out


# ---------------------------------------------------------------- 트랩 추적
#
# 시스템 콜 하나를 단계마다 브레이크포인트로 멈춘다. 다른 CPU의 시스템 콜이나
# 같은 스택에 중첩된 인터럽트에는 멈추지 않도록 rsp와 cr3를 비교한다.

class StepBp(gdb.Breakpoint):
    def __init__(self, spec, step):
        super().__init__(spec, gdb.BP_BREAKPOINT, internal=True)
        self.step = step

    def stop(self):
        try:
            return J.check(self.step)
        except Exception as e:
            gdb.write("xv6viz stop error: %s\n" % e, gdb.STDERR)
            return False


def find_insn(start, mnemonic):
    for ins in inf().architecture().disassemble(start, count=60):
        if ins["asm"].startswith(mnemonic):
            return ins["addr"]
    raise gdb.GdbError("%s 없음" % mnemonic)


def x86_steps():
    iretq = find_insn(u(ev("(long)&trapret")), "iretq")
    return [
        ("vector64", "*vector64", None),
        ("alltraps", "*alltraps", lambda j, r: r["rsp"] == j.top - 56),
        ("trap", "*trap", lambda j, r: r["rsp"] == j.tf - 8),
        ("syscall", "*syscall", lambda j, r: j.base <= r["rsp"] < j.tf),
        ("handler", None, lambda j, r: j.base <= r["rsp"] < j.tf),
        ("trapret", "*trapret", lambda j, r: r["rsp"] == j.tf),
        ("iretq", "*0x%x" % iretq, lambda j, r: r["rsp"] == j.top - 40),
        ("user", None, None),
    ]


def riscv_steps():
    T, TF = A.TRAMPOLINE, A.TRAPFRAME
    tv = lambda s: T + u(ev("(long)&%s" % s)) - A.tramp_sym
    iretq = T + find_insn(u(ev("(long)&userret")), "iretq") - A.tramp_sym
    return [
        ("uvec64", "*0x%x" % tv("uvec64"), None),
        ("uservec", "*0x%x" % tv("uservec"), lambda j, r: r["rsp"] == TF + 152 and r["cr3"] == j.upt),
        ("usertrap", "*usertrap", lambda j, r: r["rsp"] == j.top - 8),
        ("syscall", "*syscall", lambda j, r: j.base <= r["rsp"] < j.top),
        ("handler", None, lambda j, r: j.base <= r["rsp"] < j.top),
        ("prepare_return", "*prepare_return", lambda j, r: j.base <= r["rsp"] < j.top),
        ("userret", "*0x%x" % tv("userret"), lambda j, r: j.base <= r["rsp"] <= j.top),
        ("iretq", "*0x%x" % iretq, lambda j, r: r["rsp"] == TF + 168 and r["cr3"] == j.upt),
        ("user", None, None),
    ]


REGS = ["rip", "rsp", "rax", "rdi", "rsi", "rdx", "cs", "cr3", "eflags"]


class Journey:
    def __init__(self):
        self.bps = {}
        self.reset()

    def reset(self):
        self.active = False
        self.steps = []
        self.expect = None
        self.hit = None
        self.pid = self.slot = self.name = None
        self.base = self.top = self.tf = self.upt = None
        self.num = None
        self.retval = None
        self.filter_name = self.filter_sys = None
        self.prev = {}
        self.done = False

    def clear_bps(self):
        for b in self.bps.values():
            try:
                b.delete()
            except Exception:
                pass
        self.bps = {}

    def order(self):
        return [s[0] for s in self.steps]

    def start(self, name, sysname):
        self.clear_bps()
        self.reset()
        self.active = True
        self.filter_name = None if name in ("", "-") else name
        self.filter_sys = None if sysname in ("", "-") else sysname
        self.steps = x86_steps() if isinstance(A, X86Style) else riscv_steps()
        for sname, spec, _ in self.steps:
            if spec:
                self.bps[sname] = StepBp(spec, sname)
        self.expect = self.steps[0][0]
        self.sync_enabled()
        return self.state()

    def sync_enabled(self):
        for k, b in self.bps.items():
            b.enabled = (k == self.expect)

    def cancel(self):
        self.clear_bps()
        self.reset()
        return {"active": False}

    def regs(self):
        f = gdb.selected_frame()
        return {r: u(f.read_register(r)) for r in REGS}

    def check(self, step):
        if not self.active or step != self.expect:
            return False
        r = self.regs()
        if step == self.steps[0][0]:
            return self.first(r)
        cond = {s[0]: s[2] for s in self.steps}[step]
        if cond(self, r):
            self.hit = step
            return True
        return False

    def first(self, r):
        for i in live_procs():
            p = ev("proc[%d]" % i)
            base = u(p["kstack"])
            if isinstance(A, X86Style):
                ok = r["rsp"] == base + PGSIZE - 40
            else:
                ok = r["cr3"] == A.pt_pa(u(p["pagetable"])) and r["rsp"] == A.TRAPFRAME + 168
            if not ok:
                continue
            name = cstr(p["name"])
            sc = SYSCALLS.get(r["rax"])
            if self.filter_name and name != self.filter_name:
                return False
            if self.filter_sys and (not sc or sc["name"] != self.filter_sys):
                return False
            self.slot, self.pid, self.name = i, int(p["pid"]), name
            self.base, self.top = base, base + PGSIZE
            self.tf = u(p["trapframe"])
            self.upt = A.pt_pa(u(p["pagetable"]))
            self.num = r["rax"]
            self.hit = self.steps[0][0]
            return True
        return False

    def advance(self):
        if not self.active:
            return {"action": "none"}
        cur = self.hit
        self.hit = None
        if cur is None:
            return {"action": "continue"}
        if cur == "user" or self.done:
            self.cancel()
            return {"action": "finish"}
        order = self.order()
        nxt = order[order.index(cur) + 1]
        if cur == "syscall":
            sc = SYSCALLS.get(self.num)
            if "handler" in self.bps:
                self.bps.pop("handler").delete()
            if sc:
                self.bps["handler"] = StepBp("*0x%x" % sc["addr"], "handler")
            else:
                nxt = order[order.index("handler") + 1]
        self.expect = nxt
        self.sync_enabled()
        if nxt == "user":
            for b in self.bps.values():
                b.enabled = False
            return {"action": "stepi"}
        return {"action": "continue"}

    def after_stepi(self):
        if self.active and self.expect == "user" and self.regs()["cs"] & 3 == 3:
            self.hit = "user"
            self.done = True
        return self.state()

    def state(self):
        if not self.active:
            return {"active": False}
        out = {"active": True, "arch": A.name, "steps": self.order(),
               "filter": {"name": self.filter_name, "syscall": self.filter_sys},
               "pid": self.pid, "name": self.name}
        if self.hit is None:
            out.update({"phase": "waiting", "expect": self.expect})
            return out
        r = self.regs()
        user = (r["cs"] & 3) == 3
        rsp = r["rsp"]
        ksp, tsp = self.top, None
        if not user and self.base <= rsp <= self.top:
            ksp = rsp
        if isinstance(A, RiscvStyle) and not user and A.TRAPFRAME <= rsp < A.TRAPFRAME + PGSIZE:
            tsp = self.tf + (rsp - A.TRAPFRAME)
        if isinstance(A, X86Style) and user:
            ksp = self.top
        frames = frames_for(self.slot, ksp, tsp, self.prev)
        self.prev = {fr["name"]: {int(s["addr"], 16): (int(s["value"], 16), s["live"]) for s in fr["slots"]}
                     for fr in frames}
        order = self.order()
        idx = order.index(self.hit)
        after_handler = idx > order.index("handler")
        tfv = {f["name"]: f["value"] for f in tf_fields(self.tf)}
        if after_handler:
            v = int(tfv["rax"], 16)
            self.retval = v - (1 << 64) if v >> 63 else v
        early = idx < order.index("syscall") and self.hit not in ("trap", "usertrap")
        args = [hx(r["rdi"]), hx(r["rsi"]), hx(r["rdx"])] if early else [tfv["rdi"], tfv["rsi"], tfv["rdx"]]
        sc = SYSCALLS.get(self.num)
        pt = "user" if r["cr3"] == self.upt else ("kernel" if r["cr3"] == A.kpt_pa else hx(r["cr3"]))
        out.update({
            "phase": "stopped", "step": self.hit, "index": idx,
            "procState": STATES[int(ev("proc[%d].state" % self.slot))],
            "cpu": A.cpu_index(gdb.selected_thread().num),
            "syscall": {"num": self.num, "name": sc["name"] if sc else None,
                        "handler": sc["handler"] if sc else None, "args": args},
            "retval": self.retval if after_handler else None,
            "regs": {k: hx(v) for k, v in r.items()}, "user": user, "pagetable": pt,
            "fn": None if user else sym(r["rip"]),
            "src": None if user else src_of(r["rip"]),
            "frames": frames,
            "final": self.done or (self.hit == "handler" and bool(sc) and sc["name"] == "exit"),
        })
        return out


J = Journey()


# ---------------------------------------------------------------- MI 명령

class Mi(gdb.MICommand):
    def __init__(self, name, fn):
        super().__init__(name)
        self.fn = fn

    def invoke(self, argv):
        try:
            init()
            res = self.fn(*argv)
        except Exception as e:
            res = {"error": "%s: %s" % (type(e).__name__, e)}
        return {"json": json.dumps(res)}


def _walk(pid, va):
    i = proc_by_pid(int(pid))
    return walk(A.pt_pa(u(ev("proc[%d].pagetable" % i))), int(va, 0))


Mi("-xv6-init", lambda: {"arch": A.name})
Mi("-xv6-snapshot", lambda pid=None: snapshot(pid))
Mi("-xv6-walk", _walk)
Mi("-xv6-physmem", physmem)
Mi("-xv6-syscalls", lambda: sorted(({"num": v["num"], "name": v["name"]} for v in SYSCALLS.values()),
                                   key=lambda x: x["num"]))
Mi("-xv6-journey-start", lambda name="-", sysname="-": J.start(name, sysname))
Mi("-xv6-journey-advance", lambda: J.advance())
Mi("-xv6-journey-after-stepi", lambda: J.after_stepi())
Mi("-xv6-journey-state", lambda: J.state())
Mi("-xv6-journey-cancel", lambda: J.cancel())
Mi("-xv6-journey-hit", lambda: {"hit": J.hit})
