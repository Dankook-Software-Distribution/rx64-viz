// 화면 배치 (VS Code처럼). 뷰(cpus[], Graph, Stack, Memory, Source, Shell, Trace ...)를 네 영역에 담는다.
//   left, right   섹션을 위아래로 쌓는다. 제목을 누르면 접고, 경계선으로 높이를 나눈다.
//   center, bottom 탭 그룹을 가로로 늘어놓는다. 경계선으로 폭을 나눈다.
// 탭이나 섹션 제목을 끌어서 다른 그룹/섹션으로 옮기고, 그룹 왼쪽/오른쪽 가장자리에 놓으면 그룹이 나뉜다.
// 배치는 localStorage에 저장하고, 프리셋으로 한 번에 바꾼다.
import { h, clear } from "./dom.js";

const KEY = "xv6viz.dock.v1";
const VIEWS = {};      // id -> { id, title, el, sub, acts }
const HOME = {         // 메뉴에서 다시 켤 때 들어갈 영역
  cpus: "left", procs: "left", callstack: "left", variables: "left", breakpoints: "left",
  graph: "center", stack: "center", memory: "center", source: "right", shell: "bottom", trace: "bottom",
};

const sec = (id, grow = 1) => ({ id, grow, collapsed: false });
const grp = (views, grow = 1) => ({ views, active: views[0], grow });

export const PRESETS = {
  Syscall: () => ({
    left: { show: true, size: 300, items: [sec("cpus", null), sec("procs")] },
    right: { show: true, size: 420, items: [sec("source")] },
    center: { groups: [grp(["graph", "memory"]), grp(["stack"])] },
    bottom: { show: true, size: 220, groups: [grp(["shell"]), grp(["trace"], 1.3)] },
  }),
  Memory: () => ({
    left: { show: true, size: 280, items: [sec("procs")] },
    right: { show: false, size: 420, items: [sec("source")] },
    center: { groups: [grp(["memory", "graph"], 1)] },
    bottom: { show: true, size: 180, groups: [grp(["shell"]), grp(["trace"], 1.3)] },
  }),
  Debug: () => ({
    left: { show: true, size: 320, items: [sec("callstack"), sec("variables", 1.4), sec("breakpoints", 0.6)] },
    right: { show: true, size: 360, items: [sec("cpus", null), sec("procs")] },
    center: { groups: [grp(["source"], 1.2), grp(["disasm"]), grp(["stack", "graph"])] },
    bottom: { show: true, size: 200, groups: [grp(["shell"]), grp(["trace"], 1.3)] },
  }),
};

export const S = { follow: true, max: null }; // follow는 저장, max는 저장하지 않음
let M = load();
let hooks = {};
let compareEl = null;
const $ = (id) => document.getElementById(id);
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

function load() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY));
    if (s?.center?.groups && s.left?.items && s.right?.items && s.bottom?.groups) {
      S.follow = s.follow !== false;
      return s;
    }
  } catch { /* 깨졌으면 기본값 */ }
  return PRESETS.Syscall();
}

function save() {
  localStorage.setItem(KEY, JSON.stringify({ ...M, follow: S.follow }));
}

export { save as saveSettings };

export function register(id, title, el, { sub = null, acts = null } = {}) {
  VIEWS[id] = { id, title, el, sub, acts };
}

// ---------------------------------------------------------------- 모델 조작

// 뷰가 지금 어디 있나: { area, gi, i } (stack 영역은 gi = -1)
function locate(id) {
  for (const a of ["left", "right"]) {
    const i = M[a].items.findIndex((x) => x.id === id);
    if (i >= 0) return { area: a, gi: -1, i };
  }
  for (const a of ["center", "bottom"]) {
    for (let gi = 0; gi < M[a].groups.length; gi++) {
      const i = M[a].groups[gi].views.indexOf(id);
      if (i >= 0) return { area: a, gi, i };
    }
  }
  return null;
}

function detach(id) {
  const at = locate(id);
  if (!at) return;
  if (at.gi < 0) M[at.area].items.splice(at.i, 1);
  else {
    const g = M[at.area].groups[at.gi];
    g.views.splice(at.i, 1);
    if (g.active === id) g.active = g.views[Math.min(at.i, g.views.length - 1)];
  }
}

