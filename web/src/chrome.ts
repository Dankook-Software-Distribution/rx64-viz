// Title bar with the debug toolbar (VS Code icons and keys), position in
// the recording, and the status bar.

import type { StepOp } from "./api";
import { cursor, stepping, type CursorState, type Granularity } from "./cursor";
import { grouped, hex, ring } from "./format";
import { el } from "./panels/panel";
import { shell } from "./shell";

type Action = {
  icon: string;
  title: string;
  keys: string;
  /** the key events that run it */
  match: (ev: KeyboardEvent) => boolean;
  run: () => StepOp;
};

const ACTIONS: Action[] = [
  { icon: "debug-reverse-continue", title: "Previous trap: back to the last interrupt, exception or syscall",
    keys: "[", match: (e) => e.key === "[", run: () => "prev-trap" },
  { icon: "debug-step-back", title: "Step back",
    keys: "←", match: (e) => e.key === "ArrowLeft", run: () => stepping.op("back") },
  { icon: "debug-step-over", title: "Step over: calls and traps run without stopping",
    keys: "F10", match: (e) => e.key === "F10", run: () => stepping.op("over") },
  { icon: "debug-step-into", title: "Step into",
    keys: "F11 or →", match: (e) => (e.key === "F11" && !e.shiftKey) || e.key === "ArrowRight",
    run: () => stepping.op("into") },
  { icon: "debug-step-out", title: "Step out of the current function or trap",
    keys: "Shift+F11", match: (e) => e.key === "F11" && e.shiftKey, run: () => "out" },
  { icon: "debug-continue", title: "Next trap: forward to the next interrupt, exception or syscall",
    keys: "]", match: (e) => e.key === "]", run: () => "next-trap" },
];

function icon(name: string): HTMLElement {
  return el("span", `codicon codicon-${name}`);
}

export function toolbar(host: HTMLElement, actions: { resetLayout(): void; toggleTheme(): void }) {
  host.replaceChildren();
  host.append(el("span", "app-name", "rxviz"));

  // kernel the shell runs; changing it reboots xv6
  const kpick = el("div", "kernel-pick");
  const select = el("select", "kernel-select");
  select.title = "Kernel the shell runs";
  const restart = el("button", "icon-btn");
  restart.append(icon("debug-restart"));
  restart.title = "Restart xv6 (clears this shell's history)";
  select.onchange = () => {
    const k = select.value;
    const cur = shell.value.shell?.kernel;
    if (cur && cur !== k && !confirm(`Switch to ${k}? xv6 restarts and this shell's history is cleared.`)) {
      select.value = cur;
      return;
    }
    cursor.close();
    void shell.boot(k);
  };
  restart.onclick = () => {
    const k = shell.value.kernel;
    if (!k || !confirm("Restart xv6? This shell's history is cleared.")) return;
    cursor.close();
    void shell.boot(k);
  };
  shell.subscribe((s) => {
    const ids = s.kernels.map((k) => k.id).join(",");
    if (select.dataset.ids !== ids) {
      select.replaceChildren(...s.kernels.map((k) => {
        const o = el("option", "", k.id);
        o.value = k.id;
        return o;
      }));
      select.dataset.ids = ids;
    }
    if (s.kernel && document.activeElement !== select) select.value = s.kernel;
    select.disabled = s.booting || !s.kernels.length;
    restart.disabled = s.booting || !s.kernel;
  });
  kpick.append(select, restart);
  host.append(kpick);

  const bar = el("div", "debug-toolbar");
  const buttons: HTMLButtonElement[] = [];
  for (const a of ACTIONS) {
    const b = el("button", "icon-btn");
    b.append(icon(a.icon));
    b.title = `${a.title} (${a.keys})`;
    b.setAttribute("aria-label", a.title);
    b.onclick = () => void cursor.op(a.run());
    buttons.push(b);
    bar.append(b);
  }
  host.append(bar);

  const gran = el("div", "segmented");
  gran.title = "What Step over, Step into and Step back move by";
  const segs: [Granularity, string][] = [["line", "Line"], ["instruction", "Instruction"]];
  const segButtons = segs.map(([g, label]) => {
    const b = el("button", "seg", label);
    b.onclick = () => stepping.set(g);
    gran.append(b);
    return [g, b] as const;
  });
  stepping.subscribe((g) => {
    for (const [k, b] of segButtons) b.classList.toggle("on", k === g);
  });
  host.append(gran);

  const pos = el("div", "position");
  const input = el("input", "step-input");
  input.type = "text";
  input.inputMode = "numeric";
  input.title = "Step number; press Enter to go there";
  input.onkeydown = (ev) => {
    if (ev.key !== "Enter") return;
    const n = Number(input.value.replace(/[,_\s]/g, ""));
    if (Number.isFinite(n)) void cursor.go(n);
    input.blur();
  };
  const total = el("span", "total");
  const scrub = el("input", "scrubber");
  scrub.type = "range";
  scrub.min = "0";
  scrub.title = "Drag to move through the recording";
  let scrubTimer: number | undefined;
  scrub.oninput = () => {
    window.clearTimeout(scrubTimer);
    scrubTimer = window.setTimeout(() => void cursor.go(Number(scrub.value)), 60);
  };
  pos.append(input, total, scrub);
  host.append(pos);

  const right = el("div", "title-actions");
  const theme = el("button", "icon-btn");
  theme.append(icon("color-mode"));
  theme.title = "Switch between dark and light theme";
  theme.onclick = () => actions.toggleTheme();
  const reset = el("button", "icon-btn");
  reset.append(icon("layout"));
  reset.title = "Reset layout: put every panel back in its default place";
  reset.onclick = () => actions.resetLayout();
  right.append(theme, reset);
  host.append(right);

  cursor.subscribe((c: CursorState) => {
    const ready = !!c.state;
    for (const b of buttons) b.disabled = !ready;
    input.disabled = !c.rec;
    scrub.disabled = !c.rec;
    if (c.rec) {
      scrub.max = String(c.rec.steps - 1);
      total.textContent = `/ ${grouped(c.rec.steps)}`;
    } else {
      total.textContent = "";
      input.value = "";
    }
    if (c.state) {
      if (document.activeElement !== input) input.value = grouped(c.state.step);
      if (document.activeElement !== scrub) scrub.value = String(c.state.step);
    }
  });

  window.addEventListener("keydown", (ev) => {
    const t = ev.target as HTMLElement;
    if (t.closest("input, textarea, .xterm")) return;
    const a = ACTIONS.find((x) => x.match(ev));
    if (!a) return;
    ev.preventDefault();
    void cursor.op(a.run());
  });
}

export function statusbar(host: HTMLElement) {
  cursor.subscribe((c) => {
    host.replaceChildren();
    host.classList.toggle("debugging", !!c.rec);
    const item = (text: string, cls = "") => {
      const e = el("span", `item ${cls}`);
      e.textContent = text;
      host.append(e);
      return e;
    };
    if (c.error) {
      item(c.error, "error");
      return;
    }
    if (!c.rec) {
      item("No recording open");
      return;
    }
    item(c.rec.kernel);
    item(c.rec.title);
    const s = c.state;
    if (!s) return;
    const run = s.path[0]?.label ?? "";
    if (run && run !== c.rec.title) item(run);
    item(`${ring(s.cpl)} mode`);
    if (s.location.file) item(`${s.location.file}:${s.location.line}`);
    item(s.location.label, "mono");
    if (s.registers.cr3) item(`cr3 ${hex(s.registers.cr3)}`, "mono");
  });
}
