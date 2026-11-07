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

export type StepOp =
  | "next" | "prev" | "over" | "out"
  | "line-into" | "line-over" | "line-back"
  | "next-trap" | "prev-trap";

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
  tree: (rid: string, node: number, signal?: AbortSignal) =>
    call<TreeNode[]>("GET", `${rec(rid)}/tree?node=${node}`, undefined, signal),
  nodes: (rid: string, ids: number[], signal?: AbortSignal) =>
    call<TreeNode[]>("GET", `${rec(rid)}/nodes?ids=${ids.join(",")}`, undefined, signal),
  state: (rid: string, step: number, signal?: AbortSignal) =>
    call<State>("GET", `${rec(rid)}/state?step=${step}`, undefined, signal),
  step: (rid: string, from: number, op: StepOp) =>
    call<{ step: number }>("GET", `${rec(rid)}/step?from=${from}&op=${op}`),
  disasm: (rid: string, step: number, signal?: AbortSignal) =>
    call<Line[]>("GET", `${rec(rid)}/disasm?step=${step}&before=24&after=60`, undefined, signal),
  code: (rid: string, step: number, line = 0, signal?: AbortSignal) =>
    call<Line[]>("GET", `${rec(rid)}/code?step=${step}&line=${line}`, undefined, signal),
  source: (kernel: string, path: string) =>
    call<SourceFile>("GET", `/api/kernels/${kernel}/source?path=${encodeURIComponent(path)}`),
  /** the newest `last` stores before step `to`, newest first */
  stores: (rid: string, from: number, to: number, last: number, signal?: AbortSignal) =>
    call<Store[]>("GET", `${rec(rid)}/stores?from=${from}&to=${to}&last=${last}`, undefined, signal),
};

/** The console WebSocket of a session. */
export function consoleSocket(session: string): WebSocket {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return new WebSocket(`${proto}//${location.host}/ws/sessions/${session}`);
}
