// Registers at the cursor, grouped, with control-register bits spelled
// out. Values that changed since the previous position are marked.

import { cursor, hover, type CursorState } from "../cursor";
import { flags, hex, ring, selector } from "../format";
import { el, Panel, placeholder } from "./panel";

const GROUPS: [string, string[]][] = [
  ["General", ["rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp",
               "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"]],
  ["Flow", ["rip", "eflags"]],
  ["Segments", ["cs", "ss", "ds", "es", "fs", "gs"]],
  ["System", ["cr0", "cr2", "cr3", "cr4", "efer", "fs_base", "gs_base", "k_gs_base"]],
];

export class RegistersPanel extends Panel {
  private body = el("div", "regs");

  constructor() {
    super("registers");
  }

  protected mount() {
    this.element.append(this.body);
    this.onDispose(cursor.subscribe((c) => this.render(c)));
    this.onDispose(hover.subscribe((a) => {
      for (const v of this.body.querySelectorAll<HTMLElement>(".val"))
        v.classList.toggle("hovered", a !== null && v.dataset.v === a);
    }));
  }

  private render(c: CursorState) {
    const s = c.state;
    if (!s) {
      this.body.replaceChildren(placeholder(c, "Open a recording to see registers."));
      return;
    }
    const regs: Record<string, string> = { ...s.registers, rip: s.pc };
    const prevRegs: Record<string, string> | null = c.prev ? { ...c.prev.registers, rip: c.prev.pc } : null;
    const frag = document.createDocumentFragment();
    for (const [title, names] of GROUPS) {
      const g = el("section", "reg-group");
      g.append(el("h3", "", title));
      for (const name of names) {
        const v = regs[name];
        if (v === undefined) continue;
        const changed = prevRegs !== null && prevRegs[name] !== undefined && prevRegs[name] !== v;
        const r = el("div", "reg" + (changed ? " changed" : ""));
        r.append(el("span", "name", name));
        const val = el("span", "val", hex(v));
        val.dataset.v = hex(v);
        val.onmouseenter = () => hover.set(hex(v));
        val.onmouseleave = () => hover.set(null);
        r.append(val);
        r.append(el("span", "note-text", annotate(name, v, s.cpl, s.location.label)));
        g.append(r);
      }
      frag.append(g);
    }
    this.body.replaceChildren(frag);
  }
}

function annotate(name: string, v: string, cpl: number, where: string): string {
  if (name === "rip") return where;
  if (name === "cs") return `${selector(v)}, ${ring(cpl)} mode`;
  if (["ss", "ds", "es", "fs", "gs"].includes(name)) return BigInt(v) ? selector(v) : "";
  if (name === "cr2") return "last page-fault address";
  if (name === "cr3") return "page table root (PML4)";
  return flags(name, v).join(" ");
}
