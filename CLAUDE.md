# rxviz

A visualizer that helps students understand how an OS works by recording
what two teaching kernels actually execute and letting them step through
it like a debugger. The kernels are two x86-64 ports of xv6 in `os/`
(git submodules): `rx64-x86-style` (higher-half kernel, trapframe on the
kernel stack) and `rx64-riscv-style` (identity-mapped, separate user and
kernel page tables with trampoline / TRAPFRAME / CPUENTRY pages).

The user is a single student running everything locally in Docker; the
UI talks to one local server. See README.md for the API and how to run.

## Working with the user

- Present the design and get an explicit go-ahead before implementing a
  new feature or a large piece. Small checks (reading code, trying a
  tool) are fine without asking.
- The user writes in Korean; answer in Korean. Everything in the product
  (UI labels, terms, code, comments, docs in the repo) is in English.
- UI direction the user chose: VS Code look and behavior (Dark/Light
  Modern colors, codicons, dockable panels, debugger-style stepping,
  yellow current line, blue changed values). No explanation/tutorial
  panel. C source first; instructions open under the running line, not
  in a second code panel. One xv6 shell, no session management.
- Only visualize what these kernels really do (real recorded values, no
  hand-made models). Features the kernels lack (threads, scheduling
  policies, swap, ...) are out of scope.

## Layout

| Path | What |
|---|---|
| `os/` | The two kernels (submodules). Never modify; the image copies and builds them. |
| `extra/user/` | Extra user programs added to the image's copies (`lazytouch`: lazy sbrk + 3 page faults). |
| `tracer/plugin/rxtrace.c` | QEMU TCG plugin: counts, or records every instruction (changed registers, stores, interrupts) into zstd segment files; marks via a FIFO; `arm=` starts recording at the kernel entry. |
| `tracer/build-kernels.sh` | Copies `os/*`, adds `extra/user`, builds kernel + fs.img. |
| `tracer/run.py`, `tracer/decode.py` | Standalone phase-1 tools (drive QEMU, summarize a trace). |
| `server/` | Spring Boot 4.1 / Java 25 / Gradle. `config/` kernel facts (`application.yml` + `KernelProfile`), `elf/` symbols, objdump, DWARF lines, `trace/` segment reader, `index/` indexer (chunks, checkpoints, event tree, stepping), `session/` QEMU shell and boot recordings, `api/` REST + WebSocket. |
| `server/e2e/` | End-to-end checks against a running server (`e2e.py`, `boot.py`, `ws.mjs`, helpers in `common.py`). |
| `web/` | TypeScript 7 + Vite 8, dockview-core (CSS extracted by `scripts/dockview-css.mjs`), xterm.js, codicons. Panels in `src/panels/`. |
| `Dockerfile` | Stages: Gradle (server jar), Node (web), Ubuntu 26.04 runtime with QEMU 10.2.1, kernels, plugin. |

## Build, run, test

```sh
docker build -t rxviz .
docker run -d --name rxviz-server -p 127.0.0.1:8080:8080 -v "$PWD/runs/data:/data" rxviz
python3 server/e2e/e2e.py && python3 server/e2e/boot.py && node server/e2e/ws.mjs
```

- The UI is at http://localhost:8080. For UI work: `cd web && npm install && npm run dev` (port 5173, proxies to 8080); `npx tsc --noEmit` to typecheck.
- The first server start records each kernel's boot (~4 min, both in parallel) into `runs/data/boot/`; later starts only re-index (~20 s). A changed kernel or plugin re-records.
- Screenshots of the UI: Playwright has no Ubuntu 26.04 build, so run it in `mcr.microsoft.com/playwright:v1.56.1-noble` with `--network host`.
- After changing server or web code, rebuild the image and restart the container; the e2e scripts must stay green.

## Things that are easy to get wrong

- QEMU must run with `-icount` (set in `Qemu.java`): without it, the slowed, traced guest drowns in timer interrupts and stalls.
- Plugins get memory callbacks only for instructions: the frame the CPU pushes on an interrupt/exception is read back by the plugin ('W' flags 2); device DMA (virtio disk reads) and the accessed/dirty bits the MMU sets in page-table entries are not recorded at all (so the UI does not show A/D).
- QEMU's plugin disassembly is empty (Ubuntu's QEMU has no capstone): instruction kinds come from the bytes (`InsnKind`), text from objdump.
- Addresses are unsigned 64-bit: use `elf/Addr` helpers and `Long.compareUnsigned`; kernel addresses are negative longs. JSON carries 64-bit values as `"0x..."` strings.
- "State at step N" is the state before instruction N runs.
- `lretq` is not a function return (riscv-style reloads CS with it); `swtch` ends with an indirect `jmp`, which closes its frame.
- Kernel-specific facts live in `application.yml` (kstack base/stride, IRQ vectors, syscall vector, riscv-style TRAPFRAME page and trampoline alias); x86 exception names are in `KernelProfile`.
- Segment file names come from `index/SegmentFiles`; the plugin writes `trace.NNN.zst` / `trace.NNN.mem.zst`.

## Status and next steps

Done: tracer, indexer, REST/WebSocket API, boot recordings from `_entry`, UI with History, Events, Code (C with inline instructions), Registers, Call Stack, Console, Stores, Stack (`index/StackView`: frames from the event tree plus the saved-rbp chain, trapframe field names from DWARF via `elf/Structs`), Memory (`index/AddressSpace`: regions of CR3's page table, physical frame map with the real `kmem.freelist`; region names from ELF segments, kstack slots, exec's layout and `regions:` in `application.yml`).

Timeline (`index/Timeline`: per-thread lanes bucketed on the server, user-mode share from per-256-step counts made while indexing), Events folds runs of threads that take turns. Step filter (title-bar toggle, on by default): line stepping and Events skip the lock helpers in `step-skip` (`application.yml`); Instruction mode never skips. Syscalls (`index/Syscalls`: args from `syscall-args` registers typed by `user/user.h`, strings read at entry, pointer outputs from the call's recorded stores, result = rax on return; also exceptions with the CPU's error code), shown in a Syscalls panel, event-tree `detail`, Call Stack and Timeline; command recordings open at their first user-mode C line (`Summary.start`). Loop stepping (`loop-out`/`loop-back`: a loop = a backward-jump target up to its last back-edge, from objdump) and Run to Cursor (`/run-to`, scans the whole recording in time order, ~15 s worst case on a boot). While CR0.PG is off (early boot) Stack/Memory treat addresses as physical (`Recording.paging`).

Next (3c, design to be agreed first): Processes, CPU structures (GDT/IDT/TSS), side-by-side kernel comparison.

Known shortcuts worth revisiting: thread identity is inferred from the stack in use (could come from `swtch`'s arguments); exec is detected when the running program's code changes (could come from a CR3 change on syscall return); indexes are rebuilt on every server start.
