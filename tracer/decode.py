#!/usr/bin/env python3
"""Summarize an rxtrace trace (see tracer/plugin/rxtrace.c for the format).

usage: decode.py PREFIX KERNEL_ELF [--files NNN ...]

PREFIX is the plugin's out= prefix: PREFIX.NNN.zst holds one segment
each (000 = boot).
--files picks segment files to read (default: all).

Prints, per segment: record counts and bytes, user/kernel instruction
split, and the kernel functions that executed the most instructions.
"""
import argparse
import bisect
import collections
import glob
import struct
import subprocess
import sys


class Chain:
    """Read several zstd files one after another as one stream."""

    def __init__(self, paths):
        self.paths = list(paths)
        self.cur = None

    def read(self, n):
        while True:
            if self.cur is None:
                if not self.paths:
                    return b""
                p = subprocess.Popen(["zstd", "-dcq", self.paths.pop(0)],
                                     stdout=subprocess.PIPE, bufsize=1 << 20)
                self.cur = p.stdout
            b = self.cur.read(n)
            if b:
                return b
            self.cur = None


def trace_files(prefix, only):
    segs = sorted(glob.glob(prefix + ".[0-9][0-9][0-9].zst"))
    if only:
        segs = [p for p in segs if p[-7:-4] in only]
    return segs


def load_symbols(elf):
    out = subprocess.run(["nm", "-n", elf], capture_output=True, text=True).stdout
    addrs, names = [], []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] in "tTwW":
            addrs.append(int(parts[0], 16))
            names.append(parts[2])
    return addrs, names


class Reader:
    def __init__(self, f):
        self.f = f
        self.buf = b""
        self.pos = 0

    def need(self, n):
        if len(self.buf) - self.pos < n:
            self.buf = self.buf[self.pos:] + self.f.read(max(n, 1 << 20))
            self.pos = 0
            if len(self.buf) < n:
                raise EOFError
        b = self.buf[self.pos:self.pos + n]
        self.pos += n
        return b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("kernel_elf")
    ap.add_argument("--files", nargs="*", help="segment numbers, e.g. 002")
    ap.add_argument("--top", type=int, default=12)
    ap.add_argument("--discon", action="store_true",
                    help="print every interrupt/exception with its target")
    a = ap.parse_args()

    sym_addr, sym_name = load_symbols(a.kernel_elf)

    def symbol(pc):
        if pc < 0x100000:
            return "(firmware or real-mode code)"
        if sym_addr and pc < sym_addr[0]:
            # x86-style runs its first code at the physical load address,
            # below the higher-half link address (KERNBASE + pa).
            pc += 0xFFFFFFFF80000000
        i = bisect.bisect_right(sym_addr, pc) - 1
        return sym_name[i] if i >= 0 else "?"

    r = Reader(Chain(trace_files(a.prefix, a.files)))
    regnames = []
    insn_pc = {}            # id -> vaddr
    insn_fn = {}            # id -> kernel symbol (lazily)
    cs_idx = None
    cpl = 0

    seg = "(before the first mark)"
    stats = collections.OrderedDict()

    def new_stats():
        return {"recs": collections.Counter(), "bytes": collections.Counter(),
                "user": 0, "kernel": 0, "fn": collections.Counter()}

    st = stats.setdefault(seg, new_stats())

    try:
        while True:
            t = r.need(1)
            if t == b"R":
                n = r.need(1)[0]
                for _ in range(n):
                    ln = r.need(1)[0]
                    regnames.append(r.need(ln).decode())
                cs_idx = regnames.index("cs") if "cs" in regnames else None
                size = 2 + sum(1 + len(x) for x in regnames)
            elif t == b"I":
                iid, va, sz = struct.unpack("<IQB", r.need(13))
                r.need(sz)
                (dl,) = struct.unpack("<H", r.need(2))
                r.need(dl)
                insn_pc[iid] = va
                size = 1 + 13 + sz + 2 + dl
            elif t == b"X":
                vcpu, iid = struct.unpack("<BI", r.need(5))
                size = 6
                if regnames:
                    (mask,) = struct.unpack("<I", r.need(4))
                    nset = bin(mask).count("1")
                    vals = r.need(8 * nset)
                    size += 4 + 8 * nset
                    if cs_idx is not None and mask & (1 << cs_idx):
                        k = bin(mask & ((1 << cs_idx) - 1)).count("1")
                        cpl = struct.unpack_from("<Q", vals, 8 * k)[0] & 3
                if cpl == 3:
                    st["user"] += 1
                else:
                    st["kernel"] += 1
                    fn = insn_fn.get(iid)
                    if fn is None:
                        fn = insn_fn[iid] = symbol(insn_pc.get(iid, 0))
                    st["fn"][fn] += 1
            elif t == b"W":
                vcpu, flags, sz = struct.unpack("<BBB", r.need(3))
                r.need(16 + sz)
                size = 4 + 16 + sz
            elif t == b"D":
                vcpu, typ, frm, to = struct.unpack("<BBQQ", r.need(18))
                size = 19
                if a.discon:
                    kind = {1: "interrupt", 2: "exception"}.get(typ, typ)
                    print(f"   [{seg}] {kind:9} {frm:#x} -> {symbol(to)}")
            elif t == b"M":
                at, ln = struct.unpack("<QH", r.need(10))
                name = r.need(ln).decode()
                size = 11 + ln
                seg = name
                key = seg
                k = 2
                while key in stats:
                    key = f"{seg}#{k}"
                    k += 1
                seg = key
                st = stats.setdefault(seg, new_stats())
            else:
                sys.exit(f"bad record type {t!r}")
            st["recs"][t.decode()] += 1
            st["bytes"][t.decode()] += size
    except EOFError:
        pass

    for name, s in stats.items():
        total = sum(s["bytes"].values())
        print(f"== {name}: {total/1e6:.1f} MB raw, "
              f"insns user={s['user']:,} kernel={s['kernel']:,}")
        print("   records: " + ", ".join(
            f"{k}={s['recs'][k]:,} ({s['bytes'][k]/1e6:.1f}MB)"
            for k in sorted(s["recs"])))
        if s["fn"]:
            ktot = s["kernel"] or 1
            for fn, c in s["fn"].most_common(a.top):
                print(f"   {c:>12,} {100*c/ktot:5.1f}%  {fn}")


if __name__ == "__main__":
    main()
