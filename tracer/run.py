#!/usr/bin/env python3
"""Boot one kernel under QEMU with the rxtrace plugin and type commands.

Segments in the trace:
  boot          power-on until the first "$ " prompt
  idle          time spent waiting for input
  cmd:<text>    from pressing Enter until the next "$ " prompt

usage: run.py KERNEL_DIR OUT_DIR [--mode count|pc|full] [--cmd TEXT ...]
"""
import argparse
import os
import shutil
import subprocess
import sys
import threading
import time

PLUGIN = "/work/plugin/rxtrace.so"
PROMPT = b"$ "


class Guest:
    def __init__(self, argv):
        self.proc = subprocess.Popen(argv, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT)
        self.buf = bytearray()
        self.cv = threading.Condition()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        while True:
            chunk = self.proc.stdout.read1(4096)
            if not chunk:
                break
            with self.cv:
                self.buf += chunk
                self.cv.notify_all()

    def wait_for(self, pattern, start, timeout):
        """Wait until pattern appears at or after offset start."""
        deadline = time.monotonic() + timeout
        with self.cv:
            while True:
                i = self.buf.find(pattern, start)
                if i >= 0:
                    return i + len(pattern)
                left = deadline - time.monotonic()
                if left <= 0 or self.proc.poll() is not None:
                    raise TimeoutError(f"no {pattern!r}; output tail:\n"
                                       + self.buf[-600:].decode(errors="replace"))
                self.cv.wait(left)

    def send(self, data):
        self.proc.stdin.write(data)
        self.proc.stdin.flush()

    def pos(self):
        with self.cv:
            return len(self.buf)


def mark(g, ctl, name, timeout=600):
    """Start a new segment and wait until the plugin applied it."""
    ack = ctl + ".ack"
    with open(ctl, "w") as f:
        f.write(name + "\n")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if g.proc.poll() is not None:
            raise RuntimeError("qemu exited; output tail:\n"
                               + g.buf[-600:].decode(errors="replace"))
        if os.path.exists(ack):
            with open(ack) as f:
                if name in f.read().splitlines():
                    return
        time.sleep(0.01)
    raise TimeoutError(f"mark {name} not applied")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kernel_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--mode", default="count", choices=["count", "pc", "full"])
    ap.add_argument("--cmd", action="append", default=[])
    ap.add_argument("--smp", type=int, default=1)
    ap.add_argument("--boot", default="on", choices=["on", "off"],
                    help="record boot in --mode (off: count only, fast)")
    ap.add_argument("--snap", action="store_true",
                    help="dump physical memory at every mark")
    ap.add_argument("--timeout", type=float, default=3600)
    ap.add_argument("--icount-shift", type=int, default=3,
                    help="one instruction = 2^N ns of guest time")
    a = ap.parse_args()

    os.makedirs(a.out_dir, exist_ok=True)
    out = os.path.abspath(a.out_dir)
    fsimg = os.path.join(out, "fs.img")
    shutil.copy(os.path.join(a.kernel_dir, "fs.img"), fsimg)
    ctl = os.path.join(out, "ctl")
    if os.path.exists(ctl):
        os.unlink(ctl)
    os.mkfifo(ctl)

    popts = [f"mode={a.mode}", f"boot={a.boot}", f"stats={out}/stats.txt",
             f"ctl={ctl}"]
    if a.mode != "count":
        popts.append(f"out={out}/trace")
    if a.snap:
        popts.append(f"snap={out}/trace")
    argv = [
        "qemu-system-x86_64", "-machine", "q35",
        "-kernel", os.path.join(a.kernel_dir, "kernel/kernel"),
        "-m", "128M", "-smp", str(a.smp), "-nographic", "-nic", "none",
        "-drive", f"file={fsimg},if=none,format=raw,id=x0",
        "-device", "virtio-blk-pci,drive=x0,disable-legacy=on,addr=0x3",
        "-no-reboot",
        # Guest time follows the instruction count, not the host clock,
        # so tracing overhead does not change what the guest does.
        "-icount", f"shift={a.icount_shift},sleep=on",
        "-plugin", PLUGIN + "," + ",".join(popts),
    ]

    times = []
    t0 = time.monotonic()
    g = Guest(argv)
    end = g.wait_for(PROMPT, 0, a.timeout)
    times.append(("boot", time.monotonic() - t0))
    mark(g, ctl, "idle")

    for cmd in a.cmd:
        g.send(cmd.encode())
        end = g.wait_for(cmd.encode(), end, 60)   # console echo
        mark(g, ctl, "cmd:" + cmd)
        t = time.monotonic()
        g.send(b"\n")
        end = g.wait_for(PROMPT, end, a.timeout)
        times.append(("cmd:" + cmd, time.monotonic() - t))
        mark(g, ctl, "idle")

    g.send(b"\x01x")                               # -nographic: quit
    g.proc.wait(timeout=600)

    with open(os.path.join(out, "console.txt"), "wb") as f:
        f.write(g.buf)
    with open(os.path.join(out, "times.txt"), "w") as f:
        for name, sec in times:
            f.write(f"{name} {sec:.2f}s\n")

    print(open(os.path.join(out, "stats.txt")).read(), end="")
    for name, sec in times:
        print(f"wall {name} {sec:.2f}s")
    for fn in sorted(os.listdir(out)):
        p = os.path.join(out, fn)
        if os.path.isfile(p) and fn not in ("ctl",):
            print(f"file {fn} {os.path.getsize(p)}")


if __name__ == "__main__":
    sys.exit(main())
