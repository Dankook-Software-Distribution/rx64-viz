// Dockable layout: default arrangement, saved arrangement, reset, and
// maximize a panel by double-clicking its tab.

import { load, save } from "./storage";
import { createDockview, themeDark, themeLight, type DockviewApi } from "dockview-core";

import { CallStackPanel } from "./panels/callstack";
import { ConsolePanel } from "./panels/console";
import { EventsPanel } from "./panels/events";
import type { Panel } from "./panels/panel";
import { HistoryPanel } from "./panels/history";
import { MemoryPanel } from "./panels/memory";
import { RegistersPanel } from "./panels/registers";
import { SourcePanel } from "./panels/source";
import { StackPanel } from "./panels/stack";
import { StoresPanel } from "./panels/stores";
import { SyscallsPanel } from "./panels/syscalls";
import { TimelinePanel } from "./panels/timeline";

const KEY = "rxviz.layout.v8";

const COMPONENTS: Record<string, () => Panel> = {
  history: () => new HistoryPanel(),
  source: () => new SourcePanel(),
  events: () => new EventsPanel(),
  registers: () => new RegistersPanel(),
  callstack: () => new CallStackPanel(),
  console: () => new ConsolePanel(),
  stores: () => new StoresPanel(),
  stack: () => new StackPanel(),
  memory: () => new MemoryPanel(),
  timeline: () => new TimelinePanel(),
  syscalls: () => new SyscallsPanel(),
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

function defaultLayout(api: DockviewApi) {
  const add = (id: string, title: string, position?: { referencePanel: string; direction: "left" | "right" | "above" | "below" | "within" }) =>
    api.addPanel({ id, component: id, title, ...(position ? { position } : {}) });

  add("source", "Code");
  add("memory", "Memory", { referencePanel: "source", direction: "within" });
  add("history", "History", { referencePanel: "source", direction: "left" });
  add("syscalls", "Syscalls", { referencePanel: "history", direction: "below" });
  add("events", "Events", { referencePanel: "syscalls", direction: "below" });
  add("registers", "Registers", { referencePanel: "source", direction: "right" });
  add("callstack", "Call Stack", { referencePanel: "registers", direction: "below" });
  add("stack", "Stack", { referencePanel: "callstack", direction: "within" });
  add("console", "Console", { referencePanel: "source", direction: "below" });
  add("stores", "Stores", { referencePanel: "console", direction: "within" });
  add("timeline", "Timeline", { referencePanel: "console", direction: "within" });
  api.getPanel("console")?.api.setActive();
  api.getPanel("source")?.api.setActive();

  const w = api.width;
  const h = api.height;
  api.getPanel("history")?.group.api.setSize({ width: Math.round(w * 0.25) });
  api.getPanel("history")?.group.api.setSize({ height: Math.round(h * 0.16) });
  api.getPanel("syscalls")?.group.api.setSize({ height: Math.round(h * 0.42) });
  api.getPanel("registers")?.group.api.setSize({ width: Math.round(w * 0.24) });
  api.getPanel("registers")?.group.api.setSize({ height: Math.round(h * 0.62) });
  api.getPanel("console")?.group.api.setSize({ height: Math.round(h * 0.3) });
}

export function setLayoutTheme(api: DockviewApi, dark: boolean) {
  api.updateOptions({ theme: dark ? themeDark : themeLight });
}
