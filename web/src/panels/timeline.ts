// Timeline: the open recording over time, one lane per thread. A lane's
// bar shows when the thread ran, in kernel or user mode; ticks above it
// are the traps it took (syscalls, interrupts, exceptions). Click to go
// to a step (or the trap under the pointer), drag to pan, wheel to
// zoom, double-click to show everything.

import { api, type Timeline, type TimelineMarker } from "../api";
import { cursor, type CursorState } from "../cursor";
import { count, grouped } from "../format";
import { cssVar, el, note, Panel, placeholder, sizeCanvas } from "./panel";

const LABEL_W = 150;
const AXIS_H = 18;
const LANE_H = 22;
const BAR_H = 10;
const MARK_COLOR: Record<TimelineMarker["kind"], string> = {
  syscall: "--tk-function",
  interrupt: "--trap",
  exception: "--bad",
};

export class TimelinePanel extends Panel {
  private head = el("div", "panel-head timeline-head");
  private wrap = el("div", "timeline-wrap");
  private canvas = el("canvas", "timeline-canvas");
  private tip = el("div", "timeline-tip");

  private rid: string | null = null;
  private steps = 0;
  private view = { from: 0, to: 1 };
  private data: Timeline | null = null;
  private step = 0;
  private fetchTimer = 0;
  private drag: { x: number; from: number; to: number; moved: boolean } | null = null;

  constructor() {
    super("timeline");
  }

  protected mount() {
    this.wrap.append(this.canvas, this.tip);
    this.element.append(this.head, this.wrap);
    this.onDispose(cursor.subscribe((c) => this.onCursor(c)));
    this.onResize(() => this.schedule());
    this.onTheme(() => this.draw());

    this.canvas.onwheel = (ev) => {
      ev.preventDefault();
      const at = this.stepAt(ev.offsetX);
      const f = ev.deltaY > 0 ? 1.25 : 0.8;
      this.setView(at - (at - this.view.from) * f, at + (this.view.to - at) * f);
    };
    this.canvas.onmousedown = (ev) => {
      this.drag = { x: ev.clientX, from: this.view.from, to: this.view.to, moved: false };
    };
    const move = (ev: MouseEvent) => {
      if (!this.drag) return;
      const dx = ev.clientX - this.drag.x;
      if (Math.abs(dx) > 3) this.drag.moved = true;
      if (!this.drag.moved) return;
      const per = (this.drag.to - this.drag.from) / this.plotWidth();
      this.setView(this.drag.from - dx * per, this.drag.to - dx * per);
    };
    const up = (ev: MouseEvent) => {
      const d = this.drag;
      this.drag = null;
      if (d && !d.moved && ev.target === this.canvas) this.click(ev.offsetX, ev.offsetY);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
    this.onDispose(() => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    });
    this.canvas.ondblclick = () => this.setView(0, this.steps);
    this.canvas.onmousemove = (ev) => this.hover(ev.offsetX, ev.offsetY);
    this.canvas.onmouseleave = () => (this.tip.style.display = "none");
  }

  private onCursor(c: CursorState) {
    if (!c.rec) {
      this.rid = null;
      this.data = null;
      this.head.textContent = "";
      this.wrap.replaceChildren(placeholder(c, "Open a recording to see its timeline."));
      return;
    }
    if (c.rec.id !== this.rid) {
      this.rid = c.rec.id;
      this.steps = c.rec.steps;
      this.view = { from: 0, to: c.rec.steps };
      this.data = null;
      this.wrap.replaceChildren(this.canvas, this.tip);
      this.schedule();
    }
    if (c.state) {
      this.step = c.state.step;
      const { from, to } = this.view;
      if (this.step < from || this.step >= to) {
        const half = (to - from) / 2;
        this.setView(this.step - half, this.step + half);
      } else {
        this.draw();
      }
    }
  }

  private setView(from: number, to: number) {
    const min = Math.min(this.steps, Math.max(1, this.plotWidth() / 4));
    let span = Math.max(min, Math.min(this.steps, to - from));
    let f = Math.round(Math.max(0, Math.min(from, this.steps - span)));
    span = Math.round(span);
    this.view = { from: f, to: Math.min(this.steps, f + span) };
    this.draw();
    this.schedule();
  }

  private plotWidth(): number {
    return Math.max(50, this.wrap.clientWidth - LABEL_W - 12);
  }

  /** Fetch the view soon (moves while dragging or zooming come in bursts). */
  private schedule() {
    clearTimeout(this.fetchTimer);
    this.fetchTimer = window.setTimeout(() => void this.fetch(), 40);
  }

  private async fetch() {
    if (!this.rid || this.element.offsetWidth === 0) return;
    const signal = this.fresh();
    const { from, to } = this.view;
    try {
      const t = await api.timeline(this.rid, from, to, Math.floor(this.plotWidth()), signal);
      if (signal.aborted) return;
      this.data = t;
      this.draw();
    } catch (e) {
      if (!signal.aborted) this.wrap.replaceChildren(note((e as Error).message, "error"));
    }
  }

  private stepAt(x: number): number {
    const { from, to } = this.view;
    return from + ((x - LABEL_W) / this.plotWidth()) * (to - from);
  }

  private xOf(step: number): number {
    const { from, to } = this.view;
    return LABEL_W + ((step - from) / (to - from)) * this.plotWidth();
  }

