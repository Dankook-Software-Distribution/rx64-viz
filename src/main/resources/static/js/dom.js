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

