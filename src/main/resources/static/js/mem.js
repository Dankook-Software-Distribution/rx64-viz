// 메모리 그림: 가상 주소 공간(높은 주소가 위), 페이지 테이블 트리, 물리 메모리 맵
import { h, clear, addr, big } from "./dom.js";

// ---------------------------------------------------------------- 가상 주소 공간

function items(space) {
  const out = space.regions.map((r) => ({ start: big(r.start), end: big(r.end), name: r.name, note: r.note, kind: "k" }));
  for (const p of space.pages) {
    const va = big(p.va);
    out.push({ start: va, end: va + 4096n, name: p.kind, note: "", kind: "u-" + p.kind, page: p });
  }
  out.sort((a, b) => (b.start > a.start ? 1 : b.start < a.start ? -1 : 0));
  // 같은 종류의 연속된 유저 페이지는 그대로 둔다 (페이지마다 PTE가 다르다)
  return out;
}

function spaceFigure(space, pickedVa, onPick) {
  const rip = space.marks.rip ? big(space.marks.rip) : null;
  const rsp = space.marks.rsp ? big(space.marks.rsp) : null;
  const picked = pickedVa ? big(pickedVa) & ~0xfffn : null;
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
    const body = it.page
      ? h("button.as-box." + it.kind + (picked === it.start ? ".sel" : ""), { type: "button", onclick: () => onPick(it.page.va) },
        h("span.as-m", marks), h("b", it.name), h("code.dim", addr(it.page.pa, { short: true })),
        h("span.flags", it.page.flags.map((f) => h("i", f))))
      : h("div.as-box.k", h("b", it.name), it.note ? h("span.dim", it.note) : null);
    // 주소는 칸의 아래쪽 경계, 곧 시작 주소
    fig.append(h("div.as-row", h("code.as-a", addr("0x" + it.start.toString(16))), body));
    prevStart = it.start;
  }
  if (space.marks.sz) fig.append(h("div.as-sz", h("code", "p->sz " + space.marks.sz)));
  return fig;
}

export function renderAspace(el, as, pickedVa, onPick) {
  clear(el);
  if (!as) return;
  el.append(h("div.spaces", as.spaces.map((sp) =>
    h("div.space",
      h("div.space-h", h("b", sp.name), h("code", " cr3 " + addr(sp.root, { short: true }))),
      sp.shared.length ? h("div.dim.small", "PML4 " + sp.shared.map((x) => x.idx + (x.same ? " = kernel_pagetable" : "")).join(", ")) : null,
      spaceFigure(sp, pickedVa, onPick)))));
}

// ---------------------------------------------------------------- 페이지 테이블 트리

const SEGS = [["PML4", 47, 39], ["PDPT", 38, 30], ["PD", 29, 21], ["PT", 20, 12], ["offset", 11, 0]];

function idxPath(va) {
  if (!va) return null;
  const v = big(va);
  return SEGS.slice(0, 4).map(([, , lo]) => Number((v >> BigInt(lo)) & 0x1ffn));
}

function bits(va) {
  const v = big(va);
  return h("div.va-bits", SEGS.map(([name, hi, lo]) => {
    const n = hi - lo + 1;
    const x = (v >> BigInt(lo)) & ((1n << BigInt(n)) - 1n);
    return h("div.va-seg", { style: { flexGrow: n } },
      h("span.dim", `${hi}:${lo}`), h("b", lo === 0 ? "0x" + x.toString(16) : String(x)), h("span.dim", name));
  }));
}

