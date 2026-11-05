# CLAUDE.md

Web visualizer for two x86-64 ports of xv6: `rx64-x86-style` and `rx64-riscv-style`
(separate git repos, kept in `os/rx64-x86-style` and `os/rx64-riscv-style`). It boots each
kernel in QEMU, attaches gdb, and draws live kernel data structures in the browser.
Audience: OS students. The kernels are never modified; everything is read through gdb.

## Commands

```
make up        # Docker: build image (kernels + jar) and run in background, http://127.0.0.1:8080
make down      # stop the container
make logs      # follow container logs
make local     # build os/ kernels, then ./mvnw spring-boot:run against them
make kernels   # build kernel/kernel + fs.img in os/rx64-*-style
make test      # unit tests (MI parser only; no VM)
```

`make up` and `make local` both use port 8080; run `make down` before `make local`.

Requirements: Docker only for `make up`. For `make local`: JDK 25 (not just a JRE),
`qemu-system-x86_64`, gdb >= 12 with Python, gcc/binutils, perl.
Kernel paths and `-smp` are in `src/main/resources/application.properties` (defaults point at
`../rx64-*-style`); `make local` and the Docker image override the paths on the command line
to use `os/rx64-*-style`.

### Docker

- `Dockerfile` has three stages: Ubuntu 24.04 builds both kernels from `os/`, JDK 25 builds the
  jar, JRE 25 + `qemu-system-x86` + `gdb` runs it with `--server.address=0.0.0.0`.
- `compose.yaml` publishes only on `127.0.0.1:8080` and sets `init: true` to reap QEMU/gdb.
- Kernel or `xv6viz.py` changes need `make up` again (rebuilds the image).

## Layout

```
src/main/java/dev/xv6viz/
  Xv6Props.java          xv6viz.kernels.<id>=<dir>, xv6viz.cpus
  vm/VmManager.java      one Vm per configured kernel
  vm/Vm.java             QEMU + gdb lifecycle, pause/resume, 1 s auto-snapshot, trap trace state
  vm/GdbMi.java          gdb --interpreter=mi3 process, token-matched commands
  vm/MiRecord.java       GDB/MI line parser
  web/Hub.java           WebSocket /ws; every message carries "vm": <kernel id>
  web/ApiController.java /api/walk, /api/physmem, /api/source
src/main/resources/gdb/xv6viz.py   runs inside gdb, defines -xv6-* MI commands returning JSON
src/main/resources/static/         plain ES modules, no build step; xterm.js vendored in vendor/
```

### gdb module (`xv6viz.py`)

- Common code plus an `Arch` adapter: `X86Style` and `RiscvStyle`, chosen by whether the
  `trampoline` symbol exists.
- x86-style: higher-half kernel mapped in every page table, so gdb's normal virtual reads work.
- riscv-style: user page tables do not map the kernel. The module switches QEMU to physical
  memory mode (`maint packet Qqemu.PhyMemMode:1`); kernel data is identity-mapped, and
  non-identity kernel addresses (KSTACK, TRAMPOLINE, CPUENTRY) are translated by walking
  `kernel_pagetable` in Python (`RiscvStyle.rdk`).
- `init()` runs on the first MI command, at `main()`. Anything set up later in boot
  (e.g. `kernel_pagetable`) must be read lazily, not cached in `init()`.
- All 64-bit values go to the browser as `"0x..."` strings (JS numbers lose precision).
- Trap trace: one breakpoint per step, only the expected one enabled. Each step checks exact
  `rsp` (and `cr3` for riscv-style) so other CPUs' syscalls and nested interrupts on the same
  stack are ignored. The last step is a `stepi` over `iretq`.
- The module is copied into a temp dir when a VM starts, so Python changes need a server restart.

### Vm control flow

All control actions run on the single `vm-ctl` executor. gdb `*stopped` events either complete
a pending `stopWaiter` (a stop we asked for) or are handed to `onUnexpectedStop` (a trace
breakpoint). `whileStopped()` interrupts, runs the work, then resumes unless a trace step hit.

## UI rules (from the project owner)

- No explanatory or tutorial prose in the UI: no step narratives, hint paragraphs, or
  empty-state guidance sentences. Show the components and their values only.
- Labels come from the code (`proc[]`, `cpus[]`, `p->kstack`, `struct file`) or standard terms.
- Minimal, plain styling: white background, 1px lines, no rounded corners or shadows,
  monospace for values. Color only for meaning: blue = pointer/selection, yellow = changed value.
- Memory diagrams are vertical, textbook style: high addresses at the top.
- Ask the owner before installing anything (system packages, npm/maven downloads).

## Verifying changes

- The browser caches static files aggressively if headers are missing; static files are served
  with `no-cache` (see `application.properties`). After changing JS, hard-reload.
- Headless check: Firefox here is a snap, so a profile must live under `~/snap/firefox/common/`;
  drive it with WebDriver BiDi (`--remote-debugging-port`) and capture screenshots.
- To test the gdb module without Java, boot QEMU with `-S -gdb tcp::PORT` and run
  `gdb -nx -batch -x script.py` that sources `xv6viz.py` and calls its functions directly.
  sh blocks on its first `read`, so trace a boot-time syscall (init) unless you can feed input.
- When killing test QEMUs, use `-pidfile`; `pkill -f` on a pattern that appears in the same
  shell command line kills the shell itself.
