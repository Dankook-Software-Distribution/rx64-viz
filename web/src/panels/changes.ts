// Changes: what the system call or exception at the cursor changed in
// the kernel's state, from the recorded memory at its start and its end:
// proc[] fields, the open-file table and pipes, free physical frames,
// and the processes' user page tables. Every change links to the
// instruction whose store made it; changes the cursor has not reached
// yet are dimmed, so stepping through a call shows them happen. Changes
// made by other processes while the call slept are labeled with who
// made them. Events can point it at any call instead (changesRange).

import { api, type Diff, type FileChange, type FrameChange, type MapChange, type Moment, type ProcChange, type SyscallRow } from "../api";
import { callAt, callStart, syscallsOf } from "../calls";
import { changesRange, cursor, memorySpace, showPanel, type CursorState } from "../cursor";
import { grouped } from "../format";
import { el, note, Panel, placeholder } from "./panel";
import { STATE_TITLE } from "./processes";

/** A row that a change (or several) made, and the steps of their stores. */
interface Item {
  el: HTMLElement;
  steps: number[];
  /** a count to update with how many are made so far ("4 frames") */
  count?: { el: HTMLElement; total: number; unit: string };
  /** counted in "N of M changes made so far" */
  tally: boolean;
}

export class ChangesPanel extends Panel {
  private head = el("div", "changes-head");
  private body = el("div", "changes");
  private rid: string | null = null;
  private rows: SyscallRow[] = [];
  /** the call shown, by index in rows */
  private shown = -1;
  private items: Item[] = [];
  private progress = el("span", "progress");
  private dirty = false;
  /** the Events range shown ("from-to"), or "" */
  private rangeKey = "";

  constructor() {
    super("changes");
  }

  protected mount() {
    this.element.append(this.head, this.body);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
    this.onDispose(changesRange.subscribe(() => {
      this.shown = -1;
      this.rangeKey = "";
      void this.update(cursor.value);
    }));
    this.onResize(() => {
      if (this.dirty && this.visible()) void this.update(cursor.value);
    });
  }

  private async update(c: CursorState) {
    if (!c.rec || !c.state) {
      this.rid = null;
      this.shown = -1;
      this.head.replaceChildren();
      this.body.replaceChildren(placeholder(c, "Open a recording to see what its system calls change."));
      return;
    }
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const step = c.state.step;
    if (c.rec.id !== this.rid) {
      const signal = this.fresh();
      let rows: SyscallRow[];
      try {
        rows = await syscallsOf(c.rec.id);
      } catch (e) {
        if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
        return;
      }
      if (signal.aborted) return;
      this.rid = c.rec.id;
      this.rows = rows;
      this.shown = -1;
    }
    const range = changesRange.value;
    if (range) {
      await this.showRange(c, range);
      return;
    }
    this.rangeKey = "";
    const at = callAt(this.rows, step, c.state);
    if (at < 0) {
      this.shown = -1;
      this.head.replaceChildren();
      this.body.replaceChildren(note(this.rows.length
        ? "No system call or exception has started yet at this step. Changes shows what the one at the cursor changes."
        : "No system calls or exceptions in this recording."));
      return;
    }
    if (at !== this.shown) {
      const r = this.rows[at];
      const signal = this.fresh();
      this.renderHead(r, c.rec.steps, step);
      this.body.replaceChildren(note("Reading the state before and after…"));
      let d: Diff;
      try {
        d = await api.diff(c.rec.id, callStart(r), Math.min(r.end, c.rec.steps), signal);
      } catch (e) {
        if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
        return;
      }
      if (signal.aborted) return;
      this.shown = at;
      this.render(r, d);
    }
    this.renderHead(this.rows[at], c.rec.steps, cursor.value.state?.step ?? step);
    this.mark(cursor.value.state?.step ?? step);
  }

