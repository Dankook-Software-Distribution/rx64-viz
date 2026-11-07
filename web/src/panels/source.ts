// Code: the C (or .S) file of the instruction at the cursor. The running
// line is marked, and the instructions it compiled to open right under
// it, with the running instruction marked; click a line number to open
// or close any line's instructions. Code with no source (firmware) is
// shown as instructions only.

import { api, type Line } from "../api";
import { cursor, runTarget, type CursorState } from "../cursor";
import { highlight } from "../highlight";
import { sourceLines } from "../sources";
import { el, note, Panel, placeholder } from "./panel";

/** One instruction row: gutter (current-instruction arrow), address, mnemonic, operands. */
function asmRow(l: Line, current: boolean): HTMLElement {
  const r = el("div", "asm" + (current ? " current" : ""));
  r.append(
    el("span", "gutter"),
    el("span", "addr", l.addr.replace(/^0x/, "")),
    el("span", "mn", l.mnemonic),
    el("span", "ops", l.operands),
  );
  return r;
}

export class SourcePanel extends Panel {
  private crumbs = el("div", "breadcrumbs");
  private body = el("div", "editor");
  private fileKey = "";        // kernel:file shown
  private fnKey = "";          // function the pinned lines belong to
  private rows: HTMLElement[] = [];
  private pinned = new Set<number>();
  private blocks = new Map<number, HTMLElement>();
  private currentLine = 0;
  /* a line's instructions only depend on the function and line */
  private codeRid = "";
  private code = new Map<string, Promise<Line[]>>();

  constructor() {
    super("source");
  }

  protected mount() {
    this.element.append(this.crumbs, this.body);
    this.onDispose(cursor.subscribe((c) => void this.update(c)));
  }

  /** Instructions of `line` (0: the running line) in the function running at step. */
  private lineCode(rid: string, step: number, fn: string, line: number): Promise<Line[]> {
    if (rid !== this.codeRid) {
      this.codeRid = rid;
      this.code.clear();
    }
    const key = `${fn}:${line}`;
    let p = this.code.get(key);
    if (!p) {
      p = api.code(rid, step, line); /* shared by later callers: never aborted */
      p.catch(() => this.code.delete(key));
      this.code.set(key, p);
    }
    return p;
  }

  private async update(c: CursorState) {
    const s = c.state;
    if (!c.rec || !s) {
      this.fileKey = "";
      this.crumbs.replaceChildren();
      this.body.replaceChildren(placeholder(c, "Open a recording to see the code that ran."));
      return;
    }
    const signal = this.fresh();
    const { file, line, symbol, binary } = s.location;
    this.renderCrumbs(file, symbol, binary);
    if (!file) {
      this.fileKey = "";
      await this.showInstructionsOnly(c.rec.id, s.step, signal);
      return;
    }

    const key = `${c.rec.kernel}:${file}`;
    if (key !== this.fileKey) {
      let lines: string[];
      try {
        lines = await sourceLines(c.rec.kernel, file);
      } catch (e) {
        if (!signal.aborted) this.body.replaceChildren(note(`Cannot open ${file}: ${(e as Error).message}`, "error"));
        return;
      }
      if (signal.aborted) return;
      this.renderFile(lines, file);
      this.fileKey = key;
    }
    const fnKey = `${key}:${binary}:${symbol}`;
    if (fnKey !== this.fnKey) {
      this.fnKey = fnKey;
      this.pinned.clear();
    }

    // instructions of the running line (assembly files need none)
    const asm = file.endsWith(".S");
    let insns: Line[] = [];
    if (!asm) {
      try {
        insns = await this.lineCode(c.rec.id, s.step, fnKey, line);
      } catch {
        /* show the line without its instructions */
      }
      if (signal.aborted) return;
    }
    this.markLine(line);
    for (const [ln, b] of this.blocks)
      if (ln !== line && !this.pinned.has(ln)) {
        b.remove();
        this.blocks.delete(ln);
      }
    if (!asm) this.putBlock(line, insns, s.pc, true);
    for (const ln of this.pinned)
      if (ln !== line) void this.showPinned(c.rec.id, s.step, ln, signal);
    this.scrollToCurrent();
  }

  private renderCrumbs(file: string | null, fn: string | null, binary: string | null) {
    const parts = file ? file.split("/") : [binary ?? "unknown code"];
    if (fn) parts.push(fn);
    this.crumbs.replaceChildren();
    parts.forEach((p, i) => {
      if (i) this.crumbs.append(el("span", "codicon codicon-chevron-right sep"));
      this.crumbs.append(el("span", i === parts.length - 1 && fn ? "crumb fn" : "crumb", p));
    });
  }

