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
The Code panel shows the C (or .S) line that is running, with the
instructions it compiled to opened under it (click a line number to open
any other line); step by line
or by instruction (toggle in the title bar) with F10 (over), F11 or →
(into), ← (back), Shift+F11 (out), [ ] (previous / next trap). Panels
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
| Trace plugin | `tracer/plugin/rxtrace.c` | QEMU TCG plugin. Counts only (fast) until a mark, then records every instruction with the registers it changed and every store. One zstd file per segment, plus a physical-memory snapshot at its start. |
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
| GET | `/api/recordings/{rid}/tree?node=-1` | children of a node; repeated call sequences come grouped (`count`, `members`) |
| GET | `/api/recordings/{rid}/state?step=N` | registers, instruction, symbol, path in the tree |
| GET | `/api/recordings/{rid}/step?from=N&op=...` | where a step command leads: `next`, `prev`, `over`, `out` (instructions), `line-into`, `line-over`, `line-back` (source lines), `next-trap`, `prev-trap`; moves follow one thread across context switches |
| GET | `/api/kernels/{id}/source?path=kernel/proc.c` | a source file of the kernel tree |
| GET | `/api/recordings/{rid}/memory?step=N&addr=0x..&len=L&space=virt\|phys` | memory at a step |
| GET | `/api/recordings/{rid}/pagewalk?step=N&va=0x..` | 4-level walk through CR3 at a step |
| GET | `/api/recordings/{rid}/nodes?ids=1,2` | tree nodes by id (members of a grouped entry) |
| GET | `/api/recordings/{rid}/disasm?step=N&before=B&after=A` | static disassembly around the instruction of a step |
| GET | `/api/recordings/{rid}/code?step=N&line=L` | the instructions a source line compiled to, in the function running at step (L = 0: that step's line) |
| GET | `/api/recordings/{rid}/stores?from=A&to=B[&last=N]` | stores in a step range (with `last`, the N newest, newest first) |

"State at step N" is the state just before instruction N runs.

## Phase 1 tools

`tracer/run.py` drives QEMU + plugin without the server (`--mode count`
for instruction counts, `full` for traces); `tracer/decode.py` summarizes
a trace by kernel function.
