// CPU: the descriptor tables the CPU uses at the cursor, read from
// memory: the GDT (which segment registers select which entry), the TSS
// that TR points to (rsp0: the stack a trap from user mode switches
// to), and the IDT (each vector's handler and who may raise it with
// int n). GDTR and IDTR come from the lgdt / lidt that loaded them,
// shown with a link to that instruction. Values that changed since the
// previous position are marked; the gate of the trap the cursor is in
// is marked too.

import { api, type CpuView, type Gate, type GdtEntry, type TableLoad } from "../api";
import { cursor, type CursorState } from "../cursor";
import { grouped } from "../format";
import { el, note, Panel, placeholder } from "./panel";

export class CpuPanel extends Panel {
  private body = el("div", "cpu-view");
  /** row texts of the last view, by key: what "changed" is measured against */
  private last: { rid: string; step: number; rows: Map<string, string> } | null = null;
  private closed = new Set<string>();
  private dirty = false;
  private view: { v: CpuView; before: Map<string, string> | null; trap: number | null } | null = null;

  constructor() {
    super("cpu");
  }

  protected mount() {
    this.element.append(this.body);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
    this.onResize(() => {
      if (this.dirty && this.visible()) void this.update(cursor.value);
    });
  }

  private async update(c: CursorState) {
    const s = c.state;
    if (!c.rec || !s) {
      this.body.replaceChildren(placeholder(c, "Open a recording to see the CPU's descriptor tables."));
      this.last = null;
      return;
    }
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const signal = this.fresh();
    let v: CpuView;
    try {
      v = await api.cpu(c.rec.id, s.step, signal);
    } catch (e) {
      if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
      return;
    }
    if (signal.aborted) return;
    const before = this.last && this.last.rid === c.rec.id && this.last.step === c.prev?.step ? this.last.rows : null;
    this.last = { rid: c.rec.id, step: v.step, rows: rowTexts(v) };
    /* the innermost trap the cursor is in: its gate is the one in use */
    const traps = s.path.filter((n) => n.kind === "trap" && n.vector !== null);
    const trap = traps.length ? traps[traps.length - 1].vector : null;
    this.view = { v, before, trap };
    this.render();
  }

  private render() {
    if (!this.view) return;
    const { v, before, trap } = this.view;
    const changed = (key: string, text: string) => before !== null && before.has(key) && before.get(key) !== text;
    const frag = document.createDocumentFragment();

    const mode = cursor.value.state?.mode;
    if (mode) {
      const m = el("div", "cpu-mode");
      m.append(el("span", "k", "Mode"), el("span", "v", mode));
      if (!mode.startsWith("64"))
        m.append(el("div", "dim", "A segment register keeps the descriptor it was loaded with: until the far jump reloads cs, "
          + "the CPU runs the code as 32-bit even where cs's number now selects a 64-bit entry of a new GDT."));
      frag.append(m);
    }
    frag.append(this.section("gdt", "GDT", "Global Descriptor Table: the segments that cs, ss, ... select, and the TSS"));
    if (!this.closed.has("gdt")) {
      frag.append(loadLine("GDTR", "lgdt", v.gdtr, changed("gdtr", v.gdtr.base ?? "")));
      for (const g of v.gdt) frag.append(gdtRow(g, changed(`gdt${g.index}`, g.raw + g.selectors.join()), mode ?? ""));
      if (v.pastLimit?.length) {
        const p = el("div", "cpu-row past-limit");
        p.append(el("span", "k", v.pastLimit.join(", ")), el("span", "dim", " select an entry past the GDT's limit: "
          + "the register keeps the descriptor it was loaded with (from the GDT before)"));
        frag.append(p);
      }
    }

    frag.append(this.section("tss", "TSS", "Task State Segment: the stack pointers the CPU loads on a trap"));
    if (!this.closed.has("tss")) {
      const t = v.tss;
      if (!t) frag.append(note("No TSS is loaded (TR): no busy TSS descriptor in the GDT."));
      else {
        const head = el("div", "cpu-load");
        head.append(el("span", "k", "TR"), el("span", "v", `GDT[${t.gdtIndex}] · ${t.base}`),
          el("span", "dim", t.name ? ` · ${t.name}` : ""));
        frag.append(head);
        const kv = (k: string, val: string, what: string, title: string, key: string) => {
          const r = el("div", "cpu-row tss" + (changed(key, val) ? " changed" : ""));
          r.append(el("span", "k", k), el("span", "val", val), el("span", "what", what));
          r.title = title;
          frag.append(r);
        };
        kv("rsp0", t.rsp[0] ?? "?", t.rsp0Name ?? "", "The stack the CPU switches to when a trap or interrupt comes from user mode (ring 3 → ring 0)", "rsp0");
        t.rsp.slice(1).forEach((x, i) => {
          if (x && BigInt(x) !== 0n) kv(`rsp${i + 1}`, x, "", `Stack for ring ${i + 1} (unused by these kernels)`, `rsp${i + 1}`);
        });
        t.ist.forEach((x, i) => {
          if (x && BigInt(x) !== 0n) kv(`ist${i + 1}`, x, `stack for gates with IST ${i + 1}`, "A gate with this IST number always switches to this stack", `ist${i + 1}`);
        });
        kv("iomb", String(t.iomb), t.iomb >= 104 ? "beyond the TSS: no I/O port bitmap" : "", "I/O map base: where the I/O permission bitmap starts", "iomb");
      }
    }

    frag.append(this.section("idt", "IDT", "Interrupt Descriptor Table: where each vector goes"));
    if (!this.closed.has("idt")) {
      frag.append(loadLine("IDTR", "lidt", v.idtr, changed("idtr", v.idtr.base ?? "")));
      if (v.idt.length) {
        const h = el("div", "cpu-row gate gate-head");
        for (const c of ["Vector", "", "Handler", "Gate", "DPL"]) h.append(el("span", "", c));
        frag.append(h);
      }
      for (const run of runs(v.idt)) {
        if (run.length === 1) {
          const g = run[0];
          frag.append(gateRow(g, g.vector === trap, changed(`idt${g.vector}`, gateText(g))));
        } else {
          const hit = run.find((g) => g.vector === trap) ?? null;
          frag.append(runRow(run, hit, run.some((g) => changed(`idt${g.vector}`, gateText(g)))));
        }
      }
    }
    const top = this.element.scrollTop;
    this.body.replaceChildren(frag);
    this.element.scrollTop = top;
  }

