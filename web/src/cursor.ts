// The one cursor the whole UI follows: a recording and a step in it.
// Panels subscribe and redraw; the previous state is kept so they can
// mark what changed.

import { api, type State, type StepOp } from "./api";
import { load, save } from "./storage";
import { store } from "./store";

export interface RecordingRef {
  id: string;
  kernel: string;
  title: string;
  steps: number;
  /** where to open it (default 0) */
  start?: number;
}

export interface CursorState {
  rec: RecordingRef | null;
  state: State | null;
  /** state before the last move, within the same recording */
  prev: State | null;
  error: string | null;
  /** a slow move under way (run to a line), for the status bar */
  busy: string | null;
}

const CLOSED: CursorState = { rec: null, state: null, prev: null, error: null, busy: null };

class Cursor {
  private s = store<CursorState>(CLOSED);
  private move: AbortController | null = null;

  get value(): CursorState {
    return this.s.value;
  }

  subscribe(fn: (c: CursorState) => void): () => void {
    return this.s.subscribe(fn);
  }

  /** Close the open recording. */
  close() {
    this.move?.abort();
    this.s.set(CLOSED);
  }

  /** Open a recording at its first step. */
  async open(rec: RecordingRef) {
    const cur = this.s.value;
    this.s.set({ rec, state: cur.rec?.id === rec.id ? cur.state : null, prev: null, error: null, busy: null });
    await this.go(rec.start ?? 0);
  }

  async go(step: number) {
    const rec = this.s.value.rec;
    if (!rec) return;
    this.move?.abort(); // a newer move wins
    const move = (this.move = new AbortController());
    try {
      const state = await api.state(rec.id, Math.max(0, Math.min(step, rec.steps - 1)), move.signal);
      if (move.signal.aborted) return;
      this.s.set({ rec, state, prev: this.s.value.state, error: null, busy: null });
    } catch (e) {
      if (move.signal.aborted) return;
      this.s.set({ ...this.s.value, error: (e as Error).message });
    }
  }

  async op(op: StepOp) {
    const { rec, state } = this.s.value;
    if (!rec || !state) return;
    const r = await api.step(rec.id, state.step, op, stepFilter.value);
    if (!r.found) {
      this.s.set({ ...this.s.value, error: op.startsWith("loop") ? "Not in a loop: no backward jump around this instruction in its function." : `Nowhere to go (${op}).` });
      return;
    }
    await this.go(r.step);
  }

  /** Run to the next (back: previous) time file:line starts, in any thread. */
  async runTo(file: string, line: number, back: boolean) {
    const { rec, state } = this.s.value;
    if (!rec || !state) return;
    this.s.set({ ...this.s.value, error: null, busy: `Running ${back ? "back " : ""}to ${file}:${line}…` });
    let r;
    try {
      r = await api.runTo(rec.id, state.step, file, line, back);
    } catch (e) {
      this.s.set({ ...this.s.value, error: (e as Error).message, busy: null });
      return;
    }
    if (!r.found) {
      this.s.set({ ...this.s.value, busy: null, error: `${file}:${line} does not run ${back ? "before" : "after"} this step.` });
      return;
    }
    await this.go(r.step);
  }
}

export const cursor = new Cursor();

/** Whether Over / Into / Back move by source line or by instruction. */
export type Granularity = "line" | "instruction";
const GRAN_KEY = "rxviz.granularity";
const granularity = store<Granularity>(load(GRAN_KEY) === "instruction" ? "instruction" : "line");

export const stepping = {
  get granularity() {
    return granularity.value;
  },
  set(g: Granularity) {
    save(GRAN_KEY, g);
    granularity.set(g);
  },
  subscribe: granularity.subscribe,
  /** The server op for a debugger action at the current granularity. */
  op(action: "into" | "over" | "back"): StepOp {
    const line = granularity.value === "line";
    switch (action) {
      case "into": return line ? "line-into" : "next";
      case "over": return line ? "line-over" : "over";
      case "back": return line ? "line-back" : "prev";
    }
  },
};

/**
 * The step filter: stepping by line does not stop inside the kernel's
 * lock helpers, and Events leaves their calls out (on by default).
 */
const FILTER_KEY = "rxviz.stepFilter";
export const stepFilter = store<boolean>(load(FILTER_KEY) !== "off");
stepFilter.subscribe((on) => save(FILTER_KEY, on ? "on" : "off"));

/** The line Run to Cursor (Ctrl+F10) goes to: the last line number clicked in Code. */
export const runTarget = store<{ file: string; line: number } | null>(null);

/** Hovered address, so panels can highlight the same value elsewhere. */
export const hover = store<string | null>(null);