  /** A call picked in Events: shown like a system call, until cleared. */
  private async showRange(c: CursorState, range: NonNullable<typeof changesRange.value>) {
    const key = `${c.rec!.id}:${range.from}-${range.to}`;
    /* a fake row: callStart() is step - 1 */
    const r: SyscallRow = {
      step: range.from + 1, end: range.to, returned: false, resumeStep: null, process: range.run ?? "",
      kind: "syscall", name: range.label, text: range.label, result: null, output: null, node: -1,
      handlerStep: null, handler: null, pid: null, earlier: false, waited: null, waits: [], resumesAt: null, slot: slotOf(range.run), program: null,
    };
    if (key !== this.rangeKey) {
      const signal = this.fresh();
      this.shown = -1;
      this.renderRangeHead(range);
      this.body.replaceChildren(note("Reading the state before and after…"));
      let d: Diff;
      try {
        d = await api.diff(c.rec!.id, range.from, Math.min(range.to, c.rec!.steps), signal);
      } catch (e) {
        if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
        return;
      }
      if (signal.aborted) return;
      this.rangeKey = key;
      this.render(r, d);
    }
    this.renderRangeHead(range);
    this.mark(c.state!.step);
  }

  private renderRangeHead(range: NonNullable<typeof changesRange.value>) {
    const title = el("div", "ch-title");
    title.append(el("span", "glyph codicon codicon-symbol-method"), el("span", "call", range.label));
    if (range.run) title.append(el("span", "proc", range.run));
    const sub = el("div", "ch-sub");
    sub.append(el("span", "", `Picked in Events · its state at step ${grouped(range.from)} vs ${grouped(range.to)} · `));
    const back = el("button", "link", "follow the cursor's system call again");
    back.onclick = () => changesRange.set(null);
    sub.append(back, this.progress);
    this.head.replaceChildren(title, sub);
  }

  private renderHead(r: SyscallRow, steps: number, step: number) {
    const title = el("div", "ch-title");
    const exc = r.kind === "exception";
    title.append(el("span", `glyph codicon codicon-${exc ? "warning" : "symbol-event"}`));
    const call = el("span", "call", r.text + (r.returned && r.result !== null ? ` = ${r.result}` : ""));
    title.append(call, el("span", "proc", r.process));
    const sub = el("div", "ch-sub");
    const inside = step >= callStart(r) && step < r.end;
    const end = r.end >= steps ? "the end of the recording" : `step ${grouped(r.end)}`;
    sub.append(el("span", "", `${inside ? "The cursor is inside this call" : "The last call before the cursor"} · its state at step ${grouped(callStart(r))} vs ${end}`));
    sub.append(this.progress);
    this.head.replaceChildren(title, sub);
    this.head.title = "Click a change to go to the instruction that made it";
  }

  /** Dim what the cursor has not reached yet; count what is made so far. */
  private mark(step: number) {
    let made = 0, total = 0;
    for (const it of this.items) {
      const done = it.steps.filter((s) => s < step).length;
      const known = it.steps.length;
      it.el.classList.toggle("pending", known > 0 && done === 0);
      it.el.classList.toggle("partial", done > 0 && done < known);
      if (it.count) {
        const c = it.count;
        c.el.textContent = done > 0 && done < known ? `${done} of ${c.total} ${c.unit}` : `${c.total} ${c.unit}`;
      }
      /* a change whose store was not found has no place in time: not counted */
      if (!it.tally || !known) continue;
      total += known;
      made += done;
    }
    this.progress.textContent = total ? ` · ${made} of ${total} changes made so far` : "";
  }

  /** what the call slept on ("pipe 0x…", "proc[1]", "ticks"): its rows are marked */
  private chans: string[] = [];

  private onChan(object: string): boolean {
    return this.chans.some((c) => object === c || object.startsWith(c + " "));
  }

  private chanMark(row: HTMLElement, object: string) {
    if (!this.onChan(object)) return;
    row.classList.add("on-chan");
    const t = el("span", "chan-tag codicon codicon-debug-pause");
    t.title = "What this call slept on (its p->chan)";
    row.querySelector(".mark")?.replaceWith(t);
  }