// 빈 그룹을 지운다. 그룹이 하나도 없으면 빈 그룹 하나를 남긴다 (끌어다 놓을 자리)
function normalize() {
  for (const a of ["center", "bottom"]) {
    M[a].groups = M[a].groups.filter((g) => g.views.length);
    if (!M[a].groups.length) M[a].groups.push({ views: [], active: null, grow: 1 });
    for (const g of M[a].groups) if (!g.views.includes(g.active)) g.active = g.views[0] ?? null;
  }
}

// target: { area, kind: "tab"|"into"|"before"|"after"|"sec", gi, index }
function move(id, t) {
  const from = locate(id);
  const was = from && from.gi >= 0 ? M[from.area].groups[from.gi] : null;
  detach(id);
  if (t.kind === "sec") {
    // 같은 영역 안에서 아래로 옮기면 떼어 낸 만큼 한 칸 당긴다
    const index = from && from.area === t.area && from.gi < 0 && from.i < t.index ? t.index - 1 : t.index;
    M[t.area].items.splice(clamp(index, 0, M[t.area].items.length), 0, sec(id));
    M[t.area].show = true;
  } else {
    let groups = M[t.area].groups;
    let gi = t.gi;
    // detach로 앞쪽 그룹이 비어 사라질 수 있으니 같은 그룹 객체를 다시 찾는다
    const target = t.group;
    if (target) gi = groups.indexOf(target);
    if (t.kind === "before" || t.kind === "after" || gi < 0) {
      const at = gi < 0 ? groups.length : t.kind === "after" ? gi + 1 : gi;
      groups.splice(at, 0, { views: [id], active: id, grow: 1 });
    } else {
      const g = groups[gi];
      const same = was === g && from.i < t.index;
      g.views.splice(t.kind === "tab" ? clamp(t.index - (same ? 1 : 0), 0, g.views.length) : g.views.length, 0, id);
      g.active = id;
    }
    if (t.area === "bottom") M.bottom.show = true;
  }
  normalize();
  save();
  render();
}

export function applyPreset(name) {
  const f = S.follow;
  M = PRESETS[name]();
  S.follow = f;
  S.max = null;
  save();
  render();
}

export function toggleArea(a) {
  if (compareEl && a !== "bottom") return;
  M[a].show = !M[a].show;
  save();
  render();
  hooks.shown?.(a, M[a].show);
}

export function toggleView(id) {
  if (locate(id)) {
    detach(id);
    normalize();
  } else {
    const a = HOME[id] || "center";
    if (a === "left" || a === "right") {
      M[a].items.push(sec(id));
      M[a].show = true;
    } else {
      const g = M[a].groups[M[a].groups.length - 1] || (M[a].groups[0] = { views: [], grow: 1 });
      g.views.push(id);
      g.active = id;
      if (a === "bottom") M.bottom.show = true;
    }
  }
  save();
  render();
}

/** 뷰를 보이게 한다: 탭이면 그 탭을 고르고, 영역이 꺼져 있으면 켠다. */
export function activate(id) {
  const at = locate(id);
  if (!at) return;
  let changed = false;
  if (at.gi >= 0) {
    const g = M[at.area].groups[at.gi];
    if (g.active !== id) { g.active = id; changed = true; }
  } else if (M[at.area].items[at.i].collapsed) {
    M[at.area].items[at.i].collapsed = false;
    changed = true;
  }
  if ((at.area !== "center") && !M[at.area].show) { M[at.area].show = true; changed = true; }
  if (changed) { save(); render(); }
}

/** 뷰가 지금 화면에 보이나 (탭이면 고른 탭, 섹션이면 펼친 것) */
export function visible(id) {
  if (compareEl) return id === "shell";
  const at = locate(id);
  if (!at) return false;
  if (at.area !== "center" && !M[at.area].show) return false;
  if (at.gi >= 0) return M[at.area].groups[at.gi].active === id;
  return !M[at.area].items[at.i].collapsed;
}

export function setCompare(el) {
  compareEl = el;
  S.max = null;
  render();
}

export function toggleMax(key) {
  S.max = S.max === key ? null : key;
  applyMax();
}

// ---------------------------------------------------------------- 그리기

function icon(cls, title, onclick) {
  return h("button.icon", { type: "button", title, onclick: (e) => { e.stopPropagation(); onclick(); } }, h("i.ico." + cls));
}

function maxBtn(key) {
  const b = icon("m", "Maximize", () => toggleMax(key));
  b.dataset.maxBtn = key;
  return b;
}

