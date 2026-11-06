import { Terminal } from "./vendor/xterm.mjs";
import { FitAddon } from "./vendor/addon-fit.mjs";
import { h, clear, addr, markChanges, reveal } from "./js/dom.js";
import { framesView, regsView, slotAddr } from "./js/stack.js";
import { renderGraph } from "./js/graph.js";
import { renderMemory } from "./js/mem.js";
import { showSource, fetchFile, lineNode } from "./js/source.js";
import * as dock from "./js/dock.js";
import { initDecl, setStructs } from "./js/decl.js";

const LABEL = { riscv: "riscv-style", x86: "x86-style" };
const STATE = { STARTING: "Booting", RUNNING: "Running", PAUSED: "Paused", STEPPING: "Running", STOPPED: "Stopped", FAILED: "Failed" };

const $ = (id) => document.getElementById(id);
const vms = {};
let kernels = [];
let view = null;
let ws = null;
let cmdText = ""; // Compare의 "both shells" 입력 (위쪽 줄을 다시 그려도 남는다)

function send(vm, msg) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ ...msg, vm }));
}

// ---------------------------------------------------------------- 뷰
//
// 뷰마다 element를 한 번 만들어 dock에 등록한다. dock은 이 element를 영역 사이로 옮겨 붙인다.

const E = {
  cpus: h("div.body"), procs: h("div.body"),
  graph: h("div.body.graph-body"), graphSub: h("span.sub"),
  stack: h("div.body"), stackSub: h("span.sub"),
  memory: h("div.body.mem-body"), memSub: h("span.sub"),
  source: h("div.body.source"), srcSub: h("span.sub"), srcActs: h("span.src-acts"),
  shell: h("div.panel-body"),
  trace: h("div.body.rec-list"), traceSub: h("span.sub"),
  disasm: h("div.body.dasm"), disasmSub: h("span.sub"),
  callstack: h("div.body.dbg-list"), variables: h("div.body.dbg-list"), breakpoints: h("div.body.dbg-list"),
  compare: h("div.compare"),
};
dock.register("cpus", "cpus[]", E.cpus);
dock.register("procs", "proc[]", E.procs);
dock.register("graph", "Graph", E.graph, { sub: E.graphSub });
dock.register("stack", "Stack", E.stack, { sub: E.stackSub });
dock.register("memory", "Memory", E.memory, { sub: E.memSub });
dock.register("source", "Source", E.source, { sub: E.srcSub, acts: E.srcActs });
dock.register("shell", "Shell", E.shell);
dock.register("trace", "Trace", E.trace, { sub: E.traceSub });
dock.register("disasm", "Disassembly", E.disasm, { sub: E.disasmSub });
dock.register("callstack", "Call Stack", E.callstack);
dock.register("variables", "Variables", E.variables);
dock.register("breakpoints", "Breakpoints", E.breakpoints);

