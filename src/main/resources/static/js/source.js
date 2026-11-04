// 소스 패널. 파일을 한 번 받아 두고 줄을 강조한다.
import { h, clear } from "./dom.js";

const cache = new Map();
const shown = new Map(); // 패널 element -> key

const KW = /\b(if|else|for|while|return|switch|case|break|default|struct|static|void|int|uint|uint64|char|long|enum|sizeof|goto|extern|const|unsigned)\b/g;

function lineNode(text) {
  const frag = document.createDocumentFragment();
  const ci = text.search(/\/\/|#\s|\/\*/);
  const code = ci >= 0 ? text.slice(0, ci) : text;
  let last = 0;
  for (const m of code.matchAll(KW)) {
    frag.append(code.slice(last, m.index), h("span.kw", m[0]));
    last = m.index + m[0].length;
  }
  frag.append(code.slice(last));
  if (ci >= 0) frag.append(h("span.cm", text.slice(ci)));
  return frag;
}

function fetchFile(vm, file) {
  const k = vm + ":" + file;
  if (!cache.has(k)) {
    cache.set(k, fetch(`/api/source?vm=${vm}&file=${encodeURIComponent(file)}`).then(async (r) => {
      if (!r.ok) throw new Error((await r.json()).error);
      return (await r.text()).split("\n");
    }));
  }
  return cache.get(k);
}

export async function showSource(el, vm, src) {
  const key = src ? `${vm}:${src.file}:${src.line}` : "";
  if (shown.get(el) === key) return;
  shown.set(el, key);
  clear(el);
  if (!src) return;
  let lines;
  try {
    lines = await fetchFile(vm, src.file);
  } catch (e) {
    el.append(h("p.err", e.message));
    return;
  }
  if (shown.get(el) !== key) return;
  const ol = h("ol.code");
  let mark = null;
  lines.forEach((t, i) => {
    const li = h("li", { value: i + 1 }, lineNode(t));
    if (i + 1 === src.line) {
      li.className = "here";
      mark = li;
    }
    ol.append(li);
  });
  el.append(ol);
  if (mark) el.scrollTop = mark.offsetTop - el.clientHeight / 2;
}