  private render(r: SyscallRow, d: Diff) {
    this.items = [];
    /* "pipe 0x87f67000 nread" -> "pipe 0x87f67000": the object, not its field */
    this.chans = r.waits.filter((w) => w.reason === "sleep" && w.on)
      .map((w) => w.on!.startsWith("pipe ") ? w.on!.split(" ").slice(0, 2).join(" ") : w.on!.split(" ")[0]);
    const me = r.slot;
    const frag = document.createDocumentFragment();
    const nothing = !d.procs.length && !d.files.length && !d.allocated.length && !d.freed.length && !d.mappings.length && !d.passing;
    if (nothing) {
      this.body.replaceChildren(note("Nothing changed in proc[], the open-file table, the free frames or the user page tables."
        + (r.returned && r.result !== null ? ` What the call did comes back in rax (= ${r.result})` : "")
        + (r.kind === "syscall" && (r.name === "write" || r.name === "read") ? " and through the bytes it moved (Syscalls, Console)." : r.returned ? "." : "")));
      return;
    }
    if (!d.stepsFound)
      frag.append(note("This call ran too long to look up which instruction made each change; only the before and after are shown."));
    const timeline = d.timeline?.length ? d.timeline : null;
    this.tallyTimeline = timeline !== null;
    const why = r.waited?.reason === "preempted" ? "was preempted by the timer" : "slept";
    if (timeline) {
      frag.append(el("div", "ch-slept", `This call ${why} while other code ran. In time order: every change while it ran, and who made it; then the net effect from its start to its end.`));
      frag.append(section("In time order", "proc[] fields, open files and pipes, each time a process changed them"));
      for (const m of timeline) frag.append(this.moment(m, me));
      frag.append(section("From start to end", "What is different at the end of the call from its start"));
    } else if (r.resumeStep !== null)
      frag.append(el("div", "ch-slept", `This call ${why} while other code ran: changes made by others are marked with who made them.`));

    if (d.procs.length) {
      frag.append(section("Processes", "proc[] slots: struct proc fields"));
      /* the calling process first: what it did itself */
      const procs = [...d.procs].sort((x, y) => Number(x.slot !== me) - Number(y.slot !== me));
      for (const p of procs) frag.append(...this.proc(p, me));
    }
    if (d.files.length) {
      frag.append(section("Open files", "ftable.file[] (shared by every descriptor that points to it) and pipes"));
      for (const f of d.files) frag.append(this.file(f, me));
    }
    if (d.allocated.length || d.freed.length || d.passing) {
      const delta = d.freeAfter - d.freeBefore;
      frag.append(section("Physical memory", "Frames taken from or put back on kmem.freelist"));
      const free = el("div", "ch-row ch-free");
      free.append(el("span", "mark"), el("span", "what", "free frames"),
        el("span", "change", `${grouped(d.freeBefore)} → ${grouped(d.freeAfter)} (${delta > 0 ? "+" : ""}${delta})`));
      frag.append(free);
      frag.append(...this.frames(d.allocated, "allocated", me), ...this.frames(d.freed, "freed", me));
      if (d.passing) {
        const p = el("div", "ch-row");
        p.append(el("span", "mark"), el("span", "what", "in and out"),
          el("span", "change dim", `${d.passing} frame${d.passing > 1 ? "s" : ""} taken from the free list and put back during the call`));
        p.title = "kalloc() then kfree() inside the call (exec's argument pages, a pipe that was closed...): not in the net change";
        frag.append(p);
      }
    }
    if (d.mappings.length) {
      frag.append(section("Page tables", "User-half pages of each process's page table"));
      let key = "";
      for (const m of d.mappings) {
        const k = `${m.slot}:${m.table}`;
        if (k !== key) {
          key = k;
          const h = el("div", "ch-sub-head");
          h.append(el("span", "ch-slot", `proc[${m.slot}] ${m.name}`), el("span", "dim", `page table ${m.table}`));
          const mem = el("button", "pt-mem codicon codicon-layers");
          mem.title = `Show proc[${m.slot}]'s address space in Memory (at the cursor)`;
          const slot = m.slot, name = m.name;
          mem.onclick = () => {
            memorySpace.set({ slot, name });
            showPanel("memory");
          };
          h.append(mem);
          frag.append(h);
        }
        frag.append(this.mapping(m, me));
      }
    }
    this.body.replaceChildren(frag);
  }