function vmOf(id) {
  if (!vms[id]) {
    const host = h("div.term");
    const term = new Terminal({
      fontFamily: '"D2CodingLigature Nerd Font Mono", "D2Coding", monospace', fontSize: 13,
      cursorBlink: true, convertEol: true, scrollback: 2000,
      theme: { background: "#181818", foreground: "#cccccc", cursor: "#aeafad", selectionBackground: "#264f78" },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.attachCustomKeyEventHandler((ev) => !dock.layoutKey(ev) && !debugKey(ev));
    term.onData((d) => send(id, { type: "input", data: d }));
    vms[id] = {
      id, state: "STARTING", snap: null, focus: null, cpu: null,
      // Memory 뷰: 고른 주소 공간(user/kernel), 고른 VA와 그 walk, 물리 메모리 지도
      mem: { which: "user", va: null, walk: null, phys: null },
      term, fit, host, opened: false, autoRefresh: true,
      // Trace: rows는 화면에 보이는 줄(연속 read/write를 합친 것). sel은 고른 줄과 그 단계.
      rec: { active: false, events: [], pending: [], rows: [] },
      sel: null, details: new Map(),
      // Debug: 멈춘 곳(호출 스택, 변수)과 중단점. srcPin은 사용자가 연 파일.
      dbg: null, bps: [], srcPin: null, files: null,
    };
    new ResizeObserver(() => vms[id].opened && fit.fit()).observe(host);
  }
  return vms[id];
}

function mountTerm(vm, slot) {
  if (vm.host.parentElement !== slot) slot.append(vm.host);
  if (!vm.opened) {
    vm.term.open(vm.host);
    vm.opened = true;
  }
  requestAnimationFrame(() => vm.fit.fit());
}

const visible = () => (view === "compare" ? kernels.map(vmOf) : view ? [vmOf(view)] : []);
const cur = () => (view && view !== "compare" ? vmOf(view) : null);

// ---------------------------------------------------------------- 상단

function renderKernels() {
  const nav = clear($("kernels"));
  for (const k of [...kernels, "compare"]) {
    nav.append(h("button", { type: "button", class: k === view ? "on" : null, onclick: () => setView(k) }, k === "compare" ? "Compare" : LABEL[k] || k));
  }
}

function renderBar() {
  const vs = visible();
  const st = clear($("status"));
  for (const vm of vs) {
    st.append(h("span.st", { "data-s": vm.state }, (view === "compare" ? LABEL[vm.id] + " " : "") + (STATE[vm.state] || vm.state)),
      vm.snap ? h("code.dim", ` ticks ${vm.snap.ticks}  `) : null);
  }
  const paused = vs.length && vs.every((v) => v.state === "PAUSED");
  $("btn-pause").textContent = paused ? "Continue" : "Pause";
  $("chk-refresh").checked = vs.every((v) => v.autoRefresh);
}

$("btn-pause").onclick = () => {
  const vs = visible();
  const resume = vs.every((v) => v.state === "PAUSED");
  vs.forEach((v) => send(v.id, { type: resume ? "resume" : "pause" }));
};
$("btn-restart").onclick = () => visible().forEach((v) => send(v.id, { type: "restart" }));
$("chk-refresh").onchange = (e) => visible().forEach((v) => send(v.id, { type: "autoRefresh", on: e.target.checked }));

let errTimer;
function showError(t) {
  const el = $("error");
  el.textContent = t;
  el.hidden = false;
  clearTimeout(errTimer);
  errTimer = setTimeout(() => (el.hidden = true), 8000);
}

// ---------------------------------------------------------------- 위쪽 줄: Trace 켜기, 단계 레일, Debug 도구

function renderTrace() {
  const fid = document.activeElement?.id;
  const sel = fid === "t-cmd" ? [document.activeElement.selectionStart, document.activeElement.selectionEnd] : null;
  const el = clear($("trace"));
  const vs = visible();
  if (!vs.length) return;
  const vm = cur();
  if (vm && (vm.state === "PAUSED" || vm.state === "STEPPING")) {
    el.append(...debugBar(vm));
    return;
  }
  el.append(traceToggle(vs));
  if (view === "compare") el.append(cmdForm(vs));
  else if (vm.sel) el.append(rail(vm) || "", h("code.call", callNodes(vm.sel.ev)), h("code.dim", `pid ${vm.sel.ev.pid} ${vm.sel.ev.name}`));
  // 다시 그려도 쓰던 입력과 커서를 지킨다
  if (fid === "t-cmd" && $(fid)) {
    $(fid).focus();
    $(fid).setSelectionRange(...sel);
  }
}

// Trace: 켜 두는 동안 셸에서 친 명령의 시스템 콜을 남긴다
function traceToggle(vs) {
  const on = vs.some((v) => v.rec.active);
  const ready = vs.every((v) => v.state === "RUNNING");
  const n = vs.map((v) => v.rec.events.length).join(" / ");
  return h("span.tform",
    h("button.rec-tg" + (on ? ".on" : ""), {
      type: "button", disabled: !on && !ready, title: "Record every system call while on",
      onclick: () => vs.forEach((v) => send(v.id, { type: on ? "recordStop" : "recordStart" })),
    }, h("i.dot"), "Trace"),
    h("code.dim", n),
    h("button", { type: "button", disabled: !vs.some((v) => v.rec.events.length), onclick: () => { vs.forEach((v) => send(v.id, { type: "recordClear" })); clearSel(); } }, "Clear"));
}

function cmdForm(vs) {
  return h("form.tform", {
    onsubmit: (e) => {
      e.preventDefault();
      const c = e.target.elements.cmd.value;
      vs.forEach((v) => send(v.id, { type: "input", data: c + "\r" }));
      e.target.elements.cmd.value = cmdText = "";
    },
  }, h("label", "both shells"), h("input.cmd", { id: "t-cmd", name: "cmd", value: cmdText, placeholder: "$", spellcheck: "false", autocomplete: "off", oninput: (e) => (cmdText = e.target.value) }), h("button", { type: "submit" }, "Send"));
}

// ---------------------------------------------------------------- Trace 목록

// 연속된 같은 fd의 read/write는 한 줄로 합친다 (셸의 한 글자씩 읽기, printf의 한 글자씩 쓰기)
function mergeEvents(list) {
  const out = [];
  for (const e of list) {
    const last = out[out.length - 1];
    const io = (x) => (x.sys === "read" || x.sys === "write") && x.buf !== undefined && x.ret >= 0;
    if (last && io(last) && io(e) && last.sys === e.sys && last.pid === e.pid && last.args[0] === e.args[0] && !last.buf.endsWith("\n")) {
      last.buf += e.buf;
      last.more ||= e.more;
      last.ret += e.ret;
      last.cnt += Number(e.args[2]) || 0;
      last.n++;
      continue;
    }
    out.push({ ...e, n: 1, cnt: Number(e.args?.[2]) || 0 });
  }
  return out;
}

// 셸이 stdin에서 줄바꿈까지 읽은 줄은 명령 한 줄
const cmdline = (e) => e.sys === "read" && e.name === "sh" && e.args?.[0] === "0" && e.buf?.endsWith("\n") ? e.buf.trim() : null;

function argNode(a) {
  if (a.startsWith('"') || a.startsWith("[")) return h("span.str", a);
  if (/^-?\d+$/.test(a)) return h("span.num", a);
  return h("span", a);
}

function callNodes(e) {
  let args = e.args || [];
  if (e.n > 1) {
    const b = e.buf.length > 48 ? e.buf.slice(0, 48) : e.buf;
    args = [args[0], JSON.stringify(b) + (e.more || e.buf.length > 48 ? "…" : ""), String(e.cnt)];
  }
  const out = [h("span.fn", e.sys), "("];
  args.forEach((a, i) => out.push(i ? ", " : "", argNode(a)));
  out.push(")");
  if (e.ret != null) out.push(" = ", h("span" + (e.ret < 0 ? ".neg" : ".num"), String(e.ret)));
  return out;
}

function evRow(vm, e, idx, pending) {
  return h("div.ev" + (vm.sel?.idx === idx && !pending ? ".sel" : "") + (pending ? ".pending" : "") + (e.early ? ".early" : ""), {
    "data-idx": pending ? null : idx,
    onclick: pending ? null : () => pickRow(vm, idx),
  },
    h("span.ev-pid", e.pid), h("span.ev-name", e.name),
    h("code.ev-call", callNodes({ ...e, ret: null })),
    h("code.ev-ret" + (e.ret < 0 ? ".neg" : ""), pending ? "…" : e.ret == null ? "" : "= " + e.ret),
    h("span.ev-x", e.exec ? h("span.ev-exec", "→ " + e.exec) : null, e.n > 1 ? h("span.dim", " ×" + e.n) : null));
}

function renderRecordList(vm, list) {
  const r = vm.rec;
  const atEnd = list.scrollHeight - list.scrollTop - list.clientHeight < 30;
  const top = list.scrollTop;
  const out = [];
  r.rows.forEach((e, i) => {
    const c = cmdline(e);
    if (c != null) out.push(h("div.ev-cmd", h("code", "$ " + c)));
    out.push(evRow(vm, e, i, false));
  });
  clear(list).append(...out, ...r.pending.map((e) => evRow(vm, e, 0, true)));
  list.scrollTop = r.active && atEnd ? list.scrollHeight : top;
  list.querySelector(".ev.sel")?.scrollIntoView({ block: "nearest" });
}

function renderRecord(vm) {
  if (view !== vm.id) return;
  E.traceSub.textContent = vm.rec.events.length ? `${vm.rec.events.length}` : "";
  renderRecordList(vm, E.trace);
}

// 단계 레일: 그 시스템 콜이 지나간 단계와 그때의 페이지 테이블(user/kernel)
function rail(vm) {
  const d = vm.sel?.detail;
  if (!d?.stages.length) return null;
  return h("ol.rail", d.stages.map((s, k) => h("li", {
    class: k === vm.sel.stage ? "here" : k < vm.sel.stage ? "done" : null,
    onclick: () => setStage(k),
  }, h("span", s.step === "handler" ? vm.sel.ev.handler || "sys_*" : s.step), h("small", s.pagetable))));
}

// 비교 화면에서 같은 시스템 콜 찾기: 같은 (프로세스 이름, 시스템 콜)의 몇 번째인지로 맞춘다
function matchRow(rowsA, idx, rowsB) {
  const e = rowsA[idx];
  const nth = rowsA.slice(0, idx + 1).filter((x) => x.name === e.name && x.sys === e.sys).length;
  let k = 0;
  return rowsB.findIndex((x) => x.name === e.name && x.sys === e.sys && ++k === nth);
}

function pickRow(vm, idx) {
  if (vm.sel?.idx === idx) return clearSel();
  select(vm, idx, 0);
  if (view === "compare") {
    for (const o of visible()) if (o !== vm) {
      const j = matchRow(vm.rec.rows, idx, o.rec.rows);
      if (j >= 0) select(o, j, 0);
    }
  }
}

// stage: 단계 번호. -1이면 마지막 단계.
async function select(vm, idx, stage) {
  const e = vm.rec.rows[idx];
  if (!e) return;
  vm.sel = { idx, ev: e, detail: vm.details.get(e.i) || null, stage: 0 };
  vm.srcPin = null;
  if (!vm.sel.detail) {
    let d = null;
    if (e.detail) {
      try {
        const r = await fetch(`/api/record?vm=${vm.id}&i=${e.i}`);
        d = await r.json();
        if (d.error) throw new Error(d.error);
        // 켜져 있는 동안에는 뒤 단계(trapret, iretq)가 아직 안 찍혔을 수 있어 캐시하지 않는다
        if (!vm.rec.active) vm.details.set(e.i, d);
      } catch (err) {
        d = null;
        showError(`${LABEL[vm.id]}: ${err.message}`);
      }
    }
    // 기다리는 사이 목록이 다시 만들어졌을 수 있으니 객체가 아니라 번호로 비교한다
    if (vm.sel?.idx !== idx || vm.sel.ev.i !== e.i) return;
    vm.sel.detail = d || { stages: [], after: null };
  }
  const n = vm.sel.detail.stages.length;
  vm.sel.stage = stage < 0 ? Math.max(0, n - 1) : Math.min(stage, Math.max(0, n - 1));
  if (vm.snap?.procs.some((p) => p.pid === e.pid)) setFocus(vm, e.pid);
  refresh();
}

function clearSel() {
  visible().forEach((v) => (v.sel = null));
  refresh();
}

function setStage(k) {
  for (const v of visible()) {
    const n = v.sel?.detail?.stages.length;
    if (n) v.sel.stage = Math.max(0, Math.min(k, n - 1));
  }
  refresh();
}

// N, →: 다음 단계 (마지막이면 다음 줄). ←: 이전 단계. ↓ ↑: 다음/이전 줄.
function step(dir) {
  for (const v of visible()) {
    if (!v.sel) continue;
    const n = v.sel.detail?.stages.length || 0;
    const k = v.sel.stage + dir;
    if (k >= 0 && k < n) v.sel.stage = k;
    else row(v, dir, dir > 0 ? 0 : -1);
  }
  refresh();
}

function row(v, dir, stage = 0) {
  const j = v.sel.idx + dir;
  if (j >= 0 && j < v.rec.rows.length) select(v, j, stage);
}

// ---------------------------------------------------------------- Debug
//
// 커널을 실제로 멈추고 한 줄씩 실행한다. Source 줄 번호를 누르면 중단점.
// F5 Continue, F10 Step Over, F11 Step Into, Shift+F11 Step Out.
// 명령어 하나씩: Ctrl+F10 nexti (call은 건너뜀), Ctrl+F11 stepi (call 안으로).

const DBG_KEYS = {
  F5: "resume", F10: "debugNext", F11: "debugStep", "S-F11": "debugFinish",
  "C-F10": "debugNexti", "C-F11": "debugStepi",
};

function debugKey(e) {
  if (e.altKey || e.metaKey) return null;
  return DBG_KEYS[(e.ctrlKey ? "C-" : "") + (e.shiftKey ? "S-" : "") + e.key] || null;
}

function debugBar(vm) {
  const d = vm.dbg;
  const stepping = vm.state === "STEPPING";
  const btn = (t, label, title, dis) => h("button" + (t === "resume" ? ".primary" : ""), { type: "button", title, disabled: dis, onclick: () => send(vm.id, { type: t }) }, label);
  const where = d?.frames?.[d.level || 0];
  return [
    h("b.dbg-t", stepping ? "Running" : "Paused"),
    btn("resume", "Continue", "F5", stepping),
    btn("debugNext", "Step Over", "F10", stepping),
    btn("debugStep", "Step Into", "F11", stepping),
    btn("debugFinish", "Step Out", "Shift+F11", stepping),
    h("span.dbg-sep"),
    btn("debugNexti", "nexti", "Ctrl+F10", stepping),
    btn("debugStepi", "stepi", "Ctrl+F11", stepping),
    stepping ? h("button", { type: "button", onclick: () => send(vm.id, { type: "pause" }) }, "Pause") : null,
    d && !stepping ? h("code.dim", `cpu ${d.cpu}` + (d.pid != null ? `  pid ${d.pid} ${d.name}` : "") + (d.user ? "  user mode" : "") +
      (where ? `  ${where.fn || where.pc}` + (where.file ? `  ${where.file}:${where.line}` : "") : "")) : null,
    d?.returned != null && !stepping ? h("code.call", "returned ", h("span.num", d.returned)) : null,
  ].filter(Boolean);
}

function renderDebug(vm) {
  if (view !== vm.id) return;
  const d = vm.state === "PAUSED" ? vm.dbg : null;
  // Call Stack
  clear(E.callstack);
  for (const f of d?.frames || []) {
    E.callstack.append(h("div.dbg-row" + (f.level === (d.level || 0) ? ".sel" : ""), {
      onclick: () => send(vm.id, { type: "debugFrame", level: f.level }),
    }, h("span.fn", f.fn || f.pc), f.file ? h("span.dim", `${f.file}:${f.line}`) : null));
  }
  // Variables: 인자와 지역 변수. 같은 함수에서 한 줄 넘길 때 바뀐 값은 노랗게.
  const vars = d?.locals || [];
  paint(E.variables, key(vars), () => {
    clear(E.variables);
    for (const v of vars) {
      E.variables.append(h("div.var", h("span.var-n" + (v.arg ? ".arg" : ""), v.name), h("code.var-v", { "data-k": v.name }, v.value)));
    }
  }, d ? `${vm.id}:${d.frames?.[d.level || 0]?.fn}` : vm.id);
  // Breakpoints
  clear(E.breakpoints);
  for (const b of vm.bps) {
    E.breakpoints.append(h("div.dbg-row", { onclick: () => openSource(vm, b.file, b.line) },
      h("i.bp-dot"), h("span", `${b.file.split("/").pop()}:${b.line}`), h("span.dim", b.file),
      h("button.icon", { type: "button", title: "Remove", onclick: (e) => { e.stopPropagation(); send(vm.id, { type: "breakpoint", file: b.file, line: b.line, on: false }); } }, h("i.ico.x"))));
  }
}

// Disassembly: 고른 프레임의 함수. C 줄 머리 아래에 그 줄의 명령어, 지금 명령어는 노랗게.
// 머리의 줄 내용은 Source와 같은 캐시(fetchFile)에서 읽어 채운다.
function renderDisasm(vm) {
  const a = vm.state === "PAUSED" ? vm.dbg?.asm : null;
  E.disasmSub.textContent = a?.fn || "";
  const k = key(vm.id, a?.fn, a?.pc, a?.rows.length);
  const fresh = paint(E.disasm, k, () => {
    clear(E.disasm);
    if (!a) return;
    for (const r of a.rows) {
      if (r.asm == null) {
        const code = h("code");
        E.disasm.append(h("div.da-src", h("span.da-loc", `${r.file.split("/").pop()}:${r.line}`), code));
        fetchFile(vm.id, r.file).then((ls) => {
          if (E.disasm._key === k) code.append(lineNode(ls[r.line - 1] || ""));
        }, () => {});
        continue;
      }
      const [mn, ...rest] = r.asm.trim().split(/\s+/);
      E.disasm.append(h("div.da-i" + (r.addr === a.pc ? ".here" : ""),
        h("span.da-a", r.addr.replace(/^0x/, "")), h("span.da-o", `<+${r.off}>`),
        h("code", h("span.da-m", mn), " ", rest.join(" "))));
    }
  });
  const here = fresh && E.disasm.querySelector(".da-i.here");
  if (here) reveal(E.disasm, here, 40, 1 / 3);
}

document.addEventListener("keydown", (e) => {
  const t = e.target;
  const vm = cur();
  const dk = debugKey(e);
  if (dk && vm && (vm.state === "PAUSED" || vm.state === "STEPPING")) {
    e.preventDefault();
    if (vm.state === "PAUSED") send(vm.id, { type: dk });
    return;
  }
  if (t.closest?.(".term") || ["INPUT", "SELECT", "TEXTAREA"].includes(t.tagName)) return;
  if (e.ctrlKey || e.altKey || e.metaKey || !visible().some((v) => v.sel)) return;
  if (e.key === "ArrowRight" || e.key === "n" || e.key === "N") step(1);
  else if (e.key === "ArrowLeft") step(-1);
  else if (e.key === "ArrowDown") visible().forEach((v) => v.sel && row(v, 1));
  else if (e.key === "ArrowUp") visible().forEach((v) => v.sel && row(v, -1));
  else if (e.key === "Escape" && !dock.S.max) clearSel();
  else return;
  e.preventDefault();
});

// ---------------------------------------------------------------- Source

async function loadFiles(vm) {
  if (vm.files) return;
  vm.files = [];
  try {
    vm.files = await (await fetch(`/api/files?vm=${vm.id}`)).json();
  } catch { /* 목록이 없으면 고르기만 못 한다 */ }
  if (view === vm.id) renderSingle(vm);
}

function openSource(vm, file, line) {
  vm.srcPin = { file, line: line || null };
  renderSingle(vm);
  dock.activate("source");
}

function renderSourceActs(vm, file) {
  const files = vm.files || [];
  // 매번 새로 만들면 펼친 목록이 닫힌다: 파일 목록이나 지금 파일이 바뀔 때만
  const k = vm.id + ":" + files.length + ":" + file;
  if (E.srcActs._k === k) return;
  E.srcActs._k = k;
  clear(E.srcActs).append(h("select.src-pick", {
    title: "Open file",
    onchange: (e) => e.target.value && openSource(vm, e.target.value, null),
  }, h("option", { value: "" }, "Open…"), files.map((f) => h("option", { value: f, selected: f === file }, f))));
}

// 지금 보여 줄 소스 위치: 연 파일 > Debug에서 멈춘 곳 > Trace에서 고른 단계 > 고른 CPU
function sourceLocation(vm) {
  if (vm.srcPin) return { src: vm.srcPin, where: "" };
  const d = vm.state === "PAUSED" ? vm.dbg : null;
  const f = d?.frames?.[d.level || 0];
  if (f?.file) return { src: { file: f.file, line: f.line }, where: f.fn || "" };
  const stg = vm.sel?.detail?.stages[vm.sel.stage];
  if (stg) return { src: stg.src, where: stg.fn || "" };
  if (vm.sel) return { src: vm.sel.ev.src, where: vm.sel.ev.handler || "" };
  const c = vm.snap?.cpus.find((x) => x.cpu === selCpu(vm));
  return { src: c?.src || null, where: c ? `cpu ${c.cpu}` : "" };
}

// ---------------------------------------------------------------- 단일 화면

function selCpu(vm) {
  const s = vm.snap;
  if (!s) return null;
  if (vm.cpu != null && s.cpus.some((c) => c.cpu === vm.cpu)) return vm.cpu;
  return (s.cpus.find((c) => c.pid != null) || s.cpus[0]).cpu;
}

function renderCpus(vm) {
  const el = clear(E.cpus);
  const s = vm.snap;
  if (!s) return;
  const owner = Object.fromEntries(s.procs.map((p) => [p.pagetablePa, p.name]));
  el.append(h("table.t",
    h("thead", h("tr", ["cpu", "mode", "proc", "rip", "cr3", "IF"].map((t) => h("th", { "data-decl": t === "proc" ? "cpu.proc" : null }, t)))),
    h("tbody", s.cpus.map((c) => {
      const td = (col, v) => h("td", { "data-k": `${c.cpu}.${col}` }, v);
      return h("tr", {
        class: c.cpu === selCpu(vm) ? "sel" : null, "data-n": "cpu:" + c.cpu,
        onclick: () => {
          vm.cpu = c.cpu;
          vm.srcPin = null;
          if (vm.state === "PAUSED") send(vm.id, { type: "debugThread", cpu: c.cpu });
          renderSingle(vm);
        },
      },
        h("td", c.cpu), td("mode", c.mode), td("proc", c.pid != null ? `${c.pid} ${c.pname}` : ""),
        td("rip", c.fn || addr(c.rip, { short: true })),
        td("cr3", addr(c.cr3, { short: true }) + (owner[c.cr3] ? " " + owner[c.cr3] : "")),
        td("if", c.intr ? 1 : 0));
    }))));
}

function renderProcs(vm) {
  const el = clear(E.procs);
  const s = vm.snap;
  if (!s) return;
  el.append(h("table.t",
    h("thead", h("tr", [["pid", "pid"], ["name", "name"], ["state", "state"], ["ppid", "parent"], ["chan", "chan"], ["sz", "sz"]]
      .map(([t, f]) => h("th", { "data-decl": "proc." + f }, t)))),
    h("tbody", s.procs.map((p) => {
      const td = (col, v) => h("td", { "data-k": `${p.pid}.${col}` }, v);
      return h("tr", { class: p.pid === vm.focus ? "sel" : null, "data-n": "pid:" + p.pid, onclick: () => setFocus(vm, p.pid) },
        h("td", p.pid), td("name", p.name), td("state", h("span.state." + p.state, p.state)), td("ppid", p.parent ?? ""),
        td("chan", p.chan ? p.chan.label || addr(p.chan.addr, { short: true }) : ""), td("sz", "0x" + p.sz.toString(16)));
    }))));
}

function memData(vm) {
  const f = vm.snap?.focus;
  const as = f?.addrspace;
  if (!as) return null;
  const names = as.spaces.map((s) => s.name);
  const which = vm.mem.which === "kernel" && as.spaces.length > 1 ? 1 : 0;
  return { space: as.spaces[which], spaces: names, which: names[which], tree: f.ptree,
    va: vm.mem.va, walk: vm.mem.walk, phys: vm.mem.phys, focusPid: f.pid };
}

function renderMem(vm) {
  const el = E.memory;
  const d = memData(vm);
  const p = d && vm.snap.procs.find((x) => x.pid === d.focusPid);
  E.memSub.textContent = p ? `pid ${p.pid} ${p.name}` : "";
  if (!dock.visible("memory")) return;
  if (d && (!vm.mem.phys || (vm.mem.phys.error && Date.now() - vm.mem.phys.at > 3000))) loadPhys(vm, !!vm.mem.phys);
  paint(el, key(vm.id, d), () => renderMemory(el, d || {}, {
    space: (name) => { vm.mem.which = name === "kernel_pagetable" ? "kernel" : "user"; vm.mem.va = vm.mem.walk = null; renderSingle(vm); },
    pick: (va) => pickVa(vm, va),
    cell: (pa) => pickPa(vm, pa),
    loadPhys: () => loadPhys(vm, true),
  }), vm.id + ":mem:" + d?.focusPid);
}

// 스냅샷은 1초마다 온다. 값이 바뀐 패널만 다시 그리고, 다시 그릴 때는 스크롤 위치를 지킨다.
// 패널 안의 입력칸에 쓰는 중이면 건드리지 않는다.
// 다시 그린 뒤에는 같은 scope의 직전 그림과 비교해 바뀐 값을 노랗게 칠한다.
// 다시 그렸으면 true
function paint(el, key, fn, scope) {
  if (el._key === key) return false;
  if (el.contains(document.activeElement) && ["INPUT", "SELECT"].includes(document.activeElement.tagName)) return false;
  el._key = key;
  const top = el.scrollTop, left = el.scrollLeft;
  fn();
  if (scope !== undefined) markChanges(el, scope);
  el.scrollTop = top;
  el.scrollLeft = left;
  return true;
}
const key = (...xs) => JSON.stringify(xs);

// 연결 그림은 스택 영역의 위치와 trapframe 필드만 쓴다 (나머지 칸이 바뀌어도 다시 그리지 않게)
const graphFrames = (frs) => (frs || []).map((f) => ({ ...f, slots: f.slots.filter((x) => x.field) }));

// 연결 그림에 그릴 것. Trace에서 줄을 골랐으면 그 시스템 콜 직후 상태와 고른 단계의 스택.
function graphData(vm) {
  const d = vm.sel?.detail;
  if (d?.after) {
    return { cpus: [], p: d.after.proc, frames: graphFrames(d.stages[vm.sel.stage]?.frames), files: d.after.files, pt: d.after.pt };
  }
  const s = vm.snap, f = s?.focus;
  const p = f && s.procs.find((x) => x.pid === f.pid);
  if (!p) return null;
  const sp = f.addrspace.spaces[0];
  return { cpus: s.cpus.filter((c) => c.pid === p.pid), p, frames: graphFrames(f.frames), files: f.files,
    pt: { root: sp.root, pages: sp.pages.length, pml4: f.ptree.entries.map((e) => e.idx) } };
}

// Stack 위 레지스터 열. Trace 단계: 그 단계와 다음 단계. 실시간: 프로세스가 도는 CPU, 아니면 p->context.
function regCols(vm, sel) {
  const col = (head, r, fn) => ({ head, rip: r.rip, rsp: r.rsp, rbp: r.rbp || null, fn });
  if (sel) {
    const st = sel.detail.stages, k = sel.stage;
    if (!st[k]) return [];
    const out = [col(st[k].step, st[k].regs, st[k].user ? "user" : st[k].fn)];
    if (st[k + 1]) out.push(col("→ " + st[k + 1].step, st[k + 1].regs, st[k + 1].user ? "user" : st[k + 1].fn));
    else if (st[k].step === "iretq") {
      // iretq가 스택에서 꺼내는 rip, cs, rflags, rsp, ss
      const pop = (off) => {
        const v = BigInt(st[k].regs.rsp) + BigInt(off);
        for (const fr of st[k].frames) {
          const a = slotAddr(fr, v);
          const sl = a !== null && fr.slots.find((x) => BigInt(x.addr) === a);
          if (sl) return sl.value;
        }
        return null;
      };
      const rip = pop(0), rsp = pop(24);
      if (rip) out.push(col("→ user", { rip, rsp, rbp: st[k].regs.rbp }, "user"));
    }
    return out;
  }
  const s = vm.snap, pid = s?.focus?.pid;
  if (pid == null) return [];
  const c = s.cpus.find((x) => x.pid === pid);
  if (c) return [col(`cpus[${c.cpu}]`, c, c.mode === "user" ? "user" : c.fn)];
  const p = s.procs.find((x) => x.pid === pid);
  return p ? [col("p->context", p.context, p.context.ripSym)] : [];
}

function stackBody(fr, rc, dbg) {
  const body = h("div");
  if (rc.length) body.append(regsView(rc, fr));
  body.append(framesView(fr, { rbp: rc[0]?.rbp, dbg }));
  return body;
}

function renderSingle(vm) {
  if (view !== vm.id) return;
  renderBar();
  const s = vm.snap;
  const sel = vm.sel?.detail ? vm.sel : null;
  const stg = sel?.detail.stages[sel.stage];
  const rscope = sel && `${vm.id}:rec:${sel.ev.pid}`;
  paint(E.cpus, key(vm.id, s?.cpus, s?.procs.map((x) => [x.pagetablePa, x.name]), selCpu(vm)), () => renderCpus(vm), vm.id);
  paint(E.procs, key(vm.id, s?.procs, vm.focus), () => renderProcs(vm), vm.id);
  const gd = graphData(vm);
  if (dock.visible("graph")) {
    paint(E.graph, key(vm.id, gd), () => renderGraph(E.graph, gd, (what) => {
      if (what === "frames") dock.activate("stack");
      else if (what === "ptree") dock.activate("memory");
    }), rscope && sel.detail.after ? rscope : vm.id + ":" + gd?.p.pid);
  }
  const tag = sel ? `#${sel.ev.i} ${sel.ev.sys}  ` : "";
  E.graphSub.textContent = gd ? `${tag}pid ${gd.p.pid} ${gd.p.name}` : "";
  const fr = sel ? stg?.frames : s?.focus?.frames;
  const rc = regCols(vm, sel);
  // Debug로 멈췄고 그 CPU의 프로세스를 보고 있으면 프레임 영역과 변수 이름을 스택에 그린다
  const dg = !sel && vm.state === "PAUSED" && vm.dbg && vm.dbg.pid === s?.focus?.pid && !vm.dbg.user
    ? { frames: vm.dbg.frames, level: vm.dbg.level } : null;
  const fresh = paint(E.stack, key(vm.id, fr, rc, dg), () => {
    const fe = clear(E.stack);
    if (fr) fe.append(stackBody(fr, rc, dg));
  }, rscope || vm.id + ":" + s?.focus?.pid);
  // Step 뒤에는 rsp 줄이 보이도록 (스택은 아래로 자라므로 패널 아래쪽에 둔다)
  const rp = fresh && dg && E.stack.querySelector(".stk .ptr.rsp");
  if (rp) reveal(E.stack, rp, 24, 0.85);
  E.stackSub.textContent = sel ? `${tag}${stg?.step || ""}` : s?.focus ? `pid ${s.focus.pid}` : "";
  renderMem(vm);
  const { src, where } = sourceLocation(vm);
  E.srcSub.textContent = src ? `${where}  ${src.file}:${src.line || ""}` : where;
  renderSourceActs(vm, src?.file);
  showSource(E.source, vm.id, src, {
    bps: vm.bps.filter((b) => b.file === src?.file).map((b) => b.line),
    here: !vm.srcPin,
    onLine: (line) => src?.file && send(vm.id, { type: "breakpoint", file: src.file, line, on: !vm.bps.some((b) => b.file === src.file && b.line === line) }),
  });
  renderDebug(vm);
  renderDisasm(vm);
}

// ---------------------------------------------------------------- 비교 화면
//
// 두 커널에서 같은 명령을 Trace로 남기고, 한쪽에서 줄을 고르면 다른 쪽도 같은 시스템 콜을 고른다.
// 단계는 같은 번호로 함께 넘어간다.

function renderCompare() {
  if (view !== "compare") return;
  const root = E.compare;
  if (!root.dataset.built) {
    clear(root);
    for (const id of kernels) {
      root.append(h("section.ccol", { id: "c-" + id },
        h("h2", LABEL[id] || id, h("span.sub", { id: "cs-" + id })),
        h("div.c-rail", { id: "cr-" + id }),
        h("div.c-body",
          h("div.pane", h("h2", "Trace"), h("div.body.rec-list", { id: "cl-" + id })),
          h("div.pane", h("h2", "Stack"), h("div.body", { id: "cf-" + id })))));
    }
    root.dataset.built = "1";
  }
  for (const id of kernels) {
    const vm = vmOf(id);
    const sel = vm.sel?.detail ? vm.sel : null;
    const stg = sel?.detail.stages[sel.stage];
    clear($("cs-" + id)).append(...(sel ? ["  ", ...callNodes(sel.ev), stg ? `   cr3 ${stg.pagetable}` : ""] : []));
    const r = clear($("cr-" + id));
    const rl = rail(vm);
    if (rl) r.append(rl);
    // 스냅샷마다 오므로 Trace 목록은 기록이나 선택이 바뀔 때만 다시 만든다
    const list = $("cl-" + id), lk = key(vm.rec.events.length, vm.rec.pending, vm.sel?.idx, vm.sel?.ev?.i);
    if (list._key !== lk) {
      list._key = lk;
      renderRecordList(vm, list);
    }
    const fr = stg?.frames;
    const rc = regCols(vm, sel);
    paint($("cf-" + id), key(fr, rc), () => {
      const fe = clear($("cf-" + id));
      if (fr) fe.append(stackBody(fr, rc));
    }, sel ? `${id}:rec:${sel.ev.pid}` : id);
  }
  renderBar();
}

// ---------------------------------------------------------------- 동작

// Shell 뷰: 비교 화면에서는 두 커널의 셸을 나란히
function mountShell() {
  const body = clear(E.shell);
  body.classList.toggle("cols", view === "compare");
  for (const vm of visible()) {
    const slot = h("div.term-host");
    body.append(view === "compare" ? h("div.pterm", h("div.pterm-h", LABEL[vm.id] || vm.id), slot) : slot);
    mountTerm(vm, slot);
  }
}

function setView(k) {
  view = k;
  dock.setCompare(k === "compare" ? E.compare : null);
  renderKernels();
  mountShell();
  if (k !== "compare") {
    const vm = vmOf(k);
    loadFiles(vm);
    renderRecord(vm);
    renderSingle(vm);
  } else {
    renderCompare();
  }
  renderTrace();
}

function refresh() {
  renderTrace();
  for (const v of visible()) {
    renderRecord(v);
    rerender(v);
  }
}

initDecl((el) => el.closest(".ccol")?.id.slice(2) || view);
$("chk-follow").checked = dock.S.follow;
$("chk-follow").onchange = (e) => { dock.S.follow = e.target.checked; dock.saveSettings(); };

dock.init({
  changed: () => {
    const vm = cur();
    if (vm) renderSingle(vm);
  },
  shown: (which, on) => { if (which === "bottom" && on) visible()[0]?.term.focus(); },
});

function setFocus(vm, pid) {
  if (vm.focus === pid) return;
  vm.focus = pid;
  vm.mem.va = vm.mem.walk = null;
  send(vm.id, { type: "focus", pid });
}

async function pickVa(vm, va) {
  vm.mem.va = va;
  vm.mem.walk = null;
  if (va) {
    try {
      const r = await fetch(`/api/walk?vm=${vm.id}&pid=${vm.focus}&va=${encodeURIComponent(va)}&root=${vm.mem.which}`);
      const w = await r.json();
      if (vm.mem.va !== va) return; // 기다리는 사이 다른 VA나 프로세스를 골랐다
      vm.mem.walk = w;
    } catch (e) {
      if (vm.mem.va !== va) return;
      vm.mem.walk = { error: e.message };
    }
  }
  renderSingle(vm);
}

// 물리 페이지를 누르면: 이 프로세스의 페이지면 그 VA를 고른다
function pickPa(vm, pa) {
  const sp = vm.snap?.focus?.addrspace?.spaces[0];
  const pg = sp?.pages.find((p) => BigInt(p.pa) === BigInt(pa));
  if (pg) {
    vm.mem.which = "user";
    pickVa(vm, pg.va);
  }
}

async function loadPhys(vm, force = false) {
  if (vm.mem.loading || (vm.mem.phys && !force)) return;
  vm.mem.loading = true;
  try {
    const r = await fetch(`/api/physmem?vm=${vm.id}`);
    vm.mem.phys = await r.json();
  } catch (e) {
    vm.mem.phys = { error: e.message, at: Date.now() };
  }
  if (vm.mem.phys?.error && !vm.mem.phys.at) vm.mem.phys.at = Date.now();
  vm.mem.loading = false;
  renderSingle(vm);
}

// 새 프로세스가 생기면 그 프로세스로(가장 큰 pid), 고른 프로세스가 끝나면 부모로 넘어간다.
function follow(vm, prev, curSnap) {
  const alive = new Set(curSnap.procs.map((p) => p.pid));
  if (dock.S.follow && prev && !vm.sel && vm.state === "RUNNING") {
    const before = new Set(prev.procs.map((p) => p.pid));
    const born = curSnap.procs.filter((p) => !before.has(p.pid));
    if (born.length) {
      setFocus(vm, Math.max(...born.map((p) => p.pid)));
      return;
    }
  }
  if (vm.focus != null && alive.has(vm.focus)) return;
  const was = prev?.procs.find((p) => p.pid === vm.focus);
  const pick = (was && curSnap.procs.find((p) => p.pid === was.parent)) || curSnap.procs.find((p) => p.name === "sh") || curSnap.procs[0];
  if (pick) {
    vm.focus = null;
    setFocus(vm, pick.pid);
  }
}

function rerender(vm) {
  if (view === vm.id) renderSingle(vm);
  if (view === "compare") renderCompare();
}

function onMessage(m) {
  if (m.type === "kernels") {
    kernels = m.data;
    kernels.forEach(vmOf);
    if (!view) setView(kernels.includes("x86") ? "x86" : kernels[0]);
    return;
  }
  const vm = vmOf(m.vm);
  switch (m.type) {
    case "console":
      if (m.reset || m.replay) vm.term.reset();
      if (m.data) vm.term.write(m.data);
      break;
    case "status": {
      const was = vm.state;
      vm.state = m.state;
      vm.autoRefresh = m.autoRefresh;
      if (m.failure) showError(`${LABEL[vm.id]}: ${m.failure}`);
      if (m.state === "PAUSED" && was !== "PAUSED") vm.sel = null;
      renderBar();
      renderTrace();
      rerender(vm);
      break;
    }
    case "snapshot": {
      const prev = vm.snap;
      vm.snap = m.data;
      follow(vm, prev, m.data);
      rerender(vm);
      break;
    }
    case "structs":
      setStructs(vm.id, m.data);
      break;
    case "record": {
      const d = m.data;
      if (d.reset) {
        vm.rec.events = [];
        vm.details.clear();
        vm.sel = null;
      }
      vm.rec.events.push(...d.events);
      vm.rec.rows = mergeEvents(vm.rec.events);
      Object.assign(vm.rec, { active: d.active, pending: d.pending });
      if (vm.sel) vm.sel.ev = vm.rec.rows[vm.sel.idx] || vm.sel.ev;
      renderTrace();
      renderRecord(vm);
      if (view === "compare") renderCompare();
      break;
    }
    case "debug":
      vm.dbg = m.data;
      vm.srcPin = null;
      if (m.data.pid != null && m.data.pid !== vm.focus) setFocus(vm, m.data.pid);
      if (m.data.cpu != null) vm.cpu = m.data.cpu;
      renderTrace();
      rerender(vm);
      break;
    case "breakpoints":
      vm.bps = m.data;
      rerender(vm);
      break;
    case "error":
      showError(`${LABEL[vm.id] || vm.id}: ${m.message}`);
      break;
  }
}

function connect() {
  ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
  ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  ws.onopen = () => Object.values(vms).forEach((v) => v.focus != null && send(v.id, { type: "focus", pid: v.focus }));
  ws.onclose = () => {
    $("status").textContent = "Disconnected";
    setTimeout(connect, 1500);
  };
}
connect();
