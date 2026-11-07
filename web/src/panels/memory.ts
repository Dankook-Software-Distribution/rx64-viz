// Memory: the address space of the page table in CR3 at the cursor, the
// walk from CR3 to the frame of one virtual address, and what every
// physical frame is used for, side by side. The virtual address can
// follow a register (rip, rsp, cr2) or be typed in; the frame follows
// the walk unless one is picked on the grid.

import { api, type FrameKind, type Memory, type PhysFrame, type PhysMap, type Region, type Regions, type Walk } from "../api";
import { cursor, type CursorState } from "../cursor";
import { grouped, hex } from "../format";
import { cssVar, el, note, Panel, placeholder, sizeCanvas } from "./panel";

const KIND_NAMES: Record<FrameKind, string> = {
  u: "user pages (this space)",
  t: "page tables (this space)",
  s: "kernel stacks",
  x: "other kernel pages mapped here",
  k: "kernel image",
  o: "allocated, not mapped here",
  f: "free (kmem.freelist)",
  l: "low memory (firmware, boot)",
};
const KIND_ORDER: FrameKind[] = ["u", "t", "s", "x", "k", "o", "f", "l"];
const FRAME_VAR: Record<FrameKind, string> = {
  u: "--m-user", t: "--m-table", s: "--m-kstack", x: "--m-mapped",
  k: "--m-kernel", o: "--m-alloc", f: "--m-free", l: "--m-low",
};

const PAGING_OFF = "Paging is off (CR0.PG = 0): the CPU uses every address as a physical address.";

function regionVar(kind: string): string {
  if (kind.startsWith("user") || kind === "heap") return "--m-user";
  switch (kind) {
    case "guard": return "--m-low";
    case "kernel-text": case "kernel-data": case "kernel": return "--m-kernel";
    case "kstack": return "--m-kstack";
    case "ram": return "--m-alloc";
    case "device": return "--m-device";
    default: return "--m-mapped";
  }
}

type Follow = "rip" | "rsp" | "cr2" | null;

const LEVELS = [
  { name: "PML4", hi: 47, lo: 39 },
  { name: "PDPT", hi: 38, lo: 30 },
  { name: "PD", hi: 29, lo: 21 },
  { name: "PT", hi: 20, lo: 12 },
];

/** 4096 -> "4 KB", 134217728 -> "128 MB" */
function bytes(n: bigint): string {
  const units = ["B", "KB", "MB", "GB", "TB", "PB", "EB"];
  let u = 0;
  let v = Number(n);
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024;
    u++;
  }
  return `${v >= 100 || Number.isInteger(v) ? Math.round(v) : v.toFixed(1)} ${units[u]}`;
}


export class MemoryPanel extends Panel {
  private vaCol = el("div", "mem-col");
  private walkCol = el("div", "mem-col");
  private physCol = el("div", "mem-col");
  private cols = el("div", "memory-cols");

  private follow: Follow = "rip";
  private va: bigint | null = null;
  /** frame picked on the grid (null: the walk's frame) */
  private picked: bigint | null = null;
  private kstacksOpen = false;
  private dirty = true;

  /* what the last render showed, to mark changes */
  private last: {
    rid: string; step: number; regions: Set<string>; walk: Walk | null;
    dump: { pa: bigint; data: string } | null;
  } | null = null;
  private data: {
    c: CursorState; regions: Regions; phys: PhysMap; walk: Walk; frame: PhysFrame | null; dump: Memory | null;
    va: bigint; framePa: bigint | null;
  } | null = null;

  private canvas = el("canvas", "phys-grid");
  private hoverLine = el("div", "phys-hover");
  private geom = { cols: 128, cell: 3, gutter: 0 };

  constructor() {
    super("memory");
  }

  protected mount() {
    this.cols.append(this.vaCol, this.walkCol, this.physCol);
    this.element.append(this.cols);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
    this.onResize(() => {
      if (!this.visible()) return;
      if (this.dirty) void this.update(cursor.value);
      else this.draw();
    });
    this.onTheme(() => this.draw());
    this.canvas.onmousemove = (ev) => this.hover(ev);
    this.canvas.onmouseleave = () => (this.hoverLine.textContent = "");
    this.canvas.onclick = (ev) => {
      const i = this.cellAt(ev);
      if (i === null) return;
      this.select({ picked: BigInt(i) * 4096n });
    };
  }

