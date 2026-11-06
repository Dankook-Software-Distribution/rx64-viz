// 연결 그림: cpu -> proc -> kstack / trapframe / pagetable / file -> inode, pipe -> buf
// 포인터 필드는 실제 값으로 이어 그린다.
import { h, clear, addr, big } from "./dom.js";

const SVG = "http://www.w3.org/2000/svg";

// 노드 key 앞부분 -> 구조체 이름 (필드 위에 마우스를 올리면 선언을 보여 준다)
const STRUCT = { cpu: "cpu", proc: "proc", tf: "trapframe", file: "file", inode: "inode", pipe: "pipe", buf: "buf" };

function node(key, title, at, rows, onClick) {
  const st = STRUCT[key.match(/^[a-z]+/)[0]];
  rows = rows.filter(Boolean);
  for (const r of rows) {
    const name = r.firstChild.textContent;
    r.dataset.k = key + "/" + name; // 값이 바뀌면 노랗게
    if (st) r.firstChild.dataset.decl = st + "." + name.replace(/\[.*$/, "").split(".")[0];
  }
  return h("div.node", { "data-node": key, "data-n": key, onclick: onClick || null, class: onClick ? "click" : null },
    h("div.node-h", h("b", title), at ? h("code", at) : null),
    rows);
}

// [이름, 값, 가리키는 노드 key]
function row(name, value, to) {
  return h("div.row" + (to ? ".ptr" : ""), { "data-to": to || null },
    h("span.k", name), h("code.v", value ?? ""));
}

const short = (x) => (x ? addr(x, { short: true }) : "0");

// d: { cpus, p, frames, files, pt: { root, pages, pml4 } }. 실시간 스냅샷이나 기록한 시스템 콜 직후 상태.
export function renderGraph(el, d, onPick) {
  clear(el);
  if (!d?.p) return;
  const p = d.p;
  const fr = Object.fromEntries((d.frames || []).map((x) => [x.name, x]));
  const files = d.files;
  // 묶음: cpu, proc, 메모리(kstack, trapframe, pagetable), 파일, 파일 시스템(inode, pipe), 버퍼
  const G = { cpu: [], proc: [], mem: [], files: [], fs: [], bufs: [] };

  for (const c of d.cpus || []) {
    const pt = c.cr3 === p.pagetablePa ? "pt" : null;
    G.cpu.push(node("cpu" + c.cpu, `cpus[${c.cpu}]`, short(c.addr), [
      row("proc", short(c.proc), "proc"),
      row("mode", c.mode),
      row("rip", c.fn || short(c.rip)),
      row("cr3", short(c.cr3) + (pt ? "" : " kernel"), pt),
    ]));
  }

  const ofileRows = [];
  p.ofile.forEach((fp, fd) => fp && ofileRows.push(row(`ofile[${fd}]`, short(fp), "file:" + fp)));
  G.proc.push(node("proc", `proc[${p.slot}]`, short(p.addr), [
    row("pid", p.pid), row("name", p.name), row("state", p.state),
    row("parent", p.parent ?? "0"),
    row("chan", p.chan ? (p.chan.label || short(p.chan.addr)) : "0"),
    row("kstack", short(p.kstack), "kstack"),
    row("trapframe", short(p.trapframe), "tf"),
    row("pagetable", short(p.pagetable), "pt"),
    ...ofileRows,
    row("cwd", short(p.cwd), p.cwd ? "inode:" + p.cwd : null),
    row("context.rip", p.context.ripSym || short(p.context.rip)),
  ]));

  const ks = fr.kstack;
  const used = ks && ks.rsp ? Number(big(ks.top) - big(ks.rsp)) : 0;
  G.mem.push(node("kstack", "kstack", short(ks?.base), [
    row("top", short(ks?.top)), row("rsp", short(ks?.rsp)), row("used", `${used} B`),
  ], () => onPick("frames")));

  const tfr = fr.trapframe || ks;
  const tfVal = (name) => tfr?.slots.find((x) => x.field === name)?.value;
  const inKstack = !fr.trapframe;
  G.mem.push(node("tf", "struct trapframe", short(p.trapframe), [
    inKstack ? (ks ? row("at", "kstack+" + (big(p.trapframe) - big(ks.base)).toString(16)) : null) : row("uva", fr.trapframe.uva),
    row("rip", short(tfVal("rip"))), row("rsp", short(tfVal("rsp"))),
    row("rax", short(tfVal("rax"))), row(tfr?.slots.some((x) => x.field === "vector") ? "vector" : "trapno", short(tfVal("vector") || tfVal("trapno"))),
  ], () => onPick("frames")));

  G.mem.push(node("pt", "pagetable", short(d.pt?.root || p.pagetablePa), [
    d.pt ? row("user pages", d.pt.pages) : null,
    d.pt ? row("PML4", d.pt.pml4.join(" ")) : null,
  ], () => onPick("ptree")));

  for (const fl of files.files) {
    const fds = files.fds.filter((d) => d.file === fl.addr).map((d) => d.fd).join(",");
    G.files.push(node("file:" + fl.addr, "struct file", short(fl.addr), [
      row("fd", fds), row("type", fl.type), row("ref", fl.ref),
      row("rw", `${fl.readable}${fl.writable}`), row("off", fl.off),
      fl.ip ? row("ip", short(fl.ip), "inode:" + fl.ip) : null,
      fl.pipe ? row("pipe", short(fl.pipe), "pipe:" + fl.pipe) : null,
      fl.devsw ? row("devsw", fl.devsw) : null,
    ]));
  }

  for (const ino of files.inodes) {
    G.fs.push(node("inode:" + ino.addr, "struct inode", short(ino.addr), [
      row("inum", ino.inum), row("type", ino.type), row("size", ino.size),
      row("nlink", ino.nlink), row("ref", ino.ref),
      row("addrs", ino.addrs.slice(0, 6).join(" ") + (ino.addrs.length > 6 ? " …" : "")),
      ...ino.cached.map((b) => row(`block ${b.blockno}`, `buf[${b.buf}]`, "buf:" + b.buf)),
    ]));
    for (const b of ino.cached) {
      if (G.bufs.some((n) => n.dataset.node === "buf:" + b.buf)) continue;
      G.bufs.push(node("buf:" + b.buf, `bcache.buf[${b.buf}]`, short(b.addr), [
        row("blockno", b.blockno), row("refcnt", b.refcnt),
      ]));
    }
  }
  for (const pi of files.pipes) {
    G.fs.push(node("pipe:" + pi.addr, "struct pipe", short(pi.addr), [
      row("nread", pi.nread), row("nwrite", pi.nwrite),
      row("readopen", pi.readopen), row("writeopen", pi.writeopen),
      row("data", JSON.stringify(pi.data)),
    ]));
  }

  const svg = document.createElementNS(SVG, "svg");
  svg.classList.add("wires");
  const wrap = h("div.g", svg);
  wrap._groups = G;
  el.append(wrap);
  fit(el);
  if (!fitted.has(el)) {
    fitted.add(el);
    new ResizeObserver(() => fit(el)).observe(el);
  }
}

// 배치 두 가지 중 패널에 더 크게 들어가는 쪽을 고른다.
//   row:  cpu | proc | kstack trapframe pagetable file | inode pipe | buf
//   grid: cpu proc | kstack trapframe pagetable
//         file     | inode pipe buf
// 그 다음 전체가 들어가도록 zoom (0.75~1.4배). 화살표는 zoom 1에서 그린다.
const fitted = new WeakSet();
const ZMIN = 0.75, ZMAX = 1.4;

function arrange(wrap, mode) {
  const G = wrap._groups;
  for (const c of [...wrap.querySelectorAll(":scope > .gcol")]) c.remove();
  const cells = mode === "row"
    ? [G.cpu, G.proc, [...G.mem, ...G.files], G.fs, G.bufs].filter((c) => c.length)
    : [[...G.cpu, ...G.proc], G.mem, G.files, [...G.fs, ...G.bufs]];
  wrap.classList.toggle("grid", mode === "grid");
  for (const c of cells) wrap.append(h("div.gcol", c));
}

function fit(el) {
  const wrap = el.querySelector(":scope > .g");
  if (!wrap) return;
  const cs = getComputedStyle(el);
  const w = el.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
  const hgt = el.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
  if (w <= 0 || hgt <= 0) return;
  const top = el.scrollTop, left = el.scrollLeft;
  wrap.style.zoom = "";
  const zoomFor = (mode) => {
    arrange(wrap, mode);
    return Math.min(ZMAX, w / wrap.scrollWidth, hgt / wrap.scrollHeight);
  };
  const zGrid = zoomFor("grid");
  const zRow = zoomFor("row");
  // 한 줄 배치가 읽기 쉬우니, 비슷하면 그쪽을 쓴다
  const z = zRow >= zGrid * 0.9 ? zRow : (arrange(wrap, "grid"), zGrid);
  wire(wrap, wrap.querySelector("svg.wires"));
  wrap.style.zoom = String(Math.floor(Math.max(ZMIN, z) * 100) / 100);
  el.scrollTop = top;
  el.scrollLeft = left;
}

function wire(wrap, svg) {
  clear(svg);
  const box = wrap.getBoundingClientRect();
  svg.setAttribute("width", wrap.scrollWidth);
  svg.setAttribute("height", wrap.scrollHeight);
  const defs = document.createElementNS(SVG, "defs");
  defs.innerHTML = '<marker id="ah" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L8,4 L0,8 z"/></marker>';
  svg.append(defs);
  for (const r of wrap.querySelectorAll("[data-to]")) {
    const t = wrap.querySelector(`[data-node="${CSS.escape(r.dataset.to)}"]`);
    if (!t) continue;
    const a = r.getBoundingClientRect();
    const b = t.getBoundingClientRect();
    const x1 = a.right - box.left, y1 = a.top + a.height / 2 - box.top;
    let x2 = b.left - box.left, y2 = b.top + 12 - box.top, c2 = x2 - 28;
    if (x2 < x1) { x2 = b.right - box.left; c2 = x2 + 28; } // 같은 열이거나 접혀서 왼쪽 아래에 있다
    const d = `M${x1},${y1} C${x1 + 28},${y1} ${c2},${y2} ${x2},${y2}`;
    const path = document.createElementNS(SVG, "path");
    path.setAttribute("d", d);
    path.setAttribute("marker-end", "url(#ah)");
    svg.append(path);
  }
}
