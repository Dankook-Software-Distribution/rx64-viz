// Syscalls: every system call and exception of the open recording, in
// the order they ran, the way strace prints them: which process made
// it, the call with its real arguments, and what came back. A row opens
// at the user instruction that made the call (int $64, or the faulting
// instruction); its handler link goes to the kernel function that
// handles it (sys_write). Runs of one-byte reads or writes on one fd
// (a shell reading a line, wc printing digits) are folded into one row
// with the bytes joined. The row the cursor is in is marked.

import type { SyscallRow } from "../api";
import { callAt, callStart, syscallsOf } from "../calls";
import { cursor, type CursorState } from "../cursor";
import { grouped } from "../format";
import { el, note, Panel } from "./panel";

export class SyscallsPanel extends Panel {
  private head = el("div", "panel-head", "System calls and exceptions in the order they ran · click one to go to it");
  private list = el("div", "syscall-list");
  private rid: string | null = null;
  private rows: SyscallRow[] = [];
  /** the element that stands for each row: the row, or its folded group */
  private els: HTMLElement[] = [];
  private marked: HTMLElement | null = null;
  /** folded groups the user opened, by their first row */
  private opened = new Set<number>();
  private steps = 0;

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
        rows = await syscallsOf(c.rec.id);
      } catch (e) {
        if (!signal.aborted) this.list.replaceChildren(note((e as Error).message, "error"));
        return;
      }
      if (signal.aborted) return;
      this.rid = c.rec.id;
      this.rows = rows;
      this.opened.clear();
      this.steps = c.rec.steps;
      this.render();
    }
    if (cursor.value.state) this.mark(cursor.value.state.step);
  }

  private render() {
    this.marked = null;
    if (!this.rows.length) {
      this.els = [];
      this.list.replaceChildren(note("No system calls or exceptions in this recording."));
      return;
    }
    const head = el("div", "sc-row sc-head");
    head.append(el("span", ""), el("span", "", "Process"), el("span", "", "Call"), el("span", "", "Result"));
    const out: HTMLElement[] = [head];
    this.els = new Array(this.rows.length);
    for (const [a, b] of folds(this.rows)) {
      if (a === b) {
        out.push((this.els[a] = this.row(this.rows[a])));
        continue;
      }
      const open = this.opened.has(a);
      const g = this.group(a, b, open);
      out.push(g);
      for (let i = a; i <= b; i++) {
        if (open) {
          const e = this.row(this.rows[i]);
          e.classList.add("member");
          out.push((this.els[i] = e));
        } else this.els[i] = g;
      }
    }
    this.list.replaceChildren(...out);
    if (cursor.value.state) this.mark(cursor.value.state.step);
  }

  /** A folded run of rows a..b: read(0, …) ×15 · kernel wrote "cat README | wc\n". */
  private group(a: number, b: number, open: boolean): HTMLElement {
    const r0 = this.rows[a];
    const e = el("div", "sc-row sc-group");
    e.append(el("span", `glyph codicon codicon-${open ? "chevron-down" : "chevron-right"}`));
    const proc = processCell(r0);
    const call = el("span", "call");
    call.append(el("span", "tk-function", r0.name), document.createTextNode(`(${firstArg(r0.text)}, …)`),
      el("span", "times", ` ×${b - a + 1}`));
    e.append(proc, call, el("span", "result"));
    const bytes = joined(this.rows.slice(a, b + 1));
    if (bytes !== null) {
      const line = el("span", "sc-more");
      const o = el("span", "out");
      o.append(el("span", "dim", r0.name === "read" ? "kernel wrote " : "wrote "), el("span", "tk-string", `"${bytes}"`));
      line.append(o);
      e.append(line);
    }
    e.title = `${b - a + 1} ${r0.name} calls of ${r0.process}, one after another. Click to ${open ? "fold" : "show"} them.`;
    e.onclick = () => {
      if (open) this.opened.delete(a);
      else this.opened.add(a);
      this.render();
    };
    return e;
  }

  private row(r: SyscallRow): HTMLElement {
    const steps = this.steps;
    const exc = r.kind === "exception";
    const e = el("div", `sc-row kind-${r.kind}`);
    e.append(el("span", `glyph codicon codicon-${exc ? "warning" : "symbol-event"}`));
    const proc = processCell(r);
    const call = el("span", "call");
    call.append(...colorize(r.text));
    const res = el("span", "result");
    if (exc) {
      /* an exception has no result; what happened goes on the second line */
    } else if (r.returned) {
      res.append(el("span", "eq", "= "), el("span", "tk-number", r.result ?? ""));
      /* fork: the child's slot, from the calls the child makes */
      if (r.name === "fork" && r.result && Number(r.result) > 0) {
        const child = this.rows.find((x) => x.pid === Number(r.result));
        const slot = child?.slot != null ? `proc[${child.slot}]` : null;
        if (slot) {
          const c = el("span", "dim", ` (${slot})`);
          c.title = `pid ${r.result} is ${slot}`;
          res.append(c);
        }
      }
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
    if (r.earlier) {
      const b = el("span", "earlier", "began before the recording");
      b.title = "This call was already under way when the recording started (it was waiting): "
        + "its frames were found on the kernel stack, its arguments read back from the trap frame when it returned";
      more.push(b);
    }
    /* every time it slept: on what, who woke it, where it went on; preemptions counted */
    const sleeps = r.waits.filter((w) => w.reason === "sleep");
    const pre = r.waits.filter((w) => w.reason === "preempted");
    sleeps.forEach((w, k) => {
      const nth = sleeps.length > 1 ? `${k + 1}. ` : "";
      more.push(goButton("slept", "debug-pause", nth + (w.on ? `slept on ${w.on}` : "slept"),
        `This call called sleep()${w.on ? ` on ${w.on} (p->chan)` : ""} and other processes ran. `
          + `Click: go to the moment it was switched out (step ${grouped(w.step)}, in swtch)`, w.step));
      if (w.by && w.wokenStep >= 0)
        more.push(goButton("slept", "bell", `woken by ${w.by}`,
          `The store that made it RUNNABLE again (step ${grouped(w.wokenStep)}). Click to go there.`, w.wokenStep));
      if (w.resumed >= 0)
        more.push(goButton("slept", "debug-continue", "went on",
          `Where this call went on after the switch (step ${grouped(w.resumed)}). Click to go there.`, w.resumed));
    });
    if (pre.length) {
      more.push(goButton("slept preempted", "watch", pre.length > 1 ? `preempted ×${pre.length}` : "preempted",
        `A timer interrupt made this call yield the CPU${pre.length > 1 ? ` ${pre.length} times` : ""} (it did not wait for anything); other processes ran. `
          + `Click: go to the first time it was switched out (step ${grouped(pre[0].step)})`, pre[0].step));
      if (!sleeps.length && pre[0].resumed >= 0)
        more.push(goButton("slept", "debug-continue", "went on",
          `Where this call went on after the switch (step ${grouped(pre[0].resumed)}). Click to go there.`, pre[0].resumed));
    }
    if (!r.waits.length && r.resumeStep !== null)
      more.push(goButton("slept", "debug-continue", "went on",
        `Where this call went on after other threads ran (step ${grouped(r.resumeStep)}). Click to go there.`, r.resumeStep));
    if (r.handlerStep !== null && r.handler)
      more.push(goButton("handler", "arrow-right", `${r.handler}()`,
        `Go to ${r.handler}(), the kernel function that handles this ${exc ? "exception" : "call"}`, r.handlerStep));
    if (exc && r.returned && r.resumesAt)
      more.push(goButton("handler", "debug-restart", `resumes at ${r.resumesAt}`,
        "iretq returns to the instruction that faulted, which runs again (now that its page is mapped). Click to go there.",
        r.end));
    if (more.length) {
      const line = el("span", "sc-more");
      line.append(...more);
      e.append(line);
    }
    const target = callStart(r);
    e.title = `${r.process}: ${r.text}${r.returned && r.result ? " = " + r.result : ""}\n`
      + (exc ? "Click: go to the instruction that caused it" : "Click: go to the instruction that made this call (int $64)");
    e.onclick = () => void cursor.go(target);
    return e;
  }

  /** Mark the call the cursor is in, or the last one before it. */
  private mark(step: number) {
    const at = callAt(this.rows, step, cursor.value.state);
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

/** "proc[2] pid 3 sh": slot and pid first, so a long program name is what gets cut. */
function processCell(r: SyscallRow): HTMLElement {
  const proc = el("span", "proc");
  if (r.slot !== null) proc.append(el("span", "sc-slot", `proc[${r.slot}] `));
  if (r.pid !== null) {
    const pid = el("span", "sc-pid", `pid ${r.pid} `);
    pid.title = `p->pid of ${r.slot !== null ? `proc[${r.slot}]` : "this process"}: what fork() returns to its parent and kill() takes`;
    proc.append(pid);
  }
  proc.append(el("span", "sc-prog", r.slot !== null ? r.program ?? "" : r.process ?? ""));
  proc.title = r.process ?? "";
  return proc;
}

/** Runs [a, b] of the rows: a fold of 3 or more one-byte reads / writes in a row on one fd by one process, else one row. */
function folds(rows: SyscallRow[]): [number, number][] {
  const out: [number, number][] = [];
  const foldable = (r: SyscallRow) => r.kind === "syscall" && r.returned
    && (r.name === "read" || r.name === "write") && /, 1\)$/.test(r.text);
  let i = 0;
  while (i < rows.length) {
    let j = i;
    if (foldable(rows[i]))
      while (j + 1 < rows.length && foldable(rows[j + 1]) && rows[j + 1].name === rows[i].name
             && rows[j + 1].process === rows[i].process && firstArg(rows[j + 1].text) === firstArg(rows[i].text))
        j++;
    if (j - i + 1 >= 3) out.push([i, j]);
    else for (let k = i; k <= j; k++) out.push([k, k]);
    i = j + 1;
  }
  return out;
}

/** "read(0, 0x4f4f, 1)" -> "0" */
function firstArg(text: string): string {
  const m = /^\w+\(([^,)]*)/.exec(text);
  return m ? m[1] : "";
}

/** The bytes a run moved, joined (still escaped): what reads got, what writes wrote; null if not known. */
function joined(rows: SyscallRow[]): string | null {
  const parts: string[] = [];
  for (const r of rows) {
    const src = r.name === "read" ? r.output : /^\w+\([^,]*, ("(?:[^"\\]|\\.)*")/.exec(r.text)?.[1] ?? null;
    if (!src || !/^".*"$/.test(src)) return null;
    parts.push(src.slice(1, -1));
  }
  return parts.join("");
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

/** A small pill in a row's second line that moves the cursor to step (and does not select the row). */
function goButton(cls: string, icon: string, text: string, title: string, step: number | null): HTMLElement {
  const b = el("button", `pill ${cls}`);
  b.append(el("span", `codicon codicon-${icon}`), document.createTextNode(` ${text}`));
  b.title = title;
  b.onclick = (ev) => {
    ev.stopPropagation();
    if (step !== null) void cursor.go(step);
  };
  return b;
}
