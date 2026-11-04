// 연결 그림: cpu -> proc -> kstack / trapframe / pagetable / file -> inode, pipe -> buf
// 포인터 필드는 실제 값으로 이어 그린다.
import { h, clear, addr, big } from "./dom.js";

const SVG = "http://www.w3.org/2000/svg";

function node(key, title, at, rows, onClick) {
  return h("div.node", { "data-node": key, onclick: onClick || null, class: onClick ? "click" : null },
    h("div.node-h", h("b", title), at ? h("code", at) : null),
    rows.filter(Boolean));
}

// [이름, 값, 가리키는 노드 key]
function row(name, value, to) {
  return h("div.row" + (to ? ".ptr" : ""), { "data-to": to || null },
    h("span.k", name), h("code.v", value ?? ""));
}

const short = (x) => (x ? addr(x, { short: true }) : "0");

export function renderGraph(el, vm, onPick) {
  const s = vm.snap;
  const f = s?.focus;
  clear(el);
  if (!s || !f) return;
  const p = s.procs.find((x) => x.pid === f.pid);
  if (!p) return;
  const fr = Object.fromEntries(f.frames.map((x) => [x.name, x]));
  const files = f.files;
  const cols = [[], [], [], [], []];

  for (const c of s.cpus.filter((c) => c.pid === p.pid)) {
    const pt = c.cr3 === p.pagetablePa ? "pt" : null;
    cols[0].push(node("cpu" + c.cpu, `cpus[${c.cpu}]`, short(c.addr), [
      row("proc", short(c.proc), "proc"),
      row("mode", c.mode),
      row("rip", c.fn || short(c.rip)),
      row("cr3", short(c.cr3) + (pt ? "" : " kernel"), pt),
    ]));
  }

  const ofileRows = [];
  p.ofile.forEach((fp, fd) => fp && ofileRows.push(row(`ofile[${fd}]`, short(fp), "file:" + fp)));
  cols[1].push(node("proc", `proc[${p.slot}]`, short(p.addr), [
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
  cols[2].push(node("kstack", "kstack", short(ks?.base), [
    row("top", short(ks?.top)), row("rsp", short(ks?.rsp)), row("used", `${used} B`),
  ], () => onPick("frames")));

  const tfr = fr.trapframe || ks;
  const tfVal = (name) => tfr?.slots.find((x) => x.field === name)?.value;
  const inKstack = !fr.trapframe;
  cols[2].push(node("tf", "struct trapframe", short(p.trapframe), [
    inKstack ? row("at", "kstack+" + (big(p.trapframe) - big(ks.base)).toString(16)) : row("uva", fr.trapframe.uva),
    row("rip", short(tfVal("rip"))), row("rsp", short(tfVal("rsp"))),
    row("rax", short(tfVal("rax"))), row(tfr?.slots.some((x) => x.field === "vector") ? "vector" : "trapno", short(tfVal("vector") || tfVal("trapno"))),
  ], () => onPick("frames")));

  const sp = f.addrspace.spaces[0];
  cols[2].push(node("pt", "pagetable", short(sp.root), [
    row("user pages", sp.pages.length),
    row("PML4", f.ptree.entries.map((e) => e.idx).join(" ")),
  ], () => onPick("ptree")));

  for (const fl of files.files) {
    const fds = files.fds.filter((d) => d.file === fl.addr).map((d) => d.fd).join(",");
    cols[2].push(node("file:" + fl.addr, "struct file", short(fl.addr), [
      row("fd", fds), row("type", fl.type), row("ref", fl.ref),
      row("rw", `${fl.readable}${fl.writable}`), row("off", fl.off),
      fl.ip ? row("ip", short(fl.ip), "inode:" + fl.ip) : null,
      fl.pipe ? row("pipe", short(fl.pipe), "pipe:" + fl.pipe) : null,
      fl.devsw ? row("devsw", fl.devsw) : null,
    ]));
  }

  for (const ino of files.inodes) {
    cols[3].push(node("inode:" + ino.addr, "struct inode", short(ino.addr), [
      row("inum", ino.inum), row("type", ino.type), row("size", ino.size),
      row("nlink", ino.nlink), row("ref", ino.ref),
      row("addrs", ino.addrs.slice(0, 6).join(" ") + (ino.addrs.length > 6 ? " …" : "")),
      ...ino.cached.map((b) => row(`block ${b.blockno}`, `buf[${b.buf}]`, "buf:" + b.buf)),
    ]));
    for (const b of ino.cached) {
      if (cols[4].some((n) => n.dataset.node === "buf:" + b.buf)) continue;
      cols[4].push(node("buf:" + b.buf, `bcache.buf[${b.buf}]`, short(b.addr), [
        row("blockno", b.blockno), row("refcnt", b.refcnt),
      ]));
    }
  }
  for (const pi of files.pipes) {
    cols[3].push(node("pipe:" + pi.addr, "struct pipe", short(pi.addr), [
      row("nread", pi.nread), row("nwrite", pi.nwrite),
      row("readopen", pi.readopen), row("writeopen", pi.writeopen),
      row("data", JSON.stringify(pi.data)),
    ]));
  }

  const svg = document.createElementNS(SVG, "svg");
  svg.classList.add("wires");
  const wrap = h("div.g", svg, cols.filter((c) => c.length).map((c) => h("div.gcol", c)));
  el.append(wrap);
  requestAnimationFrame(() => wire(wrap, svg));
}

function wire(wrap, svg) {
  while (svg.firstChild) svg.firstChild.remove();
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
    let x2 = b.left - box.left, y2 = b.top + 12 - box.top;
    if (x2 < x1) x2 = b.right - box.left; // 같은 열
    const d = `M${x1},${y1} C${x1 + 28},${y1} ${x2 - 28},${y2} ${x2},${y2}`;
    const path = document.createElementNS(SVG, "path");
    path.setAttribute("d", d);
    path.setAttribute("marker-end", "url(#ah)");
    svg.append(path);
  }
}