  /** One change in time order: who, what, before → after. */
  private moment(m: Moment, me: number | null): HTMLElement {
    const row = el("div", "ch-row ch-moment");
    const body = el("span", "body");
    const head = el("span", "head");
    head.append(el("span", "obj", m.object));
    if (m.field) head.append(el("span", "dim", " · "), el("span", "field", m.field));
    if (m.by) head.append(el("span", "who" + (slotOf(m.by) === me ? " me" : ""), m.by));
    if (m.call) head.append(el("span", "in-call", `in ${m.call}`));
    if (m.field || !m.before || !m.after) body.append(head, change(m.before, m.after, false));
    else {
      /* a file or pipe: only the parts that changed (ref 14 → ref 13) */
      const [, before, after] = splitCommon(m.before, m.after);
      const c = change(before, after, false);
      c.title = `${m.before}\n→ ${m.after}`;
      body.append(head, c);
    }
    row.append(el("span", "mark"), body);
    this.chanMark(row, m.object);
    if (m.by && slotOf(m.by) !== me) row.classList.add("other");
    const it = this.item(row, [m.step], null, me);
    it.tally = true;
    return row;
  }

  private tallyTimeline = false;

  /** A row for one or more changes; click goes to the first one the cursor has not reached (or the first). */
  private item(row: HTMLElement, steps: (number | null)[], by: string | null, me: number | null): Item {
    const known = steps.filter((s): s is number => s !== null).sort((a, b) => a - b);
    const it: Item = { el: row, steps: known, tally: !this.tallyTimeline };
    if (by !== null && slotOf(by) !== me) {
      row.classList.add("other");
      row.append(el("span", "by", `by ${by}`));
    }
    if (known.length) {
      row.classList.add("link");
      row.onclick = () => {
        const now = cursor.value.state?.step ?? 0;
        const next = known.find((s) => s >= now) ?? known[0];
        void cursor.go(next);
      };
      row.title = (row.title ? row.title + "\n" : "") + `Click: go to the instruction that made it (step ${grouped(known[0])})`;
    }
    this.items.push(it);
    return it;
  }

  private proc(p: ProcChange, me: number | null): HTMLElement[] {
    const out: HTMLElement[] = [];
    const h = el("div", `ch-sub-head kind-${p.kind}`);
    h.append(el("span", "ch-slot", `proc[${p.slot}]`), el("span", "name", p.name || "(no name)"),
      el("span", "dim", `pid ${p.pid}`), el("span", "badge", p.kind));
    h.title = p.kind === "created" ? "A free (UNUSED) slot became a process"
      : p.kind === "freed" ? "The process's slot became UNUSED again" : "";
    out.push(h);
    for (const f of p.fields) {
      const row = el("div", "ch-row");
      row.append(el("span", "mark"), el("span", "what", f.field), change(f.before, f.after, p.kind === "created"));
      if (f.field === "state") row.title = `${f.after}: ${STATE_TITLE[f.after] ?? ""}`;
      this.item(row, [f.step], f.by, me);
      out.push(row);
    }
    return out;
  }

  private file(f: FileChange, me: number | null): HTMLElement {
    const row = el("div", "ch-row");
    const [label, before, after] = splitCommon(f.before, f.after);
    const what = el("span", "what", f.object);
    row.append(el("span", "mark"), what);
    const c = el("span", "change");
    if (!f.before) c.append(el("span", "verb", "opened "), el("span", "", f.after));
    else if (!f.after) c.append(el("span", "verb", "closed "), el("span", "dim", f.before));
    else {
      if (label) c.append(el("span", "dim", label + " · "));
      c.append(el("span", "before", before), el("span", "arrow", " → "), el("span", "after", after));
    }
    row.append(c);
    row.title = `${f.before || "(not in use)"}\n→ ${f.after || "(not in use)"}`;
    this.chanMark(row, f.object);
    this.item(row, [f.step], f.by, me);
    return row;
  }

