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
History, next to the kernel's Boot recording.
A command's recording opens at its first user-mode C line. Syscalls
lists every system call and exception the way strace prints them
(`exec("echo", ["echo", "hi"]) = 2`, `page fault at 0x4000: user write
at main+0x18, page not present`); click one to go to the instruction
that made it. Event tree nodes, the Call Stack and the Timeline use the
same one-line form.
Timeline shows the recording over time, one lane per thread (kernel
and user mode, and the traps it took); click to go there, wheel to zoom.
Memory shows the address space of the page table in CR3, the walk of
one virtual address (following rip, rsp or cr2, or typed in) and what
every physical frame is used for; Stack shows the stack in use, slot by
slot. The Code panel shows the C (or .S) line that is running, with the
instructions it compiled to opened under it (click a line number to open
any other line); step by line
or by instruction (toggle in the title bar) with F10 (over), F11 or →
(into), ← (back), Shift+F11 (out), [ ] (previous / next trap),
Alt+F10 (step over the loop around the current instruction; with Shift,
back to before it), Ctrl+click on a line number or Ctrl+F10 (run to that
line, in any thread; with Shift, back to its last run). The
filter button next to Line / Instruction (on by default) skips the
kernel's lock helpers (`step-skip` in `application.yml`: acquire,
release, push_off, pop_off, holding, mycpu) when stepping by line and in
Events; `echo hi` drops from about 45,000 events to about 1,300. Panels
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
| Extra program | `extra/user/lazytouch.c` | `sbrklazy` then touch 3 pages: three page faults. Added to the image's copy of each kernel. |

## API

| Method | Path | |
|---|---|---|
| GET | `/api/kernels` | kernels, their user programs and boot recording status |
| GET | `/api/kernels/{id}/boot` | boot recording status; its recording id is `boot-<kernel>` |
| POST | `/api/sessions` `{"kernel": "rx64-x86-style"}` | boot a guest |
| POST | `/api/sessions/{id}/ready` | wait for the first prompt |
| POST | `/api/sessions/{id}/run` `{"command": "ls"}` | type a command, wait until indexed |
| POST | `/api/sessions/{id}/input` `{"data": "..."}` | raw console input |
| GET | `/api/sessions/{id}/console` | console text so far |
| GET / POST | `/api/shell` (`{"kernel": ...}`) | the UI's one shell: the running guest / boot a new one, stopping the others |
| WS | `/ws/sessions/{id}` | console in/out and recording status |
| GET | `/api/recordings/{rid}` | summary |
| GET | `/api/recordings/{rid}/syscalls` | system calls and exceptions in the order they started, strace-style: process, `write(1, "hi", 2)`, result, what the kernel wrote through pointer arguments, where a sleeping call went on |
| GET | `/api/recordings/{rid}/tree?node=-1[&skip=true]` | children of a node (with `skip`, calls of the `step-skip` functions are left out and their children move up); repeated call sequences come grouped (`count`, `members`), and so do runs of threads that take turns 3 or more times |
| GET | `/api/recordings/{rid}/state?step=N` | registers, instruction, symbol, path in the tree |
| GET | `/api/recordings/{rid}/step?from=N&op=...[&skip=true]` | where a step command leads (with `skip`, `line-into` and `line-back` go over calls of the `step-skip` functions): `next`, `prev`, `over`, `out` (instructions), `line-into`, `line-over`, `line-back` (source lines), `next-trap`, `prev-trap`, `loop-out`, `loop-back` (the innermost loop as compiled: backward jumps of the function); `found: false` if there was nowhere to go; moves follow one thread across context switches |
| GET | `/api/recordings/{rid}/run-to?from=N&file=kernel/proc.c&line=L[&back=true]` | the next (previous) time line L starts to run, in any thread; a line with no code means the next one that has code |
| GET | `/api/kernels/{id}/source?path=kernel/proc.c` | a source file of the kernel tree |
| GET | `/api/recordings/{rid}/memory?step=N&addr=0x..&len=L&space=virt\|phys` | memory at a step |
| GET | `/api/recordings/{rid}/pagewalk?step=N&va=0x..` | 4-level walk through CR3 at a step |
| GET | `/api/recordings/{rid}/regions?step=N` | the address space of CR3 at a step: mapped ranges with their rights and names (program segments, guard page, stack, heap, kernel image, kernel stacks, regions from `application.yml`) |
| GET | `/api/recordings/{rid}/physmap?step=N` | one character per 4 KB physical frame: kernel image, free (walked from `kmem.freelist`), this address space's page tables / user pages / kernel stacks, allocated elsewhere, low memory |
| GET | `/api/recordings/{rid}/frame?step=N&pa=0x..` | one frame: its kind, the virtual addresses that map it in CR3's page table, its place on the free list |
| GET | `/api/recordings/{rid}/timeline?from=A&to=B&buckets=W` | [A, B) in W buckets: per thread, the percent of each bucket it ran and the percent of that in user mode; the traps per bucket (syscall, interrupt, exception) |
| GET | `/api/recordings/{rid}/stack?step=N` | the stack in use: 8-byte slots from rsp to its top (return addresses, saved rbp, `struct trapframe` fields from DWARF) and the frames on it |
| GET | `/api/recordings/{rid}/nodes?ids=1,2` | tree nodes by id (members of a grouped entry) |
| GET | `/api/recordings/{rid}/disasm?step=N&before=B&after=A` | static disassembly around the instruction of a step |
| GET | `/api/recordings/{rid}/code?step=N&line=L` | the instructions a source line compiled to, in the function running at step (L = 0: that step's line) |
| GET | `/api/recordings/{rid}/stores?from=A&to=B[&last=N]` | stores in a step range (with `last`, the N newest, newest first) |

"State at step N" is the state just before instruction N runs.

## Phase 1 tools

`tracer/run.py` drives QEMU + plugin without the server (`--mode count`
for instruction counts, `full` for traces); `tracer/decode.py` summarizes
a trace by kernel function.
