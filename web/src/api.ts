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
}

export interface StackSlot {
  addr: Hex;
  value: Hex;
  mapped: boolean;
  /** what the kernel keeps there: "return address", "saved rbp", "trapframe.rip", ... */
  label: string | null;
  /** the code a code address points at ("syscall+0x3a") */
  target: string | null;
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
  /** first frame */
  pa: Hex;
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
  kind: "syscall" | "interrupt" | "exception";
  label: string;
  count: number;
  lane: number;
}

export interface Timeline {
  from: number;
  to: number;
  buckets: number;
  lanes: TimelineLane[];
  markers: TimelineMarker[];
}

export type StepOp =
  | "next" | "prev" | "over" | "out"
  | "line-into" | "line-over" | "line-back"
  | "next-trap" | "prev-trap"
  | "loop-out" | "loop-back";

/** Where a move leads; found: false if it had nowhere to go (step is then where it started). */
export interface StepResult {
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
  regions: (rid: string, step: number, signal?: AbortSignal) =>
    call<Regions>("GET", `${rec(rid)}/regions?step=${step}`, undefined, signal),
  physmap: (rid: string, step: number, signal?: AbortSignal) =>
    call<PhysMap>("GET", `${rec(rid)}/physmap?step=${step}`, undefined, signal),
  frame: (rid: string, step: number, pa: Hex, signal?: AbortSignal) =>
    call<PhysFrame>("GET", `${rec(rid)}/frame?step=${step}&pa=${pa}`, undefined, signal),
  pagewalk: (rid: string, step: number, va: Hex, signal?: AbortSignal) =>
    call<Walk>("GET", `${rec(rid)}/pagewalk?step=${step}&va=${va}`, undefined, signal),
  memory: (rid: string, step: number, addr: Hex, len: number, space: "virt" | "phys", signal?: AbortSignal) =>
    call<Memory>("GET", `${rec(rid)}/memory?step=${step}&addr=${addr}&len=${len}&space=${space}`, undefined, signal),
  timeline: (rid: string, from: number, to: number, buckets: number, signal?: AbortSignal) =>
    call<Timeline>("GET", `${rec(rid)}/timeline?from=${from}&to=${to}&buckets=${buckets}`, undefined, signal),
  syscalls: (rid: string, signal?: AbortSignal) =>
    call<SyscallRow[]>("GET", `${rec(rid)}/syscalls`, undefined, signal),
  /** the newest `last` stores before step `to`, newest first */
  stores: (rid: string, from: number, to: number, last: number, signal?: AbortSignal) =>
    call<Store[]>("GET", `${rec(rid)}/stores?from=${from}&to=${to}&last=${last}`, undefined, signal),
};

/** The console WebSocket of a session. */
export function consoleSocket(session: string): WebSocket {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return new WebSocket(`${proto}//${location.host}/ws/sessions/${session}`);
}