  private renderFile(lines: string[], path: string) {
    const html = highlight(lines, path);
    const frag = document.createDocumentFragment();
    const asm = path.endsWith(".S");
    this.rows = [];
    this.blocks.clear();
    this.pinned.clear();
    this.currentLine = 0;
    html.forEach((h, i) => {
      const r = el("div", "src-line");
      const ln = el("span", "ln", String(i + 1));
      ln.title = (asm ? "" : "Click: show or hide the instructions of this line\n")
        + "Ctrl+click: run to this line (Ctrl+F10); with Shift, back to its last run (Ctrl+Shift+F10)";
      ln.onclick = (ev) => {
        runTarget.set({ file: path, line: i + 1 });
        this.markTarget(i + 1);
        if (ev.ctrlKey || ev.metaKey) void cursor.runTo(path, i + 1, ev.shiftKey);
        else if (!asm) void this.togglePin(i + 1);
      };
      const code = el("span", "code");
      code.innerHTML = h || " ";
      r.append(el("span", "gutter"), ln, code);
      this.rows.push(r);
      frag.append(r);
    });
    this.body.replaceChildren(frag);
    const t = runTarget.value;
    if (t && t.file === path) this.markTarget(t.line);
  }

  /** The Run to Cursor line, marked in the gutter. */
  private markTarget(line: number) {
    for (const r of this.body.querySelectorAll(".src-line.target")) r.classList.remove("target");
    this.rows[line - 1]?.classList.add("target");
  }

  private markLine(line: number) {
    this.rows[this.currentLine - 1]?.classList.remove("current");
    this.currentLine = line;
    this.rows[line - 1]?.classList.add("current");
  }

  /** Put (or replace) the instruction block under a line. */
  private putBlock(line: number, insns: Line[], pc: string, running: boolean) {
    this.blocks.get(line)?.remove();
    this.blocks.delete(line);
    const row = this.rows[line - 1];
    if (!row) return;
    const b = el("div", "inline-asm" + (running ? " running" : ""));
    if (!insns.length) b.append(el("div", "asm-note", "No instructions for this line in the running function."));
    for (const i of insns) b.append(asmRow(i, i.addr === pc));
    row.after(b);
    this.blocks.set(line, b);
  }

  private async togglePin(line: number) {
    const c = cursor.value;
    if (!c.rec || !c.state) return;
    if (this.pinned.has(line)) {
      this.pinned.delete(line);
      if (line !== this.currentLine) {
        this.blocks.get(line)?.remove();
        this.blocks.delete(line);
      }
      return;
    }
    this.pinned.add(line);
    await this.showPinned(c.rec.id, c.state.step, line, this.fresh());
  }

  private async showPinned(rid: string, step: number, line: number, signal: AbortSignal) {
    let insns: Line[];
    try {
      insns = await this.lineCode(rid, step, this.fnKey, line);
    } catch {
      return;
    }
    if (signal.aborted || !this.pinned.has(line) || line === this.currentLine) return;
    this.putBlock(line, insns, cursor.value.state?.pc ?? "", false);
  }

  private scrollToCurrent() {
    const row = this.rows[this.currentLine - 1];
    if (!row) return;
    const block = this.blocks.get(this.currentLine);
    const b = this.body.getBoundingClientRect();
    const top = row.getBoundingClientRect().top;
    const bottom = (block ?? row).getBoundingClientRect().bottom;
    if (top < b.top + 30 || bottom > b.bottom - 30) this.body.scrollTop += top - b.top - b.height / 3;
  }

  /** No source line: list the instructions around the running one. */
  private async showInstructionsOnly(rid: string, step: number, signal: AbortSignal) {
    let lines: Line[];
    try {
      lines = await api.disasm(rid, step, signal);
    } catch (e) {
      if (!signal.aborted) this.body.replaceChildren(note((e as Error).message, "error"));
      return;
    }
    if (signal.aborted) return;
    const b = el("div", "inline-asm listing-only");
    for (const l of lines) {
      if (l.function) b.append(el("div", "fn", `${l.function}:`));
      b.append(asmRow(l, l.current));
    }
    this.rows = [];
    this.blocks.clear();
    this.body.replaceChildren(el("div", "asm-note top", "No source for this code; showing its instructions."), b);
    b.querySelector(".asm.current")?.scrollIntoView({ block: "center" });
  }
}
