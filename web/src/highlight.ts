// Small syntax highlighter for C and GNU assembler (.S) source, with the
// token classes VS Code's Dark+ / Light+ themes color. Returns one HTML
// string per line; block comments carry across lines.

const C_KEYWORDS = new Set([
  "if", "else", "for", "while", "do", "switch", "case", "default", "break",
  "continue", "return", "goto", "sizeof", "typedef", "extern", "static",
  "const", "volatile", "inline", "register", "struct", "union", "enum",
  "__attribute__", "asm", "__asm__", "__volatile__",
]);
const C_CONTROL = new Set([
  "if", "else", "for", "while", "do", "switch", "case", "default", "break",
  "continue", "return", "goto",
]);
const C_TYPES = new Set([
  "void", "char", "short", "int", "long", "unsigned", "signed", "float", "double",
  "uint", "uint8", "uint16", "uint32", "uint64", "ushort", "uchar", "pde_t",
  "pte_t", "pagetable_t", "size_t", "bool",
]);

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function span(cls: string, text: string): string {
  return `<span class="tk-${cls}">${esc(text)}</span>`;
}

export function highlight(lines: string[], path: string): string[] {
  return path.endsWith(".S") ? highlightAsm(lines) : highlightC(lines);
}

function highlightC(lines: string[]): string[] {
  const out: string[] = [];
  let inComment = false;
  for (const line of lines) {
    let i = 0;
    let html = "";
    if (inComment) {
      const end = line.indexOf("*/");
      if (end < 0) {
        out.push(span("comment", line));
        continue;
      }
      html += span("comment", line.slice(0, end + 2));
      i = end + 2;
      inComment = false;
    }
    if (/^\s*#/.test(line.slice(i))) {
      // preprocessor: directive, then the rest (strings and comments kept)
      const m = line.slice(i).match(/^(\s*#\s*\w+)(.*)$/);
      if (m) {
        html += span("preproc", m[1]);
        const rest = m[2];
        const cm = rest.indexOf("//");
        html += cm >= 0
          ? span("string", rest.slice(0, cm)) + span("comment", rest.slice(cm))
          : (/^\s*[<"]/.test(rest) ? span("string", rest) : esc(rest));
        out.push(html);
        continue;
      }
    }
    while (i < line.length) {
      const rest = line.slice(i);
      let m: RegExpMatchArray | null;
      if (rest.startsWith("//")) {
        html += span("comment", rest);
        break;
      }
      if (rest.startsWith("/*")) {
        const end = rest.indexOf("*/", 2);
        if (end < 0) {
          html += span("comment", rest);
          inComment = true;
          break;
        }
        html += span("comment", rest.slice(0, end + 2));
        i += end + 2;
        continue;
      }
      if ((m = rest.match(/^"(?:[^"\\]|\\.)*"?|^'(?:[^'\\]|\\.)*'?/))) {
        html += span("string", m[0]);
        i += m[0].length;
        continue;
      }
      if ((m = rest.match(/^(?:0[xX][0-9a-fA-F]+|\d+(?:\.\d+)?)[uUlL]*/))) {
        html += span("number", m[0]);
        i += m[0].length;
        continue;
      }
      if ((m = rest.match(/^[A-Za-z_]\w*/))) {
        const w = m[0];
        const after = rest.slice(w.length);
        if (C_CONTROL.has(w)) html += span("control", w);
        else if (C_KEYWORDS.has(w)) html += span("keyword", w);
        else if (C_TYPES.has(w)) html += span("type", w);
        else if (/^\s*\(/.test(after)) html += span("function", w);
        else if (/^[A-Z][A-Z0-9_]+$/.test(w)) html += span("constant", w);
        else html += span("variable", w);
        i += w.length;
        continue;
      }
      html += esc(rest[0]);
      i++;
    }
    out.push(html);
  }
  return out;
}

function highlightAsm(lines: string[]): string[] {
  const out: string[] = [];
  let inComment = false;
  for (const line of lines) {
    if (inComment) {
      const end = line.indexOf("*/");
      if (end < 0) {
        out.push(span("comment", line));
        continue;
      }
      inComment = false;
      out.push(span("comment", line.slice(0, end + 2)) + esc(line.slice(end + 2)));
      continue;
    }
    // cpp directives are preprocessor; any other '#' starts a comment
    const pp = line.match(/^(\s*#\s*(?:include|define|undef|if|ifdef|ifndef|else|elif|endif)\b)(.*)$/);
    if (pp) {
      out.push(span("preproc", pp[1]) + span("string", pp[2]));
      continue;
    }
    let code = line;
    let comment = "";
    const hash = line.indexOf("#");
    const cstart = line.indexOf("/*");
    if (cstart >= 0 && (hash < 0 || cstart < hash)) {
      const end = line.indexOf("*/", cstart + 2);
      code = line.slice(0, cstart);
      comment = end < 0 ? line.slice(cstart) : line.slice(cstart, end + 2);
      if (end < 0) inComment = true;
    } else if (hash >= 0) {
      code = line.slice(0, hash);
      comment = line.slice(hash);
    }
    let html = "";
    let rest = code;
    const label = rest.match(/^(\s*)([.\w$]+:)/);
    if (label) {
      html += esc(label[1]) + span("function", label[2]);
      rest = rest.slice(label[0].length);
    }
    const op = rest.match(/^(\s*)([.\w]+)/);
    if (op) {
      html += esc(op[1]) + span(op[2].startsWith(".") ? "keyword" : "control", op[2]);
      rest = rest.slice(op[0].length);
    }
    html += rest.replace(/(%\w+)|(\$[-\w()]+)|(\b0x[0-9a-fA-F]+\b|\b\d+\b)|([^%$\d]+|.)/g,
      (_m, reg: string, imm: string, num: string, other: string) =>
        reg ? span("variable", reg) : imm ? span("number", imm) : num ? span("number", num) : esc(other));
    if (comment) html += span("comment", comment);
    out.push(html);
  }
  return out;
}
