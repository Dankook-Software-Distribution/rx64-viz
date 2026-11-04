// 스택 영역 그림. 높은 주소가 위, 한 칸 8바이트. 왼쪽 숫자는 영역 시작 기준 오프셋.
import { h, big } from "./dom.js";

// trapframe 필드를 누가 저장했는지
const PUSHER = {
  ss: "CPU", rsp: "CPU", rflags: "CPU", cs: "CPU", rip: "CPU",
  err: "stub", trapno: "stub", vector: "stub",
  kernel_cr3: "kernel", kernel_sp: "kernel", kernel_trap: "kernel", pad: "kernel",
};

export function stackFigure(fr) {
  const base = big(fr.base);
  const rsp = fr.rsp ? big(fr.rsp) : null;
  const tf = fr.tf ? big(fr.tf) : null;

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
    if (tf !== null && a === tf && fr.name === "kstack") ptr.push(h("span.ptr", "tf"));
    const cls = "stk-v" + (s.live ? "" : " empty") + (s.changed ? " chg" : "");
    const label = s.field || (s.live && s.sym ? s.sym.split("+")[0] : "");
    g.append(
      h("div.stk-p", { style: { gridRow: r } }, ptr),
      h("div.stk-o", { style: { gridRow: r } }, (a - base).toString(16)),
      h("div", { class: cls, style: { gridRow: r }, title: s.addr }, s.live ? "0x" + big(s.value).toString(16) : ""),
      h("div.stk-f" + (s.field ? "" : ".ret"), { style: { gridRow: r } }, label));
    const key = s.field ? (PUSHER[s.field] || (fr.name === "trapframe" ? "uservec" : "alltraps")) : s.live ? "call" : null;
    const last = groups[groups.length - 1];
    if (key && last && last.key === key && last.end === r - 1) last.end = r;
    else if (key) groups.push({ key, start: r, end: r });
  });
  for (const gr of groups) {
    g.append(h("div.stk-b", { style: { gridRow: `${gr.start} / ${gr.end + 1}` } }, h("span", gr.key)));
  }
  return g;
}

export function framesView(frames) {
  return h("div.frames", frames.map((fr) =>
    h("div.frame",
      h("div.frame-h", h("b", fr.name), " ", h("code", fr.base), fr.uva ? h("span.dim", "  uva " + fr.uva) : null),
      stackFigure(fr))));
}