  /** Change what is shown (the address to walk, a picked frame) and redraw. */
  private select(p: { follow?: Follow; va?: bigint | null; picked?: bigint | null }) {
    if (p.follow !== undefined) this.follow = p.follow;
    if (p.va !== undefined) this.va = p.va;
    if (p.picked !== undefined) this.picked = p.picked;
    void this.update(cursor.value);
  }

  private async update(c: CursorState) {
    const s = c.state;
    if (!c.rec || !s) {
      this.cols.replaceChildren(placeholder(c, "Open a recording to see its memory."));
      this.data = null;
      this.last = null;
      return;
    }
    if (!this.visible()) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    const signal = this.fresh();
    const rid = c.rec.id;
    const va = this.follow ? BigInt(s.registers[this.follow] ?? s.pc) : this.va ?? BigInt(s.pc);
    const vaHex = hex(va);
    try {
      const [regions, phys, walk] = await Promise.all([
        api.regions(rid, s.step, signal),
        api.physmap(rid, s.step, signal),
        api.pagewalk(rid, s.step, vaHex, signal),
      ]);
      const framePa = this.picked ?? (walk.pa ? BigInt(walk.pa) & ~0xfffn : null);
      const [frame, dump] = framePa !== null && framePa < BigInt(phys.frames.length) * 4096n
        ? await Promise.all([
            api.frame(rid, s.step, hex(framePa), signal),
            api.memory(rid, s.step, hex(framePa), 4096, "phys", signal),
          ])
        : [null, null];
      if (signal.aborted) return;
      const prev = this.last && this.last.rid === rid && this.last.step === c.prev?.step ? this.last : null;
      this.data = { c, regions, phys, walk, frame, dump, va, framePa };
      this.render(prev);
      this.last = {
        rid, step: s.step,
        regions: new Set(regions.regions.map(regionKey)),
        walk,
        dump: dump && framePa !== null ? { pa: framePa, data: dump.data } : null,
      };
    } catch (e) {
      if (!signal.aborted) this.cols.replaceChildren(note((e as Error).message, "error"));
    }
  }

  private render(prev: MemoryPanel["last"]) {
    const d = this.data!;
    this.renderRegions(d.regions, d.va, prev);
    this.renderWalk(d.walk, d.va, prev);
    this.renderPhys(d.phys, d.frame, d.dump, prev);
    if (this.cols.firstChild !== this.vaCol) this.cols.replaceChildren(this.vaCol, this.walkCol, this.physCol);
    this.draw();
    reveal(this.vaCol, this.vaCol.querySelector(".region.selected"));
    if (d.framePa !== null) {
      const { cols, cell } = this.geom;
      const y = this.canvas.offsetTop + Math.floor(Number(d.framePa >> 12n) / cols) * cell;
      revealY(this.physCol, y);
    }
  }

  // ---------------- virtual address space ----------------

