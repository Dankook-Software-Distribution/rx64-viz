// Processes: the kernel's process table (proc[]) at the cursor, read
// from the recorded memory. One row per slot in use: pid, name, state,
// where a process that is not running is stopped (its saved call chain)
// and what it waits for, its parent, size and open files. Values that
// changed since the previous position are marked; a row opens to show
// the rest of struct proc.

import { api, type Fd, type Proc, type Procs } from "../api";
import { cursor, memorySpace, showPanel, type CursorState } from "../cursor";
import { el, note, Panel, placeholder } from "./panel";

/** What each state of enum procstate means in these kernels. */
export const STATE_TITLE: Record<string, string> = {
  UNUSED: "Free slot",
  USED: "Allocated by allocproc(), not runnable yet",
  RUNNABLE: "Ready: the scheduler may pick it to run",
  RUNNING: "Running on a CPU",
  SLEEPING: "Waiting in sleep() until someone calls wakeup() on its chan",
  ZOMBIE: "Exited: its parent has not collected it with wait() yet",
};

const COLUMNS = ["", "Slot", "PID", "Name", "State", "Stopped in / waiting for", "Parent", "Size", "Open files", ""];

export class ProcessesPanel extends Panel {
  private head = el("div", "panel-head");
  private body = el("div", "proc-table");
  private open = new Set<number>();
  /** cell texts of the last table shown, by slot: what "changed" is measured against */
  private last: { rid: string; step: number; cells: Map<number, string[]> } | null = null;
  private dirty = false;

  constructor() {
    super("processes");
  }

  protected mount() {
    this.element.append(this.head, this.body);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
    this.onResize(() => {
      if (this.dirty && this.visible()) void this.update(cursor.value);
    });
  }

  private async update(c: CursorState) {
    const s = c.state;
    if (!c.rec || !s) {
      this.head.textContent = "";
      this.body.replaceChildren(placeholder(c, "Open a recording to see its processes."));
      this.last = null;
      return;
    }
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const signal = this.fresh();
    let v: Procs;
    try {
      v = await api.procs(c.rec.id, s.step, signal);
    } catch (e) {
      if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
      return;
    }
    if (signal.aborted) return;
    const before = this.last && this.last.rid === c.rec.id && this.last.step === c.prev?.step ? this.last.cells : null;
    const cells = new Map(v.procs.map((p) => [p.slot, cellTexts(p)]));
    this.last = { rid: c.rec.id, step: v.step, cells };
    this.render(v, before);
  }

  private render(v: Procs, before: Map<number, string[]> | null) {
    if (v.error) {
      this.head.textContent = "";
      this.body.replaceChildren(note(v.error, "error"));
      return;
    }
    const bySlot = new Map(v.procs.map((p) => [p.slot, p]));
    const cpus = v.cpus.map((c) => {
      const p = c.slot !== null ? bySlot.get(c.slot) : undefined;
      return `CPU ${c.cpu}: ${p ? `proc[${p.slot}] ${p.name}` : "scheduler (no process)"}`;
    });
    this.head.textContent = `proc[] · ${v.procs.length} of ${v.nproc} slots in use · ${cpus.join(" · ")}`;
    if (!v.procs.length) {
      this.body.replaceChildren(note("No process exists yet: every proc[] slot is UNUSED."));
      return;
    }
    const frag = document.createDocumentFragment();
    const h = el("div", "pt-row pt-head");
    for (const c of COLUMNS) h.append(el("span", "", c));
    frag.append(h);
    for (const p of v.procs) {
      const texts = cellTexts(p);
      const prev = before?.get(p.slot);
      const row = el("div", `pt-row state-${p.state.toLowerCase()}` + (p.cpu !== null ? " on-cpu" : "") + (prev ? "" : before ? " new" : ""));
      const isOpen = this.open.has(p.slot);
      row.append(el("span", `glyph codicon codicon-${isOpen ? "chevron-down" : "chevron-right"}`));
      const cls = ["pslot", "pid", "name", "state", "where", "parent", "size", "files"];
      texts.forEach((t, i) => {
        const c = el("span", cls[i] + (prev && prev[i] !== t ? " changed" : ""));
        if (cls[i] === "state") {
          c.append(el("span", "badge", t));
          c.title = STATE_TITLE[p.state] ?? "";
        } else if (cls[i] === "where") {
          c.append(...whereNodes(p, cursor.value.state?.slot ?? null));
          c.title = whereTitle(p);
        } else if (cls[i] === "files") {
          c.append(...fileNodes(p.files));
          c.title = p.files.map(fdLine).join("\n");
        } else {
          c.textContent = t;
        }
        row.append(c);
      });
      const mem = el("button", "pt-mem codicon codicon-layers");
      mem.title = `Show proc[${p.slot}]'s address space in Memory (its page table)`;
      mem.onclick = (ev) => {
        ev.stopPropagation();
        memorySpace.set({ slot: p.slot, name: p.name });
        showPanel("memory");
      };
      row.append(mem);
      row.onclick = () => {
        if (isOpen) this.open.delete(p.slot);
        else this.open.add(p.slot);
        this.render(v, before);
      };
      frag.append(row);
      if (isOpen) frag.append(detail(p));
    }
    this.body.replaceChildren(frag);
  }
}

/** The text of each column after the glyph, for marking changes. */
function cellTexts(p: Proc): string[] {
  return [
    `proc[${p.slot}]`,
    String(p.pid),
    p.name,
    p.state,
    whereTitle(p),
    p.parent >= 0 ? `proc[${p.parent}] ${p.parentName ?? ""}` : "",
    size(p.sz),
    p.files.map(fdLine).join("|"),
  ];
}

function size(sz: number): string {
  return sz % 1024 === 0 ? `${sz / 1024} KB` : `${sz} B`;
}

