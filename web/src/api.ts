// Types and calls for the server API (see README.md). 64-bit values are
// "0x..." strings; numbers are only used for counts and step indexes.

export type Hex = string;

export interface Summary {
  steps: number;
  userSteps: number;
  kernelSteps: number;
  stores: number;
  syscalls: number;
  interrupts: number;
  exceptions: number;
  switches: number;
  nodes: number;
  chunkBytes: number;
  indexMillis: number;
  /** where to open it: a command's first user-mode step (0 for a boot) */
  start: number;
}

export interface BootInfo {
  id: string;
  status: "WAITING" | "RECORDING" | "INDEXING" | "READY" | "FAILED";
  error: string | null;
  summary: Summary | null;
}

export interface KernelInfo {
  id: string;
  programs: string[];
  boot: BootInfo;
  /** functions the step filter skips (lock helpers) */
  stepSkip: string[];
}

export interface RecInfo {
  id: string;
  seq: number;
  command: string;
  status: "recording" | "indexing" | "ready" | "failed";
  error: string | null;
  summary: Summary | null;
}

export interface SessionInfo {
  id: string;
  kernel: string;
  state: "BOOTING" | "READY" | "RUNNING" | "STOPPED";
  recordings: RecInfo[];
}

export interface TreeNode {
  id: number;
  kind: "run" | "call" | "trap";
  label: string;
  start: number;
  end: number;
  children: number;
  continued: boolean;
  suspended: boolean;
  vector: number | null;
  count: number;
  members: number[] | null;
  /** a syscall or exception in one line: write(1, "hi", 2) = 2 */
  detail: string | null;
  /** where the frame really began (a continued one: in an earlier run); null if not found */
  entered: number | null;
  /** it began before the recording (found on the stack at the thread's first step) */
  earlier: boolean;
}

/** A system call or exception, strace-style (see Syscalls.java). */
export interface SyscallRow {
  step: number;
  /** where its trap ended */
  end: number;
  /** it came back to user mode */
  returned: boolean;
  /** where it went on after sleeping */
  resumeStep: number | null;
  /** "proc[2] echo" */
  process: string;
  kind: "syscall" | "exception";
  name: string;
  /** write(1, "hi", 2) or page fault at 0x4000 */
  text: string;
  result: string | null;
  /** what the kernel wrote through a pointer argument; for an exception, what happened */
  output: string | null;
  node: number;
  /** where the kernel function that handles it starts (sys_write; for an exception, the function trap() spent it in) */
  handlerStep: number | null;
  handler: string | null;
  /** the caller's process slot and pid (null: none), and the program that made the call */
  slot: number | null;
  pid: number | null;
  program: string | null;
  /** it began before the recording (arguments read back when it returned) */
  earlier: boolean;
  /** why it stopped running before it ended: its first sleep, else its first preemption */
  waited: Wait | null;
  /** every time it was switched out, in order */
  waits: Wait[];
  /** an exception that returned: where it went on (the faulting instruction again) */
  resumesAt: string | null;
}

/** See Syscalls.Wait: reason, what it slept on, who woke it. */
export interface Wait {
  reason: "sleep" | "preempted";
  /** last step before it was switched out */
  step: number;
  /** what its chan pointed at */
  on: string | null;
  /** the store that made it RUNNABLE again (-1: not found) */
  wokenStep: number;
  /** "proc[4] wc in piperead()" */
  by: string | null;
  /** where the call went on (-1: not in the recording) */
  resumed: number;
}

export interface Location {
  binary: string | null;
  symbol: string | null;
  label: string;
  disas: string | null;
  /** source file relative to the kernel tree (kernel/... or user/...) */
  file: string | null;
  line: number;
}

export interface State {
  step: number;
  pc: Hex;
  cpl: number;
  location: Location;
  registers: Record<string, Hex>;
  path: TreeNode[];
  /** real mode, protected mode 32-bit, compatibility mode, 64-bit mode */
  mode: string;
  /** registers the instruction just before this step wrote */
  changed: string[];
  /** the process of the thread running (slot, pid); null for the scheduler or boot */
  slot: number | null;
  pid: number | null;
}

export interface Line {
  addr: Hex;
  mnemonic: string;
  operands: string;
  function: string | null;
  current: boolean;
}

export interface SourceFile {
  kernel: string;
  path: string;
  lines: string[];
}

export interface Store {
  step: number;
  pa: Hex;
  size: number;
  value: Hex;
  old: Hex;
  /** what the address is in the kernel image (proc[2].state), or null */
  name: string | null;
}

