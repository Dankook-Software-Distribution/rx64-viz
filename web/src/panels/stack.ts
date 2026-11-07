// Stack: the stack in use at the cursor, 8-byte slots from the top
// (highest address) down to rsp, grouped into the frames on it. Slots
// the kernel gave a meaning (return address, saved rbp, trapframe
// fields) are labeled; values changed since the previous position are
// marked; a value that points into this stack highlights that slot.

import { api, type Stack, type StackFrame } from "../api";
import { cursor, hover, type CursorState } from "../cursor";
import { grouped, hex } from "../format";
import { el, GLYPH, note, Panel, placeholder } from "./panel";

export class StackPanel extends Panel {
  private head = el("div", "panel-head");
  private body = el("div", "stack-view");
  /** the last stack shown: what "changed" is measured against */
  private last: { rid: string; step: number; values: Map<string, string> } | null = null;
  /* a move happened while the tab was hidden */
  private dirty = false;

  constructor() {
    super("stack");
  }

  protected mount() {
    this.element.append(this.head, this.body);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
    this.onResize(() => {
      if (this.dirty && this.visible()) void this.update(cursor.value);
    });
    this.onDispose(hover.subscribe((a) => {
      for (const v of this.body.querySelectorAll<HTMLElement>("[data-v]"))
        v.classList.toggle("hovered", a !== null && v.dataset.v === a);
      for (const r of this.body.querySelectorAll<HTMLElement>(".slot"))
        r.classList.toggle("pointed", a !== null && r.dataset.addr === a);
    }));
  }

  private async update(c: CursorState) {
    const s = c.state;
    if (!c.rec || !s) {
      this.head.textContent = "";
      this.body.replaceChildren(placeholder(c, "Open a recording to see the stack."));
      this.last = null;
      return;
    }
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const signal = this.fresh();
    let st: Stack;
    try {
      st = await api.stack(c.rec.id, s.step, signal);
    } catch (e) {
      if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
      return;
    }
    if (signal.aborted) return;
    const before = this.last && this.last.rid === c.rec.id && this.last.step === c.prev?.step ? this.last.values : null;
    this.last = { rid: c.rec.id, step: st.step, values: new Map(st.slots.map((x) => [x.addr, x.value])) };
    this.render(st, before);
  }

  private render(st: Stack, before: Map<string, string> | null) {
    const used = (BigInt(st.top) - (BigInt(st.rsp) & ~7n));
    const size = BigInt(st.top) - BigInt(st.base);
    this.head.textContent = `${st.label} · ${used <= size ? used : size} / ${size} bytes in use`;
    this.head.title = `${hex(st.base)} – ${hex(st.top)}`;
    if (!st.slots.length) {
      this.body.replaceChildren(note("The stack is empty (rsp is at its top)."));
      return;
    }
    const base = BigInt(st.base), top = BigInt(st.top);
    const onStack = (v: string) => {
      const b = BigInt(v);
      return b >= base && b < top;
    };
    const rsp = BigInt(st.rsp), rbp = BigInt(st.rbp);
    const frag = document.createDocumentFragment();
    const frames = [...st.frames].reverse(); // highest first
    let rspRow: HTMLElement | null = null;
    for (const f of frames) {
      frag.append(this.frameHead(f, f === st.frames[0]));
      const from = BigInt(f.from), to = BigInt(f.to);
      for (let i = st.slots.length - 1; i >= 0; i--) {
        const x = st.slots[i];
        const a = BigInt(x.addr);
        if (a < from || a >= to) continue;
        const changed = before !== null && before.get(x.addr) !== undefined && before.get(x.addr) !== x.value;
        const r = el("div", `slot kind-${f.kind}` + (changed ? " changed" : "") + (f === st.frames[0] ? " current" : "") + (a < (rsp & ~7n) ? " below" : ""));
        if (a < (rsp & ~7n)) r.title = "Below rsp: not pushed by this trap yet";
        r.dataset.addr = hex(x.addr);
        const marks = el("span", "marks");
        if (a === (rsp & ~7n)) {
          marks.append(el("span", "ptr", "rsp"));
          rspRow = r;
        }
        if (a === rbp) marks.append(el("span", "ptr", "rbp"));
        const addr = el("span", "addr", "…" + (a & 0xffffn).toString(16).padStart(4, "0"));
        addr.title = `${hex(x.addr)} (top − ${hex(top - a)})`;
        const val = el("span", "val", x.mapped ? hex(x.value) : "unmapped");
        if (x.mapped) {
          val.dataset.v = hex(x.value);
          val.onmouseenter = () => hover.set(hex(x.value));
          val.onmouseleave = () => hover.set(null);
        }
        const meaning = el("span", "meaning");
        if (x.label === "return address") meaning.append(el("span", "field", "return to"));
        else if (x.label) meaning.append(el("span", "field", x.label.replace(/^trapframe\./, "")));
        if (x.target) meaning.append(el("span", "target", x.target));
        else if (x.mapped && onStack(x.value) && BigInt(x.value) !== a)
          meaning.append(el("span", "ref", "→ this stack"));
        meaning.title = [x.label, x.target].filter(Boolean).join(" ");
        r.append(marks, addr, val, meaning);
        frag.append(r);
      }
    }
    this.body.replaceChildren(frag);
    rspRow?.scrollIntoView({ block: "nearest" });
  }

  private frameHead(f: StackFrame, current: boolean): HTMLElement {
    const trap = f.kind === "trapframe";
    const h = el(f.step !== null ? "button" : "div", `stack-frame kind-${trap ? "trap" : "call"}` + (current ? " current" : ""));
    h.append(el("span", `glyph codicon codicon-${current ? "debug-stackframe" : trap ? GLYPH.trap : GLYPH.call}`));
    h.append(el("span", "label", trap ? `trapframe · ${f.function}` : f.function ?? "(not in use)"));
    const size = Number(BigInt(f.to) - BigInt(f.from));
    h.append(el("span", "len", `${size} bytes` + (f.step !== null ? ` · step ${grouped(f.step)}` : "")));
    if (f.step !== null) {
      const step = f.step;
      h.title = trap ? `Go to the start of ${f.function}` : `Go to where ${f.function} was entered`;
      h.onclick = () => void cursor.go(step);
    }
    return h;
  }
}
