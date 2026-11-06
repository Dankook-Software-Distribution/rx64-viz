// Memory 뷰: Virtual(주소 공간, 높은 주소가 위) → Page table(트리 또는 고른 VA의 4단계 walk) → Physical(물리 메모리 지도).
// 고른 VA에서 walk의 각 단계, 그 단계의 페이지 테이블이 놓인 물리 페이지, 마지막 데이터 페이지까지 선으로 잇는다.
import { h, clear, addr, big } from "./dom.js";

const SVG = "http://www.w3.org/2000/svg";
const SEGS = [["PML4", 47, 39], ["PDPT", 38, 30], ["PD", 29, 21], ["PT", 20, 12], ["offset", 11, 0]];

// ---------------------------------------------------------------- Virtual

function items(space) {
  const out = space.regions.map((r) => ({ start: big(r.start), end: big(r.end), name: r.name, note: r.note, kind: "k", region: r }));
  for (const p of space.pages) {
    const va = big(p.va);
    out.push({ start: va, end: va + 4096n, name: p.kind, kind: "u-" + p.kind, page: p });
  }
  out.sort((a, b) => (b.start > a.start ? 1 : b.start < a.start ? -1 : 0));
  return out;
}

function spaceFigure(space, picked, onPick) {
  const rip = space.marks.rip ? big(space.marks.rip) : null;
  const rsp = space.marks.rsp ? big(space.marks.rsp) : null;
  const sel = picked ? big(picked) & ~0xfffn : null;
  const fig = h("div.as");
  const list = items(space);
  if (list.length) fig.append(h("div.as-row", h("code.as-a", addr("0x" + list[0].end.toString(16))), h("div.as-edge")));
  let prevStart = null;
  for (const it of list) {
    if (prevStart !== null && it.end < prevStart) {
      fig.append(h("div.as-gap"));
      fig.append(h("div.as-row", h("code.as-a", addr("0x" + it.end.toString(16))), h("div.as-edge")));
    }
    const marks = [];
    if (it.page) {
      if (rsp !== null && rsp >= it.start && rsp < it.end) marks.push(h("span.ptr.rsp", "rsp"));
      if (rip !== null && rip >= it.start && rip < it.end) marks.push(h("span.ptr", "rip"));
    }
    const va = "0x" + it.start.toString(16);
    const isSel = sel !== null && (it.page ? sel === it.start : sel >= it.start && sel < it.end);
    const body = it.page
      ? h("button.as-box." + it.kind + (isSel ? ".sel" : ""), { type: "button", onclick: () => onPick(it.page.va), "data-n": "va:" + it.page.va, "data-k": "va:" + it.page.va, "data-va": isSel ? "sel" : null },
        h("span.as-m", marks), h("b", it.name), h("code.dim", addr(it.page.pa, { short: true })),
        h("span.flags", it.page.flags.map((f) => h("i", f))))
      : h("button.as-box.k" + (isSel ? ".sel" : ""), { type: "button", onclick: () => onPick(va), "data-va": isSel ? "sel" : null },
        h("b", it.name), it.note ? h("span.dim", it.note) : null);
    fig.append(h("div.as-row.box", h("code.as-a", addr(va)), body));
    prevStart = it.start;
  }
  if (space.marks.sz) fig.append(h("div.as-sz", h("code", "p->sz " + space.marks.sz)));
  return fig;
}

// ---------------------------------------------------------------- Page table

function bits(va) {
  const v = big(va);
  return h("div.va-bits", SEGS.map(([name, hi, lo]) => {
    const n = hi - lo + 1;
    const x = (v >> BigInt(lo)) & ((1n << BigInt(n)) - 1n);
    return h("div.va-seg", { style: { flexGrow: n } },
      h("span.dim", `${hi}:${lo}`), h("b", lo === 0 ? "0x" + x.toString(16) : String(x)), h("span.dim", name));
  }));
}

// 고른 VA의 4단계: 단계마다 그 단계 표가 놓인 물리 주소(table), 인덱스, PTE, 다음 주소
function walkTable(w) {
  const t = h("table.pt.walk");
  t.append(h("thead", h("tr", ["", "table", "idx", "PTE", "flags", "→"].map((x) => h("th", x)))));
  const tb = h("tbody");
  w.levels.forEach((l, k) => tb.append(h("tr" + (l.present ? "" : ".miss"), { "data-lvl": k, "data-table": l.table },
    h("td", l.level), h("td", h("code", addr(l.table, { short: true }))), h("td.num", l.idx),
    h("td", h("code", addr(l.pte, { short: true }))), h("td", h("span.flags", l.flags.map((f) => h("i", f)))),
    h("td", l.present ? h("code", addr(l.next, { short: true })) : h("span.err", "not present")))));
  t.append(tb);
  return t;
}