  private section(id: string, title: string, hint: string): HTMLElement {
    const h = el("button", "cpu-section");
    const open = !this.closed.has(id);
    h.append(el("span", `codicon codicon-chevron-${open ? "down" : "right"}`), el("span", "", title));
    h.title = hint;
    h.onclick = () => {
      if (open) this.closed.add(id);
      else this.closed.delete(id);
      this.render();
    };
    return h;
  }
}

function rowTexts(v: CpuView): Map<string, string> {
  const m = new Map<string, string>();
  m.set("gdtr", v.gdtr.base ?? "");
  m.set("idtr", v.idtr.base ?? "");
  for (const g of v.gdt) m.set(`gdt${g.index}`, g.raw + g.selectors.join());
  if (v.tss) {
    v.tss.rsp.forEach((x, i) => m.set(`rsp${i}`, x ?? "?"));
    v.tss.ist.forEach((x, i) => m.set(`ist${i + 1}`, x ?? "?"));
    m.set("iomb", String(v.tss.iomb));
  }
  for (const g of v.idt) m.set(`idt${g.vector}`, gateText(g));
  return m;
}

/**
 * Consecutive gates with no name of their own ("vector 37"), alike in
 * type, DPL and IST, folded into runs; every other gate alone.
 */
function runs(gates: Gate[]): Gate[][] {
  const out: Gate[][] = [];
  const plain = (g: Gate) => g.name === `vector ${g.vector}`;
  for (const g of gates) {
    const last = out[out.length - 1];
    const prev = last?.[last.length - 1];
    if (prev && plain(g) && plain(prev) && prev.vector + 1 === g.vector
        && prev.type === g.type && prev.dpl === g.dpl && prev.ist === g.ist)
      last.push(g);
    else out.push([g]);
  }
  return out;
}

function runRow(run: Gate[], hit: Gate | null, changed: boolean): HTMLElement {
  const a = run[0], z = run[run.length - 1];
  const r = el("div", "cpu-row gate run" + (hit ? " in-use" : "") + (changed ? " changed" : ""));
  r.append(el("span", "idx", `${a.vector}–${z.vector}`), el("span", "what dim", `${run.length} vectors`),
    el("span", "fn", `${a.handlerName ?? a.handler} … ${z.handlerName ?? z.handler}`),
    el("span", "type", a.type + (a.ist ? ` · IST ${a.ist}` : "")), el("span", "dpl", String(a.dpl)));
  r.title = `Vectors ${a.vector}–${z.vector}: no device or exception of these kernels uses them; `
    + `each still has its own stub (${a.handlerName ?? a.handler} … ${z.handlerName ?? z.handler})`
    + (hit ? `\nThe cursor is inside a trap through vector ${hit.vector}` : "");
  return r;
}