  private draw() {
    const t = this.data;
    const { from, to } = this.view;
    this.head.textContent = `steps ${grouped(from)} – ${grouped(to)} (${count(to - from)} of ${count(this.steps)}) · wheel: zoom · drag: pan · double-click: all`;
    if (!t) return;
    const W = this.wrap.clientWidth;
    const H = AXIS_H + t.lanes.length * LANE_H + 6;
    const ctx = sizeCanvas(this.canvas, W, H);
    const color = cssVar;
    const pw = this.plotWidth();
    const bw = pw / t.buckets;
    const bx = (b: number) => LABEL_W + ((t.from + ((t.to - t.from) * b) / t.buckets - from) / (to - from)) * pw;
    const scale = (t.to - t.from) / (to - from); // the data may lag the view while panning
    const font = color("--ui");

    // axis
    ctx.fillStyle = color("--faint");
    ctx.strokeStyle = color("--line");
    ctx.font = `10px ${font}`;
    ctx.textBaseline = "top";
    const ticks = niceTicks(from, to, Math.max(2, Math.floor(pw / 110)));
    for (const s of ticks) {
      const x = this.xOf(s);
      ctx.fillRect(x, AXIS_H - 4, 1, 4);
      ctx.fillText(count(s), x + 2, 2);
      ctx.fillStyle = color("--line");
      ctx.fillRect(x, AXIS_H, 1, H - AXIS_H);
      ctx.fillStyle = color("--faint");
    }

    // lanes
    const kernel = color("--kernel"), user = color("--user");
    t.lanes.forEach((lane, i) => {
      const y = AXIS_H + i * LANE_H;
      ctx.fillStyle = color("--dim");
      ctx.font = `12px ${font}`;
      ctx.textBaseline = "middle";
      ctx.fillText(ellipsize(ctx, lane.label, LABEL_W - 16), 8, y + LANE_H / 2 + 2);
      const top = y + LANE_H - BAR_H - 3;
      for (let b = 0; b < t.buckets; b++) {
        const run = lane.run[b];
        if (!run) continue;
        const x = bx(b), w = Math.max(1, bw * scale);
        ctx.globalAlpha = 0.35 + 0.65 * (run / 100);
        const uh = Math.round((BAR_H * lane.user[b]) / 100);
        ctx.fillStyle = kernel;
        ctx.fillRect(x, top + uh, w, BAR_H - uh);
        if (uh) {
          ctx.fillStyle = user;
          ctx.fillRect(x, top, w, uh);
        }
      }
      ctx.globalAlpha = 1;
    });

    // traps, above their lane's bar
    for (const m of t.markers) {
      if (m.lane < 0) continue;
      const x = bx(m.bucket) + Math.max(1, bw * scale) / 2;
      const y = AXIS_H + m.lane * LANE_H + LANE_H - BAR_H - 3;
      ctx.fillStyle = color(MARK_COLOR[m.kind]);
      ctx.fillRect(Math.round(x) - 1, y - 6, 2, 5);
    }

    // the cursor
    const cx = this.xOf(this.step);
    if (cx >= LABEL_W && cx <= LABEL_W + pw) {
      ctx.fillStyle = color("--frame-mark");
      ctx.fillRect(Math.round(cx) - 1, AXIS_H - 2, 2, H - AXIS_H + 2);
    }
  }

  /** The trap marker near (x, y), if any. */
  private markerAt(x: number, y: number): TimelineMarker | null {
    const t = this.data;
    if (!t) return null;
    const lane = Math.floor((y - AXIS_H) / LANE_H);
    let best: TimelineMarker | null = null, bestD = 5;
    for (const m of t.markers) {
      if (m.lane !== lane) continue;
      const d = Math.abs(this.xOf(m.step) - x);
      if (d < bestD) {
        best = m;
        bestD = d;
      }
    }
    return best;
  }

  private click(x: number, y: number) {
    if (x < LABEL_W) return;
    const m = this.markerAt(x, y);
    void cursor.go(m ? m.step : Math.round(this.stepAt(x)));
  }

  private hover(x: number, y: number) {
    const t = this.data;
    if (!t || x < LABEL_W || this.drag?.moved) {
      this.tip.style.display = "none";
      return;
    }
    const lane = t.lanes[Math.floor((y - AXIS_H) / LANE_H)];
    const s = Math.round(this.stepAt(x));
    const b = Math.floor(((s - t.from) / (t.to - t.from)) * t.buckets);
    const lines = [`step ${grouped(s)}`];
    if (lane && b >= 0 && b < t.buckets && lane.run[b])
      lines.push(`${lane.label}: ran ${lane.run[b]}%, ${lane.user[b]}% of that in user mode`);
    const m = this.markerAt(x, y);
    if (m) lines.push(m.count > 1 ? `${m.label} and ${m.count - 1} more ${m.kind}s (click: the first)` : m.label);
    this.tip.textContent = lines.join("\n");
    this.tip.style.display = "block";
    this.tip.style.left = `${Math.min(x + 12, this.wrap.clientWidth - 260)}px`;
    this.tip.style.top = `${y + 14}px`;
  }
}

/** Round step numbers between from and to, about n of them. */
function niceTicks(from: number, to: number, n: number): number[] {
  const raw = (to - from) / n;
  const mag = 10 ** Math.floor(Math.log10(Math.max(1, raw)));
  const step = [1, 2, 5, 10].map((k) => k * mag).find((k) => k >= raw) ?? 10 * mag;
  const out: number[] = [];
  for (let s = Math.ceil(from / step) * step; s < to; s += step) out.push(s);
  return out;
}

function ellipsize(ctx: CanvasRenderingContext2D, s: string, w: number): string {
  if (ctx.measureText(s).width <= w) return s;
  while (s.length > 1 && ctx.measureText(s + "…").width > w) s = s.slice(0, -1);
  return s + "…";
}
