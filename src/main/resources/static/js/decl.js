// 필드 위에 마우스를 올리면 그 필드의 C 선언 줄을 보여 준다. 대상: [data-decl="proc.pid"]
// 구조체 위치는 gdb의 info types에서 오고(-xv6-structs), 선언 줄은 그 소스 파일에서 찾는다.
import { h, clear } from "./dom.js";
import { fetchFile, lineNode } from "./source.js";

const where = {};          // vm -> { struct: { file, line } }
const parsed = new Map();  // "vm:struct" -> Promise<{ file, fields: Map<field, { line, text }> }>

export function setStructs(vm, s) {
  where[vm] = s;
  for (const k of [...parsed.keys()]) if (k.startsWith(vm + ":")) parsed.delete(k);
}

// "struct x {" 다음 줄부터 "}"까지의 필드 선언
function parse(lines, start) {
  const out = new Map();
  for (let i = start; i < lines.length; i++) {
    const raw = lines[i];
    if (/^\s*}/.test(raw)) break;
    const code = raw.replace(/\/\/.*|\/\*.*?\*\//g, "");
    for (const stmt of code.split(";")) {
      const t = stmt.replace(/\[[^\]]*\]/g, "").trim();
      if (!t || t.endsWith("{")) continue;
      const parts = t.split(",");
      const names = [parts[0].match(/([A-Za-z_]\w*)\s*$/)?.[1], ...parts.slice(1).map((x) => x.replace(/[*\s]/g, ""))];
      for (const n of names) if (n && !out.has(n)) out.set(n, { line: i + 1, text: raw.trim() });
    }
  }
  return out;
}

function fieldsOf(vm, st) {
  const w = where[vm]?.[st];
  if (!w) return null;
  const k = vm + ":" + st;
  if (!parsed.has(k)) parsed.set(k, fetchFile(vm, w.file).then((lines) => ({ file: w.file, fields: parse(lines, w.line) })));
  return parsed.get(k);
}

let card, timer, cur;

export function initDecl(vmOf) {
  card = h("div.decl");
  card.hidden = true;
  document.body.append(card);
  document.addEventListener("mouseover", (e) => {
    const t = e.target.closest?.("[data-decl]");
    if (t === cur) return;
    cur = t;
    clearTimeout(timer);
    card.hidden = true;
    if (t) timer = setTimeout(() => show(t, vmOf(t)), 300);
  });
}

async function show(t, vm) {
  const [st, f] = t.dataset.decl.split(".");
  let r;
  try {
    r = await fieldsOf(vm, st);
  } catch {
    return;
  }
  const d = r?.fields.get(f);
  if (!d || cur !== t || !t.isConnected) return;
  clear(card).append(h("div.decl-h", h("b", `struct ${st}`), h("span", `${r.file}:${d.line}`)), h("code.decl-c", lineNode(d.text)));
  card.hidden = false;
  const b = t.getBoundingClientRect();
  card.style.left = Math.max(4, Math.min(b.left, innerWidth - card.offsetWidth - 8)) + "px";
  card.style.top = (b.bottom + 6 + card.offsetHeight > innerHeight ? b.top - card.offsetHeight - 6 : b.bottom + 6) + "px";
}
