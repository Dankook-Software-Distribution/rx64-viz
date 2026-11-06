// 스택 영역 그림. 높은 주소가 위, 한 칸 8바이트. 왼쪽 숫자는 영역 시작 기준 오프셋.
import { h, big } from "./dom.js";

// trapframe 필드를 누가 저장했는지
const PUSHER = {
  ss: "CPU", rsp: "CPU", rflags: "CPU", cs: "CPU", rip: "CPU",
  err: "stub", trapno: "stub", vector: "stub",
  kernel_cr3: "kernel", kernel_sp: "kernel", kernel_trap: "kernel", pad: "kernel",
};

export function stackFigure(fr, opt = {}) {
  const base = big(fr.base);
  const rsp = fr.rsp ? big(fr.rsp) : null;
  const rbp = opt.rbp != null ? slotAddr(fr, big(opt.rbp)) : null;
  const tf = fr.tf ? big(fr.tf) : null;
  // Debug: 프레임별 영역 [sp_i, sp_{i+1})과 스택에 있는 변수 이름
  const regions = [], names = new Map();
  const df = opt.dbg?.frames || [];
  df.forEach((f, i) => {
    for (const v of f.vars || []) {
      const at = slotAddr(fr, big(v.addr) & ~7n);
      if (at === null) continue;
      if (!names.has(at)) names.set(at, []);
      names.get(at).push(v.name);
    }
    const nx = df[i + 1];
    if (f.sp && nx?.sp) {
      const lo = slotAddr(fr, big(f.sp)), hi = slotAddr(fr, big(nx.sp));
      if (lo !== null && hi !== null && lo < hi) regions.push({ lo, hi, f });
    }
  });
  const inStack = (v) => v >= base && v < big(fr.top);

  const rows = [];
  let empty = 0;
  for (const s of fr.slots) {
    if (s.field || s.live) rows.push(s);
    else if (empty++ < 1) rows.push(s);
  }

  const g = h("div.stk");
  g.append(h("div.stk-top", { style: { gridRow: 1 } }, "+" + (big(fr.top) - base).toString(16)));
  const groups = [];
  rows.forEach((s, i) => {
    const r = i + 2;
    const a = big(s.addr);
    const ptr = [];
    if (rsp !== null && a === rsp) ptr.push(h("span.ptr.rsp", "rsp"));
    if (rbp !== null && a === rbp) ptr.push(h("span.ptr.rbp", "rbp"));
    if (tf !== null && a === tf && fr.name === "kstack") ptr.push(h("span.ptr", "tf"));
    const cls = "stk-v" + (s.live ? "" : " empty") + (s.changed ? " chg" : "");
    const rg = !s.field && regions.find((x) => a >= x.lo && a < x.hi);
    let label = s.field || (s.live && s.sym ? s.sym.split("+")[0] : "");
    let lcls = s.field ? "" : ".ret";
    if (rg) {
      if (names.has(a)) { label = names.get(a).join(", "); lcls = ".var"; }
      else if (a === rg.hi - 8n && s.sym) label = "ret → " + s.sym.split("+")[0];
      else if (a === rg.hi - 16n && inStack(big(s.value))) { label = "saved rbp"; lcls = ".sv"; }
    }
    g.append(
      h("div.stk-p", { style: { gridRow: r } }, ptr),
      h("div.stk-o", { style: { gridRow: r } }, (a - base).toString(16)),
      h("div", { class: cls, style: { gridRow: r }, title: s.addr, "data-k": fr.name + ":" + s.addr }, s.live ? "0x" + big(s.value).toString(16) : ""),
      h("div.stk-f" + lcls, { style: { gridRow: r }, title: label || null, "data-decl": s.field ? "trapframe." + s.field : null }, label));
    const key = rg ? "f" + rg.f.level : s.field ? (PUSHER[s.field] || (fr.name === "trapframe" ? "uservec" : "alltraps")) : s.live ? "call" : null;
    const text = rg ? rg.f.fn.split("+")[0] : key;
    const sel = rg && rg.f.level === (opt.dbg.level || 0);
    const last = groups[groups.length - 1];
    if (key && last && last.key === key && last.end === r - 1) last.end = r;
    else if (key) groups.push({ key, text, sel, start: r, end: r });
  });
  for (const gr of groups) {
    g.append(h("div.stk-b" + (gr.sel ? ".sel" : ""), { style: { gridRow: `${gr.start} / ${gr.end + 1}` } }, h("span", gr.text)));
  }
  return g;
}

// 레지스터 값 v가 이 영역의 어느 칸인지 (riscv-style trapframe은 uva로 보이므로 base로 옮긴다)
export function slotAddr(fr, v) {
  const base = big(fr.base), size = big(fr.top) - base;
  if (v >= base && v <= base + size) return v;
  if (fr.uva) {
    const uva = big(fr.uva);
    if (v >= uva && v <= uva + size) return base + (v - uva);
  }
  return null;
}

// 레지스터 값이 가리키는 곳: 스택 영역이면 name+offset
function where(frames, v) {
  for (const fr of frames || []) {
    const a = slotAddr(fr, v);
    if (a !== null) return fr.name + "+0x" + (a - big(fr.base)).toString(16);
  }
  return "";
}

// cols: [{head, rip, rsp, rbp, fn}]. 첫 열이 지금, 나머지는 다음 단계나 저장된 값.
export function regsView(cols, frames) {
  const g = h("div.regs", { style: { gridTemplateColumns: `34px repeat(${cols.length}, minmax(min-content, max-content))` } });
  g.append(h("div.rg-h"), ...cols.map((c) => h("div.rg-h", c.head)));
  for (const n of ["rip", "rsp", "rbp"]) {
    g.append(h("div.rg-n", n));
    cols.forEach((c, i) => {
      const v = c[n];
      const note = !v ? "" : n === "rip" ? c.fn || "" : where(frames, big(v));
      g.append(h("div.rg-v" + (i ? ".next" : ""), v ? h("code", { "data-k": i ? null : "reg:" + n }, v) : "", note ? h("span.rg-w", note) : null));
    });
  }
  return g;
}

export function framesView(frames, opt = {}) {
  return h("div.frames", frames.map((fr) =>
    h("div.frame",
      h("div.frame-h", h("b", fr.name), " ", h("code", fr.base), fr.uva ? h("span.dim", "  uva " + fr.uva) : null),
      stackFigure(fr, opt))));
}