function treeTable(tree, onPick) {
  const t = h("table.pt");
  t.append(h("thead", h("tr", ["", "idx", "PTE", "flags", "→"].map((x) => h("th", x)))));
  const tb = h("tbody");
  const walk = (n, depth) => {
    for (const e of n.entries) {
      tb.append(h("tr",
        h("td", { style: { paddingLeft: 6 + depth * 14 + "px" } }, n.level), h("td.num", e.idx),
        h("td", h("code", addr(e.pte, { short: true }))), h("td", h("span.flags", e.flags.map((f) => h("i", f)))),
        h("td", e.child ? h("code", addr(e.next, { short: true })) : e.va
          ? h("button.link", { type: "button", onclick: () => onPick(e.va) }, h("code", addr(e.va, { short: true })), " → ", h("code", addr(e.next, { short: true })))
          : null)));
      if (e.child) walk(e.child, depth + 1);
    }
  };
  walk(tree, 0);
  t.append(tb);
  return t;
}

// ---------------------------------------------------------------- Physical

export const COLORS = {
  free: "#242424", kernel: "#8b949e", low: "#484f58", pagetable: "#4daafc", user: "#d18616",
  kstack: "#b180d7", trapframe: "#3fb950", pipe: "#ce9178", kpagetable: "#1f4f99", unknown: "#5a5a5a",
};
const DRAW = 4;

// 한 줄 칸 수와 칸 크기를 칸 폭에 맞춰 고른다 (한 칸 = 4KB 페이지)
function grid(width, height, npages) {
  let best = { cols: 256, cell: 0 };
  for (const cols of [128, 256]) {
    const cell = Math.min(6, width / cols, Math.max(height, 200) / Math.ceil(npages / cols));
    if (cell > best.cell) best = { cols, cell };
  }
  return { COLS: best.cols, CELL: Math.max(1.2, best.cell) };
}

function physFigure(pm, width, height, focusPid, hl, onCell) {
  const { COLS, CELL } = grid(width - 40, height - 110, pm.npages);
  const rows = Math.ceil(pm.npages / COLS);
  const cv = h("canvas.pm", { width: COLS * DRAW, height: rows * DRAW, style: { width: COLS * CELL + "px", height: rows * CELL + "px" } });
  const ctx = cv.getContext("2d");
  ctx.fillStyle = "#181818";
  ctx.fillRect(0, 0, cv.width, cv.height);
  const at = new Array(pm.npages);
  for (const [start, len, k, pid] of pm.runs) {
    const kind = pm.kinds[k];
    for (let n = start; n < start + len; n++) {
      at[n] = [kind, pid];
      const x = (n % COLS) * DRAW, y = Math.floor(n / COLS) * DRAW;
      ctx.fillStyle = COLORS[kind];
      ctx.fillRect(x, y, DRAW - (kind === "free" ? 1 : 0), DRAW - (kind === "free" ? 1 : 0));
      if (focusPid && pid === focusPid && kind !== "free") {
        ctx.fillStyle = "#fff";
        ctx.fillRect(x + 1, y + 1, 2, 2);
      }
    }
  }
  // 고른 VA의 walk에 걸린 페이지: 표 페이지와 데이터 페이지
  ctx.lineWidth = 2;
  for (const [pa, strong] of hl) {
    const n = Number(big(pa) >> 12n);
    if (n < 0 || n >= pm.npages) continue;
    ctx.strokeStyle = strong ? "#4daafc" : "#9cdcfe";
    ctx.strokeRect((n % COLS) * DRAW - 3, Math.floor(n / COLS) * DRAW - 3, DRAW + 6, DRAW + 6);
  }
  const tip = h("code.pm-tip", "");
  const cellAt = (e) => {
    const r = cv.getBoundingClientRect();
    const n = Math.floor((e.clientY - r.top) / r.height * rows) * COLS + Math.floor((e.clientX - r.left) / r.width * COLS);
    return n >= 0 && n < pm.npages ? n : null;
  };
  cv.addEventListener("mousemove", (e) => {
    const n = cellAt(e);
    if (n == null) return;
    const [kind, pid] = at[n] || ["?", 0];
    tip.textContent = `PA ${addr("0x" + (n * 4096).toString(16), { short: true })}  ${kind}${pid ? "  pid " + pid : ""}`;
  });
  cv.addEventListener("click", (e) => {
    const n = cellAt(e);
    if (n != null) onCell("0x" + (n * 4096).toString(16));
  });
  const ruler = h("div.pm-ruler");
  const rowsPerMB = 256 / COLS;
  for (let mb = 0; mb * rowsPerMB < rows; mb += 16) ruler.append(h("code", { style: { top: mb * rowsPerMB * CELL + "px" } }, mb + "M"));

  // 확대: walk에 걸린 페이지가 있는 32페이지 구간을 상자로 크게 (전체 지도에서는 1~2px라 구분이 안 된다)
  const zoom = zoomBox(pm, at, hl, onCell);
  if (zoom) {
    const n0 = zoom.start;
    ctx.strokeStyle = "#e7e7e7";
    ctx.lineWidth = 1;
    ctx.strokeRect((n0 % COLS) * DRAW - 1.5, Math.floor(n0 / COLS) * DRAW - 1.5, Math.min(ZN, COLS) * DRAW + 3, Math.ceil(ZN / COLS) * DRAW + 3);
  }
  const fig = h("div.pm-fig", zoom?.el || null, h("div.pm-wrap", ruler, cv), tip,
    h("div.pm-legend", Object.entries(pm.counts).filter(([, c]) => c).map(([k, c]) =>
      h("span", h("i", { style: { background: COLORS[k] } }), `${k} ${c}`))));
  // 선을 그릴 때 쓰는 물리 주소 -> 확대 상자(있으면) 또는 지도 칸 중심
  fig._cell = (pa) => {
    const n = Number(big(pa) >> 12n);
    const b = zoom?.boxes.get(n);
    if (b) {
      const r = b.getBoundingClientRect();
      return { x: r.left, y: r.top + r.height / 2 };
    }
    const r = cv.getBoundingClientRect();
    return { x: r.left + ((n % COLS) + 0.5) * (r.width / COLS), y: r.top + (Math.floor(n / COLS) + 0.5) * (r.height / rows) };
  };
  return fig;
}

