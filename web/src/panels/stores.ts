// Stores: the memory writes just before the cursor, newest first. The
// write made by the previous instruction is marked.

import { api, type Store } from "../api";
import { cursor, hover, type CursorState } from "../cursor";
import { grouped, hex } from "../format";
import { el, note, Panel, placeholder } from "./panel";

const WINDOW = 4096;
const SHOW = 80;

export class StoresPanel extends Panel {
  private body = el("div", "stores-list");

  constructor() {
    super("stores");
  }

  protected mount() {
    this.element.append(this.body);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
  }

  private async update(c: CursorState) {
    const s = c.state;
    if (!c.rec || !s) {
      this.body.replaceChildren(placeholder(c, "Open a recording to see memory writes."));
      return;
    }
    const signal = this.fresh();
    let recent: Store[];
    try {
      recent = await api.stores(c.rec.id, Math.max(0, s.step - WINDOW), s.step, SHOW, signal);
    } catch (e) {
      if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
      return;
    }
    if (signal.aborted) return;
    if (!recent.length) {
      this.body.replaceChildren(note(`No memory writes in the ${WINDOW} steps before this one.`));
      return;
    }
    const head = el("div", "store head");
    for (const t of ["step", "physical address", "size", "before", "after"]) head.append(el("span", "", t));
    const rows = recent.map((w) => {
      const r = el("button", "store" + (w.step === s.step - 1 ? " changed" : ""));
      r.append(
        el("span", "", grouped(w.step)),
        addrCell(w.pa, w.name),
        el("span", "", String(w.size)),
        el("span", "dim", hex(w.old)),
        el("span", "", hex(w.value)),
      );
      r.title = "Go to the step after this write";
      r.onclick = () => void cursor.go(w.step + 1);
      r.onmouseenter = () => hover.set(hex(w.pa));
      r.onmouseleave = () => hover.set(null);
      return r;
    });
    this.body.replaceChildren(head, ...rows);
  }
}

function addrCell(pa: string, name: string | null): HTMLElement {
  const c = el("span", "st-addr", hex(pa));
  if (name) {
    c.append(el("span", "st-name", ` ${name}`));
    c.title = `${hex(pa)}: ${name} (a kernel global, named from its symbol and DWARF type)`;
  }
  return c;
}
