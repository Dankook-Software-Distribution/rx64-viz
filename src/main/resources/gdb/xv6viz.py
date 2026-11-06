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
PHYSTOP = 128 * 1024 * 1024  # init()에서 $xv6_phystop (QEMU -m, Vm.java)으로 바꾼다
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

    phys_on = True  # 우리 코드가 메모리를 읽는 방식 (riscv-style만 바뀐다)

    def setup(self):
        pass

    def phys(self, on):
        """riscv-style: gdb 자체 기능(호출 스택, 변수, step)은 가상 주소로 읽어야 해서 끄고,
        우리 코드는 물리 주소로 읽으니 켠다. x86-style은 늘 가상 주소라 할 일이 없다."""

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
        self.phys_on = True

    def phys(self, on):
        if on == self.phys_on:
            return
        gdb.execute("maint packet Qqemu.PhyMemMode:%d" % (1 if on else 0), to_string=True)
        gdb.execute("maint flush register-cache", to_string=True)  # 다른 방식으로 읽어 둔 값 버리기
        self.phys_on = on

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
    global TF_SIZE, TF_FIELDS, TF_OFF, SYSCALLS, PHYSTOP
    if _INIT:
        return
    mem = gdb.convenience_variable("xv6_phystop")
    if mem is not None:
        PHYSTOP = int(mem)
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


SYM_CACHE = {}


def sym(addr):
    """주소의 심볼 이름+오프셋. 커널 이미지는 바뀌지 않으니 주소별로 기억한다."""
    if addr not in SYM_CACHE:
        if len(SYM_CACHE) > 50000:
            SYM_CACHE.clear()
        SYM_CACHE[addr] = sym_lookup(addr)
    return SYM_CACHE[addr]


def sym_lookup(addr):
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
    raise gdb.GdbError("no pid %d" % pid)


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
        "context": {"rip": hx(rip), "ripSym": sym(rip) if rip else None, "rsp": hx(rsp),
                    "rbp": hx(u(p["context"]["rbp"]))},
        "ofile": [hx(u(p["ofile"][k])) if u(p["ofile"][k]) else None for k in range(NOFILE)],
        "cwd": hx(u(p["cwd"])) if u(p["cwd"]) else None,
    }