const ZN = 32; // 확대 구간: 32페이지 = 128KB, 8칸 x 4줄

function zoomBox(pm, at, hl, onCell) {
  const pages = hl.map(([pa]) => Number(big(pa) >> 12n)).filter((n) => n >= 0 && n < pm.npages);
  if (!pages.length) return null;
  const strong = new Set(hl.filter(([, s]) => s).map(([pa]) => Number(big(pa) >> 12n)));
  const lo = Math.min(...pages), hi = Math.max(...pages);
  // 다 들어가면 그 구간, 아니면 데이터 페이지 둘레
  const anchor = hi - lo < ZN ? lo : [...strong][0] ?? lo;
  let start = Math.floor(anchor / 8) * 8;
  if (hi - lo < ZN && hi >= start + ZN) start = Math.floor((hi - ZN + 8) / 8) * 8;
  start = Math.max(0, Math.min(start, pm.npages - ZN));
  const boxes = new Map();
  const grid = h("div.pz-grid");
  for (let n = start; n < start + ZN; n++) {
    const [kind, pid] = at[n] || ["unknown", 0];
    const mark = strong.has(n) ? ".pz-data" : pages.includes(n) ? ".pz-table" : "";
    const b = h("button.pz" + mark, { type: "button", title: kind + (pid ? "  pid " + pid : ""), onclick: () => onCell("0x" + (n * 4096).toString(16)) },
      h("i", { style: { background: COLORS[kind] } }), h("code", addr("0x" + (n * 4096).toString(16), { short: true }).slice(2)), h("span.dim", kind));
    boxes.set(n, b);
    grid.append(b);
  }
  return { start, boxes, el: h("div.pz", h("div.dim.small", `${addr("0x" + (start * 4096).toString(16), { short: true })} – ${addr("0x" + ((start + ZN) * 4096 - 1).toString(16), { short: true })}`), grid) };
}

// ---------------------------------------------------------------- 합치기

/**
 * d: { space (그릴 주소 공간), spaces (고를 수 있는 이름들), which, tree, va, walk, phys, focusPid }
 * on: { space(name), pick(va), cell(pa), loadPhys() }
 */