function groupEl(area, g, gi) {
  const key = `${area}:${gi}`;
  const tabs = h("div.tabs");
  const fixed = area === "bottom" && !!compareEl; // Compare의 Shell은 고정
  g.views.forEach((id, i) => {
    const v = VIEWS[id];
    const t = h("div.tab" + (g.active === id ? ".on" : ""), { "data-view": id },
      h("span", v.title), fixed ? null : icon("x", "Close", () => toggleView(id)));
    if (!fixed) t.addEventListener("pointerdown", (e) => startDrag(e, id, () => {
      if (g.active === id) return;
      g.active = id;
      save();
      render();
    }));
    tabs.append(t);
  });
  const av = VIEWS[g.active];
  tabs.append(...[h("span.tabs-fill"), av?.sub, h("span.acts", av?.acts || null, g.views.length ? maxBtn(key) : null)].filter(Boolean));
  tabs.addEventListener("dblclick", (e) => { if (!e.target.closest(".tab .icon, .acts")) toggleMax(key); });
  const body = h("div.grp-body", av?.el || null);
  const el = h("section.grp", { "data-key": key }, tabs, body);
  el._drop = { area, group: g, gi };
  return el;
}

function tabArea(area, A = M[area]) {
  const row = h("div.area-tabs.card." + area);
  const cols = [];
  A.groups.forEach((g, gi) => {
    if (gi) {
      const s = h("div.gutter.v.gsash");
      sashBetween(s, A.groups, gi, row);
      row.append(s);
      cols.push("1px");
    }
    row.append(groupEl(area, g, gi));
    cols.push(`minmax(0, ${g.grow || 1}fr)`);
  });
  row.style.gridTemplateColumns = cols.join(" ");
  row._area = area;
  return row;
}

function stackArea(area) {
  const A = M[area];
  const col = h("aside.area-stack.card." + area);
  col._area = area;
  A.items.forEach((it, i) => {
    const v = VIEWS[it.id];
    if (!v) return;
    if (i) {
      const s = h("div.gutter.h.ssash");
      const both = !it.collapsed && !A.items[i - 1].collapsed;
      s.hidden = !both;
      if (both) sashSections(s, A.items, i, col);
      col.append(s);
    }
    const key = `${area}:${it.id}`;
    const hd = h("h2.sec-h", { "data-view": it.id }, h("span.sec-t", v.title), v.sub || null,
      h("span.acts", v.acts || null, maxBtn(key), icon("x", "Close", () => toggleView(it.id))));
    hd.addEventListener("pointerdown", (e) => startDrag(e, it.id, () => {
      it.collapsed = !it.collapsed;
      save();
      render();
    }));
    const s = h("section.sec" + (it.collapsed ? ".collapsed" : ""), { "data-key": key }, hd, h("div.sec-body", v.el));
    s.style.flex = it.collapsed ? "none" : it.grow == null ? "0 1 auto" : `${it.grow} 1 0`;
    if (it.grow == null && !it.collapsed) s.style.maxHeight = "50%";
    s._drop = { area, index: i };
    col.append(s);
  });
  return col;
}

export function render() {
  const work = $("work");
  // 다시 만들기 전에 뷰 element를 떼어 둔다 (그대로 옮겨 붙이므로 상태가 유지된다).
  // 떼면 스크롤 위치가 사라지니 적어 두었다가 되돌린다.
  const scroll = new Map();
  for (const v of Object.values(VIEWS)) {
    if (v.el.isConnected) scroll.set(v.el, [v.el.scrollTop, v.el.scrollLeft]);
    v.el.remove();
    v.sub?.remove();
    v.acts?.remove();
  }
  clear(work);
  if (compareEl) {
    const center = h("div.center-col",
      h("div.area-tabs.card.center", h("section.grp", h("div.grp-body", compareEl))),
      h("div.gutter.h.card-gap", { id: "gut-bottom" }),
      tabArea("bottom", { groups: [grp(["shell"])] }));
    work.append(center);
    work.style.gridTemplateColumns = "minmax(0, 1fr)";
    center.style.gridTemplateRows = `minmax(0, 1fr) var(--gap) min(${M.bottom.size}px, 85%)`;
    sashArea($("gut-bottom"), "bottom");
  } else {
    const L = M.left, R = M.right, B = M.bottom;
    const cols = [];
    if (L.show) {
      work.append(stackArea("left"), h("div.gutter.v.card-gap", { id: "gut-left" }));
      cols.push(`min(${L.size}px, 40%)`, "var(--gap)");
    }
    const center = h("div.center-col", tabArea("center"));
    if (B.show) center.append(h("div.gutter.h.card-gap", { id: "gut-bottom" }), tabArea("bottom"));
    center.style.gridTemplateRows = B.show ? `minmax(0, 1fr) var(--gap) min(${B.size}px, 85%)` : "minmax(0, 1fr)";
    work.append(center);
    cols.push("minmax(0, 1fr)");
    if (R.show) {
      work.append(h("div.gutter.v.card-gap", { id: "gut-right" }), stackArea("right"));
      cols.push("var(--gap)", `min(${R.size}px, 45%)`);
    }
    work.style.gridTemplateColumns = cols.join(" ");
    if (L.show) sashArea($("gut-left"), "left");
    if (R.show) sashArea($("gut-right"), "right");
    if (B.show) sashArea($("gut-bottom"), "bottom");
  }
  applyMax();
  for (const [el, [t, l]] of scroll) {
    if (el.isConnected) {
      el.scrollTop = t;
      el.scrollLeft = l;
    }
  }
  for (const [id, a] of [["tg-left", "left"], ["tg-bottom", "bottom"], ["tg-right", "right"]]) {
    $(id).classList.toggle("off", !M[a].show);
    $(id).disabled = !!compareEl && a !== "bottom";
  }
  hooks.changed?.();
}