export interface StackSlot {
  addr: Hex;
  value: Hex;
  mapped: boolean;
  /** what the kernel keeps there: "return address", "saved rbp", "trapframe.rip", ... */
  label: string | null;
  /** the code a code address points at ("syscall+0x3a") */
  target: string | null;
  /** for a trap frame's slot: who stored it (CPU, vector64, alltraps, syscall) */
  writer: string | null;
  /** a trap frame's slot nothing stored since the trap began: an older value */
  stale: boolean;
}

export interface StackFrame {
  function: string | null;
  /** [from, to) */
  from: Hex;
  to: Hex;
  kind: "frame" | "trapframe";
  /** step where the frame was entered, if recorded */
  step: number | null;
}

export interface Stack {
  step: number;
  kind: "kernel" | "trap" | "user" | "cpu" | "unknown";
  label: string;
  base: Hex;
  top: Hex;
  rsp: Hex;
  rbp: Hex;
  /** from rsp up to top, lowest address first */
  slots: StackSlot[];
  /** lowest address first */
  frames: StackFrame[];
}

export interface Region {
  va: Hex;
  end: Hex;
  kind: string;
  label: string;
  writable: boolean;
  user: boolean;
  executable: boolean;
  /** first frame (null: no frame, a range below p->sz not mapped) */
  pa: Hex | null;
  /** the frames follow each other */
  contiguous: boolean;
  pageSize: number;
  pages: number;
}

export interface Regions {
  step: number;
  /** false while CR0.PG is off: no regions, addresses are physical */
  paging: boolean;
  cr3: Hex;
  program: string | null;
  /** page-table pages of this address space */
  tables: number;
  /** of those, the user half's (the rest are shared with every process) */
  userTables: number;
  /** p->sz of the process this page table belongs to (null: none) */
  sz: Hex | null;
  regions: Region[];
}

/** Frame kinds, one character per 4 KB frame (see AddressSpace.java). */
export type FrameKind = "l" | "k" | "f" | "o" | "t" | "u" | "s" | "x";

export interface PhysMap {
  step: number;
  cr3: Hex;
  frameSize: number;
  frames: string;
  counts: Record<FrameKind, number>;
  freeHead: Hex;
  freeCount: number;
  freeError: string | null;
}

export interface PhysFrame {
  step: number;
  pa: Hex;
  kind: FrameKind;
  /** virtual addresses that map it in this address space */
  mappedAt: Hex[];
  /** position on kmem.freelist */
  freeIndex: number | null;
}

export interface WalkLevel {
  table: string;
  index: number;
  entryAddr: Hex;
  entry: Hex;
  present: boolean;
  writable: boolean;
  user: boolean;
  largePage: boolean;
  noExecute: boolean;
}

/** An address space other than CR3's: proc[slot]'s page table, or the table at root (physical). */
export interface Space {
  slot?: number;
  root?: Hex;
}

function spaceQuery(s: Space): string {
  return s.root !== undefined ? `&root=${s.root}` : s.slot !== undefined ? `&slot=${s.slot}` : "";
}

/** A page-table-entry bit the MMU ignores that the kernel names (riscv-style's PTE_R). */
export interface SoftBit {
  bit: number;
  name: string;
  what: string;
}

export interface Walk {
  step: number;
  /** false while CR0.PG is off: no levels, pa = va */
  paging: boolean;
  va: Hex;
  cr3: Hex;
  levels: WalkLevel[];
  /** null: not mapped */
  pa: Hex | null;
  pageSize: number;
  softBits: SoftBit[];
  /** whose table was walked ("proc[2] pid 3 sh", "kernel_pagetable"), or null */
  table: string | null;
}

export interface Memory {
  step: number;
  space: "virt" | "phys";
  addr: Hex;
  len: number;
  /** hex, two digits per byte */
  data: string;
}

export interface TimelineLane {
  thread: number;
  label: string;
  /** per bucket: percent of it this thread ran */
  run: number[];
  /** per bucket: percent of that in user mode */
  user: number[];
}

export interface TimelineMarker {
  bucket: number;
  /** the first trap of `count` of this kind in the bucket */
  step: number;
  kind: "syscall" | "interrupt" | "exception" | "cr3";
  label: string;
  count: number;
  lane: number;
}

/** A stretch a process did not run, by its p->state then. */
export interface TimelineSpan {
  lane: number;
  from: number;
  to: number;
  state: "SLEEPING" | "RUNNABLE" | "ZOMBIE";
}

/** The store that made a sleeping process RUNNABLE: made in lane `by`, for lane `lane`. */
export interface TimelineWake {
  step: number;
  by: number;
  lane: number;
  label: string;
}