export function renderMemory(el, d, on) {
  clear(el);
  if (!d.space) return;
  const wrap = h("div.memv");
  const svg = document.createElementNS(SVG, "svg");
  svg.classList.add("mem-wires");

  const virt = h("div.mcol.mvirt",
    h("div.mcol-h", d.spaces.length > 1
      ? h("span.seg.small-seg", d.spaces.map((s) => h("button", { type: "button", class: s === d.which ? "on" : null, onclick: () => on.space(s) }, s)))
      : h("b", d.spaces[0]),
      h("code.dim", " CR3 " + addr(d.space.root, { short: true }))),
    d.space.shared?.length ? h("div.dim.small", "PML4 " + d.space.shared.map((x) => x.idx + (x.same ? " = kernel_pagetable" : "")).join(", ")) : null,
    spaceFigure(d.space, d.va, on.pick));

  const w = d.walk;
  const ptc = h("div.mcol.mwalk", h("div.mcol-h", h("b", w && !w.error ? "Page table walk" : "Page table"),
    w && !w.error ? h("button.link", { type: "button", onclick: () => on.pick(null) }, "  tree") : null));
  if (d.va) ptc.append(bits(d.va));
  if (w?.error) ptc.append(h("p.err", w.error));
  else if (w) {
    ptc.append(h("div.walk-root", h("span.dim", "CR3 "), h("code", addr(w.root, { short: true }))), walkTable(w),
      w.pa ? h("p.pa", h("code", "VA " + addr(w.va) + "  →  PA "), h("code.hl", addr(w.pa, { short: true })))
        : h("p.pa.err", h("code", "VA " + addr(w.va) + "  not mapped")));
  } else if (d.tree && d.which === d.spaces[0]) ptc.append(treeTable(d.tree, on.pick));

  const phc = h("div.mcol.mphys", h("div.mcol-h", h("b", "Physical"),
    h("button", { type: "button", onclick: on.loadPhys }, d.phys ? "Reload" : "Load"),
    d.phys && !d.phys.error ? h("span.dim", ` ${d.phys.npages} pages × 4KB`) : null));
  wrap.append(svg, virt, ptc, phc);
  el.append(wrap);

  let fig = null;
  const place = () => {
    if (!d.phys || d.phys.error) {
      phc.querySelector(":scope > p.err")?.remove();
      if (d.phys?.error) phc.append(h("p.err", d.phys.error));
      return;
    }
    const hl = [];
    if (w && !w.error) {
      w.levels.forEach((l) => hl.push([l.table, false]));
      if (w.pa) hl.push([w.pa, true]);
    }
    fig?.remove();
    const r = phc.getBoundingClientRect();
    fig = physFigure(d.phys, r.width, el.clientHeight, d.focusPid, hl, on.cell);
    phc.append(fig);
  };
  place();
  requestAnimationFrame(() => wires(wrap, svg, fig));
  // 칸 폭이 바뀌면 지도 크기와 선을 다시 맞춘다
  if (!el._ro) {
    el._ro = new ResizeObserver(() => el._redraw?.());
    el._ro.observe(el);
  }
  el._redraw = () => { place(); wires(wrap, svg, fig); };
}

// 선: 고른 VA → walk 첫 줄, 단계마다 → 그 표의 물리 페이지, PT 줄 → 데이터 페이지.
// 열이 나란하면 곡선, 위아래로 쌓였으면 오른쪽 여백을 따라 꺾어 내려간다.
function wires(wrap, svg, fig) {
  clear(svg);
  if (!wrap.isConnected) return;
  const box = wrap.getBoundingClientRect();
  svg.setAttribute("width", wrap.scrollWidth);
  svg.setAttribute("height", wrap.scrollHeight);
  let lane = 0;
  const line = (a, b, cls) => {
    const p = document.createElementNS(SVG, "path");
    const x1 = a.x - box.left, y1 = a.y - box.top, x2 = b.x - box.left, y2 = b.y - box.top;
    if (x2 > x1 + 24) {
      const dx = Math.max(30, (x2 - x1) / 2);
      p.setAttribute("d", `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`);
    } else {
      const xr = box.width - 6 - 5 * lane++;
      p.setAttribute("d", `M${x1},${y1} H${xr} V${y2} H${x2}`);
    }
    p.setAttribute("class", cls);
    svg.append(p);
  };
  const right = (el) => { const r = el.getBoundingClientRect(); return { x: r.right, y: r.top + r.height / 2 }; };
  const left = (el) => { const r = el.getBoundingClientRect(); return { x: r.left, y: r.top + r.height / 2 }; };
  const vsel = wrap.querySelector('[data-va="sel"]');
  const rows = [...wrap.querySelectorAll(".walk tbody tr")];
  if (vsel && rows.length) {
    const a = right(vsel), b = left(rows[0]);
    if (b.x > a.x + 24) line(a, b, "w-va");
  }
  if (!fig?._cell) return;
  for (const tr of rows) line(right(tr.lastElementChild), fig._cell(tr.dataset.table), "w-tab");
  const pa = wrap.querySelector(".pa code.hl");
  const last = rows[rows.length - 1];
  if (pa && last) line(right(pa), fig._cell(pa.textContent.replace(/_/g, "")), "w-pa");
}
