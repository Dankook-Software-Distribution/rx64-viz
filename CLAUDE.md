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
  vm/Vm.java             QEMU + gdb lifecycle, 1 s auto-snapshot, Trace, Debug (breakpoints, step)
  vm/GdbMi.java          gdb --interpreter=mi3 process, token-matched commands
  vm/MiRecord.java       GDB/MI line parser
  web/Hub.java           WebSocket /ws; every message carries "vm": <kernel id>
  web/ApiController.java /api/walk (root=user|kernel), /api/physmem, /api/source, /api/files, /api/record
src/main/resources/gdb/xv6viz.py   runs inside gdb, defines -xv6-* MI commands returning JSON
src/main/resources/static/         plain ES modules, no build step; xterm.js vendored in vendor/
  app.js                 views, rendering, Trace list/replay, Debug UI
  js/dock.js             VS Code-like layout: areas, tab groups, drag & drop, presets, View menu
  js/mem.js              Memory view (Virtual → Page table walk → Physical, wires, zoom boxes)
  js/graph.js js/stack.js js/source.js js/decl.js js/dom.js
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
- Command record (`Recorder`): all breakpoints are non-stopping (`stop()` returns False); the VM
  never halts for them. `syscall` entry and every `ret` in `syscall()` give the call list; user
  memory is read by walking `p->pagetable` (both styles). Syscalls already in flight at start are
  seeded from the trapframe of SLEEPING procs. Java polls `-xv6-record-poll <since>` inside the 1 s
  tick. Trace is a toggle: it runs until turned off or `REC_MAX` events.
- Trap stages (`x86_steps`/`riscv_steps`): one breakpoint per stage plus one at every `sys_*`
  handler except `sys_exit` (a breakpoint there makes exit return to user and the guest dies).
  A per-process `Trap` records each stage only if it is the expected next one and its exact `rsp`
  (and `cr3` for riscv-style) matches, which filters other CPUs and nested interrupts.
  `stage_view()` saves regs, frames and source; after each call `after()` saves proc, files
  (no bcache) and a page-table summary. The browser fetches them via `/api/record?vm=&i=`.
- `read_cpus()` reads memory only from a CPU with CR0.PG set (APs right after boot have no paging).
- `-xv6-structs` gives each struct's file:line from `info types`; the browser parses the C source
  for the field-declaration hover.
- Debug: `debug_state()` reads the call stack and the selected frame's args/locals with gdb frames.
  riscv-style must turn QEMU physical-memory mode off for anything gdb unwinds or steps (KSTACK is
  not identity-mapped): `Arch.phys(on)` switches it and flushes gdb's caches. Every `-xv6-*` MI command
  turns it back on first, except `-xv6-step-prep` (sent right before `-exec-next/step/finish`).
  Trace breakpoints skip their work while it is off.
- The module is copied into a temp dir when a VM starts (also on Reboot), so Python changes need a
  server restart or a VM reboot.

### Vm control flow

All control actions run on the single `vm-ctl` executor. States: STARTING, RUNNING, PAUSED (halted:
Pause, a user breakpoint, or a finished step), STEPPING (`-exec-next/step/finish` running), STOPPED, FAILED.
gdb `*stopped` events either complete a pending `stopWaiter` (a stop we asked for) or go to
`onUnexpectedStop`: a user breakpoint hit or the end of a step → `debugStopped()` (PAUSED, sends the
`debug` message and a snapshot); otherwise (a late interrupt) it resumes. `whileStopped()` interrupts,
runs the work, then resumes, unless the interrupt raced with a user breakpoint (`pendingBp`), in which
case it stays paused. It refuses to run while STEPPING (REST queries get 503). `interrupt()` retries
every second because an interrupt sent while gdb steps over a non-stopping breakpoint can be lost.

## UI rules (from the project owner)

- No explanatory or tutorial prose in the UI: no step narratives, hint paragraphs, or
  empty-state guidance sentences. Show the components and their values only.
- Labels come from the code (`proc[]`, `cpus[]`, `p->kstack`, `struct file`) or standard terms.
- Theme: VS Code Dark Modern look. Colors are CSS variables at the top of `style.css`
  (`--bg` editor, `--bg2` bars/sidebars/panel, `--line` borders, `--accent` #0078d4).
  Areas are cards with a 4px gap and 6px radius; tabs look like VS Code editor/panel tabs;
  VM state and ticks go in the bottom status bar. Monospace for values.
- Color only for meaning: blue (`--blue`) = pointer/selection, yellow (`--yellow-bg`) = changed
  value / current position. Do not uppercase labels taken from code (`cpus[]`, not `CPUS[]`).
- UI text is English. Labels from code stay as in code.
- Layout (`js/dock.js`): views are registered once (`dock.register`) and their elements are moved
  between areas, never rebuilt. left/right areas stack sections; center/bottom are rows of tab groups.
  Drag a tab or section header to move it; drop on a group's left/right edge to split. Presets
  (Syscall, Memory, Debug) and view toggles are in the View menu. Hide areas with Ctrl+B / Ctrl+J /
  Ctrl+Alt+B; maximize with the header button or a double-click. Saved in `localStorage` key
  `xv6viz.dock.v1`. Compare view is a fixed layout (compare columns + both shells).
- Panes repaint only when their data changes (`paint()` in `app.js`), keep scroll position, and mark
  changed `[data-k]` values / new `[data-n]` items yellow (`markChanges` in `dom.js`).
- Audience: an instructor explains while using it in class. Make things easy to point at; do not
  add guidance text.
- Memory diagrams are vertical, textbook style: high addresses at the top.
- Stack view has a register strip (rip/rsp/rbp) above the stack figure: a Trace stage and the next
  stage (`→ user` at iretq, read from the iret frame); live, the CPU running the process, else
  `p->context`. rsp and rbp are also pointers on the stack figure.
  When paused in Debug, `debug_state()` also sends each frame's `sp` and its in-memory variables
  (`mem_vars`): the stack figure brackets each function's slots [sp_i, sp_{i+1}), names variable
  slots, `ret → fn` and `saved rbp`, and scrolls the Stack pane to rsp after each step.
- Disassembly view (Debug): `debug_state()` sends `asm` from `disasm()`: the selected frame's function
  (C functions by gdb block, .S code from the symbol to the next symbol, at most `ASM_MAX` insns) with
  a C/.S source line header whenever the line changes. nexti/stepi buttons (Ctrl+F10 / Ctrl+F11) send
  `-exec-next-instruction` / `-exec-step-instruction`. CSS prefix `da-` (`as-` is the Memory view's).
- Ask the owner before installing anything (system packages, npm/maven downloads).

## Verifying changes

- The browser caches static files aggressively if headers are missing; static files are served
  with `no-cache` (see `application.properties`). After changing JS, hard-reload.
- Headless check: Firefox here is a snap, so a profile must live under `~/snap/firefox/common/`;
  drive it with WebDriver BiDi (`--remote-debugging-port`) and capture screenshots.
  `--window-size` is ignored (headless stays 1366x682); to test other sizes start Firefox with
  `-remote-allow-system-access` and call `browsingContext.setViewport` after the first navigate.
- To test the gdb module without Java, boot QEMU with `-S -gdb tcp::PORT` and run
  `gdb -nx -batch -x script.py` that sources `xv6viz.py` and calls its functions directly.
  sh blocks on its first `read`, so use a boot-time syscall (init) unless you can feed input.
- When killing test QEMUs, use `-pidfile`; `pkill -f` on a pattern that appears in the same
  shell command line kills the shell itself.
