// Events: runs of each thread, and inside them calls and traps, loaded a
// level at a time. Follows the cursor: the path to the current step is
// expanded and its innermost node marked. Rows are built once; moving
// the cursor only expands what is new and moves the mark.

import { api, type TreeNode } from "../api";
import { changesRange, cursor, showPanel, stepFilter, type CursorState } from "../cursor";
import { shell } from "../shell";
import { count, grouped } from "../format";
import { el, GLYPH, note, Panel } from "./panel";

const PAGE = 200;
const MAX_IDS = 1000; // per /nodes request

interface Row {
  node: TreeNode;
  depth: number;
  expanded: boolean;
  children: Row[] | null; // null: not loaded
  rowEl: HTMLElement;
  kidsEl: HTMLElement;
  moreEl: HTMLElement | null;
}

export class EventsPanel extends Panel {
  private list = el("div", "tree");
  private rid: string | null = null;
  private roots: Row[] = [];
  private byId = new Map<number, HTMLElement>();
  private active: HTMLElement | null = null;

  constructor() {
    super("events");
  }

  protected mount() {
    this.element.append(this.list);
    this.onDispose(cursor.subscribe((c) => void this.follow(c)));
    let first = true;
    this.onDispose(stepFilter.subscribe(() => {
      if (first) return void (first = false);
      this.rid = null; /* reload with or without the filtered calls */
      void this.follow(cursor.value);
    }));
  }

  private async follow(c: CursorState) {
    if (!c.rec) {
      this.rid = null;
      this.list.replaceChildren(note("Open a recording to see what ran."));
      return;
    }
    const signal = this.fresh();
    if (c.rec.id !== this.rid) {
      /* rid is set once the roots are in: a newer move may abort this load */
      this.rid = null;
      this.roots = [];
      this.byId.clear();
      this.active = null;
      this.list.replaceChildren(note("Loading…"));
      let nodes: TreeNode[];
      try {
        nodes = await api.tree(c.rec.id, -1, stepFilter.value, signal);
      } catch (e) {
        if (!signal.aborted) this.list.replaceChildren(note((e as Error).message, "error"));
        return;
      }
      if (signal.aborted) return;
      this.rid = c.rec.id;
      this.list.replaceChildren();
      this.roots = nodes.map((n) => this.row(n, 0, this.list));
      if (!nodes.length) this.list.append(note("This recording has no events."));
    }
    if (c.state) {
      /* calls the filter left out are not in the tree: stop at their caller */
      const hide = new Set(stepFilter.value ? shell.value.kernels.find((k) => k.id === c.rec!.kernel)?.stepSkip ?? [] : []);
      const path = c.state.path.filter((p) => !(p.kind === "call" && hide.has(p.label)));
      await this.reveal(path.map((p) => p.id), signal);
    }
  }

  /** Expand along node ids from a root down, and mark the last. */
  private async reveal(path: number[], signal: AbortSignal) {
    let level = this.roots;
    for (let i = 0; i < path.length; i++) {
      const id = path[i];
      let r = level.find((x) => x.node.id === id && x.node.count === 1);
      if (!r) {
        const g = level.find((x) => x.node.count > 1 && x.node.members!.includes(id));
        if (!g) break;
        await this.loadGroupUntil(g, g.node.members!.indexOf(id), signal);
        if (signal.aborted) return;
        this.setExpanded(g, true);
        r = g.children!.find((x) => x.node.id === id);
        if (!r) break;
      }
      if (i < path.length - 1) {
        await this.load(r, signal);
        if (signal.aborted) return;
        this.setExpanded(r, true);
        level = r.children!;
      }
    }
    this.mark(path.at(-1));
  }

  private mark(id: number | undefined) {
    this.active?.classList.remove("active");
    this.active = id === undefined ? null : this.byId.get(id) ?? null;
    this.active?.classList.add("active");
    this.active?.scrollIntoView({ block: "nearest" });
  }

  private async load(r: Row, signal?: AbortSignal) {
    if (r.children || !this.rid) return;
    if (r.node.count > 1) {
      await this.loadGroupUntil(r, PAGE - 1, signal);
      return;
    }
    const nodes = await api.tree(this.rid, r.node.id, stepFilter.value, signal);
    if (signal?.aborted || r.children) return;
    r.children = nodes.map((n) => this.row(n, r.depth + 1, r.kidsEl));
  }

