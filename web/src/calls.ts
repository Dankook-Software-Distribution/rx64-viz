// The system calls and exceptions of a recording, fetched once and
// shared by the panels that follow them (Syscalls, Changes).

import { api, type State, type SyscallRow } from "./api";

const cache = new Map<string, Promise<SyscallRow[]>>();

export function syscallsOf(rid: string): Promise<SyscallRow[]> {
  let p = cache.get(rid);
  if (!p) {
    p = api.syscalls(rid);
    p.catch(() => cache.delete(rid));
    cache.set(rid, p);
    if (cache.size > 4) cache.delete(cache.keys().next().value!);
  }
  return p;
}

/**
 * Index of the call the cursor is in, or the last one before it (-1:
 * none yet). With the cursor's path (state.path), a call whose trap is
 * on it wins: after a sleep, the cursor is back inside a call that
 * started long before other threads' calls; outside any call, the last
 * one of the thread running (not another process's that started later).
 */
export function callAt(rows: SyscallRow[], step: number, state?: State | null): number {
  if (state) {
    for (const n of state.path) {
      if (n.kind !== "trap" || n.entered == null) continue;
      const i = rows.findIndex((r) => r.step === n.entered);
      if (i >= 0) return i;
    }
  }
  let lo = 0, hi = rows.length - 1, at = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (rows[mid].step - 1 <= step) {
      at = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  if (state && state.slot !== null) {
    for (let i = at; i >= 0; i--) if (rows[i].slot === state.slot && rows[i].pid === state.pid) return i;
  }
  return at;
}

/** The step a call's row opens at: the instruction that made it (int $64, or the faulting one). */
export function callStart(r: SyscallRow): number {
  return Math.max(0, r.step - 1);
}