  /** Frames grouped by what they hold. */
  private frames(list: FrameChange[], kind: "allocated" | "freed", me: number | null): HTMLElement[] {
    const groups = new Map<string, FrameChange[]>();
    for (const f of list) {
      const k = (f.what ?? "") + "\u0000" + (f.by ?? "");
      groups.set(k, [...(groups.get(k) ?? []), f]);
    }
    const out: HTMLElement[] = [];
    for (const fs of groups.values()) {
      const row = el("div", `ch-row frames-${kind}`);
      const n = el("span", "count");
      const what = fs[0].what ?? "not mapped by any process's page table";
      row.append(el("span", "mark"), el("span", "what", kind === "allocated" ? "+ allocated" : "− freed"));
      const c = el("span", "change");
      c.append(n, el("span", "dim", " · "), el("span", "", what));
      row.append(c);
      row.title = fs.map((f) => f.pa).join(" ");
      const it = this.item(row, fs.map((f) => f.step), fs[0].by, me);
      it.count = { el: n, total: fs.length, unit: fs.length === 1 ? "frame" : "frames" };
      out.push(row);
    }
    return out;
  }

  private mapping(m: MapChange, me: number | null): HTMLElement {
    const row = el("div", `ch-row map-${m.kind}`);
    const range = `${m.va}–${hexMinus1(m.end)}`;
    const verb = { mapped: "+ mapped", unmapped: "− unmapped", remapped: "~ remapped", rights: "~ rights" }[m.kind];
    row.append(el("span", "mark"), el("span", "what", verb));
    const c = el("span", "change");
    const pages = `${m.pages} ${m.pages === 1 ? "page" : "pages"}`;
    c.append(el("span", "va", range), el("span", "", ` ${m.label}`), el("span", "dim", ` · ${pages}`));
    if (m.kind === "mapped") c.append(el("span", "dim", ` → frame ${m.paAfter}${m.pages > 1 ? "…" : ""} · ${m.rightsAfter}`));
    else if (m.kind === "unmapped") c.append(el("span", "dim", ` · was frame ${m.paBefore}${m.pages > 1 ? "…" : ""}`));
    else if (m.kind === "remapped") c.append(el("span", "dim", ` · frame ${m.paBefore} → ${m.paAfter}`));
    else c.append(el("span", "dim", ` · ${m.rightsBefore} → ${m.rightsAfter}`));
    row.append(c);
    this.item(row, [m.step], m.by, me);
    return row;
  }
}

function section(title: string, hint: string): HTMLElement {
  const h = el("div", "ch-section");
  h.append(el("span", "", title));
  h.title = hint;
  return h;
}

/** before → after; an empty side is "none"; a new process shows only its value. */
function change(before: string, after: string, created: boolean): HTMLElement {
  const c = el("span", "change");
  if (created && !before) {
    c.append(el("span", "after", after || "none"));
    return c;
  }
  c.append(el("span", before ? "before" : "before dim", before || "none"), el("span", "arrow", " → "),
    el("span", after ? "after" : "after dim", after || "none"));
  return c;
}

/** "a · b · c", "a · x · c" -> ["a", "b · c", "x · c"]: the leading parts both share, then the rest. */
function splitCommon(before: string, after: string): [string, string, string] {
  const a = before.split(" · "), b = after.split(" · ");
  let i = 0;
  while (i < a.length - 1 && i < b.length - 1 && a[i] === b[i]) i++;
  /* also drop the parts both end with */
  let j = 0;
  while (j < a.length - i - 1 && j < b.length - i - 1 && a[a.length - 1 - j] === b[b.length - 1 - j]) j++;
  return [a.slice(0, i).join(" · "), a.slice(i, a.length - j).join(" · "), b.slice(i, b.length - j).join(" · ")];
}

/** "proc[2] sh" -> 2; null for the scheduler or other kernel threads. */
function slotOf(label: string | null): number | null {
  const m = /^proc\[(\d+)\]/.exec(label ?? "");
  return m ? Number(m[1]) : null;
}

function hexMinus1(h: string): string {
  return "0x" + (BigInt(h) - 1n).toString(16);
}
