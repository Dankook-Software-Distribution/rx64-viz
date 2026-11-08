// Registers at the cursor, grouped, with control-register bits spelled
// out. Values that changed since the previous position are blue; a dot
// marks the ones the instruction just before the cursor wrote.

import { api } from "../api";
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

  private dirty = false;

  protected mount() {
    this.element.append(this.body);
    this.onDispose(cursor.subscribe((c) => this.render(c)));
    this.onResize(() => {
      if (this.dirty && this.visible()) this.render(cursor.value);
    });
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
    this.draw(c, {});
    /* what the registers point at, named (a second, slower request): only when shown */
    if (!c.rec) return;
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const signal = this.fresh();
    api.names(c.rec.id, s.step, signal).then((n) => {
      if (!signal.aborted) this.draw(c, n.registers);
    }).catch(() => { /* names are extra */ });
  }

  private draw(c: CursorState, points: Record<string, string>) {
    const s = c.state!;
    const regs: Record<string, string> = { ...s.registers, rip: s.pc };
    const prevRegs: Record<string, string> | null = c.prev ? { ...c.prev.registers, rip: c.prev.pc } : null;
    const frag = document.createDocumentFragment();
    for (const [title, names] of GROUPS) {
      const g = el("section", "reg-group" + (title === "General" ? " two-col" : ""));
      g.append(el("h3", "", title));
      for (const name of names) {
        const v = regs[name];
        if (v === undefined) continue;
        const changed = prevRegs !== null && prevRegs[name] !== undefined && prevRegs[name] !== v;
        const r = el("div", "reg" + (changed ? " changed" : ""));
        const justNow = s.changed?.includes(name) ?? false;
        const nm = el("span", "name" + (justNow ? " just" : ""), name);
        if (justNow) nm.title = "written by the instruction just before the cursor";
        r.append(nm);
        const val = el("span", "val", hex(v));
        val.dataset.v = hex(v);
        val.onmouseenter = () => hover.set(hex(v));
        val.onmouseleave = () => hover.set(null);
        r.append(val);
        const note = el("span", "note-text", points[name] ? `→ ${points[name]}` : annotate(name, v, s.cpl, s.location.label));
        if (name === "eflags")
          note.title = "QEMU computes the arithmetic flags (CF, PF, AF, ZF, SF, OF) and DF only when an instruction needs them, "
            + "so a plugin never sees them: the recorded eflags holds only the system flags. "
            + "The rflags a trap pushes on the stack (Stack: trapframe.rflags) is complete.";
        else note.title = note.textContent ?? "";
        r.append(note);
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
  if (["ss", "ds", "es", "fs", "gs"].includes(name))
    return BigInt(v) ? selector(v) : name === "ss" && cpl === 0 ? "null selector (allowed for ss in the 64-bit kernel)" : "null selector";
  if (name === "cr2") return "last page-fault address";
  if (name === "cr3") return "page table root (PML4)";
  if (name === "eflags") {
    const b = BigInt(v);
    const sys = [(b >> 9n) & 1n ? "IF" : "IF=0 (interrupts off)", (b >> 8n) & 1n ? "TF" : "",
      (b >> 12n) & 3n ? `IOPL ${(b >> 12n) & 3n}` : ""].filter(Boolean);
    return `${sys.join(" ")} · arithmetic flags not recorded`;
  }
  return flags(name, v).join(" ");
}
