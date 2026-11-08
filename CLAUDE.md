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
| `extra/user/` | Extra user programs added to the image's copies (`lazytouch`: lazy sbrk + 3 page faults; `pipewait`: sleep on an empty pipe, wakeup, reparenting to init). |
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
- `rep stosb` and friends are one step per iteration (x86-style's entry.S clears the bss with 80k of them): Instruction-mode over takes the rest of a rep string instruction at once. Over/line-over a call that never returns in the recording (`call main`, a process's last `sched()`) steps into it instead of jumping to the end; out of a function that never returns reports nowhere to go.
- `lretq` is not a function return (riscv-style reloads CS with it); `swtch` ends with an indirect `jmp`, which closes its frame.
- Kernel-specific facts live in `application.yml` (kstack base/stride, IRQ vectors, syscall vector, riscv-style TRAPFRAME page and trampoline alias); x86 exception names are in `KernelProfile`.
- Segment file names come from `index/SegmentFiles`; the plugin writes `trace.NNN.zst` / `trace.NNN.mem.zst`.
- Threads (`TreeBuilder`): a thread is a kernel context, named by `swtch(old, new)`'s arguments (`rdi`/`rsi`): `&proc[k].context` is process k, `&cpus[c].context` CPU c's scheduler. The running thread changes only inside swtch, at the step where rsp equals new's saved sp (at the latest its `jmp`); traps, iretq and exec's new CR3 never change it. A context whose saved pc is a function's first instruction (forkret, set by allocproc) never ran: a new thread for that slot (the exited process's thread keeps its last frames, suspended). The thread running when the recording begins takes its slot from its kernel stack. Run labels carry slot and pid (`proc[2] pid 3 sh`; `Tree.slotOf`/`processOf` hold them as data). A thread back in user mode with no frame open (a forked child, a thread first seen in the kernel) gets its user frames from the user stack's rbp chain (EARLIER too). When exec's new program is recognized, only the frames open at its return to user mode are closed (the new `main` stays). A thread first seen mid-function gets its frames from the saved-rbp chain (`earlier()`, nodes flagged EARLIER): only where pc is compiled C (.c or a header-inlined function, not .S), split at a return into a trap stub.
- eflags as recorded never has the arithmetic flags (QEMU computes them lazily; plugins see only the system flags). The rflags a trap pushes is complete. Likewise the A/D bits of page-table entries: the MMU's writes are not recorded, so the UI shows them as unknown.
- Stores are recorded at the step of the instruction that made them; the CPU-pushed trap frame is read back by the plugin (StackView names those slots "CPU" by position: from rsp at the stub's first step up).
- The step filter skips only the `step-skip` lock helpers (since 2026-11-08's second student review); trap stubs and swtch are stepped through. line-back is line-over backwards. Code with no line info (riscv-style's trampoline) is a line per instruction in line stepping.
- Naming (`elf/Structs.path`): DWARF member and array types, with the access size (a 4-byte store at `&kmem` is `kmem.lock.locked`; size 0 = an address). Addresses in page-table pages are named as entries (`index/PageTables`: every table reachable from `kernel_pagetable` and each `p->pagetable`). In the kernel with a user CR3 (riscv-style's uservec), low addresses are user memory, not the kernel's identity-mapped RAM.
- `proc[k].state` stores are collected while indexing (`ProcStates`): Timeline's state spans and wakeups, and sleep vs preempted per switch-out (`Syscalls.waits`).
- The kernels' entry code is `.code32`: those ranges (from the .S sources and DWARF lines) are disassembled with `-m i386`, and lgdt there uses 32-bit addressing (riscv-style's runs in compatibility mode with EFER.LMA already on).

## Status and next steps

Done: tracer, indexer, REST/WebSocket API, boot recordings from `_entry`, UI with History, Events, Code (C with inline instructions), Registers, Call Stack, Console, Stores, Stack (`index/StackView`: frames from the event tree plus the saved-rbp chain, trapframe field names from DWARF via `elf/Structs`), Memory (`index/AddressSpace`: regions of CR3's page table, physical frame map with the real `kmem.freelist`; region names from ELF segments, kstack slots, exec's layout and `regions:` in `application.yml`).

Timeline (`index/Timeline`: per-thread lanes bucketed on the server, user-mode share from per-256-step counts made while indexing), Events folds runs of threads that take turns. Step filter (title-bar toggle, on by default): line stepping and Events skip the lock helpers in `step-skip` (`application.yml`); Instruction mode never skips. Syscalls (`index/Syscalls`: args from `syscall-args` registers typed by `user/user.h`, strings read at entry, pointer outputs from the call's recorded stores, result = rax on return; also exceptions with the CPU's error code), shown in a Syscalls panel, event-tree `detail`, Call Stack and Timeline; command recordings open at their first user-mode C line (`Summary.start`). Loop stepping (`loop-out`/`loop-back`: a loop = a backward-jump target up to its last back-edge, from objdump) and Run to Cursor (`/run-to`, scans the whole recording in time order, ~15 s worst case on a boot). While CR0.PG is off (early boot) Stack/Memory treat addresses as physical (`Recording.paging`).

Processes (`index/Processes`: `proc[]`/`cpus[]`/`ftable` read with DWARF offsets and `enum procstate` names; a stopped process's functions from the saved rbp chain in its `context`; `chan` named as `proc[k]`, a pipe field or `symbol.member[i]`) and Changes (`index/StateDiff`, `/diff`: two snapshots at a call's start and end; proc fields, `ftable` entries and pipes, frames leaving/joining `kmem.freelist` named by their owner, user-half mappings per process (exec = old table unmapped, new table mapped); one pass over the stores finds the store behind each change and the run label says who made it). Changes follows the Syscalls row at the cursor and dims changes not reached yet.

CPU panel (`index/CpuTables`, `/cpu`): QEMU gives plugins no GDTR/IDTR, so the indexer keeps the steps of every `lgdt`/`lidt`; GDTR/IDTR at step N = the last one's memory operand (ModRM decoded from the bytes and registers) read from memory then; a command recording with no load takes the boot recording's value only if all boot loads came before the first user-mode step (`Recording.registerBoot`); TR = the GDT's busy TSS; table names via `Structs.path`/`tablePath` (also through the CPUENTRY alias).

Made easier to follow (after the user asked whether a first-timer could follow it): default layout Syscalls | Code | Changes, Console/Processes below, debugger panels in tabs; Syscalls folds runs of one-byte reads/writes and links each call to its handler (`Syscalls.Call.handler`: `sys_<name>` in the trap's subtree, else its first C function); with the step filter, line stepping skipped assembly of other files (int $64 -> first kernel C line; undone after the second student review: stubs and swtch are stepped through now); Changes shows a `timeline` for calls that span other threads' runs (StateDiff: stores per tracked field cut at run boundaries, read back after each run's last store); Memory can show `proc[slot]`'s page table (`slot` on /regions, /physmap, /frame, /pagewalk; `memorySpace` store, button in Processes and Changes). LineTable honors end-of-sequence rows (code without line info, such as riscv-style's trampoline, has no line).

Student review (four agents as first-time students, 2026-11-08) and the fixes after it: separate threads for cat/grep (reused page-table frames had merged them), calls under way when a recording begins, slept vs preempted and who woke a call, pid, `vmfault` as a page fault's handler and where it resumes, `p->sz` gaps in Memory, own vs shared page tables, PTE/error-code/eflags bits, register and operand names (`/names`), macro lines expanded, line-over out of trap() as symmetric as line-into, Changes links to the meaningful store (`*pte = 0`, freelist pop/push), timeline cut per syscall with the free list's size, Changes for any Events call, boot milestones on the Timeline, 32-bit entry code, F-keys kept out of the console, recordings open at the program's `main`, Memory next to Changes in the default layout.

Second student review (same day) and its fixes: A/D shown as unknown, exit no longer "preempted", riscv-style pids (read physically), guard page counted as the process's, struct paths without a bogus `[0]` (`proc[2].trapframe`), array elements (`syscalls[16] → sys_write()`; since then from DWARF member and array types in `elf/Structs`, the instruction's scale only for untyped assembler tables), shared boot tables counted once, device names only where the device's pa is mapped, user frames named after the program is recognized, a new process's first return to user closes its kernel frames, CPU mode (`/state` `mode`), registers the last instruction wrote (`changed`), other threads that ran during a move (`ran`), trap entry/exit summary and per-line operands (`/names`), who wrote each trap-frame slot, Variables panel (`elf/Locals` + `index/Variables`: DWARF location lists, no guessing), frames in and out during a call (`passing`), sleep/went-on links (slept vs preempted read from the last store to `p->state` before the switch: SLEEPING or RUNNABLE), Memory follows cr2 in a fault and magnifies around a frame, Boot opens on Events with milestone chips.

Third student review (same day) and its fixes: scheduler's `main` (not `spin`: a return address after a noreturn call belongs to the call site), exec keeps the new `main`, forked children's user frames, riscv-style's trampoline stepped by instruction, register names by CR3 (uservec), offset-0 fields named by access size, a forked child's program from its code bytes (`Recording.programIn`), riscv-style's PCI region only where the BARs are, PTE bits 9-11 by the kernel's names, Memory walks a fault in the faulting process's table, Changes follows the thread at the cursor, every sleep of a call, milestones on the instruction, stale trap-frame slots, Variables (`DW_OP_entry_value` from the call's step, not-assigned-yet, struct fields, arrays, hex, offset-0 location lists), any `pagetable_t` shown in Memory (`root=`), PTE names, Timeline states/wakeups/cr3 switches, control-register writes in Code, fork's child context and trap frame in Changes, sleep channel rows marked, syscall argument registers, pid in labels, ASCII stack words, earlier shells in History (`/api/shell/past`; `/api/sessions` no longer takes over the UI's shell), CPU's past-limit selectors and not-reloaded cs, folded runs say what they slept on.

Next (design to be agreed first): side-by-side kernel comparison. Goal the user set: someone seeing an OS for the first time should understand it (no explanation panel; real values only).

Known shortcuts worth revisiting: riscv-style's trampoline.S has no DWARF lines (built without them), so its code shows as instructions only; exec is detected when the running program's code changes (could come from a CR3 change on syscall return); indexes are rebuilt on every server start; user stack/heap regions are named from exec's layout (guard page pattern), not from `p->sz`; Changes still parses the slot out of `by` labels ("proc[N] pid M ..."; rows and /state carry slot and pid as fields); the CPU-pushed trap frame is decoded both in StackView and Syscalls; the step filter's hiding rule is applied on the server (`/tree`, `/nodes`, Stepper) and again in events.ts for the cursor path; StateDiff's UI covers syscalls/exceptions and Events calls (not interrupts), skips the store lookup over 64M steps, and cannot name frames held only by a local variable (exec's old page table before it is freed).
