// Variables: the parameters and local variables of the function running
// at the cursor, with their values then. Where each one lives comes from
// DWARF (a register, the stack, a constant, a register's value at the
// call); with -O, a variable has no home for part of its function
// ("optimized out here"). A variable no store has written since the call
// is "not assigned yet". Structs (and what a pointer points at) open to
// their fields. Pointers are named like elsewhere; click one to walk it
// in Memory; a pagetable_t opens its table there.

import { api, type Variables, type VarValue } from "../api";
import { cursor, memorySpace, memoryTarget, showPanel, type CursorState } from "../cursor";
import { el, note, Panel, placeholder } from "./panel";

export class VariablesPanel extends Panel {
  private head = el("div", "panel-head");
  private body = el("div", "vars");
  private last: { rid: string; step: number; values: Map<string, string | null> } | null = null;
  /** fields shown (by variable name) */
  private open = new Set<string>();
  private shown: { v: Variables; prev: Map<string, string | null> | null } | null = null;

  constructor() {
    super("variables");
  }

  private dirty = false;

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
      this.body.replaceChildren(placeholder(c, "Open a recording to see the running function's variables."));
      return;
    }
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const signal = this.fresh();
    let v: Variables;
    try {
      v = await api.variables(c.rec.id, s.step, signal);
    } catch (e) {
      if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
      return;
    }
    if (signal.aborted) return;
    const prev = this.last && this.last.rid === c.rec.id && this.last.step === c.prev?.step ? this.last.values : null;
    this.last = { rid: c.rec.id, step: s.step, values: new Map(v.vars.map((x) => [x.name, x.value])) };
    this.shown = { v, prev };
    this.render();
  }

  /** The last response, drawn again (a field opened or closed: nothing to fetch). */
  private render() {
    if (!this.shown) return;
    const { v, prev } = this.shown;
    this.head.textContent = v.function ? `${v.function}() · parameters and locals at this step (DWARF)` : "";
    if (!v.function) {
      this.body.replaceChildren(note("No variables: this code has no C function with debug information (assembly, firmware)."));
      return;
    }
    if (!v.vars.length) {
      this.body.replaceChildren(note(`${v.function}() has no parameters or local variables.`));
      return;
    }
    const frag = document.createDocumentFragment();
    for (const x of v.vars) {
      const changed = prev !== null && prev.has(x.name) && prev.get(x.name) !== x.value;
      frag.append(this.row(x, changed, v.function, 0, x.name));
      if (x.fields && this.open.has(x.name))
        for (const f of x.fields) frag.append(this.row(f, false, v.function, 1, x.name + "." + f.name));
    }
    this.body.replaceChildren(frag);
  }

  private row(x: VarValue, changed: boolean, fn: string, depth: number, key: string): HTMLElement {
    const r = el("div", "var" + (x.value === null ? " none" : "") + (changed ? " changed" : "")
      + (x.unset ? " unset" : "") + (depth ? " field" : ""));
    const tw = el("span", "twisty" + (x.fields ? ` codicon codicon-chevron-${this.open.has(key) ? "down" : "right"}` : ""));
    if (x.fields) {
      tw.title = x.type.endsWith("*") ? "Show the fields it points at" : "Show its fields";
      tw.onclick = (ev) => {
        ev.stopPropagation();
        if (this.open.has(key)) this.open.delete(key);
        else this.open.add(key);
        this.render();
      };
    }
    r.append(tw, el("span", "vname", x.name), el("span", "vtype", x.type));
    const val = el("span", "vval", x.value ?? "—");
    r.append(val);
    if (x.unset) {
      const u = el("span", "vunset", "not assigned yet");
      u.title = "No store has written this variable since the function was called: the value is left over from earlier use of that memory";
      r.append(u);
    }
    if (x.points) r.append(el("span", "vpoints", `→ ${x.points}`));
    if (x.type.endsWith("*") && x.value && x.value !== "0x0" && !x.unset) {
      val.classList.add("link");
      val.title = "Walk this address in Memory";
      const va = x.value;
      val.onclick = () => {
        memoryTarget.set({ va });
        showPanel("memory");
      };
    }
    if (x.table) {
      const root = x.table;
      const b = el("button", "link vtable", "show this page table");
      b.title = `Show the page table at ${root} in Memory (it need not be the one in CR3)`;
      b.onclick = () => {
        memorySpace.set({ root, name: `${x.name} in ${fn}()` });
        showPanel("memory");
      };
      r.append(b);
    }
    r.append(el("span", "vwhere", x.where));
    r.title = `${depth ? "field" : x.param ? "parameter" : "local"} ${x.type} ${x.name}${x.line ? ` (line ${x.line})` : ""}: ${x.where}`
      + (x.dwarf ? `\nDWARF location: ${x.dwarf}` : "");
    return r;
  }
}