export interface Timeline {
  from: number;
  to: number;
  buckets: number;
  lanes: TimelineLane[];
  markers: TimelineMarker[];
  /** boot only: control-register bits, page tables and descriptor tables loaded, the first user step */
  milestones: { step: number; label: string }[];
  spans: TimelineSpan[];
  wakes: TimelineWake[];
}

/** An open file descriptor (see Processes.java). */
export interface Fd {
  fd: number;
  addr: Hex;
  /** index in ftable.file[], or -1 */
  file: number;
  type: "none" | "pipe" | "inode" | "device" | string;
  readable: boolean;
  writable: boolean;
  ref: number;
  /** console, pipe 0x..., inode 12 */
  what: string;
  detail: string;
}

/** One proc[] slot in use. */
export interface Proc {
  slot: number;
  addr: Hex;
  pid: number;
  name: string;
  state: "USED" | "SLEEPING" | "RUNNABLE" | "RUNNING" | "ZOMBIE" | string;
  chan: Hex;
  /** what chan points at: proc[1], ticks, pipe 0x... nread, cons.r */
  chanName: string | null;
  /** kernel functions it is stopped in, innermost first (not running) */
  where: string[];
  /** where swtch() will continue it */
  resume: string | null;
  parent: number;
  parentPid: number;
  parentName: string | null;
  sz: number;
  killed: boolean;
  xstate: number;
  pagetable: Hex;
  kstack: Hex;
  trapframe: Hex;
  cpu: number | null;
  files: Fd[];
  cwd: string;
}

export interface Procs {
  step: number;
  nproc: number;
  procs: Proc[];
  cpus: { cpu: number; slot: number | null }[];
  error: string | null;
}

/** What changed between two steps (see StateDiff.java); step: the store that made it, by: who ran it. */
export interface FieldChange {
  field: string;
  before: string;
  after: string;
  step: number | null;
  by: string | null;
}

export interface ProcChange {
  slot: number;
  kind: "created" | "freed" | "changed";
  pid: number;
  name: string;
  fields: FieldChange[];
}

export interface FileChange {
  /** file[3] or pipe 0x... */
  object: string;
  before: string;
  after: string;
  step: number | null;
  by: string | null;
}

export interface FrameChange {
  pa: Hex;
  /** what the frame holds (null: no process's page table maps it) */
  what: string | null;
  step: number | null;
  by: string | null;
}

export interface MapChange {
  slot: number;
  pid: number;
  name: string;
  table: Hex;
  kind: "mapped" | "unmapped" | "remapped" | "rights";
  va: Hex;
  end: Hex;
  pages: number;
  label: string;
  paBefore: Hex | null;
  paAfter: Hex | null;
  rightsBefore: string | null;
  rightsAfter: string | null;
  step: number | null;
  by: string | null;
}

export interface Diff {
  from: number;
  to: number;
  procs: ProcChange[];
  files: FileChange[];
  freeBefore: number;
  freeAfter: number;
  freeError: string | null;
  allocated: FrameChange[];
  freed: FrameChange[];
  mappings: MapChange[];
  /** false: the range was too long to look for the stores (steps are null) */
  stepsFound: boolean;
  /** for a call during which other threads ran: every change in time order (null otherwise) */
  timeline: Moment[] | null;
  /** frames taken from the free list and put back during the range (null: not scanned) */
  passing: number | null;
}

/** One change in time order (see StateDiff.Moment). */
export interface Moment {
  step: number;
  by: string | null;
  /** the system call (read()) or exception it happened in, or null */
  call: string | null;
  /** proc[3] cat, file[1], pipe 0x... */
  object: string;
  /** a proc[] field (state, fd 1, ...), or "" */
  field: string;
  before: string;
  after: string;
}

/** How GDTR / IDTR got their value (see CpuTables.java). */
export interface TableLoad {
  known: boolean;
  /** taken from the boot recording (the table is loaded once, at boot) */
  fromBoot: boolean;
  /** the lgdt / lidt that loaded it, and its function */
  step: number | null;
  function: string | null;
  base: Hex | null;
  limit: number;
  /** cpus[0].gdt, kidt, cpuentry.uidt (at CPUENTRY) */
  name: string | null;
  /** when not known: the first load after the step */
  firstLoad: number | null;
}

export interface GdtEntry {
  index: number;
  raw: Hex;
  kind: "null" | "code" | "data" | "tss" | "tss-high" | "ldt" | "other";
  label: string;
  present: boolean;
  dpl: number;
  long64: boolean;
  busy: boolean;
  base: Hex | null;
  limit: Hex | null;
  /** segment registers that select it now */
  selectors: string[];
}

