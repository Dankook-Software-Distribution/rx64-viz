// Syscalls: every system call and exception of the open recording, in
// the order they ran, the way strace prints them: which process made
// it, the call with its real arguments, and what came back. A row opens
// at the user instruction that made the call (int $64, or the faulting
// instruction); the row the cursor is in is marked.

import { api, type SyscallRow } from "../api";
import { cursor, type CursorState } from "../cursor";
import { grouped } from "../format";
import { el, note, Panel } from "./panel";

export class SyscallsPanel extends Panel {
  private head = el("div", "panel-head", "System calls and exceptions in the order they ran · click one to go to it");
  private list = el("div", "syscall-list");
  private rid: string | null = null;
  private rows: SyscallRow[] = [];
  private els: HTMLElement[] = [];
  private marked: HTMLElement | null = null;

  constructor() {
    super("syscalls");
  }

  protected mount() {
    this.element.append(this.head, this.list);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
  }

  private async update(c: CursorState) {
    if (!c.rec) {
      this.rid = null;
      this.list.replaceChildren(note("Open a recording to see its system calls."));
      return;
    }
    if (c.rec.id !== this.rid) {
      const signal = this.fresh();
      this.list.replaceChildren(note("Loading…"));
      let rows: SyscallRow[];
      try {
        rows = await api.syscalls(c.rec.id, signal);
      } catch (e) {
        if (!signal.aborted) this.list.replaceChildren(note((e as Error).message, "error"));
        return;
      }
      if (signal.aborted) return;
      this.rid = c.rec.id;
      this.rows = rows;
      this.marked = null;
      this.render(c.rec.steps);
    }
    if (cursor.value.state) this.mark(cursor.value.state.step);
  }

  private render(steps: number) {
    if (!this.rows.length) {
      this.els = [];
      this.list.replaceChildren(note("No system calls or exceptions in this recording."));
      return;
    }
    const head = el("div", "sc-row sc-head");
    head.append(el("span", ""), el("span", "", "Process"), el("span", "", "Call"), el("span", "", "Result"));
    this.els = this.rows.map((r) => this.row(r, steps));
    this.list.replaceChildren(head, ...this.els);
  }

  private row(r: SyscallRow, steps: number): HTMLElement {
    const exc = r.kind === "exception";
    const e = el("div", `sc-row kind-${r.kind}`);
    e.append(el("span", `glyph codicon codicon-${exc ? "warning" : "symbol-event"}`));
    const proc = el("span", "proc");
    const m = /^(proc\[\d+\])\s+(.*)$/.exec(r.process ?? "");
    if (m) proc.append(el("span", "sc-slot", m[1] + " "), el("span", "sc-prog", m[2]));
    else proc.append(el("span", "sc-prog", r.process ?? ""));
    const call = el("span", "call");
    call.append(...colorize(r.text));
    const res = el("span", "result");
    if (exc) {
      /* an exception has no result; what happened goes on the second line */
    } else if (r.returned) {
      res.append(el("span", "eq", "= "), el("span", "tk-number", r.result ?? ""));
    } else if (r.name === "exit") {
      res.append(el("span", "dim", "does not return"));
      res.title = "exit() ends the process: it never comes back";
    } else if (r.end >= steps) {
      res.append(el("span", "dim", "still running"));
      res.title = "The recording ended before this call returned";
    } else {
      res.append(el("span", "dim", "did not return"));
    }
    e.append(proc, call, res);

    const more: HTMLElement[] = [];
    if (r.output) {
      const o = el("span", "out");
      if (!exc) o.append(el("span", "dim", "kernel wrote "), ...colorize(r.output));
      else o.append(el("span", "", r.output));
      more.push(o);
    }
    if (r.resumeStep !== null) {
      const slept = el("button", "slept");
      slept.append(el("span", "codicon codicon-debug-pause"), document.createTextNode(" slept"));
      slept.title = `This call slept while other processes ran, and went on at step ${grouped(r.resumeStep)}. Click to go there.`;
      const at = r.resumeStep;
      slept.onclick = (ev) => {
        ev.stopPropagation();
        void cursor.go(at);
      };
      more.push(slept);
    }
    if (more.length) {
      const line = el("span", "sc-more");
      line.append(...more);
      e.append(line);
    }
    const target = Math.max(0, r.step - 1);
    e.title = `${r.process}: ${r.text}${r.returned && r.result ? " = " + r.result : ""}\n`
      + (exc ? "Click: go to the instruction that caused it" : "Click: go to the instruction that made this call (int $64)");
    e.onclick = () => void cursor.go(target);
    return e;
  }

  /** Mark the call the cursor is in, or the last one before it. */
  private mark(step: number) {
    let lo = 0, hi = this.rows.length - 1, at = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (this.rows[mid].step - 1 <= step) {
        at = mid;
        lo = mid + 1;
      } else hi = mid - 1;
    }
    const e = at >= 0 ? this.els[at] : null;
    if (e === this.marked) {
      this.updateInside(at, step);
      return;
    }
    this.marked?.classList.remove("current", "inside");
    this.marked = e;
    if (!e) return;
    e.classList.add("current");
    this.updateInside(at, step);
    e.scrollIntoView({ block: "nearest" });
  }

  private updateInside(at: number, step: number) {
    if (at < 0 || !this.marked) return;
    const r = this.rows[at];
    this.marked.classList.toggle("inside", step >= r.step - 1 && step < r.end);
  }
}

/** write(1, "hi", 2) -> spans colored like code: the name, strings, numbers. */
function colorize(text: string): Node[] {
  const out: Node[] = [];
  const re = /("(?:[^"\\]|\\.)*"…?)|(\b0x[0-9a-f]+\b|-?\b\d+\b)|([A-Za-z_]\w*)(?=\()/g;
  let last = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    if (m.index > last) out.push(document.createTextNode(text.slice(last, m.index)));
    const cls = m[1] ? "tk-string" : m[2] ? "tk-number" : "tk-function";
    out.push(el("span", cls, m[0]));
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(document.createTextNode(text.slice(last)));
  return out;
}