function applyMax() {
  for (const el of document.querySelectorAll(".maxed")) el.classList.remove("maxed");
  const el = S.max && document.querySelector(`[data-key="${CSS.escape(S.max)}"]`);
  if (el) el.classList.add("maxed");
  else S.max = null;
  for (const b of document.querySelectorAll("[data-max-btn]")) {
    const on = S.max === b.dataset.maxBtn;
    b.classList.toggle("on", on);
    b.title = on ? "Restore  Esc" : "Maximize";
  }
}

// ---------------------------------------------------------------- 경계선

function sash(g, onMove, onReset) {
  g.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    e.preventDefault();
    g.setPointerCapture(e.pointerId);
    document.body.classList.add(g.classList.contains("v") ? "drag-v" : "drag-h");
    const mv = (ev) => onMove(ev);
    const up = () => {
      g.removeEventListener("pointermove", mv);
      g.removeEventListener("pointerup", up);
      g.removeEventListener("pointercancel", up);
      document.body.classList.remove("drag-v", "drag-h");
      save();
      render();
    };
    g.addEventListener("pointermove", mv);
    g.addEventListener("pointerup", up);
    g.addEventListener("pointercancel", up);
  });
  g.addEventListener("dblclick", () => { onReset(); save(); render(); });
}

const DEF = { left: 300, right: 420, bottom: 220 };

function sashArea(g, a) {
  sash(g, (e) => {
    const r = $("work").getBoundingClientRect();
    const col = g.closest(".center-col")?.getBoundingClientRect() || r;
    if (a === "left") M.left.size = Math.round(clamp(e.clientX - r.left, 160, r.width - 320));
    if (a === "right") M.right.size = Math.round(clamp(r.right - e.clientX, 200, r.width - 320));
    if (a === "bottom") M.bottom.size = Math.round(clamp(col.bottom - e.clientY, 60, col.height - 80));
    live();
  }, () => (M[a].size = DEF[a]));
}

// 그룹 사이: 양옆 그룹의 비율을 나눈다
function sashBetween(g, groups, i, row) {
  sash(g, (e) => {
    const els = [...row.querySelectorAll(":scope > .grp")];
    const a = els[i - 1].getBoundingClientRect(), b = els[i].getBoundingClientRect();
    const total = (groups[i - 1].grow || 1) + (groups[i].grow || 1);
    const f = clamp((e.clientX - a.left) / (b.right - a.left), 0.1, 0.9);
    groups[i - 1].grow = total * f;
    groups[i].grow = total * (1 - f);
    row.style.gridTemplateColumns = groups.map((x, k) => (k ? "1px " : "") + `minmax(0, ${x.grow || 1}fr)`).join(" ");
  }, () => { groups[i - 1].grow = groups[i].grow = 1; });
}