def read_cpus():
    sel = gdb.selected_thread()
    out = []
    try:
        regs = []
        for t in sorted(inf().threads(), key=lambda t: t.num):
            t.switch()
            f = gdb.newest_frame()
            regs.append((t, {n: u(f.read_register(n)) for n in ("rip", "rsp", "rbp", "cs", "cr3", "eflags", "cr0")}))
        # 메모리는 페이징이 켜진 CPU에서 읽는다. 막 부팅한 AP는 아직 페이징이 없어
        # x86-style의 상위 절반 주소를 읽을 수 없다.
        paging = [t for t, r in regs if r["cr0"] & (1 << 31)]
        (sel if sel in paging or not paging else paging[0]).switch()
        for t, r in regs:
            idx = A.cpu_index(t.num)
            c = ev("cpus[%d]" % idx)
            pp = u(c["proc"])
            user = (r["cs"] & 3) == 3
            out.append({
                "cpu": idx, "thread": t.num,
                "addr": hx(u(ev("(long)&cpus[%d]" % idx))),
                "mode": "user" if user else "kernel",
                "rip": hx(r["rip"]), "rsp": hx(r["rsp"]), "rbp": hx(r["rbp"]), "cr3": hx(r["cr3"]),
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
        if shift != 12 and e & 0x80:  # 큰 페이지: 이 항목이 마지막 단계
            size = 1 << shift
            pa = (e & PTE_ADDR & ~(size - 1)) | (va & (size - 1))
            return {"va": hx(va), "root": hx(root_pa), "levels": levels, "pa": hx(pa)}
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


def files_of(i, bcache=True):
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
    cached = bcache_blocks() if bcache else {}
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

    # kernel_pagetable의 모든 단계 테이블 페이지 (x86-style은 유저 PML4 256~511번과 공유)
    seen = set()
    stack = [(A.kpt_pa, 0)] if A.kpt_pa else []
    while stack:
        pa, lvl = stack.pop()
        if pa in seen:
            continue
        seen.add(pa)
        setk(pa, 9, force=True)
        if lvl < 3:
            for e in A.table(pa):
                if e & 1 and not e & 0x80:
                    stack.append((e & PTE_ADDR, lvl + 1))

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


# ---------------------------------------------------------------- 트랩 단계
#
# 시스템 콜 하나가 지나가는 단계: (이름, 브레이크포인트 위치, 조건).
# 조건은 정확한 rsp(riscv-style은 cr3도)로, 다른 CPU의 시스템 콜이나 같은 스택에
# 겹친 인터럽트를 거른다. j에는 그 프로세스의 base, top(kstack), tf, upt(유저 페이지 테이블).

def find_insn(start, mnemonic):
    for ins in inf().architecture().disassemble(start, count=60):
        if ins["asm"].startswith(mnemonic):
            return ins["addr"]
    raise gdb.GdbError("no %s" % mnemonic)


def x86_steps():
    iretq = find_insn(u(ev("(long)&trapret")), "iretq")
    return [
        ("vector64", "*vector64", lambda j, r: r["rsp"] == j.top - 40),
        ("alltraps", "*alltraps", lambda j, r: r["rsp"] == j.top - 56),
        ("trap", "*trap", lambda j, r: r["rsp"] == j.tf - 8),
        ("syscall", None, lambda j, r: j.base <= r["rsp"] < j.tf),
        ("handler", None, lambda j, r: j.base <= r["rsp"] < j.tf),
        ("trapret", "*trapret", lambda j, r: r["rsp"] == j.tf),
        ("iretq", "*0x%x" % iretq, lambda j, r: r["rsp"] == j.top - 40),
    ]


def riscv_steps():
    T, TF = A.TRAMPOLINE, A.TRAPFRAME
    tv = lambda s: T + u(ev("(long)&%s" % s)) - A.tramp_sym
    iretq = T + find_insn(u(ev("(long)&userret")), "iretq") - A.tramp_sym
    return [
        ("uvec64", "*0x%x" % tv("uvec64"), lambda j, r: r["cr3"] == j.upt and r["rsp"] == TF + 168),
        ("uservec", "*0x%x" % tv("uservec"), lambda j, r: r["rsp"] == TF + 152 and r["cr3"] == j.upt),
        ("usertrap", "*usertrap", lambda j, r: r["rsp"] == j.top - 8),
        ("syscall", None, lambda j, r: j.base <= r["rsp"] < j.top),
        ("handler", None, lambda j, r: j.base <= r["rsp"] < j.top),
        ("prepare_return", "*prepare_return", lambda j, r: j.base <= r["rsp"] < j.top),
        ("userret", "*0x%x" % tv("userret"), lambda j, r: j.base <= r["rsp"] <= j.top),
        ("iretq", "*0x%x" % iretq, lambda j, r: r["rsp"] == TF + 168 and r["cr3"] == j.upt),
    ]


REGS = ["rip", "rsp", "rbp", "rax", "rdi", "rsi", "rdx", "cs", "cr3", "eflags"]


class Trap:
    """한 프로세스가 지금 지나가는 시스템 콜 하나의 단계 기록"""
    def __init__(self, slot, base, tf, upt):
        self.slot, self.base, self.top, self.tf, self.upt = slot, base, base + PGSIZE, tf, upt
        self.k = 0          # 다음에 기다리는 단계 번호
        self.stages = []
        self.prev = {}


def stage_view(j, step):
    """지금 멈춘 CPU에서 본 단계 하나: 레지스터, 스택 영역, 소스, 그때의 페이지 테이블"""
    f = gdb.selected_frame()
    r = {n: u(f.read_register(n)) for n in REGS}
    user = (r["cs"] & 3) == 3
    rsp = r["rsp"]
    ksp, tsp = j.top, None
    if not user and j.base <= rsp <= j.top:
        ksp = rsp
    if isinstance(A, RiscvStyle) and not user and A.TRAPFRAME <= rsp < A.TRAPFRAME + PGSIZE:
        tsp = j.tf + (rsp - A.TRAPFRAME)
    frames = frames_for(j.slot, ksp, tsp, j.prev)
    j.prev = {fr["name"]: {int(s["addr"], 16): (int(s["value"], 16), s["live"]) for s in fr["slots"]}
              for fr in frames}
    pt = "user" if r["cr3"] == j.upt else ("kernel" if r["cr3"] == A.kpt_pa else hx(r["cr3"]))
    return {"step": step, "regs": {k: hx(v) for k, v in r.items()}, "user": user, "pagetable": pt,
            "cpu": A.cpu_index(gdb.selected_thread().num),
            "fn": None if user else sym(r["rip"]), "src": None if user else src_of(r["rip"]),
            "frames": frames}


# ---------------------------------------------------------------- 명령 기록
#
# syscall() 입구와 ret에 멈추지 않는 브레이크포인트를 걸어, 시스템 콜마다
# pid, 이름, 인자, 반환값을 남긴다. 인자는 시그니처에 따라 풀어 쓴다.
#   i 정수  s 문자열  b 쓸 버퍼(길이는 셋째 인자)  B 읽은 버퍼(반환값만큼)  v argv  o open 모드  p 포인터

SIG = {"fork": "", "exit": "i", "wait": "p", "pipe": "p", "read": "iBi", "write": "ibi", "close": "i",
       "kill": "i", "exec": "sv", "open": "so", "mknod": "sii", "unlink": "s", "fstat": "ip", "link": "ss",
       "mkdir": "s", "chdir": "s", "dup": "i", "getpid": "", "sbrk": "i", "sleep": "i", "pause": "i",
       "uptime": ""}
OPEN_FLAGS = [(0x001, "O_WRONLY"), (0x002, "O_RDWR"), (0x200, "O_CREATE"), (0x400, "O_TRUNC")]
REC_MAX = 3000
STAGED_MAX = 400   # 단계와 직후 상태까지 남기는 시스템 콜 수
BUF_MAX = 32


def s32(v):
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >> 31 else v


def s64(v):
    return v - (1 << 64) if v >> 63 else v


def q(b):
    return json.dumps(b.decode("latin-1"))


class RecBp(gdb.Breakpoint):
    def __init__(self, spec, fn):
        super().__init__(spec, gdb.BP_BREAKPOINT, internal=True)
        self.fn = fn

    def stop(self):
        if not A.phys_on:  # Debug step 중(riscv-style)에는 우리 방식으로 읽을 수 없어 쉰다
            return False
        try:
            self.fn()
        except Exception as e:
            gdb.write("xv6viz record error: %s\n" % e, gdb.STDERR)
        return False


class Recorder:
    def __init__(self):
        self.bps = []
        self.active = False
        self.events = []
        self.pending = {}
        self.details = {}
        self.order = []

    def setup(self):
        off = lambda t, f: int(ev("(long)&((%s *)0)->%s" % (t, f)))
        self.off = {f: off("struct proc", f) for f in ("pid", "name", "trapframe", "pagetable", "kstack")}
        self.cpu_base = u(ev("(long)&cpus[0]"))
        self.cpu_size = int(ev("sizeof(struct cpu)"))
        self.cpu_proc = off("struct cpu", "proc")
        self.tidx = {t.num: A.cpu_index(t.num) for t in inf().threads()}
        self.src = {n: src_of(sc["addr"]) for n, sc in SYSCALLS.items()}
        steps = x86_steps() if isinstance(A, X86Style) else riscv_steps()
        self.order = [n for n, _, _ in steps]
        self.cond = {n: c for n, _, c in steps}
        self.spec = {n: sp for n, sp, _ in steps if sp}

    def start(self):
        self.stop()
        self.setup()
        self.events, self.pending, self.seq = [], {}, 0
        self.details, self.traps, self.nstaged = {}, {}, 0
        self.active, self.done = True, False
        # 이미 시스템 콜 안에서 자고 있는 프로세스(셸의 read 등)는 입구를 놓쳤으니
        # trapframe에 남아 있는 번호와 인자로 미리 채운다
        for i in live_procs():
            if STATES[int(ev("proc[%d].state" % i))] == "SLEEPING":
                self.enter(self.info(PROC_BASE + i * PROC_SIZE), early=True)
        self.bps.append(RecBp("*syscall", self.enter))
        blk = func_block(u(ev("(long)&syscall")))
        for ins in inf().architecture().disassemble(blk.start, blk.end - 1):
            if ins["asm"].startswith("ret"):
                self.bps.append(RecBp("*0x%x" % ins["addr"], self.leave))
        for step, spec in self.spec.items():
            self.bps.append(RecBp(spec, lambda s=step: self.stage(s)))
        # sys_exit 입구에는 걸지 않는다. 걸면 exit가 유저로 돌아가 반복되다가 페이지 폴트로
        # 죽는다(두 버전 모두 재현). 돌아오지 않는 함수 입구의 브레이크포인트를 gdb가
        # 넘어가는 과정에서 생기는 것으로 보인다. exit의 단계는 syscall까지만 남는다.
        for addr in sorted({sc["addr"] for sc in SYSCALLS.values() if sc["name"] != "exit"}):
            self.bps.append(RecBp("*0x%x" % addr, lambda: self.stage("handler")))
        return {"active": True}

    def stop(self):
        for b in self.bps:
            try:
                b.delete()
            except Exception:
                pass
        self.bps = []
        self.active = False
        return {"active": False}

    def cur_p(self):
        i = self.tidx.get(gdb.selected_thread().num)
        return None if i is None else A.rd64k(self.cpu_base + i * self.cpu_size + self.cpu_proc)

    def regs2(self):
        f = gdb.selected_frame()
        return {"rsp": u(f.read_register("rsp")), "cr3": u(f.read_register("cr3"))}

    # 트랩 단계 브레이크포인트. 첫 단계에서 그 프로세스의 Trap을 만들고, 그 뒤로는
    # 기다리던 단계이고 조건이 맞을 때만 찍는다.
    def stage(self, step):
        p = self.cur_p()
        if not p:
            return
        if step == self.order[0]:
            if self.nstaged >= STAGED_MAX:
                return
            j = Trap(proc_index_of(p), A.rd64k(p + self.off["kstack"]), A.rd64k(p + self.off["trapframe"]),
                     A.pt_pa(A.rd64k(p + self.off["pagetable"])))
            if not self.cond[step](j, self.regs2()):
                return
            j.stages.append(stage_view(j, step))
            j.k = 1
            self.traps[p] = j
            self.nstaged += 1
            return
        j = self.traps.get(p)
        if not j or j.k >= len(self.order) or self.order[j.k] != step:
            return
        if not self.cond[step](j, self.regs2()):
            return
        j.stages.append(stage_view(j, step))
        j.k += 1
        if j.k >= len(self.order):
            del self.traps[p]

    # 시스템 콜 직후(또는 exit 직전) 그 프로세스: proc, 파일 테이블, 페이지 테이블 요약
    def after(self, p):
        slot = proc_index_of(p)
        root = A.pt_pa(A.rd64k(p + self.off["pagetable"]))
        out = {"proc": read_proc(slot), "files": files_of(slot, bcache=False), "pt": None}
        if root:
            l4 = A.table(root)
            out["pt"] = {"root": hx(root), "pages": len(user_pages(root)),
                         "pml4": [k for k in range(512) if l4[k] & 1]}
        return out

    def detail_of(self, i):
        d = self.details.get(i)
        if d is None:
            raise gdb.GdbError("no trace entry %d" % i)
        return {"i": i, "steps": self.order, "stages": d["trap"].stages if d["trap"] else [], "after": d["after"]}

    # 지금 이 CPU의 myproc()
    def cur(self):
        return self.info(self.cur_p())

    def info(self, p):
        if not p:
            return None
        pid = int.from_bytes(A.rdk(p + self.off["pid"], 4), "little", signed=True)
        name = A.rdk(p + self.off["name"], 16).split(b"\0")[0].decode("latin-1")
        tfb = A.rdk(A.rd64k(p + self.off["trapframe"]), TF_SIZE)
        reg = lambda n: int.from_bytes(tfb[TF_OFF[n]:TF_OFF[n] + 8], "little")
        return p, pid, name, reg, A.pt_pa(A.rd64k(p + self.off["pagetable"]))

    # 유저 주소 읽기: 그 프로세스의 페이지 테이블을 따라간다 (두 버전 공통)
    def uread(self, pt, va, n):
        out = b""
        while n > 0:
            pa = A.walk_pa(pt, va)
            if pa is None:
                break
            k = min(n, PGSIZE - (va & 0xFFF))
            out += A.rdpa(pa, k)
            va += k
            n -= k
        return out

    def ustr(self, pt, va, n=64):
        return self.uread(pt, va, n).split(b"\0")[0]

    def decode(self, sname, raw, pt, e):
        sig = SIG.get(sname)
        if sig is None:
            return [hx(x) for x in raw]
        out = []
        for k, v in zip(sig, raw):
            if k == "i":
                out.append(str(s32(v)))
            elif k == "s":
                out.append(q(self.ustr(pt, v)))
            elif k == "b":
                n = max(0, s32(raw[2]))
                b = self.uread(pt, v, min(n, BUF_MAX))
                e["buf"], e["more"] = b.decode("latin-1"), n > BUF_MAX
                out.append(q(b) + ("…" if n > BUF_MAX else ""))
            elif k == "B":
                out.append(hx(v))
            elif k == "v":
                av = []
                for j in range(8):
                    a = int.from_bytes(self.uread(pt, v + 8 * j, 8), "little")
                    if not a:
                        break
                    av.append(self.ustr(pt, a).decode("latin-1"))
                out.append(json.dumps(av))
            elif k == "o":
                m = s32(v)
                names = [n for bit, n in OPEN_FLAGS if m & bit] or ["O_RDONLY"]
                out.append("|".join(names))
            else:
                out.append(hx(v))
        return out

    def push(self, e):
        e["i"] = self.seq
        self.seq += 1
        j, after = e.pop("_trap", None), e.pop("_after", None)
        if j or after:
            self.details[e["i"]] = {"trap": j, "after": after}
        e["detail"] = bool(j or after)
        self.events.append(e)
        if len(self.events) >= REC_MAX:
            self.done = True

    def enter(self, c=None, early=False):
        if not early:
            c = self.cur()
        if not c:
            return
        p, pid, name, reg, pt = c
        num = reg("rax")
        sc = SYSCALLS.get(num)
        sname = sc["name"] if sc else "#%d" % num
        raw = [reg("rdi"), reg("rsi"), reg("rdx")]
        e = {"pid": pid, "name": name, "sys": sname, "ret": None, "src": self.src.get(num),
             "handler": sc["handler"] if sc else None}
        e["args"] = self.decode(sname, raw, pt, e)
        if early:
            e["early"] = True  # 기록을 시작하기 전에 들어간 시스템 콜
        else:
            j = self.traps.get(p)
            if j and j.k < len(self.order) and self.order[j.k] == "syscall" and self.cond["syscall"](j, self.regs2()):
                j.stages.append(stage_view(j, "syscall"))
                j.k += 1
                e["_trap"] = j
        if sname == "exit":
            if e.get("_trap"):
                e["_after"] = self.after(p)
            self.push(e)
            return
        self.pending[p] = (e, raw, pt)

    def leave(self):
        c = self.cur()
        if not c:
            return
        p, pid, name, reg, pt = c
        x = self.pending.pop(p, None)
        if not x:
            return
        e, raw, ept = x
        e["ret"] = s64(reg("rax"))
        if e["sys"] == "read" and e["ret"] > 0:
            b = self.uread(ept, raw[1], min(e["ret"], BUF_MAX))
            e["buf"], e["more"] = b.decode("latin-1"), e["ret"] > BUF_MAX
            e["args"][1] = q(b) + ("…" if e["more"] else "")
        if e["sys"] == "exec" and name != e["name"]:
            e["exec"] = name
        if e.get("_trap"):
            e["_after"] = self.after(p)
        self.push(e)

    def clear(self):
        self.events, self.details = [], {}
        self.nstaged = 0  # 단계까지 남기는 한도도 다시 센다
        return {"active": self.active}

    def poll(self, since):
        # 끌 때까지 남긴다. REC_MAX가 차면 done
        return {"active": self.active, "done": self.done, "events": self.events[since:],
                "pending": [{k: v for k, v in e.items() if not k.startswith("_")} for e, _, _ in self.pending.values()]}


R = Recorder()


# ---------------------------------------------------------------- Debug
#
# 커널을 실제로 멈추고 한 줄씩. 호출 스택과 변수는 gdb의 프레임으로 읽는다(riscv-style은
# 그동안 물리 주소 모드를 끈다). Step은 Java가 -exec-next/-exec-step/-exec-finish로 한다.

def fmt(v):
    """변수 값 한 줄"""
    try:
        t = v.type.strip_typedefs()
        if t.code == gdb.TYPE_CODE_STRUCT:
            parts = []
            for f in t.fields()[:6]:
                try:
                    parts.append("%s = %s" % (f.name, fmt(v[f.name]) if v[f.name].type.strip_typedefs().code != gdb.TYPE_CODE_STRUCT else "{…}"))
                except gdb.error:
                    parts.append("%s = ?" % f.name)
            return "{" + ", ".join(parts) + (", …" if len(t.fields()) > 6 else "") + "}"
        if t.code == gdb.TYPE_CODE_INT and t.sizeof >= 4 and not str(t).startswith("char"):
            n = int(v)
            if -1024 < n < 1024 or n < 0:
                return str(n)
            return "0x%x" % n if n >> 32 else "%d (0x%x)" % (n, n)
        out = str(v)
    except (gdb.error, gdb.MemoryError, RuntimeError) as e:
        return "<%s>" % e
    return out if len(out) <= 160 else out[:160] + "…"


def func_block(pc):
    """pc가 들어 있는 함수의 gdb 블록 (디버그 정보가 없으면 None)"""
    try:
        b = gdb.block_for_pc(pc)
    except RuntimeError:
        return None
    while b is not None and b.function is None:
        b = b.superblock
    return b


def frame_symbols(f):
    """프레임에서 보이는 인자와 지역 변수 (안쪽 블록부터, 같은 이름은 한 번)"""
    seen = set()
    try:
        b = f.block()
    except RuntimeError:
        return
    while b is not None:
        for sy in b:
            if (sy.is_argument or sy.is_variable) and sy.name not in seen:
                seen.add(sy.name)
                yield sy
        if b.function is not None:
            break
        b = b.superblock


def frame_vars(f):
    out = []
    for sy in frame_symbols(f):
        try:
            val = fmt(sy.value(f))
        except (gdb.error, gdb.MemoryError, RuntimeError) as e:
            val = "<%s>" % e
        out.append({"name": sy.name, "arg": sy.is_argument, "value": val})
    return sorted(out, key=lambda x: not x["arg"])


def mem_vars(f):
    """스택(메모리)에 있는 이 프레임의 변수: 이름과 주소. 레지스터에만 있는 변수는 뺀다."""
    out = []
    for sy in frame_symbols(f):
        try:
            v = sy.value(f)
            if not v.is_optimized_out and v.address is not None:
                out.append({"name": sy.name, "addr": hx(u(v.address)), "size": v.type.sizeof})
        except (gdb.error, gdb.MemoryError, RuntimeError):
            pass
    return out


ASM_MAX = 320


def disasm(pc, older=False):
    """pc가 있는 함수의 명령어. C 줄이 바뀌는 곳마다 {file, line} 머리를 넣는다(줄 내용은 브라우저가 읽는다).
    older: 호출한 쪽 프레임이면 pc는 복귀 주소이므로 그 앞(call)을 현재 명령어로 본다.
    riscv-style 트램펄린은 실행 주소와 심볼 주소가 달라 delta만큼 옮겨서 찾는다."""
    key = pc - 1 if older else pc
    s_pc = A.tramp_to_sym(key) if isinstance(A, RiscvStyle) else key
    delta = key - s_pc
    b = func_block(s_pc)
    name, lo, hi = (b.function.name, b.start, b.end) if b is not None else (None, None, None)
    if lo is None:
        # 어셈블리(.S) 함수: 블록이 없으니 심볼 시작부터 다음 심볼 전까지
        nm = sym(key)
        if nm is None:
            return None
        name, _, off = nm.partition("+")
        lo = s_pc - (int(off) if off else 0)
        hi = None
    arch = gdb.selected_frame().architecture()
    if hi is not None:
        insns = arch.disassemble(lo + delta, hi + delta - 1, ASM_MAX * 2)
    else:
        insns = []
        for x in arch.disassemble(lo + delta, count=ASM_MAX):
            if insns and (sym(x["addr"]) or "").partition("+")[0] != name:
                break
            insns.append(x)
    cur = next((i for i, x in enumerate(insns) if x["addr"] <= key < x["addr"] + x["length"]), 0)
    a0 = max(0, cur - ASM_MAX // 2)
    rows, last = [], None
    for x in insns[a0:a0 + ASM_MAX]:
        at = src_of(x["addr"])
        if at and at != last:
            rows.append(at)
        last = at or last
        rows.append({"addr": hx(x["addr"]), "off": x["addr"] - delta - lo, "asm": x["asm"]})
    return {"fn": name, "pc": hx(insns[cur]["addr"]) if insns else hx(pc), "rows": rows}


def debug_state(level=0):
    """지금 고른 CPU(스레드)가 멈춘 곳: 호출 스택, level번 프레임의 변수, 그 CPU의 프로세스"""
    level = int(level)
    th = gdb.selected_thread()
    frames, chosen = [], None
    A.phys(False)
    try:
        f = gdb.newest_frame()
        i = 0
        while f is not None and i < 24:
            pc = u(f.pc())
            sal = f.find_sal()
            name = f.name() or sym(pc)
            # 커널 코드가 아닌 곳(trapframe의 값, 스택 바닥)을 프레임으로 읽은 것은 끊는다
            if i and name is None:
                break
            try:
                sp = hx(u(f.read_register("rsp")))
            except gdb.error:
                sp = None
            frames.append({"level": i, "fn": name, "pc": hx(pc), "sp": sp, "vars": mem_vars(f),
                           "file": sal.symtab.filename if sal.symtab else None, "line": sal.line or None})
            if i == level:
                chosen = f
            try:
                f = f.older()
            except gdb.error:
                break
            i += 1
        vars_ = frame_vars(chosen) if chosen is not None else []
        try:
            asm = disasm(u(chosen.pc()), older=level > 0) if chosen is not None else None
        except (gdb.error, gdb.MemoryError, RuntimeError):
            asm = None
        rip = u(gdb.newest_frame().read_register("rip"))
        cs = u(gdb.newest_frame().read_register("cs"))
    finally:
        A.phys(True)
    cpu = A.cpu_index(th.num)
    p = u(ev("(long)cpus[%d].proc" % cpu))
    pid = name = None
    if p:
        pr = ev("*(struct proc *)0x%x" % p)
        pid, name = int(pr["pid"]), cstr(pr["name"])
    return {"cpu": cpu, "thread": th.num, "pid": pid, "name": name, "user": (cs & 3) == 3, "rip": hx(rip),
            "frames": frames, "level": min(level, max(0, len(frames) - 1)), "locals": vars_, "asm": asm}


def debug_thread(cpu):
    for t in inf().threads():
        if A.cpu_index(t.num) == int(cpu):
            t.switch()
            return debug_state(0)
    raise gdb.GdbError("no cpu %s" % cpu)


def step_prep():
    """Step 직전: riscv-style은 물리 주소 모드를 끈다 (다음 -xv6-* 명령이 다시 켠다)"""
    A.phys(False)
    return {"ok": True}


class Mi(gdb.MICommand):
    def __init__(self, name, fn):
        super().__init__(name)
        self.fn = fn

    def invoke(self, argv):
        # 실패는 MI ^error로 돌려준다. Java 쪽 GdbMi.call()이 예외로 바꾼다.
        try:
            init()
            A.phys(True)  # step-prep은 바로 다시 끈다
            res = self.fn(*argv)
        except gdb.GdbError:
            raise
        except Exception as e:
            raise gdb.GdbError("%s: %s" % (type(e).__name__, e))
        return {"json": json.dumps(res)}


STRUCTS = ["proc", "cpu", "context", "trapframe", "file", "inode", "pipe", "buf"]


def structs():
    """구조체가 정의된 소스 위치. 화면에서 필드 위에 마우스를 올리면 선언 줄을 보여 준다."""
    out = {}
    for name in STRUCTS:
        try:
            txt = gdb.execute("info types ^%s$" % name, to_string=True)
        except gdb.error:
            continue
        f = None
        for ln in txt.splitlines():
            if ln.startswith("File ") and ln.endswith(":"):
                f = ln[5:-1]
            elif f and ln.strip().endswith("struct %s;" % name):
                out[name] = {"file": f, "line": int(ln.split(":")[0])}
                break
    return out


def _walk(pid, va, which="user"):
    """which: user = 그 프로세스의 페이지 테이블, kernel = kernel_pagetable"""
    if which == "kernel":
        return walk(A.kpt_pa, int(va, 0))
    i = proc_by_pid(int(pid))
    return walk(A.pt_pa(u(ev("proc[%d].pagetable" % i))), int(va, 0))


Mi("-xv6-init", lambda: {"arch": A.name})
Mi("-xv6-snapshot", lambda pid=None: snapshot(pid))
Mi("-xv6-walk", _walk)
Mi("-xv6-physmem", physmem)
Mi("-xv6-structs", structs)
Mi("-xv6-record-start", lambda: R.start())
Mi("-xv6-record-poll", lambda since="0": R.poll(int(since)))
Mi("-xv6-record-stop", lambda: R.stop())
Mi("-xv6-record-clear", lambda: R.clear())
Mi("-xv6-debug-state", lambda level="0": debug_state(level))
Mi("-xv6-debug-thread", debug_thread)
Mi("-xv6-step-prep", step_prep)
Mi("-xv6-record-detail", lambda i: R.detail_of(int(i)))