export function renderPtree(el, tree, va, walkRes, onPick) {
  clear(el);
  if (!tree) return;
  const path = idxPath(va);
  el.append(h("form.va-form", {
    onsubmit: (e) => { e.preventDefault(); const v = e.target.elements.va.value.trim(); if (v) onPick(v); },
  }, h("label", "VA"), h("input", { name: "va", value: va || "", spellcheck: "false", autocomplete: "off" }), h("button", { type: "submit" }, "변환")));
  if (va) el.append(bits(va));

  const table = h("table.pt");
  table.append(h("thead", h("tr", ["", "idx", "PTE", "flags", "→"].map((t) => h("th", t)))));
  const tb = h("tbody");
  tb.append(h("tr.root", h("td", "CR3"), h("td"), h("td"), h("td"), h("td", h("code", addr(tree.pa, { short: true })))));
  const walk = (n, depth, onPath) => {
    for (const e of n.entries) {
      const hit = onPath && path && path[depth] === e.idx;
      tb.append(h("tr" + (hit ? ".hit" : ""),
        h("td", { style: { paddingLeft: 6 + depth * 16 + "px" } }, n.level),
        h("td.num", e.idx),
        h("td", h("code", addr(e.pte, { short: true }))),
        h("td", h("span.flags", e.flags.map((f) => h("i", f)))),
        h("td", e.child ? h("code", addr(e.next, { short: true })) : e.va
          ? h("button.link", { type: "button", onclick: () => onPick(e.va) }, h("code", addr(e.va, { short: true })), " → ", h("code", addr(e.next, { short: true })))
          : null)));
      if (e.child) walk(e.child, depth + 1, hit);
    }
  };
  walk(tree, 0, true);
  table.append(tb);
  el.append(table);
  if (walkRes?.pa) {
    el.append(h("p.pa", h("code", "VA " + addr(walkRes.va) + "  →  PA "), h("code.hl", addr(walkRes.pa, { short: true }))));
  } else if (walkRes && !walkRes.error) {
    el.append(h("p.pa.err", h("code", "VA " + addr(walkRes.va) + "  not mapped")));
  } else if (walkRes?.error) {
    el.append(h("p.err", walkRes.error));
  }
}

// ---------------------------------------------------------------- 물리 메모리

const COLORS = {
  free: "#ffffff", kernel: "#4d4d4d", low: "#a6a6a6", pagetable: "#0057d9", user: "#e07b00",
  kstack: "#7a3db8", trapframe: "#1a9850", pipe: "#a65e2e", kpagetable: "#002b80", unknown: "#cfcfcf",
};
const COLS = 256; // 한 줄 = 1MB
const CELL = 3;

export function renderPhys(el, pm, focusPid, onReload) {
  clear(el);
  el.append(h("div.pm-h", h("button", { type: "button", onclick: onReload }, pm ? "다시 읽기" : "읽기"),
    pm ? h("span.dim", ` ${pm.npages} pages × 4KB`) : null));
  if (!pm) return;
  if (pm.error) {
    el.append(h("p.err", pm.error));
    return;
  }
  const rows = pm.npages / COLS;
  const cv = h("canvas.pm", { width: COLS * CELL, height: rows * CELL });
  const ctx = cv.getContext("2d");
  ctx.fillStyle = "#e6e6e6";
  ctx.fillRect(0, 0, cv.width, cv.height);
  const at = new Array(pm.npages);
  for (const [start, len, k, pid] of pm.runs) {
    const kind = pm.kinds[k];
    for (let n = start; n < start + len; n++) {
      at[n] = [kind, pid];
      const x = (n % COLS) * CELL, y = Math.floor(n / COLS) * CELL;
      ctx.fillStyle = COLORS[kind];
      ctx.fillRect(x, y, CELL - (kind === "free" ? 1 : 0), CELL - (kind === "free" ? 1 : 0));
      if (focusPid && pid === focusPid && kind !== "free") {
        ctx.fillStyle = "#000";
        ctx.fillRect(x + 1, y + 1, 1, 1);
      }
    }
  }
  const tip = h("code.pm-tip", "");
  cv.addEventListener("mousemove", (e) => {
    const r = cv.getBoundingClientRect();
    const n = Math.floor((e.clientY - r.top) / CELL) * COLS + Math.floor((e.clientX - r.left) / CELL);
    if (n < 0 || n >= pm.npages) return;
    const [kind, pid] = at[n] || ["?", 0];
    tip.textContent = `PA ${addr("0x" + (n * 4096).toString(16), { short: true })}  ${kind}${pid ? "  pid " + pid : ""}`;
  });
  const ruler = h("div.pm-ruler");
  for (let mb = 0; mb < rows; mb += 16) ruler.append(h("code", { style: { top: mb * CELL + "px" } }, mb + "M"));
  el.append(h("div.pm-wrap", ruler, cv), tip,
    h("div.pm-legend", Object.entries(pm.counts).filter(([, c]) => c).map(([k, c]) =>
      h("span", h("i", { style: { background: COLORS[k] } }), `${k} ${c}`))));
}
