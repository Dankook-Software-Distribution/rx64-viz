# rxviz

Records what the two xv6 x86-64 ports in `os/` execute when you type a
command, and serves the recording (event tree, registers, memory, page
walks at any step) to a browser UI. Everything runs in one Docker image;
`os/` is copied in and never modified.

## Run

```sh
docker build -t rxviz .
docker run --rm -p 127.0.0.1:8080:8080 -v "$PWD/runs/data:/data" rxviz
```

On first start the server records each kernel's boot once, from its
entry point to the first prompt (about 4 minutes per kernel, one after
the other, in the background) and keeps it in `data/boot/<kernel>`
(about 660 MB each). Later starts only re-index it (about 30 s each).
Sessions are usable meanwhile; they boot without recording.

Open http://localhost:8080 for the UI: it boots one xv6 shell (pick the
kernel in the title bar; switching or restarting reboots it). Type a
command in the Console; its recording opens when indexed and stays in
History, next to the kernel's Boot recording (a replaced shell's
recordings stay there too, under Earlier shells). Processes are named by
slot and pid (`proc[2] pid 3 sh`).
A command's recording opens at the first line of its program's `main`. Syscalls
lists every system call and exception the way strace prints them
(`exec("echo", ["echo", "hi"]) = 2`, `page fault at 0x4000: user write
at main+0x18, page not present`); click one to go to the instruction
that made it, or its `→ sys_write()` link to the kernel function that
handles it (for a page fault, `→ vmfault()`, and `resumes at main+0x18`:
the faulting instruction running again). A row says whether the call
slept (`slept on pipe 0x... nread`, `woken by proc[2] pipewait in
pipewrite()`) or was preempted by the timer (every sleep, if it slept
more than once), and the process's pid; a
call already under way when the recording began is listed too. Runs of one-byte reads or writes on one fd (the shell
reading a command line, wc printing digits) are folded into one row
with the bytes joined. Event tree nodes, the Call Stack and the Timeline
use the same one-line form. The default layout puts Syscalls, Code and
Changes side by side (Memory in a tab next to Changes, so a page walk
and the code show together), with the Console and Processes below and
the debugger panels (Registers, Stack, CPU, Events, Stores) in tabs.
Changes shows what the call or exception at the cursor changed, read
from memory at its start and end: `proc[]` fields (a fork's new slot,
exit's ZOMBIE), open files and pipes, frames taken from or put back on
the free list, and each process's user page table (exec's old and new
table). Each change links to the instruction whose store made it, and
changes the cursor has not reached yet are dimmed. For a call that
slept, Changes first lists every change in time order with who made it
(cat sleeps on the pipe, wc reads it empty and wakes cat, ...), each
tagged with the call it happened in and with the free list's size,
then the net effect; the rows on what it slept on are marked. A fork's
Changes include what it set up for the child: its context (rip =
forkret) and its trap frame (rax = 0). Any call in Events (userinit(), kexec()) can be
shown in Changes too (its diff button). Processes shows the
process table at the cursor: state, what a sleeping process waits on
and the functions it is stopped in, parent, size, open files; its
Memory button shows that process's address space (its own page table,
whatever runs) in Memory.
CPU shows the descriptor tables in use: which GDT entry cs and ss
select, the TSS's rsp0 (x86-style: the running process's kernel stack;
riscv-style: the end of the trapframe), and the IDT with the lidt that
loaded it (riscv-style switches between the user and the kernel IDT on
every trap); the gate of the trap the cursor is in is marked.
Timeline shows the recording over time, one lane per thread (kernel
and user mode, the traps it took and its page-table switches); between
a process's runs its lane shows the state its `p->state` held (sleeping:
a line, runnable: dashed, zombie: dotted), and a line from another lane
marks the store that woke it; click to go there, wheel to zoom.
On a boot, marks on the axis show where the CPU's setup changed (cr0:
+PG, efer: +LMA, lgdt, the first user-mode instruction).
Memory shows the address space of the page table in CR3, the walk of
one virtual address (following rip, rsp or cr2, or typed in) and what
every physical frame is used for (a process's heap below `p->sz` that no
page maps yet is shown as such; each range's physical address and its
aliases; a page-table entry's bits are spelled out, except A and D,
which the MMU sets with writes the recording cannot see, so they show
as unknown; bits 9-11 by the kernel's own names where it keeps flags
there; inside a page fault the walk follows cr2 through the faulting
process's own page table; a `pagetable_t` in Variables opens its table
(exec's new one, before it is in CR3); the frames around
the one shown are magnified); Stack shows the stack in use, slot by slot
(a page fault's error code, the pushed rflags and selectors decoded,
and for a trap frame who wrote each slot: the CPU, the vector stub,
alltraps, syscall()), and the slots this trap has not written yet (an
older value), and slots that read as text (`"/init"`). Variables shows
the running function's parameters and locals with their values, from
DWARF locations (a register, the stack, a register's value when the
function was called, or "optimized out here"); one no store has written
since the call is "not assigned yet"; structs, and what a pointer to one
points at, open to their fields; arrays show their elements.
Registers name what a register points at (`→ trapframe.rax on kernel
stack of proc[2]`, `PT[4] of proc[2] pid 4 lazytouch (va 0x4000)`,
`SYS_write` and the call's arguments at an `int $64`) and dot the ones the
last instruction wrote; eflags shows only the system flags, since QEMU
never hands a plugin the arithmetic ones. The status bar and CPU show
the CPU's mode (real, protected 32-bit, compatibility, 64-bit). The Code panel shows the C (or .S)
line that is running, with the instructions it compiled to opened under
it (an assembly line too when it is a macro: `vector %i`,
`SYSCALL(write)`), and under the instructions their memory operands:
the address (click: walk it in Memory), what it is (`proc[2].trapframe`,
`syscalls[16] → sys_write()`), the value there and, for a store, the
value written; by line, every instruction of the line as it runs; where
the CPU takes or leaves a trap, what it did (the IDT gate, rsp from
TSS.rsp0, the words pushed or popped, cs/ss); where an instruction
writes a control register, its value before and after and what that
changes (`cr0: +PG`, `kernel_pagetable → proc[1] pid 2 sh's page
table`). Click a line number to
open any other line; step by line
or by instruction (toggle in the title bar) with F10 (over), F11 or →
(into), ← (back), Shift+F11 (out), [ ] (previous / next trap),
Alt+F10 (step over the loop around the current instruction; with Shift,
back to before it), Ctrl+click on a line number or Ctrl+F10 (run to that
line, in any thread; with Shift, back to its last run). The
filter button next to Line / Instruction (on by default) skips the
kernel's lock helpers (`step-skip` in `application.yml`: acquire,
release, push_off, pop_off, holding, mycpu) when stepping by line and in
Events.
Trap stubs and swtch are not skipped: stepping into a system call stops
on vectors.S and alltraps; code with no source line (riscv-style's
trampoline) is stepped an instruction at a time. ← is F10 backwards (it goes over calls and
traps whole). When a move goes over other threads' runs, the status bar
says which ran. F10 and F11 step even when the Console
has the focus (they are never sent to the guest). With the filter,
`echo hi` drops from about 45,000 events to about 1,300. Panels
can be dragged, and double-clicking a tab maximizes it.

UI development: `cd web && npm install && npm run dev` serves it on
:5173 against the server on :8080.

Then, with the server up:

```sh
python3 server/e2e/e2e.py      # boots both kernels, checks echo hi and lazytouch (helpers in common.py)
python3 server/e2e/boot.py     # waits for the boot recordings and checks them
node server/e2e/ws.mjs         # console over WebSocket
```

## How it works

| Part | Where | What |
|---|---|---|
| Trace plugin | `tracer/plugin/rxtrace.c` | QEMU TCG plugin. Counts only (fast) until a mark, then records every instruction with the registers it changed and every store, including the frame the CPU pushes when it delivers an interrupt or exception (read back, since plugins get no callback for it). One zstd file per segment, plus a physical-memory snapshot at its start. |
| Session | `server/.../session/Session.java` | Runs QEMU (`-icount`, so tracing overhead does not change guest timing), relays the console. Enter on a command line starts a `cmd:` segment; the next `$ ` prompt ends it and queues indexing. |
| Boot | `server/.../session/BootService.java` | Records each kernel's boot once with the plugin armed at the physical address of `_entry` (so the firmware is skipped), keyed by the kernel's and the plugin's SHA-256. |
| Indexer | `server/.../index/` | One pass over a segment: re-cuts steps into 4096-step chunks compressed independently (any step = one chunk), keeps old values of stores so memory can be rewound, writes a compressed memory checkpoint every 8M steps, builds the event tree (runs per thread, calls, traps), recognizes user programs by their code bytes. |
| API | `server/.../api/` | REST + WebSocket, below. 64-bit values are `"0x..."` strings. |
| UI | `web/` | TypeScript + Vite, dockview-core panels, xterm.js console, VS Code codicons and colors. Built in the image and served by the server. |
| Extra programs | `extra/user/` | Added to the image's copy of each kernel. `lazytouch`: `sbrklazy` then touch 3 pages, three page faults. `pipewait`: a grandchild orphaned at once (init adopts it) reads an empty pipe and sleeps until the parent writes. |

## API

| Method | Path | |
|---|---|---|
| GET | `/api/kernels` | kernels, their user programs and boot recording status |
| GET | `/api/kernels/{id}/boot` | boot recording status; its recording id is `boot-<kernel>` |
| POST | `/api/sessions` `{"kernel": "rx64-x86-style"}` | boot a guest (the UI's shell stays the one it is, if there is one) |
| POST | `/api/sessions/{id}/ready` | wait for the first prompt |
| POST | `/api/sessions/{id}/run` `{"command": "ls"}` | type a command, wait until indexed |
| POST | `/api/sessions/{id}/input` `{"data": "..."}` | raw console input |
| GET | `/api/sessions/{id}/console` | console text so far |
| GET / POST | `/api/shell` (`{"kernel": ...}`) | the UI's one shell: the running guest / boot a new one, stopping the others (the old shell's recordings stay open) |
| GET | `/api/shell/past` | shells replaced earlier (newest first, at most 4): their recordings can still be opened |
| WS | `/ws/sessions/{id}` | console in/out and recording status |
| GET | `/api/recordings/{rid}` | summary |
| GET | `/api/recordings/{rid}/syscalls` | system calls and exceptions in the order they started, strace-style: process, `write(1, "hi", 2)`, result, what the kernel wrote through pointer arguments, where a sleeping call went on, the kernel function that handles it (`handler`: `sys_write`; for an exception, the callee of trap() that ran longest, such as `vmfault`) and where it starts; `pid`; `waited`: why it stopped running, from the state its process stored before the switch (`sleep`: SLEEPING, on what its chan pointed at; `preempted`: RUNNABLE, a timer `yield`) and who woke it (the store that made it RUNNABLE: `proc[4] wc in piperead()`); `waits`: every time it was switched out, in order, each with what it slept on, who woke it and where it went on (`resumed`); `earlier`: it began before the recording (its frames found on the stack; arguments read back when it returned); for an exception that returned, `resumesAt` (the faulting instruction, run again). Flag arguments by the kernel's `#define` names (`SBRK_LAZY`, `O_CREATE\|O_WRONLY`) |
| GET | `/api/recordings/{rid}/tree?node=-1[&skip=true]` | children of a node (with `skip`, calls of the `step-skip` functions are left out and their children move up); repeated call sequences come grouped (`count`, `members`), and so do runs of threads that take turns 3 or more times |
| GET | `/api/recordings/{rid}/state?step=N` | registers, instruction, symbol, path in the tree, `slot`: the process slot of the thread running (null: scheduler, boot) |
| GET | `/api/recordings/{rid}/step?from=N&op=...[&skip=true]` | where a step command leads (with `skip`, `line-into` goes over calls of the `step-skip` functions; `line-back` is `line-over` backwards): `next`, `prev`, `over`, `out` (instructions), `line-into`, `line-over`, `line-back` (source lines; code with no line, such as riscv-style's trampoline, is a line per instruction), `next-trap`, `prev-trap`, `loop-out`, `loop-back` (the innermost loop as compiled: backward jumps of the function); `found: false` if there was nowhere to go; moves follow one thread across context switches; `ran`: the other threads that ran in between |
| GET | `/api/recordings/{rid}/run-to?from=N&file=kernel/proc.c&line=L[&back=true]` | the next (previous) time line L starts to run, in any thread; a line with no code means the next one that has code |
| GET | `/api/kernels/{id}/source?path=kernel/proc.c` | a source file of the kernel tree |
| GET | `/api/recordings/{rid}/memory?step=N&addr=0x..&len=L&space=virt\|phys` | memory at a step |
| GET | `/api/recordings/{rid}/pagewalk?step=N&va=0x..[&slot=S\|&root=0x..]` | 4-level walk through CR3 (or another page table) at a step; `table`: whose table it is; `softBits`: entry bits 9-11 the kernel names (riscv-style's `PTE_R`, `PTE_X`) |
| GET | `/api/recordings/{rid}/regions?step=N[&slot=S]` | the address space of CR3 (with `slot`, of `proc[S].pagetable`; with `root`, of the page table at that physical address, such as exec's new one held by a local; also for `physmap`, `frame`, `pagewalk`) at a step: mapped ranges with their rights and names (program segments, guard page, stack, heap, kernel image, kernel stacks, regions from `application.yml`; a device region that maps RAM is not named a device); the process's `p->sz` and the user addresses below it no page maps (kind `unmapped`: lazily grown heap); page-table pages in all and of the user half |
| GET | `/api/recordings/{rid}/physmap?step=N` | one character per 4 KB physical frame: kernel image, free (walked from `kmem.freelist`), this address space's page tables / user pages / kernel stacks, allocated elsewhere, low memory |
| GET | `/api/recordings/{rid}/frame?step=N&pa=0x..` | one frame: its kind, the virtual addresses that map it in CR3's page table, its place on the free list |
| GET | `/api/recordings/{rid}/timeline?from=A&to=B&buckets=W` | [A, B) in W buckets: per thread, the percent of each bucket it ran and the percent of that in user mode; the traps per bucket (syscall, interrupt, exception); for a boot, milestones read from the registers (cr0/cr4/efer bits turned on, the first cr3 loads, lgdt/lidt, the first user-mode step); `spans`: per process lane, the stretches it did not run by its `p->state` then (SLEEPING, RUNNABLE, ZOMBIE: every store to it is kept while indexing); `wakes`: the stores that made a sleeper RUNNABLE, from the waker's lane to the sleeper's; markers of kind `cr3`: page-table switches |
| GET | `/api/recordings/{rid}/names?step=N[&line=true]` | what the registers point at (`trapframe.rax on kernel stack of proc[2]`, `kmem.freelist`, `main+0x18`, `SYS_write`) and the memory operands of the instruction at the step: address, name, value before it runs and after (a store); with `line`, every instruction of the running line as it runs in this pass; `trap`: what the CPU did if the instruction takes or returns from a trap (IDT gate, rsp and TSS.rsp0, the words pushed or popped, cs/ss); at `int $64`, the argument registers by `user.h`'s prototype; `control`: a control register the instruction writes (before, after, the bits turned on and off, or whose page table cr3 held and holds); page-table entries named as `PT[4] of proc[2] pid 4 lazytouch (va 0x4000)` |
| GET | `/api/recordings/{rid}/variables?step=N` | the running function's parameters and locals: type, value, where it lives (register, stack, constant; "optimized out here"), from DWARF location lists; a register's value when the function was called (`DW_OP_entry_value`, read at that step); `unset`: in memory, not stored since the call began (a left-over value); `fields`: a struct's members or those a pointer points at; arrays as `{3, 4}` or a string; `table`: for a `pagetable_t`, its physical address |
| GET | `/api/recordings/{rid}/stack?step=N` | the stack in use: 8-byte slots from rsp to its top (return addresses, saved rbp, `struct trapframe` fields from DWARF) and the frames on it; `stale`: a trap-frame slot not written since the trap began |
| GET | `/api/recordings/{rid}/cpu?step=N` | the GDT (decoded entries and the segment registers that select them), the TSS in TR (rsp0, IST, I/O map base) and the IDT (present gates: handler, gate type, DPL, IST); GDTR / IDTR from the last `lgdt` / `lidt` before the step (its operand read from memory), or from the boot recording for a table loaded only before user code first ran; `pastLimit`: segment registers whose selector is past the GDT's limit |
| GET | `/api/recordings/{rid}/procs?step=N` | the process table: every `proc[]` slot in use (state, pid, name, parent, size, open files through `ftable`, what `chan` points at, the functions a stopped process is in from its saved rbp chain) and what each CPU runs (`cpus[i].proc`); field offsets and state names from DWARF |
| GET | `/api/recordings/{rid}/diff?from=A&to=B` | what changed from step A to step B: `proc[]` fields, `ftable.file[]` entries and pipes, frames taken from / put on `kmem.freelist` (named by what holds them), each process's user mappings; per change the step of the store that made it and the thread that ran it; when other threads ran in between, `timeline`: the changes to proc fields, files and pipes in time order, one per stretch a thread ran |
| GET | `/api/recordings/{rid}/nodes?ids=1,2` | tree nodes by id (members of a grouped entry) |
| GET | `/api/recordings/{rid}/disasm?step=N&before=B&after=A` | static disassembly around the instruction of a step |
| GET | `/api/recordings/{rid}/code?step=N&line=L` | the instructions a source line compiled to, in the function running at step (L = 0: that step's line) |
| GET | `/api/recordings/{rid}/stores?from=A&to=B[&last=N]` | stores in a step range (with `last`, the N newest, newest first), named when the address is a kernel global (`proc[2].state`) |

"State at step N" is the state just before instruction N runs.

## Phase 1 tools

`tracer/run.py` drives QEMU + plugin without the server (`--mode count`
for instruction counts, `full` for traces); `tracer/decode.py` summarizes
a trace by kernel function.