  private renderRegions(m: Regions, va: bigint, prev: MemoryPanel["last"]) {
    const s = this.data!.c.state!;
    const head = colHead("Virtual address space",
      `cr3 ${hex(m.cr3)} · ${m.program ?? "no user program"} · ${m.tables} page-table pages`);
    const pick = el("div", "va-pick");
    const input = el("input", "va-input");
    input.value = hex(va);
    input.spellcheck = false;
    input.title = "A virtual address; press Enter to walk it";
    input.onkeydown = (ev) => {
      if (ev.key !== "Enter") return;
      try {
        this.va = BigInt(input.value.trim().startsWith("0x") ? input.value.trim() : "0x" + input.value.trim());
      } catch {
        return;
      }
      this.select({ follow: null, va: this.va, picked: null });
    };
    pick.append(input);
    for (const r of ["rip", "rsp", "cr2"] as const) {
      const v = r === "rip" ? s.pc : s.registers[r];
      if (!v) continue;
      const chip = el("button", "chip" + (this.follow === r ? " on" : ""), r);
      chip.title = `Follow ${r} (${hex(v)})`;
      chip.onclick = () => {
        this.select({ follow: r, picked: null });
      };
      pick.append(chip);
    }

    const list = el("div", "regions");
    const rs = m.regions;
    const rip = BigInt(s.pc), rsp = BigInt(s.registers.rsp ?? "0x0");
    const inside = (g: Region, a: bigint) => a >= BigInt(g.va) && a < BigInt(g.end);
    const rows: HTMLElement[] = [];
    let i = rs.length - 1;
    let upper = 1n << 64n; // end of the address space
    while (i >= 0) {
      // a run of kernel stacks, each one page with an unmapped guard page below
      let j = i;
      if (rs[i].kind === "kstack") while (j > 0 && rs[j - 1].kind === "kstack") j--;
      if (i - j + 1 <= 2) j = i;
      const group = j < i ? rs.slice(j, i + 1) : null;
      const top = rs[i], bottom = rs[j];
      rows.push(...gapRows(BigInt(top.end), upper));
      if (group) {
        const open = this.kstacksOpen;
        const g = el("button", "region group");
        g.append(el("span", `twisty codicon codicon-chevron-${open ? "down" : "right"}`));
        g.append(swatch(regionVar("kstack")));
        g.append(el("span", "label", `kernel stacks · ${group.length}`));
        g.append(el("span", "ptr", group.some((x) => inside(x, rsp)) ? "rsp" : ""));
        g.append(el("span", "size", `${group.length} × 4 KB`));
        g.append(el("span", "flags", ""));
        g.append(el("span", "range", `${hex(bottom.va)} – ${hex(top.end)}`));
        g.title = "One page per process slot, each with an unmapped guard page below";
        g.onclick = () => {
          this.kstacksOpen = !open;
          this.render(null);
        };
        rows.push(g);
        if (open)
          for (let k = group.length - 1; k >= 0; k--) {
            if (k < group.length - 1) {
              const gg = BigInt(group[k + 1].va) - BigInt(group[k].end);
              if (gg > 0n) rows.push(gapRow(gg, true));
            }
            rows.push(this.regionRow(group[k], va, rip, rsp, prev, true));
          }
      } else {
        rows.push(this.regionRow(rs[i], va, rip, rsp, prev, false));
      }
      upper = BigInt(bottom.va);
      i = j - 1;
    }
    rows.push(...gapRows(0n, upper));
    list.append(...rows);
    if (!m.paging) list.replaceChildren(note(PAGING_OFF));
    this.vaCol.replaceChildren(head, pick, list);
  }

  private regionRow(g: Region, va: bigint, rip: bigint, rsp: bigint, prev: MemoryPanel["last"], nested: boolean): HTMLElement {
    const a = BigInt(g.va), e = BigInt(g.end);
    const sel = va >= a && va < e;
    const changed = prev !== null && !prev.regions.has(regionKey(g));
    const r = el("button", "region" + (sel ? " selected" : "") + (changed ? " changed" : "") + (nested ? " nested" : ""));
    r.append(el("span", "twisty"), swatch(regionVar(g.kind)));
    r.append(el("span", "label", g.label));
    const ptrs = [rip >= a && rip < e ? "rip" : "", rsp > a && rsp <= e ? "rsp" : ""].filter(Boolean);
    r.append(el("span", "ptr", ptrs.join(" ")));
    r.append(el("span", "size", bytes(e - a)));
    const flags = el("span", "flags");
    flags.append(flag("U", g.user), flag("W", g.writable), flag("X", g.executable));
    r.append(flags);
    r.append(el("span", "range", `${hex(g.va)} – ${hex(g.end)}`));
    r.title = `${g.pages} page${g.pages > 1 ? "s" : ""} of ${bytes(BigInt(g.pageSize))} → frame${g.pages > 1 ? "s" : ""} from ${hex(g.pa)}`
      + (g.pages > 1 ? (g.contiguous ? " (contiguous)" : " (not contiguous)") : "");
    r.onclick = () => {
      this.select({ follow: null, va: sel ? va : a, picked: null });
    };
    return r;
  }

  // ---------------- page walk ----------------