export interface Gate {
  vector: number;
  name: string;
  handler: Hex;
  handlerName: string | null;
  type: string;
  dpl: number;
  ist: number;
  selector: number;
}

export interface Tss {
  gdtIndex: number;
  base: Hex;
  name: string | null;
  rsp: (Hex | null)[];
  rsp0Name: string | null;
  ist: (Hex | null)[];
  iomb: number;
}

export interface CpuView {
  step: number;
  gdtr: TableLoad;
  gdt: GdtEntry[];
  tss: Tss | null;
  idtr: TableLoad;
  idt: Gate[];
  error: string | null;
  /** segment registers whose selector is past the GDT's limit ("ss 0x10") */
  pastLimit: string[];
}

export type StepOp =
  | "next" | "prev" | "over" | "out"
  | "line-into" | "line-over" | "line-back"
  | "next-trap" | "prev-trap"
  | "loop-out" | "loop-back";

/** Where a move leads; found: false if it had nowhere to go (step is then where it started). */
export interface Operand {
  text: string;
  addr: Hex;
  size: number;
  /** before the instruction (null: not mapped) */
  value: Hex | null;
  /** after it, if it changed (a store) */
  after: Hex | null;
  name: string | null;
}

/** What the CPU itself did at a trap boundary (see Names.Trap). */
export interface TrapStep {
  kind: "enter" | "leave";
  vector: number;
  gate: string | null;
  rspBefore: Hex;
  rspAfter: Hex;
  rsp0: string | null;
  /** [field, value], top of the stack first */
  words: [string, string][];
  cs: string;
  ss: string;
  rflags: string;
}

/** See Names.Control: a control register written, before and after, and what changed. */
export interface ControlWrite {
  reg: string;
  before: Hex;
  after: Hex;
  /** "cr0: +PG", or for cr3 whose page table it was and is */
  change: string;
}

/** What the registers and the current instruction's memory operands point at (see Names.java). */
export interface Names {
  step: number;
  registers: Record<string, string>;
  operands: Operand[];
  /** with line=true: each instruction of the running line as it runs in this pass */
  line: { step: number; addr: Hex; operands: Operand[]; trap: TrapStep | null; control: ControlWrite | null }[];
  trap: TrapStep | null;
  /** a control register this instruction writes (mov to cr3, wrmsr to EFER) */
  control: ControlWrite | null;
}

/** The running function's parameters and locals (see Variables.java). */
export interface Variables {
  step: number;
  function: string | null;
  vars: VarValue[];
}

/** A variable (or a field of one): see Variables.Value. */
export interface VarValue {
  name: string;
  type: string;
  param: boolean;
  line: number;
  value: string | null;
  where: string;
  points: string | null;
  /** in memory, and no store has written it since the call began: a left-over value */
  unset: boolean;
  /** a struct's fields, or those of the struct a pointer points at */
  fields: VarValue[] | null;
  /** the DWARF location expression, when it was not understood */
  dwarf: string | null;
  /** a pagetable_t: the physical address of its table */
  table: Hex | null;
}

export interface StepResult {
  /** other threads that ran between the start and the result */
  ran: string[];
  step: number;
  found: boolean;
}

export class ApiError extends Error {}

