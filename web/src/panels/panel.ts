// Base for dockview content renderers: an element, cleanups to run when
// the panel is closed, and request freshness.

import type { GroupPanelPartInitParameters, IContentRenderer } from "dockview-core";

import type { CursorState } from "../cursor";

/** Codicon per event-tree node kind. */
export const GLYPH = { run: "server-process", call: "symbol-method", trap: "zap" } as const;

export abstract class Panel implements IContentRenderer {
  readonly element: HTMLElement;
  private cleanups: (() => void)[] = [];
  private request: AbortController | null = null;

  constructor(className: string) {
    this.element = document.createElement("div");
    this.element.className = `panel ${className}`;
  }

  init(_params: GroupPanelPartInitParameters): void {
    this.mount();
  }

  protected abstract mount(): void;

  protected onDispose(fn: () => void) {
    this.cleanups.push(fn);
  }

  /**
   * A signal for the panel's newest round of requests: starting another
   * aborts it (cancelling its fetches), so after each await a panel only
   * needs `if (signal.aborted) return`.
   */
  protected fresh(): AbortSignal {
    this.request?.abort();
    this.request = new AbortController();
    return this.request.signal;
  }

  dispose(): void {
    this.request?.abort();
    for (const fn of this.cleanups) fn();
    this.cleanups = [];
  }
}

/** document.createElement with a class and optional text. */
export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

/** A centered note for empty or failed panels. */
export function note(text: string, kind: "empty" | "error" = "empty"): HTMLElement {
  return el("div", `note note-${kind}`, text);
}

/** The note for a cursor without a state yet: loading, or nothing open. */
export function placeholder(c: CursorState, nothingOpen: string): HTMLElement {
  return note(c.rec ? "Loading…" : nothingOpen);
}
