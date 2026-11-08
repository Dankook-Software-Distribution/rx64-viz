// Console: the xv6 shell of the active session. Every command typed here
// is recorded; when its recording is ready it opens at its first step.

import { FitAddon } from "@xterm/addon-fit";
import { Terminal, type ITheme } from "@xterm/xterm";

import { consoleSocket, type RecInfo } from "../api";
import { cursor } from "../cursor";
import { shell } from "../shell";
import { el, note, Panel } from "./panel";

export class ConsolePanel extends Panel {
  private head = el("div", "panel-head");
  private host = el("div", "term-host");
  private term: Terminal | null = null;
  private fit = new FitAddon();
  private ws: WebSocket | null = null;
  private session: string | null | undefined = undefined; // undefined: not drawn yet

  constructor() {
    super("console");
  }

  protected mount() {
    this.element.append(this.head, this.host);
    this.onDispose(shell.subscribe((s) => this.attach(s.shell?.id ?? null, s.shell?.kernel)));
    const ro = new ResizeObserver(() => this.refit());
    ro.observe(this.host);
    this.onDispose(() => ro.disconnect());
    const onTheme = () => this.term && (this.term.options.theme = termTheme());
    window.addEventListener("rx-theme", onTheme);
    this.onDispose(() => window.removeEventListener("rx-theme", onTheme));
    this.onDispose(() => this.detach());
  }

  private refit() {
    if (this.term && this.host.clientWidth > 0 && this.host.clientHeight > 0) this.fit.fit();
  }

  private detach() {
    this.ws?.close();
    this.ws = null;
    this.term?.dispose();
    this.term = null;
  }

  private attach(id: string | null, kernel: string | undefined) {
    if (id === this.session) return;
    this.detach();
    this.session = id;
    if (!id) {
      this.head.textContent = "";
      this.host.replaceChildren(note(shell.value.booting ? "Booting xv6…" : "xv6 is not running."));
      return;
    }
    this.host.replaceChildren();
    const term = new Terminal({
      fontFamily: getComputedStyle(document.documentElement).getPropertyValue("--mono").trim() || "monospace",
      fontSize: 13,
      lineHeight: 1.2,
      convertEol: true,
      cursorBlink: true,
      scrollback: 5000,
      theme: termTheme(),
    });
    term.loadAddon(this.fit);
    term.open(this.host);
    this.term = term;
    this.refit();

    const setHead = (state: string) => {
      this.head.textContent = `xv6 on ${kernel ?? "?"}: ${describe(state)}`;
    };
    setHead("BOOTING");

    const ws = consoleSocket(id);
    this.ws = ws;
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.type === "output") term.write(m.data);
      else if (m.type === "state") setHead(m.state);
      else if (m.type === "recording") this.onRecording(m.recording as RecInfo, kernel ?? "");
    };
    ws.onclose = () => {
      if (this.ws === ws) setHead("STOPPED");
    };
    /* F10 / F11 step the recording (chrome.ts), even with the console
     * focused: never send their escape sequences to the guest's shell */
    term.attachCustomKeyEventHandler((e) => e.key !== "F10" && e.key !== "F11");
    term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "input", data }));
    });
    term.focus();
  }

  private onRecording(r: RecInfo, kernel: string) {
    void shell.refresh();
    if (r.status === "ready" && r.summary)
      void cursor.open({ id: r.id, kernel, title: r.command, steps: r.summary.steps, start: r.summary.start });
  }
}

function describe(state: string): string {
  switch (state) {
    case "BOOTING": return "booting";
    case "READY": return "ready; each command you run is recorded";
    case "RUNNING": return "running a command, recording";
    case "STOPPED": return "stopped";
    default: return state.toLowerCase();
  }
}

function termTheme(): ITheme {
  const css = getComputedStyle(document.documentElement);
  const v = (n: string) => css.getPropertyValue(n).trim();
  return {
    background: v("--editor"),
    foreground: v("--text"),
    cursor: v("--text"),
    cursorAccent: v("--editor"),
    selectionBackground: v("--select"),
  };
}