// 섹션 사이: 위아래 섹션의 비율. 내용 높이(grow null)였던 섹션은 지금 높이로 바꾼다.
function sashSections(g, items, i, col) {
  sash(g, (e) => {
    const secs = [...col.querySelectorAll(":scope > .sec")];
    const ia = items.slice(0, i).filter((x) => VIEWS[x.id]).length - 1;
    const a = secs[ia].getBoundingClientRect(), b = secs[ia + 1].getBoundingClientRect();
    const total = a.height + b.height;
    const f = clamp((e.clientY - a.top) / (b.bottom - a.top), 0.08, 0.92);
    const ga = items[i - 1], gb = items[i];
    const sum = (ga.grow ?? a.height / total) + (gb.grow ?? b.height / total) || 1;
    ga.grow = sum * f;
    gb.grow = sum * (1 - f);
    secs[ia].style.flex = `${ga.grow} 1 0`;
    secs[ia].style.maxHeight = "";
    secs[ia + 1].style.flex = `${gb.grow} 1 0`;
  }, () => { items[i - 1].grow = 1; items[i].grow = 1; });
}

function live() {
  const L = M.left, R = M.right, B = M.bottom, work = $("work");
  if (compareEl) {
    work.querySelector(".center-col").style.gridTemplateRows = `minmax(0, 1fr) var(--gap) min(${B.size}px, 85%)`;
    return;
  }
  const cols = [];
  if (L.show) cols.push(`min(${L.size}px, 40%)`, "var(--gap)");
  cols.push("minmax(0, 1fr)");
  if (R.show) cols.push("var(--gap)", `min(${R.size}px, 45%)`);
  work.style.gridTemplateColumns = cols.join(" ");
  if (B.show) work.querySelector(".center-col").style.gridTemplateRows = `minmax(0, 1fr) var(--gap) min(${B.size}px, 85%)`;
}

// ---------------------------------------------------------------- 끌어 옮기기
//
// 포인터를 누른 채 5px 넘게 움직이면 끌기 시작한다. 그보다 적으면 클릭(onClick).

let drag = null;

function startDrag(e, id, onClick) {
  if (e.button !== 0 || e.target.closest(".icon, input, select, button:not(.tab)")) return;
  const x0 = e.clientX, y0 = e.clientY;
  const src = e.currentTarget;
  try {
    src.setPointerCapture(e.pointerId);
  } catch { /* 포인터가 이미 사라졌다 */ }
  const mv = (ev) => {
    if (!drag) {
      if (Math.hypot(ev.clientX - x0, ev.clientY - y0) < 5) return;
      drag = { id, ghost: h("div.drag-ghost", VIEWS[id].title), ind: h("div.drop-ind") };
      document.body.append(drag.ghost, drag.ind);
      document.body.classList.add("dnd");
    }
    drag.ghost.style.left = ev.clientX + 12 + "px";
    drag.ghost.style.top = ev.clientY + 8 + "px";
    drag.target = targetAt(ev.clientX, ev.clientY);
    showInd(drag.target);
  };
  const up = (ev) => {
    src.removeEventListener("pointermove", mv);
    src.removeEventListener("pointerup", up);
    src.removeEventListener("pointercancel", up);
    if (!drag) {
      if (ev.type === "pointerup") onClick();
      return;
    }
    const d = drag;
    drag = null;
    d.ghost.remove();
    d.ind.remove();
    document.body.classList.remove("dnd");
    if (d.target && ev.type === "pointerup") move(d.id, d.target);
  };
  src.addEventListener("pointermove", mv);
  src.addEventListener("pointerup", up);
  src.addEventListener("pointercancel", up);
  // 끄는 중에 다시 그려져 원래 element가 사라지면 pointerup이 오지 않는다: 그대로 취소
  src.addEventListener("lostpointercapture", (ev) => { if (drag) up({ type: "pointercancel" }); }, { once: true });
}

