// 소스 패널. 파일을 한 번 받아 두고 줄을 강조한다.
import { h, clear } from "./dom.js";

const cache = new Map();

// 문자열, 숫자, 제어 키워드, 타입 키워드, 전처리 지시어 (VS Code Dark Modern 색)
const TOK = new RegExp([
  /("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')/.source,
  /\b(0x[0-9a-fA-F]+|\d+)\b/.source,
  /\b(if|else|for|while|do|return|switch|case|break|continue|default|goto)\b/.source,
  /\b(struct|union|enum|typedef|static|inline|extern|const|volatile|unsigned|signed|void|int|uint|uint8|uint16|uint32|uint64|char|short|long|sizeof|asm)\b/.source,
  /^(\s*#\s*\w+)/.source,
].join("|"), "g");
const CLS = [null, "str", "num", "ctl", "kw", "pp"];

export function lineNode(text) {
  const frag = document.createDocumentFragment();
  const ci = text.search(/\/\/|#\s|\/\*/);
  const code = ci >= 0 ? text.slice(0, ci) : text;
  let last = 0;
  for (const m of code.matchAll(TOK)) {
    const g = m.findIndex((x, i) => i > 0 && x !== undefined);
    frag.append(code.slice(last, m.index), h("span." + CLS[g], m[0]));
    last = m.index + m[0].length;
  }
  frag.append(code.slice(last));
  if (ci >= 0) frag.append(h("span.cm", text.slice(ci)));
  return frag;
}

export function fetchFile(vm, file) {
  const k = vm + ":" + file;
  if (!cache.has(k)) {
    cache.set(k, fetch(`/api/source?vm=${vm}&file=${encodeURIComponent(file)}`).then(async (r) => {
      if (!r.ok) throw new Error((await r.json()).error);
      return (await r.text()).split("\n");
    }));
  }
  return cache.get(k);
}

// 줄 번호(gut)를 누르면 opt.onLine(line). opt.bps: 중단점 줄, opt.here: 실행 중인 줄로 칠할지(아니면 그냥 연 위치)
const state = new Map(); // 패널 element -> { k, rows, want }

export async function showSource(el, vm, src, opt = {}) {
  const k = src ? `${vm}:${src.file}` : "";
  let st = state.get(el);
  if (!st || st.k !== k) {
    st = { k, rows: null, line: undefined, mode: null };
    state.set(el, st);
    st.want = { src, opt };
    clear(el);
    if (!src) return;
    let lines;
    try {
      lines = await fetchFile(vm, src.file);
    } catch (e) {
      if (state.get(el) === st) el.append(h("p.err", e.message));
      return;
    }
    if (state.get(el) !== st) return;
    const box = h("div.code");
    st.rows = lines.map((t, i) => {
      const r = h("div.cl", h("span.gut", { "data-line": i + 1 }, String(i + 1)), h("span.ct", lineNode(t)));
      box.append(r);
      return r;
    });
    box.addEventListener("click", (e) => {
      const g = e.target.closest(".gut");
      if (g) st.want.opt.onLine?.(Number(g.dataset.line));
    });
    el.append(box);
    // 기다리는 사이에 온 더 새 호출이 st.want를 바꿨을 수 있으니 덮어쓰지 않는다
    decorate(el, st);
    return;
  }
  st.want = { src, opt };
  decorate(el, st);
}

function decorate(el, st) {
  if (!st.rows) return;
  const { src, opt } = st.want;
  const bps = new Set(opt.bps || []);
  st.rows.forEach((r, i) => r.classList.toggle("bp", bps.has(i + 1)));
  const line = src?.line || null;
  const mode = opt.here === false ? "goto" : "here";
  if (line === st.line && mode === st.mode) return;
  st.rows.forEach((r) => r.classList.remove("here", "goto"));
  const r = line && st.rows[line - 1];
  if (r) {
    r.classList.add(mode);
    el.scrollTop = r.offsetTop - el.clientHeight / 2;
  }
  // 숨겨져 있으면 스크롤이 안 먹으니 다음에 다시 맞추도록 기억하지 않는다
  st.line = el.isConnected && el.clientHeight ? line : undefined;
  st.mode = mode;
}