  private renderWalk(w: Walk, va: bigint, prev: MemoryPanel["last"]) {
    const head = colHead("Page walk", `virtual address ${hex(va)}`);
    const before = prev?.walk && prev.walk.va === w.va ? prev.walk : null;
    const body = el("div", "walk");
    if (!w.paging) {
      const r = el("div", "walk-result");
      r.append(el("span", "what", "physical"), el("span", "val", hex(va)));
      this.walkCol.replaceChildren(head, note(PAGING_OFF), body);
      body.append(r);
      return;
    }

    // the address cut into table indexes and the offset
    const leafLevel = w.pa === null ? 3 : w.pageSize === 1 << 30 ? 1 : w.pageSize === 1 << 21 ? 2 : 3;
    const bits = el("div", "va-bits");
    LEVELS.slice(0, leafLevel + 1).forEach((l, k) => {
      const idx = Number((va >> BigInt(l.lo)) & 0x1ffn);
      const b = el("div", `bits lv${k}`);
      b.append(el("span", "name", l.name), el("span", "val", String(idx)), el("span", "range", `bits ${l.hi}–${l.lo}`));
      bits.append(b);
    });
    const offBits = leafLevel === 3 ? 12 : leafLevel === 2 ? 21 : 30;
    const off = va & ((1n << BigInt(offBits)) - 1n);
    const ob = el("div", "bits off");
    ob.append(el("span", "name", "offset"), el("span", "val", hex(off)), el("span", "range", `bits ${offBits - 1}–0`));
    bits.append(ob);
    body.append(bits);

    const step = (text: string, cls = "") => {
      const r = el("div", "walk-step " + cls);
      r.append(el("span", "note-text", text));
      return r;
    };
    const cr3 = el("div", "walk-level");
    cr3.append(el("span", "what reg", "CR3"), el("span", "val", hex(w.cr3)));
    body.append(cr3, this.frameLink(step("→ PML4 at "), BigInt(w.cr3) & ~0xfffn));
    w.levels.forEach((l, k) => {
      const changed = before !== null && before.levels[k] !== undefined && before.levels[k].entry !== l.entry;
      const r = el("div", "walk-level" + (changed ? " changed" : "") + (l.present ? "" : " absent"));
      const name = el("span", `what lv${k}`, `${l.table}[${l.index}]`);
      name.title = `entry ${l.index} of the ${l.table} table, at physical ${hex(l.entryAddr)}`;
      const flags = el("span", "flags");
      flags.append(flag("P", l.present), flag("W", l.writable), flag("U", l.user), flag("NX", l.noExecute));
      if (l.largePage) flags.append(flag("PS", true));
      r.append(name, el("span", "val", hex(l.entry)), flags);
      body.append(r);
      const next = BigInt(l.entry) & 0x000ffffffffff000n;
      if (!l.present) {
        body.append(step("→ not present: an access here raises a page fault", "fault"));
      } else if (k === w.levels.length - 1 && w.pa !== null) {
        const size = w.pageSize === 4096 ? "4 KB" : bytes(BigInt(w.pageSize));
        const frame = BigInt(w.pa) - off;
        body.append(this.frameLink(step(`→ ${size} frame at `), frame));
      } else {
        body.append(this.frameLink(step(`→ ${LEVELS[k + 1]?.name ?? "?"} at `), next));
      }
    });
    if (w.pa !== null) {
      const r = el("div", "walk-result");
      r.append(el("span", "what", "physical"), el("span", "val", hex(w.pa)));
      r.title = "frame + offset";
      body.append(r);
    } else if (!w.levels.length) {
      body.append(note("CR3 does not point at a page table in RAM."));
    }
    this.walkCol.replaceChildren(head, body);
  }

  /** Appends a clickable physical address to a walk row: picks that frame. */
  private frameLink(row: HTMLElement, pa: bigint): HTMLElement {
    const a = el("button", "pa-link", hex(pa));
    a.title = "Show this frame";
    a.onclick = () => {
      this.select({ picked: pa });
    };
    row.querySelector(".note-text")!.append(a);
    return row;
  }

  // ---------------- physical memory ----------------