async function call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const r = await fetch(path, {
    method,
    headers: body === undefined ? {} : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  const text = await r.text();
  if (!r.ok) {
    let msg = `${r.status} ${r.statusText}`;
    try {
      msg = JSON.parse(text).error ?? msg;
    } catch {
      /* not JSON */
    }
    throw new ApiError(msg);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

const rec = (rid: string) => `/api/recordings/${encodeURIComponent(rid)}`;

export const api = {
  kernels: () => call<KernelInfo[]>("GET", "/api/kernels"),
  shell: async () => (await call<SessionInfo | undefined>("GET", "/api/shell")) ?? null,
  newShell: (kernel: string) => call<SessionInfo>("POST", "/api/shell", { kernel }),
  /** shells replaced earlier, newest first: their recordings still open */
  pastShells: () => call<SessionInfo[]>("GET", "/api/shell/past"),
  /** skip: leave out calls of the step filter's functions (their children move up) */
  tree: (rid: string, node: number, skip: boolean, signal?: AbortSignal) =>
    call<TreeNode[]>("GET", `${rec(rid)}/tree?node=${node}&skip=${skip}`, undefined, signal),
  nodes: (rid: string, ids: number[], skip: boolean, signal?: AbortSignal) =>
    call<TreeNode[]>("GET", `${rec(rid)}/nodes?ids=${ids.join(",")}&skip=${skip}`, undefined, signal),
  state: (rid: string, step: number, signal?: AbortSignal) =>
    call<State>("GET", `${rec(rid)}/state?step=${step}`, undefined, signal),
  step: (rid: string, from: number, op: StepOp, skip: boolean) =>
    call<StepResult>("GET", `${rec(rid)}/step?from=${from}&op=${op}&skip=${skip}`),
  /** the next (back: previous) time file:line starts to run, in any thread */
  runTo: (rid: string, from: number, file: string, line: number, back: boolean) =>
    call<StepResult>("GET", `${rec(rid)}/run-to?from=${from}&file=${encodeURIComponent(file)}&line=${line}&back=${back}`),
  disasm: (rid: string, step: number, signal?: AbortSignal) =>
    call<Line[]>("GET", `${rec(rid)}/disasm?step=${step}&before=24&after=60`, undefined, signal),
  code: (rid: string, step: number, line = 0, signal?: AbortSignal) =>
    call<Line[]>("GET", `${rec(rid)}/code?step=${step}&line=${line}`, undefined, signal),
  source: (kernel: string, path: string) =>
    call<SourceFile>("GET", `/api/kernels/${kernel}/source?path=${encodeURIComponent(path)}`),
  stack: (rid: string, step: number, signal?: AbortSignal) =>
    call<Stack>("GET", `${rec(rid)}/stack?step=${step}`, undefined, signal),
  names: (rid: string, step: number, signal?: AbortSignal, line = false) =>
    call<Names>("GET", `${rec(rid)}/names?step=${step}&line=${line}`, undefined, signal),
  variables: (rid: string, step: number, signal?: AbortSignal) =>
    call<Variables>("GET", `${rec(rid)}/variables?step=${step}`, undefined, signal),
  /* space: proc[slot]'s page table, or the one at root, instead of CR3's */
  regions: (rid: string, step: number, signal?: AbortSignal, space: Space = {}) =>
    call<Regions>("GET", `${rec(rid)}/regions?step=${step}${spaceQuery(space)}`, undefined, signal),
  physmap: (rid: string, step: number, signal?: AbortSignal, space: Space = {}) =>
    call<PhysMap>("GET", `${rec(rid)}/physmap?step=${step}${spaceQuery(space)}`, undefined, signal),
  frame: (rid: string, step: number, pa: Hex, signal?: AbortSignal, space: Space = {}) =>
    call<PhysFrame>("GET", `${rec(rid)}/frame?step=${step}&pa=${pa}${spaceQuery(space)}`, undefined, signal),
  pagewalk: (rid: string, step: number, va: Hex, signal?: AbortSignal, space: Space = {}) =>
    call<Walk>("GET", `${rec(rid)}/pagewalk?step=${step}&va=${va}${spaceQuery(space)}`, undefined, signal),
  memory: (rid: string, step: number, addr: Hex, len: number, space: "virt" | "phys", signal?: AbortSignal) =>
    call<Memory>("GET", `${rec(rid)}/memory?step=${step}&addr=${addr}&len=${len}&space=${space}`, undefined, signal),
  timeline: (rid: string, from: number, to: number, buckets: number, signal?: AbortSignal) =>
    call<Timeline>("GET", `${rec(rid)}/timeline?from=${from}&to=${to}&buckets=${buckets}`, undefined, signal),
  syscalls: (rid: string, signal?: AbortSignal) =>
    call<SyscallRow[]>("GET", `${rec(rid)}/syscalls`, undefined, signal),
  cpu: (rid: string, step: number, signal?: AbortSignal) =>
    call<CpuView>("GET", `${rec(rid)}/cpu?step=${step}`, undefined, signal),
  procs: (rid: string, step: number, signal?: AbortSignal) =>
    call<Procs>("GET", `${rec(rid)}/procs?step=${step}`, undefined, signal),
  diff: (rid: string, from: number, to: number, signal?: AbortSignal) =>
    call<Diff>("GET", `${rec(rid)}/diff?from=${from}&to=${to}`, undefined, signal),
  /** the newest `last` stores before step `to`, newest first */
  stores: (rid: string, from: number, to: number, last: number, signal?: AbortSignal) =>
    call<Store[]>("GET", `${rec(rid)}/stores?from=${from}&to=${to}&last=${last}`, undefined, signal),
};

/** The console WebSocket of a session. */
export function consoleSocket(session: string): WebSocket {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return new WebSocket(`${proto}//${location.host}/ws/sessions/${session}`);
}