function gateText(g: Gate): string {
  return `${g.handler} ${g.type} ${g.dpl} ${g.ist}`;
}

/** GDTR / IDTR: the table's address and name, and the instruction that loaded it. */
function loadLine(reg: string, insn: string, l: TableLoad, changed: boolean): HTMLElement {
  const d = el("div", "cpu-load" + (changed ? " changed" : ""));
  d.append(el("span", "k", reg));
  if (!l.known) {
    d.append(el("span", "dim", `No ${insn} has run yet in this recording`));
    if (l.firstLoad !== null) {
      const first = l.firstLoad;
      const b = el("button", "link", `first ${insn} at step ${grouped(first)}`);
      b.onclick = () => void cursor.go(first);
      d.append(document.createTextNode(" · "), b);
    }
    return d;
  }
  d.append(el("span", "v", `${l.base}`), el("span", "name", l.name ? ` ${l.name}` : ""));
  const by = el("div", "loaded");
  if (l.fromBoot) {
    by.append(el("span", "dim", `loaded at boot by ${insn} in ${l.function}() (boot recording); not loaded again since`));
  } else if (l.step !== null) {
    const step = l.step;
    by.append(el("span", "dim", `loaded by ${insn} in `), el("span", "fn", `${l.function}()`));
    const b = el("button", "link", `step ${grouped(step)}`);
    b.title = `Go to the ${insn} that loaded it`;
    b.onclick = () => void cursor.go(step);
    by.append(el("span", "dim", " at "), b);
  }
  d.append(by);
  return d;
}

function gdtRow(g: GdtEntry, changed: boolean, mode: string): HTMLElement {
  const r = el("div", `cpu-row seg kind-${g.kind}` + (g.selectors.length ? " selected" : "") + (changed ? " changed" : ""));
  r.append(el("span", "idx", `[${g.index}]`), el("span", "sel", g.kind === "tss-high" || g.kind === "null" ? "" : `0x${(g.index * 8).toString(16)}`));
  const what = el("span", "what", g.label);
  if (g.kind === "tss" && g.base) what.append(el("span", "dim", ` · base ${g.base}`));
  r.append(what);
  const regs = el("span", "regs");
  for (const s of g.selectors) {
    /* cs's number selects a 64-bit entry, but cs was loaded before: the CPU still runs 32-bit code */
    const stale = s === "cs" && g.long64 && mode !== "" && !mode.startsWith("64");
    const c = el("span", "reg-chip" + (stale ? " stale" : ""), stale ? "cs (not reloaded)" : s);
    if (stale) c.title = "cs holds this number, but its descriptor was loaded from the old GDT: until a far jump reloads cs, the CPU runs 32-bit code";
    regs.append(c);
  }
  r.append(regs);
  r.title = `${g.raw}${g.kind === "code" || g.kind === "data" ? `\nDPL ${g.dpl}: ${g.dpl === 3 ? "user mode (ring 3) may use it" : "kernel only (ring 0)"}` : ""}`
    + (g.selectors.length ? `\nSelected by ${g.selectors.join(", ")} now` : "");
  return r;
}

function gateRow(g: Gate, inUse: boolean, changed: boolean): HTMLElement {
  const r = el("div", "cpu-row gate" + (g.dpl === 3 ? " user" : "") + (inUse ? " in-use" : "") + (changed ? " changed" : ""));
  r.append(el("span", "idx", String(g.vector)), el("span", "what", g.name));
  const h = el("span", "fn", g.handlerName ?? g.handler);
  r.append(h, el("span", "type", g.type + (g.ist ? ` · IST ${g.ist}` : "")), el("span", "dpl", String(g.dpl)));
  r.title = `vector ${g.vector}: ${g.name}\nhandler ${g.handler}${g.handlerName ? ` (${g.handlerName})` : ""}\n`
    + `${g.type} gate: ${g.type === "interrupt" ? "interrupts are turned off on entry" : "interrupts stay on"}\n`
    + `DPL ${g.dpl}: ${g.dpl === 3 ? "user code may raise it with int $" + g.vector : "int $" + g.vector + " from user code is a general protection fault"}`
    + (g.ist ? `\nIST ${g.ist}: always switches to TSS ist${g.ist}` : "")
    + (inUse ? "\nThe cursor is inside a trap through this gate" : "");
  return r;
}
