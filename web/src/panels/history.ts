// History: what can be opened — the kernel's boot, and every command run
// in the Console, newest last. Clicking one opens it everywhere.

import type { BootInfo, RecInfo } from "../api";
import { cursor } from "../cursor";
import { count, grouped } from "../format";
import { shell } from "../shell";
import { el, note, Panel } from "./panel";

const BOOT_STATUS: Record<BootInfo["status"], string> = {
  WAITING: "preparing",
  RECORDING: "recording, a few minutes the first time",
  INDEXING: "preparing",
  READY: "",
  FAILED: "failed",
};

/** A row: ready rows open a recording; others show why they cannot. */
interface Item {
  id: string;
  title: string;
  icon: string;
  hint: string;
  steps: number | null; // set when ready
  status: string; // shown when not ready
}

export class HistoryPanel extends Panel {
  private body = el("div", "list");

  constructor() {
    super("history");
  }

  protected mount() {
    this.element.append(this.body);
    this.onDispose(shell.subscribe(() => this.render()));
    this.onDispose(cursor.subscribe(() => this.render()));
  }

  private render() {
    const { kernels, shell: sh, kernel, error } = shell.value;
    this.body.replaceChildren();
    if (error) this.body.append(note(error, "error"));
    const k = kernels.find((x) => x.id === kernel);
    if (!k) {
      if (!error) this.body.append(note("Starting…"));
      return;
    }
    this.body.append(this.row(k.id, bootItem(k.boot)));
    const recs = sh && sh.kernel === k.id ? sh.recordings : [];
    for (const r of recs) this.body.append(this.row(k.id, recItem(r)));
    if (!recs.length)
      this.body.append(el("div", "hint", "Type a command in the Console. Each one you run is recorded here."));
  }

  private row(kernel: string, it: Item): HTMLElement {
    const row = el("button", "rec" + (cursor.value.rec?.id === it.id ? " open" : ""));
    row.append(el("span", `codicon codicon-${it.icon} rec-icon`), el("span", "rec-title", it.title));
    const steps = it.steps;
    row.append(el("span", "rec-meta", steps === null ? it.status : `${count(steps)} instructions`));
    row.disabled = steps === null;
    row.title = steps === null ? it.hint : `${it.hint} (${grouped(steps)} instructions)`;
    if (steps !== null)
      row.onclick = () => void cursor.open({ id: it.id, kernel, title: it.title, steps });
    return row;
  }
}

function bootItem(b: BootInfo): Item {
  return {
    id: b.id,
    title: "Boot",
    icon: "debug-start",
    hint: "From the kernel's first instruction to the shell prompt",
    steps: b.status === "READY" ? b.summary?.steps ?? null : null,
    status: BOOT_STATUS[b.status],
  };
}

function recItem(r: RecInfo): Item {
  return {
    id: r.id,
    title: r.command,
    icon: "terminal",
    hint: r.command,
    steps: r.status === "ready" ? r.summary?.steps ?? null : null,
    status: r.status === "recording" ? "running" : r.status,
  };
}