  private renderPhys(p: PhysMap, f: PhysFrame | null, dump: Memory | null, prev: MemoryPanel["last"]) {
    const n = p.frames.length;
    const free = p.freeError ? `free list: ${p.freeError}` : `free list: ${grouped(p.freeCount)} frames`;
    const head = colHead("Physical memory", `${bytes(BigInt(n) * 4096n)} · ${grouped(n)} frames of 4 KB · ${free}`);
    const legend = el("div", "phys-legend");
    for (const k of KIND_ORDER) {
      const item = el("div", "item");
      item.append(swatch(FRAME_VAR[k]), el("span", "name", KIND_NAMES[k]), el("span", "count", grouped(p.counts[k] ?? 0)));
      legend.append(item);
    }
    const wrap = el("div", "phys-grid-wrap");
    wrap.append(this.canvas);
    const details = el("div", "frame-details");
    if (f && dump) {
      const fp = BigInt(f.pa);
      const h = el("div", "frame-head");
      h.append(swatch(FRAME_VAR[f.kind]), el("span", "label", `frame ${hex(f.pa)}`), el("span", "dim", KIND_NAMES[f.kind] ?? f.kind));
      details.append(h);
      if (f.mappedAt.length) {
        const m = el("div", "mapped-at");
        m.append(el("span", "dim", "mapped at "));
        for (const v of f.mappedAt) {
          const b = el("button", "pa-link", hex(v));
          b.title = "Walk this virtual address";
          b.onclick = () => {
            this.select({ follow: null, va: BigInt(v), picked: fp });
          };
          m.append(b);
        }
        details.append(m);
      } else {
        details.append(el("div", "mapped-at dim", "not mapped by this page table"));
      }
      if (f.freeIndex !== null)
        details.append(el("div", "mapped-at dim", `on the free list, ${f.freeIndex === 0 ? "at its head (next kalloc() returns it)" : `#${f.freeIndex + 1} from its head`}`));
      const old = prev?.dump && prev.dump.pa === fp ? prev.dump.data : null;
      details.append(hexDump(fp, dump.data, old));
    }
    this.physCol.replaceChildren(head, legend, wrap, this.hoverLine, details);
  }

  private draw() {
    const d = this.data;
    if (!d || !this.visible()) return;
    const frames = d.phys.frames;
    const n = frames.length;
    const width = this.physCol.clientWidth - 24;
    const gutter = 34;
    const cols = width - gutter >= 512 ? 256 : 128;
    const cell = Math.max(2, Math.min(5, Math.floor((width - gutter) / cols)));
    const rows = Math.ceil(n / cols);
    this.geom = { cols, cell, gutter };
    const ctx = sizeCanvas(this.canvas, gutter + cols * cell, rows * cell);
    const color = cssVar;
    const colors: Record<string, string> = {};
    for (const k of KIND_ORDER) colors[k] = color(FRAME_VAR[k]);
    for (let i = 0; i < n; i++) {
      ctx.fillStyle = colors[frames[i]] ?? colors.o;
      ctx.fillRect(gutter + (i % cols) * cell, Math.floor(i / cols) * cell, cell, cell);
    }
    // row labels every 16 MB
    ctx.fillStyle = color("--faint");
    ctx.font = `10px ${color("--mono")}`;
    ctx.textBaseline = "top";
    const perRow = cols * 4096;
    const every = Math.max(1, Math.round((16 << 20) / perRow));
    for (let r = 0; r < rows; r += every) ctx.fillText(`${(r * perRow) >> 20}M`, 0, r * cell);
    // the walk's tables and the frame shown
    const mark = (pa: bigint, c: string) => {
      const i = Number(pa >> 12n);
      if (i < 0 || i >= n) return;
      ctx.strokeStyle = c;
      ctx.lineWidth = 1.5;
      ctx.strokeRect(gutter + (i % cols) * cell - 1.5, Math.floor(i / cols) * cell - 1.5, cell + 3, cell + 3);
    };
    for (const l of d.walk.levels) mark(BigInt(l.entryAddr) & ~0xfffn, color("--accent"));
    if (d.framePa !== null) mark(d.framePa, color("--frame-mark"));
  }

  private cellAt(ev: MouseEvent): number | null {
    const { cols, cell, gutter } = this.geom;
    const x = ev.offsetX - gutter, y = ev.offsetY;
    if (x < 0) return null;
    const i = Math.floor(y / cell) * cols + Math.floor(x / cell);
    return this.data && i >= 0 && i < this.data.phys.frames.length ? i : null;
  }

  private hover(ev: MouseEvent) {
    const i = this.cellAt(ev);
    if (i === null || !this.data) {
      this.hoverLine.textContent = "";
      return;
    }
    const k = this.data.phys.frames[i] as FrameKind;
    this.hoverLine.textContent = `${hex(BigInt(i) * 4096n)} · ${KIND_NAMES[k] ?? k}`;
  }
}

/** Scroll col (only) so that e is in view. */
function reveal(col: HTMLElement, e: Element | null) {
  if (!e) return;
  const top = (e as HTMLElement).offsetTop - col.offsetTop;
  revealY(col, top, (e as HTMLElement).offsetHeight);
}

function revealY(col: HTMLElement, y: number, h = 0) {
  const head = col.querySelector<HTMLElement>(".mem-head")?.offsetHeight ?? 0;
  if (y - head < col.scrollTop || y + h > col.scrollTop + col.clientHeight)
    col.scrollTop = Math.max(0, y - head - col.clientHeight / 3);
}

function regionKey(g: Region): string {
  return `${g.va}-${g.end}-${g.kind}-${g.label}`;
}

function colHead(title: string, sub: string): HTMLElement {
  const h = el("div", "mem-head");
  const s = el("div", "sub", sub);
  s.title = sub;
  h.append(el("div", "title", title), s);
  return h;
}

function swatch(v: string): HTMLElement {
  const s = el("span", "swatch");
  s.style.background = `var(${v})`;
  return s;
}

function flag(name: string, on: boolean): HTMLElement {
  return el("span", "flag" + (on ? " on" : ""), name);
}

function gapRow(size: bigint, small = false, label = small ? "guard page (unmapped)" : "unmapped"): HTMLElement {
  const r = el("div", "region gap" + (small ? " nested" : ""));
  r.append(el("span", "twisty"), el("span", "swatch none"), el("span", "label", label), el("span", "ptr"), el("span", "size", bytes(size)));
  return r;
}

/* x86-64 addresses are 48 bits, sign-extended: the middle is not addressable */
const HOLE_LO = 1n << 47n, HOLE_HI = 0xffff800000000000n;

/** Rows for the unmapped range [lo, hi), highest first, the non-canonical hole on its own. */
function gapRows(lo: bigint, hi: bigint): HTMLElement[] {
  const out: HTMLElement[] = [];
  const add = (a: bigint, b: bigint, label?: string) => {
    if (b > a) out.push(gapRow(b - a, false, label));
  };
  add(hi > HOLE_HI ? (lo > HOLE_HI ? lo : HOLE_HI) : hi, hi);
  add(lo > HOLE_LO ? lo : HOLE_LO, hi < HOLE_HI ? hi : HOLE_HI, "non-canonical (not addressable)");
  add(lo, hi < HOLE_LO ? hi : HOLE_LO);
  return out;
}

/** 16 bytes per line; bytes that differ from `old` (same frame, previous position) are marked. */
function hexDump(base: bigint, data: string, old: string | null): HTMLElement {
  const box = el("div", "hexdump");
  for (let line = 0; line < data.length / 32; line++) {
    const row = el("div", "hex-line");
    row.append(el("span", "addr", hex(base + BigInt(line * 16)).padStart(9, " ")));
    const bytesEl = el("span", "bytes");
    let ascii = "";
    for (let b = 0; b < 16; b++) {
      const o = (line * 16 + b) * 2;
      const v = data.slice(o, o + 2);
      const changed = old !== null && old.slice(o, o + 2) !== v;
      const span = el("span", "b" + (changed ? " changed" : "") + (v === "00" ? " zero" : ""), v);
      bytesEl.append(span);
      const c = parseInt(v, 16);
      ascii += c >= 32 && c < 127 ? String.fromCharCode(c) : ".";
    }
    row.append(bytesEl, el("span", "ascii", ascii));
    box.append(row);
  }
  return box;
}
