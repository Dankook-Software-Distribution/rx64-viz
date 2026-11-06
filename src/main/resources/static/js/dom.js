// 작은 DOM 도우미. h("div.cls", {attr}, ...children)
export function h(spec, attrs, ...kids) {
  const [tag, ...classes] = spec.split(".");
  const el = document.createElement(tag || "div");
  if (classes.length) el.className = classes.join(" ");
  // 두 번째 인자가 속성 객체가 아니면 자식으로 본다 (숫자 0도 자식이다)
  if (attrs != null && (typeof attrs !== "object" || attrs instanceof Node || Array.isArray(attrs))) {
    kids.unshift(attrs);
    attrs = null;
  }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k === "class") el.className += (el.className ? " " : "") + v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  append(el, kids);
  return el;
}

function append(el, kids) {
  for (const k of kids) {
    if (k == null || k === false) continue;
    if (Array.isArray(k)) append(el, k);
    else el.append(k instanceof Node ? k : String(k));
  }
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

// 64비트 주소는 문자열로 온다. 4자리씩 끊어서 읽기 쉽게.
export function addr(hex, { short = false } = {}) {
  if (hex == null) return "";
  let s = BigInt(hex).toString(16);
  if (!short) s = s.padStart(16, "0");
  const parts = [];
  for (let i = s.length; i > 0; i -= 4) parts.unshift(s.slice(Math.max(0, i - 4), i));
  return "0x" + parts.join("_");
}

export function big(hex) {
  return BigInt(hex);
}


// 다시 그린 el 안에서 직전 그림과 비교해, 값이 바뀐 [data-k]에는 chg, 새로 생긴 [data-n]에는 new를 붙인다.
// scope가 다르면(다른 프로세스, 다른 커널) 비교하지 않는다.
export function markChanges(el, scope) {
  let old = el._marks?.scope === scope ? el._marks : null;
  if (old && !old.vals.size && !old.ids.size) old = null;
  const vals = new Map(), ids = new Set();
  for (const e of el.querySelectorAll("[data-k]")) {
    const k = e.dataset.k, v = e.textContent;
    vals.set(k, v);
    if (old?.vals.has(k) && old.vals.get(k) !== v) e.classList.add("chg");
  }
  for (const e of el.querySelectorAll("[data-n]")) {
    ids.add(e.dataset.n);
    if (old && !old.ids.has(e.dataset.n)) e.classList.add("new");
  }
  el._marks = { scope, vals, ids };
}

// el이 box 안에서 margin보다 가장자리에 붙거나 벗어났으면 box만 스크롤해 높이의 at 위치에 둔다
export function reveal(box, el, margin, at) {
  const b = box.getBoundingClientRect(), r = el.getBoundingClientRect();
  if (r.top < b.top + margin || r.bottom > b.bottom - margin) box.scrollTop += r.top - b.top - at * (b.height - r.height);
}
