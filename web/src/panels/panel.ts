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

  /** True if the panel is on screen (its tab is the active one). */
  protected visible(): boolean {
    return this.element.offsetWidth > 0 && this.element.offsetHeight > 0;
  }

  /** Run fn whenever the panel's size changes, including when its tab is shown. */
  protected onResize(fn: () => void) {
    const ro = new ResizeObserver(fn);
    ro.observe(this.element);
    this.onDispose(() => ro.disconnect());
  }

  /** Run fn when the user switches between dark and light. */
  protected onTheme(fn: () => void) {
    window.addEventListener("rx-theme", fn);
    this.onDispose(() => window.removeEventListener("rx-theme", fn));
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

/** A CSS custom property of the current theme (a color such as --kernel). */
export function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Size a canvas to w x h CSS pixels at the screen's pixel ratio; its 2D context, scaled. */
export function sizeCanvas(canvas: HTMLCanvasElement, w: number, h: number): CanvasRenderingContext2D {
  const dpr = window.devicePixelRatio || 1;
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  canvas.style.width = `${w}px`;
  canvas.style.height = `${h}px`;
  const ctx = canvas.getContext("2d")!;
  ctx.scale(dpr, dpr);
  return ctx;
}

/** A centered note for empty or failed panels. */
export function note(text: string, kind: "empty" | "error" = "empty"): HTMLElement {
  return el("div", `note note-${kind}`, text);
}

/** The note for a cursor without a state yet: loading, or nothing open. */
export function placeholder(c: CursorState, nothingOpen: string): HTMLElement {
  return note(c.rec ? "Loading…" : nothingOpen);
}
