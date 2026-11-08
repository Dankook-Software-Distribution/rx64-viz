// Dockable layout: default arrangement, saved arrangement, reset, and
// maximize a panel by double-clicking its tab.

import { load, save } from "./storage";
import { createDockview, themeDark, themeLight, type DockviewApi } from "dockview-core";

import { CallStackPanel } from "./panels/callstack";
import { VariablesPanel } from "./panels/variables";
import { ChangesPanel } from "./panels/changes";
import { ConsolePanel } from "./panels/console";
import { CpuPanel } from "./panels/cpu";
import { EventsPanel } from "./panels/events";
import type { Panel } from "./panels/panel";
import { HistoryPanel } from "./panels/history";
import { MemoryPanel } from "./panels/memory";
import { ProcessesPanel } from "./panels/processes";
import { RegistersPanel } from "./panels/registers";
import { SourcePanel } from "./panels/source";
import { StackPanel } from "./panels/stack";
import { StoresPanel } from "./panels/stores";
import { SyscallsPanel } from "./panels/syscalls";
import { TimelinePanel } from "./panels/timeline";

const KEY = "rxviz.layout.v13";

const COMPONENTS: Record<string, () => Panel> = {
  history: () => new HistoryPanel(),
  source: () => new SourcePanel(),
  events: () => new EventsPanel(),
  registers: () => new RegistersPanel(),
  callstack: () => new CallStackPanel(),
  variables: () => new VariablesPanel(),
  console: () => new ConsolePanel(),
  stores: () => new StoresPanel(),
  stack: () => new StackPanel(),
  memory: () => new MemoryPanel(),
  timeline: () => new TimelinePanel(),
  syscalls: () => new SyscallsPanel(),
  processes: () => new ProcessesPanel(),
  changes: () => new ChangesPanel(),
  cpu: () => new CpuPanel(),
};

export function createLayout(host: HTMLElement, dark: boolean): DockviewApi {
  const api = createDockview(host, {
    theme: dark ? themeDark : themeLight,
    createComponent: (o) => {
      const make = COMPONENTS[o.name];
      if (!make) throw new Error(`unknown panel ${o.name}`);
      return make();
    },
  });

  let restored = false;
  const saved = load(KEY);
  if (saved) {
    try {
      api.fromJSON(JSON.parse(saved));
      restored = api.panels.length > 0;
    } catch {
      api.clear(); /* a layout from an older version */
    }
  }
  if (!restored) defaultLayout(api);

  api.onDidLayoutChange(() => save(KEY, JSON.stringify(api.toJSON())));
  window.addEventListener("rx-show-panel", (ev) => {
    const id = (ev as CustomEvent<string>).detail;
    let p = api.getPanel(id);
    if (!p) p = api.addPanel({ id, component: id, title: id[0].toUpperCase() + id.slice(1) });
    p.api.setActive();
  });

  host.addEventListener("dblclick", (ev) => {
    const tab = (ev.target as HTMLElement).closest(".dv-tab");
    const panel = api.activePanel;
    if (!tab || !panel) return;
    if (api.hasMaximizedGroup()) api.exitMaximizedGroup();
    else api.maximizeGroup(panel);
  });
  return api;
}

export function resetLayout(api: DockviewApi) {
  api.clear();
  defaultLayout(api);
}

/**
 * The default arrangement puts what a call did up front: Syscalls (what
 * ran) on the left, Code in the middle, Changes (what it changed) on the
 * right, the shell and the process table below. Memory is a tab next to
 * Changes, so a page walk and the line that caused it show together.
 * Debugger detail (Variables, Call Stack, Registers, Stack, CPU, Events,
 * Stores) waits in tabs behind them.
 */
function defaultLayout(api: DockviewApi) {
  const add = (id: string, title: string, position?: { referencePanel: string; direction: "left" | "right" | "above" | "below" | "within" }) =>
    api.addPanel({ id, component: id, title, ...(position ? { position } : {}) });

  add("source", "Code");
  add("syscalls", "Syscalls", { referencePanel: "source", direction: "left" });
  add("events", "Events", { referencePanel: "syscalls", direction: "within" });
  add("history", "History", { referencePanel: "syscalls", direction: "above" });
  add("changes", "Changes", { referencePanel: "source", direction: "right" });
  add("memory", "Memory", { referencePanel: "changes", direction: "within" });
  add("callstack", "Call Stack", { referencePanel: "changes", direction: "below" });
  add("variables", "Variables", { referencePanel: "callstack", direction: "within" });
  add("registers", "Registers", { referencePanel: "callstack", direction: "within" });
  add("stack", "Stack", { referencePanel: "callstack", direction: "within" });
  add("cpu", "CPU", { referencePanel: "callstack", direction: "within" });
  add("console", "Console", { referencePanel: "source", direction: "below" });
  add("processes", "Processes", { referencePanel: "console", direction: "within" });
  add("timeline", "Timeline", { referencePanel: "console", direction: "within" });
  add("stores", "Stores", { referencePanel: "console", direction: "within" });
  for (const id of ["syscalls", "variables", "console", "source", "changes"]) api.getPanel(id)?.api.setActive();

  const w = api.width;
  const h = api.height;
  api.getPanel("syscalls")?.group.api.setSize({ width: Math.round(w * 0.25) });
  api.getPanel("history")?.group.api.setSize({ height: Math.round(h * 0.14) });
  api.getPanel("changes")?.group.api.setSize({ width: Math.round(w * 0.3) });
  api.getPanel("changes")?.group.api.setSize({ height: Math.round(h * 0.52) });
  api.getPanel("console")?.group.api.setSize({ height: Math.round(h * 0.34) });
}

export function setLayoutTheme(api: DockviewApi, dark: boolean) {
  api.updateOptions({ theme: dark ? themeDark : themeLight });
}