  /** Load group members up to index idx (rounded up to a page), in parallel requests. */
  private async loadGroupUntil(g: Row, idx: number, signal?: AbortSignal) {
    const members = g.node.members!;
    const have = g.children?.length ?? 0;
    const want = Math.min(members.length, Math.ceil((idx + 1) / PAGE) * PAGE);
    if (want <= have || !this.rid) return;
    const parts: number[][] = [];
    for (let i = have; i < want; i += MAX_IDS) parts.push(members.slice(i, Math.min(want, i + MAX_IDS)));
    const rid = this.rid;
    const loaded = await Promise.all(parts.map((ids) => api.nodes(rid, ids, stepFilter.value, signal)));
    if (signal?.aborted || (g.children?.length ?? 0) !== have) return;
    g.children ??= [];
    for (const n of loaded.flat()) g.children.push(this.row(n, g.depth + 1, g.kidsEl));
    this.updateMore(g);
  }

  private updateMore(g: Row) {
    const left = g.node.members!.length - (g.children?.length ?? 0);
    g.moreEl?.remove();
    g.moreEl = null;
    if (left <= 0) return;
    const b = el("button", "row more", `Show ${Math.min(PAGE, left)} more of ${count(left)}`);
    b.style.paddingLeft = `${20 + (g.depth + 1) * 14}px`;
    b.onclick = () => void this.loadGroupUntil(g, g.children!.length + PAGE - 1);
    g.kidsEl.append(b);
    g.moreEl = b;
  }

  private setExpanded(r: Row, on: boolean) {
    r.expanded = on;
    r.kidsEl.hidden = !on;
    const t = r.rowEl.querySelector(".twisty");
    if (t && t.textContent) t.textContent = on ? "▾" : "▸";
  }

  private async toggle(r: Row) {
    if (!r.expanded) {
      try {
        await this.load(r);
      } catch (e) {
        alert((e as Error).message);
        return;
      }
    }
    this.setExpanded(r, !r.expanded);
  }

  /** The label of the run a row is in ("proc[1] init"), or null. */
  private runOf(r: Row): string | null {
    let e: HTMLElement | null = r.rowEl;
    while (e && e !== this.list) {
      if (e.classList.contains("kids")) {
        const head = e.previousElementSibling;
        if (head?.classList.contains("kind-run")) return head.querySelector(".label")?.textContent ?? null;
      }
      e = e.parentElement;
    }
    return null;
  }

  /** Build a row (and its hidden children container) at the end of parent. */
  private row(n: TreeNode, depth: number, parent: HTMLElement): Row {
    const group = n.count > 1;
    const e = el("div", `row kind-${n.kind}`);
    if (n.kind === "run" && n.label.startsWith("proc[")) e.classList.add("user-run");
    e.style.paddingLeft = `${6 + depth * 14}px`;
    const kids = el("div", "kids");
    kids.hidden = true;
    const r: Row = { node: n, depth, expanded: false, children: null, rowEl: e, kidsEl: kids, moreEl: null };

    const twisty = el("span", "twisty", n.children > 0 || group ? "▸" : "");
    twisty.onclick = (ev) => {
      ev.stopPropagation();
      void this.toggle(r);
    };
    e.append(twisty, el("span", `glyph codicon codicon-${GLYPH[n.kind]}`));
    /* a run's detail (folded turns: what they waited on) goes after its label */
    let label = n.kind === "run" ? n.label : n.detail ?? n.label;
    if (n.continued) label = "… " + label;
    if (n.suspended) label = label + " …";
    e.append(el("span", "label", label));
    if (n.kind === "run" && n.detail) e.append(el("span", "dim run-why", ` · ${n.detail}`));
    if (group) e.append(el("span", "badge", `×${count(n.count)}`));
    if (!group && n.kind !== "run" && n.end > n.start) {
      const diff = el("button", "row-act codicon codicon-diff");
      diff.title = "Show in Changes what this changed (its state at its start vs its end)";
      diff.onclick = (ev) => {
        ev.stopPropagation();
        changesRange.set({ from: n.start, to: n.end, label: n.detail ?? `${n.label}()`, run: this.runOf(r) });
        showPanel("changes");
      };
      e.append(diff);
    }
    e.append(el("span", "len", count(n.end - n.start)));
    e.title =
      `${n.kind} ${n.label}${n.kind === "run" && n.detail ? `\n${n.detail}` : ""}\nsteps ${grouped(n.start)} – ${grouped(n.end)}` +
      (n.continued ? "\ncontinues from before a context switch" : "") +
      (n.suspended ? "\nswitched out here; continues later" : "");
    e.onclick = () => (group ? void this.toggle(r) : void cursor.go(n.start));
    if (!group) this.byId.set(n.id, e);
    parent.append(e, kids);
    return r;
  }
}
