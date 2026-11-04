import { Terminal } from "./vendor/xterm.mjs";
import { FitAddon } from "./vendor/addon-fit.mjs";
import { h, clear, addr, big } from "./js/dom.js";
import { framesView } from "./js/stack.js";
import { renderGraph } from "./js/graph.js";
import { renderAspace, renderPtree, renderPhys } from "./js/mem.js";
import { showSource } from "./js/source.js";

const LABEL = { riscv: "riscv-style", x86: "x86-style" };
const STATE = { STARTING: "부팅 중", RUNNING: "실행 중", PAUSED: "멈춤", JOURNEY_WAIT: "추적 대기", JOURNEY_STOP: "추적 멈춤", STOPPED: "꺼짐", FAILED: "실패" };
const MEM_TABS = [["aspace", "주소 공간"], ["ptree", "페이지 테이블"], ["phys", "물리 메모리"]];

const $ = (id) => document.getElementById(id);
const vms = {};
let kernels = [];
let view = null;
let ws = null;
const form = { name: "", syscall: "" };

function send(vm, msg) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ ...msg, vm }));
}

function vmOf(id) {
  if (!vms[id]) {
    const host = h("div.term");
    const term = new Terminal({
      fontFamily: '"D2CodingLigature Nerd Font Mono", "D2Coding", monospace', fontSize: 13,
      cursorBlink: true, convertEol: true, scrollback: 2000,
      theme: { background: "#111111", foreground: "#e6e6e6", cursor: "#e6e6e6" },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.onData((d) => send(id, { type: "input", data: d }));
    vms[id] = { id, state: "STARTING", snap: null, journey: { active: false }, syscalls: [], focus: null, cpu: null,
      mem: "aspace", va: null, walk: null, phys: null, trail: {}, term, fit, host, opened: false, autoRefresh: true };
    new ResizeObserver(() => vms[id].opened && fit.fit()).observe(host);
  }
  return vms[id];
}

function mountTerm(vm, slot, only = false) {
  if (only) for (const c of [...slot.children]) if (c !== vm.host) c.remove();
  if (vm.host.parentElement !== slot) slot.append(vm.host);
  if (!vm.opened) {
    vm.term.open(vm.host);
    vm.opened = true;
  }
  requestAnimationFrame(() => vm.fit.fit());
}

const visible = () => (view === "compare" ? kernels.map(vmOf) : view ? [vmOf(view)] : []);

// ---------------------------------------------------------------- 상단

function renderViews() {
  const nav = clear($("views"));
  for (const k of [...kernels, "compare"]) {
    nav.append(h("button", { type: "button", class: k === view ? "on" : null, onclick: () => setView(k) }, k === "compare" ? "비교" : LABEL[k] || k));
  }
}

function renderBar() {
  const vs = visible();
  const st = clear($("status"));
  for (const vm of vs) {
    st.append(h("span.st", { "data-s": vm.state }, (view === "compare" ? LABEL[vm.id] + " " : "") + (STATE[vm.state] || vm.state)),
      vm.snap ? h("code.dim", ` ticks ${vm.snap.ticks}  `) : null);
  }
  const allPaused = vs.length && vs.every((v) => v.state === "PAUSED");
  $("btn-pause").textContent = allPaused ? "계속" : "멈추기";
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

// ---------------------------------------------------------------- 트랩 추적

function callText(j) {
  const sc = j.syscall || {};
  const args = (sc.args || []).map((a) => "0x" + big(a).toString(16)).join(", ");
  return `${sc.name || "#" + sc.num}(${args})` + (j.retval != null ? ` = ${j.retval}` : "");
}

function rail(vm) {
  const j = vm.journey;
  if (!j.active) return null;
  const stopped = j.phase === "stopped";
  const cur = stopped ? j.step : j.expect;
  const ci = j.steps.indexOf(cur);
  return h("ol.rail", j.steps.map((s, i) => {
    const st = i < ci ? "done" : i === ci ? (stopped ? "here" : "next") : "";
    const label = s === "handler" ? j.syscall?.handler || "sys_*" : s;
    const pt = vm.trail[s];
    return h("li", { class: st || null }, h("span", label), pt ? h("small", pt) : null);
  }));
}

function renderTrace() {
  const fid = document.activeElement?.id;
  renderTraceInner();
  if (["t-name", "t-sys", "t-cmd"].includes(fid)) $(fid)?.focus();
}

function renderTraceInner() {
  const el = clear($("trace"));
  const vs = visible();
  if (!vs.length) return;
  const anyActive = vs.some((v) => v.journey.active);
  el.append(h("b", "트랩 추적"));
  if (!anyActive) {
    const sys = vs[0].syscalls;
    el.append(h("form.tform", {
      onsubmit: (e) => {
        e.preventDefault();
        vs.forEach((v) => { v.trail = {}; send(v.id, { type: "journeyStart", name: form.name, syscall: form.syscall }); });
      },
    },
      h("label", "p->name"), h("input", { id: "t-name", value: form.name, placeholder: "*", maxlength: 16, spellcheck: "false", oninput: (e) => (form.name = e.target.value.trim()) }),
      h("label", "syscall"), h("select", { id: "t-sys", onchange: (e) => (form.syscall = e.target.value) },
        h("option", { value: "" }, "*"), sys.map((s) => h("option", { value: s.name, selected: s.name === form.syscall }, `${s.num} ${s.name}`))),
      h("button.primary", { type: "submit" }, "시작")));
    if (view === "compare") el.append(cmdForm(vs));
    return;
  }
  if (view === "compare") el.append(cmdForm(vs));
  if (view !== "compare") {
    const vm = vs[0];
    el.append(rail(vm));
    if (vm.journey.pid != null) el.append(h("code.call", callText(vm.journey)), h("code.dim", `pid ${vm.journey.pid} ${vm.journey.name}`));
  }
  const canNext = vs.some((v) => v.journey.active && v.journey.phase === "stopped");
  el.append(
    h("button.primary", { type: "button", disabled: !canNext, onclick: next, title: "N" }, "다음"),
    h("button", { type: "button", onclick: () => vs.forEach((v) => v.journey.active && send(v.id, { type: "journeyCancel" })) }, "중지"));
}

function cmdForm(vs) {
  return h("form.tform", {
    onsubmit: (e) => {
      e.preventDefault();
      const c = e.target.elements.cmd.value;
      vs.forEach((v) => send(v.id, { type: "input", data: c + "\r" }));
      e.target.elements.cmd.value = "";
    },
  }, h("label", "양쪽 셸에 입력"), h("input.cmd", { id: "t-cmd", name: "cmd", spellcheck: "false", autocomplete: "off" }), h("button", { type: "submit" }, "보내기"));
}

function next() {
  visible().forEach((v) => v.journey.active && v.journey.phase === "stopped" && send(v.id, { type: "journeyNext" }));
}

document.addEventListener("keydown", (e) => {
  if (e.key !== "n" && e.key !== "N") return;
  const t = e.target;
  if (t.closest?.(".term") || ["INPUT", "SELECT", "TEXTAREA"].includes(t.tagName)) return;
  next();
});

// ---------------------------------------------------------------- 단일 화면

function selCpu(vm) {
  const s = vm.snap;
  if (!s) return null;
  if (vm.cpu != null && s.cpus.some((c) => c.cpu === vm.cpu)) return vm.cpu;
  return (s.cpus.find((c) => c.pid != null) || s.cpus[0]).cpu;
}

function renderCpus(vm) {
  const el = clear($("cpus"));
  const s = vm.snap;
  if (!s) return;
  const owner = Object.fromEntries(s.procs.map((p) => [p.pagetablePa, p.name]));
  el.append(h("table.t",
    h("thead", h("tr", ["cpu", "mode", "proc", "rip", "cr3", "IF"].map((t) => h("th", t)))),
    h("tbody", s.cpus.map((c) => h("tr", {
      class: c.cpu === selCpu(vm) ? "sel" : null,
      onclick: () => { vm.cpu = c.cpu; renderSingle(vm); },
    },
      h("td", c.cpu), h("td", c.mode), h("td", c.pid != null ? `${c.pid} ${c.pname}` : ""),
      h("td", c.fn || addr(c.rip, { short: true })),
      h("td", addr(c.cr3, { short: true }) + (owner[c.cr3] ? " " + owner[c.cr3] : "")),
      h("td", c.intr ? 1 : 0))))));
}

function renderProcs(vm) {
  const el = clear($("procs"));
  const s = vm.snap;
  if (!s) return;
  el.append(h("table.t",
    h("thead", h("tr", ["pid", "name", "state", "ppid", "chan", "sz"].map((t) => h("th", t)))),
    h("tbody", s.procs.map((p) => h("tr", { class: p.pid === vm.focus ? "sel" : null, onclick: () => setFocus(vm, p.pid) },
      h("td", p.pid), h("td", p.name), h("td", h("span.state." + p.state, p.state)), h("td", p.parent ?? ""),
      h("td", p.chan ? p.chan.label || addr(p.chan.addr, { short: true }) : ""), h("td", "0x" + p.sz.toString(16)))))));
}

function framesOf(vm) {
  const j = vm.journey;
  if (j.active && j.phase === "stopped") return j.frames;
  return vm.snap?.focus?.frames || null;
}

function renderMemTabs(vm) {
  const el = clear($("mem-tabs"));
  for (const [k, label] of MEM_TABS) {
    el.append(h("button", { type: "button", class: vm.mem === k ? "on" : null, onclick: () => { vm.mem = k; if (k === "phys" && !vm.phys) loadPhys(vm); renderSingle(vm); } }, label));
  }
}

function renderMem(vm) {
  const el = $("mem");
  const f = vm.snap?.focus;
  $("mem-sub").textContent = f ? `pid ${f.pid}` : "";
  if (vm.mem === "aspace") renderAspace(el, f?.addrspace, vm.va, (va) => pickVa(vm, va));
  else if (vm.mem === "ptree") renderPtree(el, f?.ptree, vm.va, vm.walk, (va) => pickVa(vm, va));
  else renderPhys(el, vm.phys, vm.focus, () => loadPhys(vm));
}

function renderSingle(vm) {
  if (view !== vm.id) return;
  renderBar();
  renderCpus(vm);
  renderProcs(vm);
  renderGraph($("graph"), vm, (what) => { if (what === "ptree") { vm.mem = "ptree"; renderSingle(vm); } });
  const p = vm.snap?.procs.find((x) => x.pid === vm.focus);
  $("graph-sub").textContent = p ? `pid ${p.pid} ${p.name}` : "";
  const fr = framesOf(vm);
  const fe = clear($("frames"));
  if (fr) fe.append(framesView(fr));
  $("frames-sub").textContent = vm.journey.phase === "stopped" ? `pid ${vm.journey.pid} ${vm.journey.step}` : p ? `pid ${p.pid}` : "";
  renderMemTabs(vm);
  renderMem(vm);
  let src = null, where = "";
  if (vm.journey.active && vm.journey.phase === "stopped") {
    src = vm.journey.src;
    where = vm.journey.fn || "";
  } else if (vm.snap) {
    const c = vm.snap.cpus.find((x) => x.cpu === selCpu(vm));
    src = c?.src;
    where = c ? `cpu ${c.cpu}` : "";
  }
  $("src-where").textContent = src ? `${where}  ${src.file}:${src.line}` : where;
  showSource($("source"), vm.id, src);
}

// ---------------------------------------------------------------- 비교 화면

function renderCompare() {
  if (view !== "compare") return;
  const root = $("compare");
  if (!root.dataset.built) {
    clear(root);
    for (const id of kernels) {
      root.append(h("section.ccol", { id: "c-" + id },
        h("h2", LABEL[id] || id, h("span.sub", { id: "cs-" + id })),
        h("div.c-term", { id: "ct-" + id }),
        h("div.c-rail", { id: "cr-" + id }),
        h("div.c-body",
          h("div.pane", h("h2", "스택"), h("div.body", { id: "cf-" + id })),
          h("div.pane", h("h2", "주소 공간"), h("div.body", { id: "ca-" + id })))));
    }
    root.dataset.built = "1";
  }
  for (const id of kernels) {
    const vm = vmOf(id);
    mountTerm(vm, $("ct-" + id));
    const j = vm.journey;
    $("cs-" + id).textContent = j.phase === "stopped" ? `  ${callText(j)}  cr3 ${j.pagetable}` : "";
    const r = clear($("cr-" + id));
    const rl = rail(vm);
    if (rl) r.append(rl);
    const fe = clear($("cf-" + id));
    const fr = framesOf(vm);
    if (fr) fe.append(framesView(fr));
    // 비교 대상은 프로세스의 페이지 테이블. riscv-style의 kernel_pagetable은 단일 화면에서 본다.
    const as = vm.snap?.focus?.addrspace;
    renderAspace($("ca-" + id), as ? { ...as, spaces: as.spaces.slice(0, 1) } : null, null, () => {});
  }
  renderBar();
}

// ---------------------------------------------------------------- 동작

function setView(k) {
  view = k;
  $("single").hidden = k === "compare";
  $("compare").hidden = k !== "compare";
  renderViews();
  if (k !== "compare") {
    const vm = vmOf(k);
    mountTerm(vm, $("term-slot"), true);
    renderSingle(vm);
  } else {
    renderCompare();
  }
  renderTrace();
}

function setFocus(vm, pid) {
  if (vm.focus === pid) return;
  vm.focus = pid;
  vm.va = null;
  vm.walk = null;
  send(vm.id, { type: "focus", pid });
}

async function pickVa(vm, va) {
  vm.va = va;
  vm.mem = "ptree";
  try {
    const r = await fetch(`/api/walk?vm=${vm.id}&pid=${vm.focus}&va=${encodeURIComponent(va)}`);
    vm.walk = await r.json();
  } catch (e) {
    vm.walk = { error: e.message };
  }
  renderSingle(vm);
}

async function loadPhys(vm) {
  try {
    const r = await fetch(`/api/physmem?vm=${vm.id}`);
    vm.phys = await r.json();
  } catch (e) {
    vm.phys = { error: e.message };
  }
  renderSingle(vm);
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
    case "status":
      vm.state = m.state;
      vm.autoRefresh = m.autoRefresh;
      if (m.failure) showError(`${LABEL[vm.id]}: ${m.failure}`);
      renderBar();
      renderTrace();
      break;
    case "snapshot":
      vm.snap = m.data;
      if (vm.focus == null || !m.data.procs.some((p) => p.pid === vm.focus)) {
        const pick = m.data.procs.find((p) => p.name === "sh") || m.data.procs[0];
        if (pick) { vm.focus = null; setFocus(vm, pick.pid); }
      }
      rerender(vm);
      break;
    case "journey":
      vm.journey = m.data;
      if (m.data.phase === "stopped") {
        vm.trail[m.data.step] = m.data.pagetable;
        if (m.data.pid !== vm.focus) setFocus(vm, m.data.pid);
      }
      if (!m.data.active) vm.trail = {};
      rerender(vm);
      renderTrace();
      break;
    case "syscalls":
      vm.syscalls = m.data;
      renderTrace();
      break;
    case "error":
      showError(`${LABEL[vm.id] || vm.id}: ${m.message}`);
      break;
  }
}

function connect() {
  ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  ws.onopen = () => Object.values(vms).forEach((v) => v.focus != null && send(v.id, { type: "focus", pid: v.focus }));
  ws.onclose = () => {
    $("status").textContent = "서버 연결 끊김";
    setTimeout(connect, 1500);
  };
}
connect();
