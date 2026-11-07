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
}

export interface CursorState {
  rec: RecordingRef | null;
  state: State | null;
  /** state before the last move, within the same recording */
  prev: State | null;
  error: string | null;
}

const CLOSED: CursorState = { rec: null, state: null, prev: null, error: null };

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
    this.s.set({ rec, state: cur.rec?.id === rec.id ? cur.state : null, prev: null, error: null });
    await this.go(0);
  }

  async go(step: number) {
    const rec = this.s.value.rec;
    if (!rec) return;
    this.move?.abort(); // a newer move wins
    const move = (this.move = new AbortController());
    try {
      const state = await api.state(rec.id, Math.max(0, Math.min(step, rec.steps - 1)), move.signal);
      if (move.signal.aborted) return;
      this.s.set({ rec, state, prev: this.s.value.state, error: null });
    } catch (e) {
      if (move.signal.aborted) return;
      this.s.set({ ...this.s.value, error: (e as Error).message });
    }
  }

  async op(op: StepOp) {
    const { rec, state } = this.s.value;
    if (!rec || !state) return;
    const { step } = await api.step(rec.id, state.step, op);
    await this.go(step);
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

/** Hovered address, so panels can highlight the same value elsewhere. */
export const hover = store<string | null>(null);