// 포인터 아래의 놓을 자리
function targetAt(x, y) {
  const el = document.elementFromPoint(x, y);
  if (!el || compareEl) return null;
  const g = el.closest(".grp");
  if (g?._drop) {
    const { area, group, gi } = g._drop;
    const tabs = g.querySelector(".tabs").getBoundingClientRect();
    if (y <= tabs.bottom) {
      const ts = [...g.querySelectorAll(".tab")];
      let index = ts.findIndex((t) => { const r = t.getBoundingClientRect(); return x < r.left + r.width / 2; });
      if (index < 0) index = ts.length;
      const r = index < ts.length ? ts[index].getBoundingClientRect() : (ts[ts.length - 1] || g).getBoundingClientRect();
      const lx = index < ts.length ? r.left : ts.length ? r.right : tabs.left;
      return { area, group, gi, kind: "tab", index, rect: { left: lx - 1, top: tabs.top, width: 3, height: tabs.height } };
    }
    const r = g.getBoundingClientRect();
    const body = { left: r.left, top: tabs.bottom, width: r.width, height: r.bottom - tabs.bottom };
    if (x < r.left + r.width * 0.25) return { area, group, gi, kind: "before", rect: { ...body, width: r.width / 2 } };
    if (x > r.right - r.width * 0.25) return { area, group, gi, kind: "after", rect: { ...body, left: r.left + r.width / 2, width: r.width / 2 } };
    return { area, group, gi, kind: "into", rect: body };
  }
  const st = el.closest(".area-stack");
  if (st) {
    const area = st._area;
    const secs = [...st.querySelectorAll(":scope > .sec")];
    for (const [k, s] of secs.entries()) {
      const r = s.getBoundingClientRect();
      if (y >= r.top && y <= r.bottom) {
        const before = y < r.top + r.height / 2;
        const index = s._drop.index + (before ? 0 : 1);
        return { area, kind: "sec", index, rect: { left: r.left, top: (before ? r.top : r.bottom) - 2, width: r.width, height: 4 } };
      }
    }
    const r = st.getBoundingClientRect();
    return { area, kind: "sec", index: M[area].items.length, rect: { left: r.left, top: r.top, width: r.width, height: r.height } };
  }
  return null;
}

function showInd(t) {
  const ind = drag.ind;
  ind.hidden = !t;
  if (!t) return;
  Object.assign(ind.style, { left: t.rect.left + "px", top: t.rect.top + "px", width: t.rect.width + "px", height: t.rect.height + "px" });
}

// ---------------------------------------------------------------- 메뉴, 단축키


function openMenu(btn) {
  closeMenu();
  const m = h("div.menu", { id: "menu" },
    h("div.menu-h", "Layout"),
    ...Object.keys(PRESETS).map((p) => h("button.menu-i", { type: "button", onclick: () => { closeMenu(); applyPreset(p); } }, p)),
    h("div.menu-sep"),
    h("div.menu-h", "Views"),
    ...Object.values(VIEWS).map((v) => h("button.menu-i" + (locate(v.id) ? ".chk" : ""), { type: "button", onclick: () => { closeMenu(); toggleView(v.id); } }, v.title)));
  document.body.append(m);
  const r = btn.getBoundingClientRect();
  m.style.left = r.left + "px";
  m.style.top = r.bottom + 4 + "px";
  setTimeout(() => document.addEventListener("pointerdown", outside), 0);
}

function outside(e) {
  if (!e.target.closest("#menu")) closeMenu();
}

function closeMenu() {
  $("menu")?.remove();
  document.removeEventListener("pointerdown", outside);
}

/** 배치 단축키. xterm이 먼저 삼키지 않도록 터미널에도 알려 준다. */
export function layoutKey(e) {
  if (!e.ctrlKey || e.shiftKey || e.metaKey) return null;
  if (e.code === "KeyB") return e.altKey ? "right" : "left";
  if (!e.altKey && (e.code === "KeyJ" || e.code === "Backquote")) return "bottom";
  return null;
}

// 저장된 배치에서 모르는 뷰와 겹친 뷰를 뺀다 (뷰가 등록된 뒤에 부른다)
function sanitize() {
  const seen = new Set();
  const ok = (id) => VIEWS[id] && !seen.has(id) && seen.add(id);
  for (const a of ["left", "right"]) M[a].items = M[a].items.filter((x) => ok(x.id));
  for (const a of ["center", "bottom"]) for (const g of M[a].groups) g.views = g.views.filter(ok);
  normalize();
}

export function init(h0) {
  hooks = h0;
  sanitize();
  $("tg-left").onclick = () => toggleArea("left");
  $("tg-bottom").onclick = () => toggleArea("bottom");
  $("tg-right").onclick = () => toggleArea("right");
  $("btn-menu").onclick = (e) => ($("menu") ? closeMenu() : openMenu(e.currentTarget));
  document.addEventListener("keydown", (e) => {
    const k = layoutKey(e);
    if (k) {
      e.preventDefault();
      toggleArea(k);
    } else if (e.key === "Escape") {
      if ($("menu")) closeMenu();
      else if (S.max && !e.target.closest?.(".term")) toggleMax(S.max);
    }
  });
  render();
}
