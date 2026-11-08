// Call Stack: the event-tree path to the cursor, innermost first.

import { cursor, type CursorState } from "../cursor";
import { grouped } from "../format";
import { el, GLYPH, note, Panel, placeholder } from "./panel";

export class CallStackPanel extends Panel {
  private body = el("div", "stack-list");

  constructor() {
    super("callstack");
  }

  protected mount() {
    this.element.append(this.body);
    this.onDispose(cursor.subscribe((c) => this.render(c)));
  }

  private render(c: CursorState) {
    const s = c.state;
    if (!s) {
      this.body.replaceChildren(placeholder(c, "Open a recording to see the call stack."));
      return;
    }
    const frag = document.createDocumentFragment();
    const path = [...s.path].reverse();
    path.forEach((n, i) => {
      const r = el("button", `frame kind-${n.kind}` + (i === 0 ? " current" : ""));
      r.append(el("span", `glyph codicon codicon-${i === 0 ? "debug-stackframe" : GLYPH[n.kind]}`));
      r.append(el("span", "label", n.detail ?? n.label));
      /* where the frame began: a continued frame began in an earlier run */
      const at = n.earlier ? null : n.entered ?? n.start;
      r.append(el("span", "len", at === null ? "before the recording" : `step ${grouped(at)}`));
      r.title = at === null
        ? `${n.detail ?? n.label} began before the recording (found on the stack); click: its first step here`
        : `Go to the start of ${n.detail ?? n.label}`;
      r.onclick = () => void cursor.go(at ?? n.start);
      frag.append(r);
    });
    if (!path.length) frag.append(note("No frame contains this step."));
    this.body.replaceChildren(frag);
  }
}