/** yield() ← trap(), at most n functions. */
function chain(p: Proc, n: number): string {
  const w = p.where.slice(0, n).map((f) => f + "()").join(" ← ");
  return p.where.length > n ? w + " ← …" : w;
}

function whereTitle(p: Proc): string {
  if (p.cpu !== null && p.state !== "RUNNING")
    return `${p.state}, but CPU ${p.cpu} still runs it (cpus[${p.cpu}].proc): sleep() and yield() change the state before sched() calls swtch()`;
  switch (p.state) {
    case "RUNNING":
      return p.cpu !== null ? `running on CPU ${p.cpu}` : "running";
    case "ZOMBIE":
      return `exited with status ${p.xstate}`;
    case "SLEEPING":
      return `${p.where.join(" ← ")} · on ${p.chanName ?? p.chan}`;
    default:
      if (!p.where.length && p.resume) return `has not run yet: starts in ${p.resume}()`;
      return p.where.join(" ← ");
  }
}

/** What a sleep channel is, or that there is none (chan is cleared before the state changes back). */
function chanText(p: Proc): string {
  return BigInt(p.chan) === 0n ? "nothing (chan already 0)" : p.chanName ?? p.chan;
}

function whereNodes(p: Proc, running: number | null): Node[] {
  if (p.cpu !== null && p.state !== "RUNNING") {
    /* sleep() / yield() set the state first, then sched() switches away;
     * the scheduler clears cpus[c].proc a few lines after swtch returns */
    const out: Node[] = [el("span", "dim", running === p.slot
      ? `still on CPU ${p.cpu}, on its way out through sched()`
      : `switched out (cpus[${p.cpu}].proc still names it until the scheduler clears it)`)];
    if (p.state === "SLEEPING") out.push(el("span", "dim", " · on "), el("span", "chan", chanText(p)));
    return out;
  }
  switch (p.state) {
    case "RUNNING":
      return [el("span", "dim", p.cpu !== null ? `running on CPU ${p.cpu}` : "running")];
    case "ZOMBIE":
      return [el("span", "dim", `exited with status ${p.xstate}`)];
    case "SLEEPING": {
      /* where[0] is sleep() itself: name who called it */
      const caller = p.where[0] === "sleep" && p.where.length > 1 ? p.where[1] : p.where[0];
      return [el("span", "dim", "in "), el("span", "fn", `${caller ?? "?"}()`), el("span", "dim", " · on "),
        el("span", "chan", chanText(p))];
    }
    default:
      if (!p.where.length && p.resume)
        return [el("span", "dim", "has not run yet: starts in "), el("span", "fn", p.resume + "()")];
      return [el("span", "dim", "in "), el("span", "fn", chain(p, 2))];
  }
}

/** A file's short name: console, pipe (r), inode 12. */
function fileShort(f: Fd): string {
  if (f.type === "pipe") return `pipe (${f.readable ? "r" : ""}${f.writable ? "w" : ""})`;
  return f.what;
}

/** Descriptors grouped by the file object they point to: "0 1 2 console · 3 pipe (r)". */
function fileNodes(files: Fd[]): Node[] {
  if (!files.length) return [el("span", "dim", "none")];
  /* fd by fd: "0:console 1:pipe(w) 2:console" (the same file shows its file[] in the title) */
  const out: Node[] = [];
  files.forEach((f, i) => {
    if (i) out.push(document.createTextNode(" "));
    const e = el("span", "fd-item");
    e.append(el("span", "fds", `${f.fd}:`), document.createTextNode(fileShort(f).replace(" ", "")));
    e.title = fdLine(f);
    out.push(e);
  });
  return out;
}

function fdLine(f: Fd): string {
  const rw = (f.readable ? "r" : "") + (f.writable ? "w" : "");
  return `fd ${f.fd} → ${f.file >= 0 ? `file[${f.file}] ` : ""}${f.what} (${rw}) · ref ${f.ref}${f.detail ? " · " + f.detail : ""}`;
}

/** The rest of struct proc, under its row. */
function detail(p: Proc): HTMLElement {
  const d = el("div", "pt-detail");
  const kv = (k: string, v: string, title?: string) => {
    const row = el("div", "kv");
    row.append(el("span", "k", k), el("span", "v", v));
    if (title) row.title = title;
    d.append(row);
  };
  kv("struct proc", `proc[${p.slot}] at ${p.addr}`);
  kv("state", `${p.state} · ${STATE_TITLE[p.state] ?? ""}`);
  if (p.where.length) kv("stopped in", p.where.join(" ← "), "Return addresses up the saved rbp chain on its kernel stack, innermost first");
  if (p.resume && p.state !== "RUNNING") kv("resumes in", p.resume, "context saved by swtch(): where this process continues when the scheduler picks it");
  if (p.state === "SLEEPING") kv("chan", `${p.chan}${p.chanName ? " · " + p.chanName : ""}`, "sleep(chan): wakeup(chan) makes it RUNNABLE again");
  kv("parent", p.parent >= 0 ? `proc[${p.parent}] ${p.parentName ?? ""} (pid ${p.parentPid})` : "none");
  kv("sz", `${p.sz} bytes of user memory`);
  kv("pagetable", `${p.pagetable} (its user page table)`);
  kv("kstack", `${p.kstack} (its kernel stack)`);
  kv("trapframe", p.trapframe);
  if (p.killed) kv("killed", "yes");
  if (p.state === "ZOMBIE") kv("xstate", `${p.xstate} (exit status for wait())`);
  kv("cwd", p.cwd || "none");
  for (const f of p.files) kv(`ofile[${f.fd}]`, fdLine(f).replace(/^fd \d+ → /, ""));
  return d;
}
